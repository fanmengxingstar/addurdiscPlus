package nya.tuyw.addurdisc.disc.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.DownloadChunk;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.DownloadStart;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.Failure;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.PlaySong;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.RequestSong;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.SongList;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.UploadChunk;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.UploadResult;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.UploadStart;
import nya.tuyw.addurdisc.disc.store.ChunkAssembler;
import nya.tuyw.addurdisc.disc.store.SongInfo;
import nya.tuyw.addurdisc.disc.store.SongStore;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.init.ModInitialize;
import nya.tuyw.addurdisc.util.OggVorbis;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server half of the disc protocol. The server owns the sounds folder, so an imported track only
 * becomes real once it has been written here, and every client is served the same bytes from here.
 *
 * <p>Handlers run on the server thread, which is what lets a plain map hold the transfers in flight
 * without any locking. The expensive parts deliberately do not: writing, hashing and reading a
 * track is tens of megabytes of disk work, and doing it on the server thread freezes the world for
 * every player, so imports go to a dedicated background thread and downloads stream off the
 * server's tick entirely. Anything that touches game state afterwards hops back with
 * {@code server.execute}, because {@code Connection.send} is only safe from the server's own
 * thread.</p>
 */
public final class DiscNetwork {
    /** Bytes every open upload together is allowed to hold in memory. */
    private static final long UPLOAD_BUDGET = 64L * 1024 * 1024;
    /** How often one player may pull a track; a client asks once per missing song. */
    private static final int REQUESTS_PER_SECOND = 8;
    private static final long REQUEST_WINDOW_MILLIS = 1000L;
    /**
     * Sustained download speed one player is allowed, which is what keeps a modified client from
     * turning "ask for every track as fast as possible" into a bandwidth and disk drain.
     */
    private static final long DOWNLOAD_BYTES_PER_SECOND = 16L * 1024 * 1024;
    /** Download burst a player gets before the sustained rate above starts to apply. */
    private static final long DOWNLOAD_BURST_BYTES = 2L * 1024 * 1024;

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, RequestLog> REQUESTS = new HashMap<>();
    /** Players with a track currently streaming to them; one download at a time per player. */
    private static final Set<UUID> DOWNLOADING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, DownloadThrottle> THROTTLES = new ConcurrentHashMap<>();
    private static long bytesInFlight;

    /**
     * One daemon thread for every write into the sounds folder. Imports are rare but each moves up
     * to the configured per-track limit, and serialising them keeps name allocation race free while
     * the server thread never blocks on disk. A daemon thread never keeps the JVM alive after the
     * server stops.
     */
    private static final ExecutorService STORE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "addurdisc-track-store");
        thread.setDaemon(true);
        return thread;
    });

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(UploadStart.TYPE, UploadStart.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UploadChunk.TYPE, UploadChunk.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RequestSong.TYPE, RequestSong.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(UploadResult.TYPE, UploadResult.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SongList.TYPE, SongList.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DownloadStart.TYPE, DownloadStart.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DownloadChunk.TYPE, DownloadChunk.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlaySong.TYPE, PlaySong.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(UploadStart.TYPE, DiscNetwork::onUploadStart);
        ServerPlayNetworking.registerGlobalReceiver(UploadChunk.TYPE, DiscNetwork::onUploadChunk);
        ServerPlayNetworking.registerGlobalReceiver(RequestSong.TYPE, DiscNetwork::onRequestSong);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.getPlayer(), SongList.TYPE)) {
                Util.backgroundExecutor().execute(() -> {
                    List<SongList> pages = DiscPayloads.pagesOf(SongStore.scan());
                    server.execute(() -> pages.forEach(page -> send(handler.getPlayer(), page)));
                });
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            finishUpload(id);
            REQUESTS.remove(id);
            DOWNLOADING.remove(id);
            THROTTLES.remove(id);
        });
    }

    private static void onUploadStart(UploadStart payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (payload.transfer() == null || payload.hand() == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, UploadResult.TYPE)
                || !DiscResources.isValidName(payload.suggestedName())
                || payload.title() == null || payload.title().length() > DiscProtocol.TITLE_CHARS
                || payload.size() <= 0 || payload.size() > ConfigUtil.getMaxSongBytes()
                || payload.sha1() == null || (!payload.sha1().isEmpty() && !payload.sha1().matches("[0-9a-fA-F]{40}"))) {
            fail(player, payload.transfer(), Failure.CORRUPT);
            return;
        }
        UUID id = player.getUUID();
        if (UPLOADS.containsKey(id)) {
            // Two transfers at once would otherwise have the second one's bytes stored under the
            // first one's name, because both are keyed on the player rather than the transfer.
            finishUpload(id);
            fail(player, payload.transfer(), Failure.BUSY);
            return;
        }
        if (!holdsDisc(player, payload.hand())) {
            fail(player, payload.transfer(), Failure.NO_DISC);
            return;
        }
        if (payload.size() <= 0 || payload.size() > ConfigUtil.getMaxSongBytes()) {
            fail(player, payload.transfer(), Failure.TOO_LARGE);
            return;
        }
        if (SongStore.count() >= ConfigUtil.getMaxTracks()) {
            fail(player, payload.transfer(), Failure.TOO_MANY_TRACKS);
            return;
        }
        long free = Math.max(0L, ConfigUtil.getMaxStoreBytes() - SongStore.totalBytes());
        if (payload.size() > free) {
            fail(player, payload.transfer(), Failure.STORE_FULL);
            return;
        }
        if (payload.size() > UPLOAD_BUDGET - bytesInFlight) {
            fail(player, payload.transfer(), Failure.BUSY);
            return;
        }
        bytesInFlight += payload.size();
        UPLOADS.put(id, new Upload(payload.transfer(), payload.hand(), player.getItemInHand(payload.hand()),
                payload.sha1(), payload.title(), new ChunkAssembler(payload.suggestedName(), payload.size())));
    }

    /** @return whether the hand still holds a disc, which is the stack an import would bind to */
    private static boolean holdsDisc(ServerPlayer player, InteractionHand hand) {
        return player.getItemInHand(hand).is(ModInitialize.CUSTOM_DISC);
    }

    private static void onUploadChunk(UploadChunk payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        UUID id = player.getUUID();
        Upload upload = UPLOADS.get(id);
        if (upload == null || !upload.transfer().equals(payload.transfer())) {
            // Chunks of a transfer that was replaced or already finished. Nothing to answer.
            return;
        }
        if (!upload.assembler().accept(payload.bytes())) {
            finishUpload(id);
            fail(player, payload.transfer(), Failure.CORRUPT);
            return;
        }
        if (!upload.assembler().isComplete()) {
            return;
        }
        finishUpload(id);
        try {
            store(context, upload);
        } catch (RuntimeException e) {
            // The client is waiting for an answer either way, and a screen stuck on "uploading"
            // gives no clue why.
            AddurDisc.LOGGER.error("Could not finish the import from {}.", describe(player), e);
            fail(player, upload.transfer(), Failure.STORAGE_FAILED);
        }
    }

    /**
     * Validates the received bytes on the server thread, then hands the disk work to the store
     * thread. The track binds to the disc only once it is safely on disk, back on the server
     * thread, where the hand check runs again for the same reason it always did.
     */
    private static void store(ServerPlayNetworking.Context context, Upload upload) {
        ServerPlayer player = context.player();
        MinecraftServer server = context.server();
        byte[] ogg = upload.assembler().bytes();
        if (!upload.sha1().isEmpty() && !SongStore.sha1(ogg).equals(upload.sha1())) {
            fail(player, upload.transfer(), Failure.CORRUPT);
            return;
        }
        // The hand is re-read because a transfer takes seconds and the player could have switched
        // stacks meanwhile; the identity check is what stops the track landing on whatever is being
        // held now instead of on the disc the file was picked for.
        ItemStack held = player.getItemInHand(upload.hand());
        if (held != upload.expectedStack() || !held.is(ModInitialize.CUSTOM_DISC)) {
            fail(player, upload.transfer(), Failure.NO_DISC);
            return;
        }
        STORE.execute(() -> storeOnDisk(server, player, upload, ogg));
    }

    /** Runs on the store thread: the whole disk side of an import, in one place and off the tick. */
    private static void storeOnDisk(MinecraftServer server, ServerPlayer player, Upload upload, byte[] ogg) {
        SongInfo stored;
        try {
            if (SongStore.count() >= ConfigUtil.getMaxTracks()
                    || ogg.length > ConfigUtil.getMaxSongBytes()
                    || ogg.length > Math.max(0L, ConfigUtil.getMaxStoreBytes() - SongStore.totalBytes())) {
                server.execute(() -> fail(player, upload.transfer(), Failure.STORE_FULL));
                return;
            }
            String name = SongStore.allocateName(upload.assembler().name());
            stored = SongStore.write(name, ogg);
            if (stored == null) {
                throw new IOException(name + " vanished while it was being written.");
            }
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not store the track uploaded by {}", describe(player), e);
            server.execute(() -> fail(player, upload.transfer(), Failure.STORAGE_FAILED));
            return;
        }
        if (!OggVorbis.isPlayableVorbis(SongStore.resolve(stored.name()))) {
            // A matching digest only proves the bytes are the ones the client sent, not that they
            // are audio; garbage would otherwise be served to every other player from here.
            AddurDisc.LOGGER.warn("Rejected the upload from {}: not a playable Ogg Vorbis stream.", describe(player));
            SongStore.remove(stored.name());
            server.execute(() -> fail(player, upload.transfer(), Failure.CORRUPT));
            return;
        }
        DiscResources.refresh();
        server.execute(() -> bindImportedDisc(server, player, upload, stored));
    }

    /** Runs back on the server thread: the game-state half of a finished import. */
    private static void bindImportedDisc(MinecraftServer server, ServerPlayer player, Upload upload, SongInfo stored) {
        ItemStack held = player.getItemInHand(upload.hand());
        if (held == upload.expectedStack() && held.is(ModInitialize.CUSTOM_DISC)) {
            held.set(ModInitialize.DISC_SONG, new SongBinding(stored.name(), stored.lengthInSeconds(), upload.title()));
            player.inventoryMenu.broadcastChanges();
        } else {
            // The write took a moment and the disc it was picked for is gone; the track itself is
            // safely in the library, only the binding is lost.
            AddurDisc.LOGGER.info("{} imported {} but no longer holds that disc.", describe(player), stored.name());
        }
        send(player, new UploadResult(upload.transfer(), Failure.NONE,
                stored.name(), stored.lengthInSeconds()));
        AddurDisc.LOGGER.info("{} imported {} ({} bytes).", describe(player), stored.name(), stored.size());
        // The importing client only learned bytes so far; the same announcement it would send to any
        // other player is what lets it keep a local copy of the track.
        server.getPlayerList().getPlayers().forEach(other -> send(other, new SongList(List.of(stored))));
    }

    private static void onRequestSong(RequestSong payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (!ServerPlayNetworking.canSend(player, DownloadStart.TYPE)
                || payload.name() == null || !DiscResources.isValidName(payload.name())) {
            return;
        }
        if (!allowRequest(player.getUUID())) {
            AddurDisc.LOGGER.warn("{} is asking for tracks faster than a sync should.", describe(player));
            return;
        }
        if (!DOWNLOADING.add(player.getUUID())) {
            // One download at a time per player. A well behaved client asks sequentially, so a
            // second request only ever comes from a modified client, and dropping it is enough.
            return;
        }
        SongInfo wanted = find(payload.name());
        if (wanted == null || wanted.size() > ConfigUtil.getMaxSongBytes()) {
            DOWNLOADING.remove(player.getUUID());
            AddurDisc.LOGGER.warn("{} asked for the unknown track {}.", describe(player), payload.name());
            return;
        }
        send(player, new DownloadStart(wanted.name(), wanted.size(), wanted.sha1(), wanted.lengthInSeconds()));
        MinecraftServer server = context.server();
        Util.backgroundExecutor().execute(() -> streamTrack(server, player, wanted));
    }

    /**
     * Streams a track off disk from a background thread, paced by the player's download budget, so
     * neither the server thread nor the network ever has to take a whole song at once. Every packet
     * goes out through {@code server.execute}, because {@code Connection.send} walks unsynchronised
     * state and is only safe from the server's own thread; the encoding itself then still happens
     * on the netty event loop, off both.
     */
    private static void streamTrack(MinecraftServer server, ServerPlayer player, SongInfo wanted) {
        try (InputStream stream = SongStore.open(wanted.name())) {
            DownloadThrottle throttle = THROTTLES.computeIfAbsent(player.getUUID(), key -> new DownloadThrottle());
            byte[] buffer = new byte[DiscProtocol.DOWNLOAD_CHUNK_BYTES];
            long sent = 0;
            int read;
            while ((read = stream.read(buffer)) > 0) {
                if (player.hasDisconnected()) {
                    // Nobody is listening any more; stop before another read, not after another
                    // megabyte of packets to a dead connection.
                    return;
                }
                sent += read;
                final long left = Math.max(wanted.size() - sent, 0);
                final byte[] chunk = read == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, read);
                throttle.acquire(chunk.length);
            server.execute(() -> send(player, new DownloadChunk(wanted.name(), chunk, (int) left)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AddurDisc.LOGGER.debug("Stopped streaming {} to {}.", wanted.name(), describe(player));
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not send the track {} to {}", wanted.name(), describe(player), e);
        } finally {
            DOWNLOADING.remove(player.getUUID());
        }
    }

    private static void finishUpload(UUID id) {
        Upload removed = UPLOADS.remove(id);
        if (removed != null) {
            bytesInFlight = Math.max(0, bytesInFlight - removed.assembler().expectedBytes());
        }
    }

    private static void fail(ServerPlayer player, UUID transfer, Failure failure) {
        AddurDisc.LOGGER.warn("Refused an import from {}: {}.", describe(player), failure);
        send(player, new UploadResult(transfer, failure, "", 0));
    }

    private static void send(ServerPlayer player, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (player.hasDisconnected()) {
            return;
        }
        if (payload instanceof UploadResult && !ServerPlayNetworking.canSend(player, UploadResult.TYPE)) return;
        if (payload instanceof SongList && !ServerPlayNetworking.canSend(player, SongList.TYPE)) return;
        if (payload instanceof DownloadStart && !ServerPlayNetworking.canSend(player, DownloadStart.TYPE)) return;
        if (payload instanceof DownloadChunk && !ServerPlayNetworking.canSend(player, DownloadChunk.TYPE)) return;
        if (payload instanceof PlaySong && !ServerPlayNetworking.canSend(player, PlaySong.TYPE)) return;
        ServerPlayNetworking.send(player, payload);
    }

    private static String describe(ServerPlayer player) {
        return player.getScoreboardName();
    }

    private static SongInfo find(String name) {
        for (SongInfo song : SongStore.scan()) {
            if (song.name().equals(name)) {
                return song;
            }
        }
        return null;
    }

    private static boolean allowRequest(UUID id) {
        long now = System.currentTimeMillis();
        RequestLog log = REQUESTS.computeIfAbsent(id, key -> new RequestLog(now, 0));
        if (now - log.windowStart() >= REQUEST_WINDOW_MILLIS) {
            REQUESTS.put(id, new RequestLog(now, 1));
            return true;
        }
        if (log.count() >= REQUESTS_PER_SECOND) {
            return false;
        }
        REQUESTS.put(id, new RequestLog(log.windowStart(), log.count() + 1));
        return true;
    }

    private record Upload(UUID transfer, InteractionHand hand, ItemStack expectedStack, String sha1,
                          String title, ChunkAssembler assembler) {
    }

    private record RequestLog(long windowStart, int count) {
    }

    /**
     * A per player token bucket: a short burst of downloads goes through at full speed, anything
     * sustained is limited to {@link #DOWNLOAD_BYTES_PER_SECOND}. The stream pauses inside
     * {@link #acquire} rather than dropping chunks, which is what keeps the answer complete.
     */
    private static final class DownloadThrottle {
        private long available = DOWNLOAD_BURST_BYTES;
        private long lastRefillNanos = System.nanoTime();

        synchronized void acquire(long bytes) throws InterruptedException {
            while (true) {
                long now = System.nanoTime();
                long refilled = (now - lastRefillNanos) * DOWNLOAD_BYTES_PER_SECOND / 1_000_000_000L;
                if (refilled > 0) {
                    available = Math.min(available + refilled, DOWNLOAD_BURST_BYTES);
                    lastRefillNanos = now;
                }
                if (available >= bytes) {
                    available -= bytes;
                    return;
                }
                long missing = bytes - available;
                long waitMillis = Math.max(1L, missing * 1000L / DOWNLOAD_BYTES_PER_SECOND);
                wait(waitMillis);
            }
        }
    }

    private DiscNetwork() {
    }
}
