package nya.tuyw.addurdisc.disc.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.disc.audio.AudioImporter;
import nya.tuyw.addurdisc.disc.audio.ImportFailedException;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.DownloadChunk;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.DownloadStart;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.Failure;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.PlaySong;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.PlaybackState;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.StopSong;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.RequestSong;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.SongList;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.UploadChunk;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.UploadResult;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.UploadStart;
import nya.tuyw.addurdisc.disc.store.SongInfo;
import nya.tuyw.addurdisc.disc.store.SongStore;
import nya.tuyw.addurdisc.disc.store.UploadAssembler;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.init.ModInitialize;
import nya.tuyw.addurdisc.util.OggVorbis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    /** Maximum disk space reserved for incomplete source uploads. */
    private static final long UPLOAD_BUDGET = DiscProtocol.MAX_UPLOAD_SOURCE_BYTES;
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

    private static final long UPLOAD_TIMEOUT_NANOS = 30_000_000_000L;
    /** Each integrated server owns its transfers, including callbacks still finishing after stop. */
    private static final Map<MinecraftServer, Transfers> TRANSFERS = new HashMap<>();

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
        PayloadTypeRegistry.playC2S().register(UploadStart.TYPE, UploadStart.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(UploadChunk.TYPE, UploadChunk.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(RequestSong.TYPE, RequestSong.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(UploadResult.TYPE, UploadResult.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(SongList.TYPE, SongList.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(DownloadStart.TYPE, DownloadStart.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(DownloadChunk.TYPE, DownloadChunk.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlaySong.TYPE, PlaySong.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlaybackState.TYPE, PlaybackState.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(StopSong.TYPE, StopSong.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(UploadStart.TYPE, DiscNetwork::onUploadStart);
        ServerPlayNetworking.registerGlobalReceiver(UploadChunk.TYPE, DiscNetwork::onUploadChunk);
        ServerPlayNetworking.registerGlobalReceiver(RequestSong.TYPE, DiscNetwork::onRequestSong);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.getPlayer(), SongList.TYPE)) {
                Util.ioPool().execute(() -> {
                    List<SongList> pages = DiscPayloads.pagesOf(SongStore.scan());
                    server.execute(() -> pages.forEach(page -> send(handler.getPlayer(), page)));
                });
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            Transfers state = TRANSFERS.get(server);
            if (state == null) return;
            finishUpload(state, id);
            state.requests.remove(id);
            state.downloads.remove(handler.getPlayer());
            state.throttles.remove(handler.getPlayer());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            Transfers state = transfers(server);
            long now = System.nanoTime();
            for (UUID id : List.copyOf(state.uploads.keySet())) {
                Upload upload = state.uploads.get(id);
                if (!state.jobs.containsKey(upload) && now - upload.lastActivity().get() > UPLOAD_TIMEOUT_NANOS) {
                    finishUpload(state, id);
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) fail(player, upload.transfer(), Failure.BUSY);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Transfers state = TRANSFERS.remove(server);
            if (state != null) {
                state.active = false;
                state.jobs.values().forEach(job -> job.cancel(true));
                for (UUID id : List.copyOf(state.uploads.keySet())) finishUpload(state, id);
                state.downloads.clear();
            }
            DiscPlayback.clear(server);
        });
    }

    private static Transfers transfers(MinecraftServer server) {
        return TRANSFERS.computeIfAbsent(server, key -> new Transfers());
    }

    private static void onUploadStart(UploadStart payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (payload.transfer() == null || payload.hand() == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, UploadResult.TYPE)
                || !DiscResources.isValidName(payload.suggestedName())
                || payload.title() == null || payload.title().length() > DiscProtocol.TITLE_CHARS
                || payload.sha1() == null || !payload.sha1().matches("[0-9a-fA-F]{40}")) {
            fail(player, payload.transfer(), Failure.CORRUPT);
            return;
        }
        if (payload.size() <= 0 || payload.size() > DiscProtocol.MAX_UPLOAD_SOURCE_BYTES) {
            fail(player, payload.transfer(), Failure.TOO_LARGE);
            return;
        }
        UUID id = player.getUUID();
        Transfers state = transfers(context.player().server);
        if (state.uploads.containsKey(id)) {
            // Two transfers at once would otherwise have the second one's bytes stored under the
            // first one's name, because both are keyed on the player rather than the transfer.
            fail(player, payload.transfer(), Failure.BUSY);
            return;
        }
        if (!holdsDisc(player, payload.hand())) {
            fail(player, payload.transfer(), Failure.NO_DISC);
            return;
        }
        if (SongStore.count() >= ConfigUtil.getMaxTracks()) {
            fail(player, payload.transfer(), Failure.TOO_MANY_TRACKS);
            return;
        }
        if (payload.size() > UPLOAD_BUDGET - state.bytesInFlight.get()) {
            fail(player, payload.transfer(), Failure.BUSY);
            return;
        }
        try {
            UploadAssembler assembler = new UploadAssembler(payload.suggestedName(), payload.size());
            state.bytesInFlight.addAndGet(payload.size());
            state.uploads.put(id, new Upload(payload.transfer(), payload.hand(), player.getItemInHand(payload.hand()),
                    payload.sha1(), payload.title(), assembler, state, new AtomicBoolean(), new AtomicLong(System.nanoTime())));
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not create a temporary upload for {}", describe(player), e);
            fail(player, payload.transfer(), Failure.STORAGE_FAILED);
        }
    }

    /** @return whether the hand still holds a disc, which is the stack an import would bind to */
    private static boolean holdsDisc(ServerPlayer player, InteractionHand hand) {
        return player.getItemInHand(hand).is(ModInitialize.CUSTOM_DISC);
    }

    private static void onUploadChunk(UploadChunk payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        UUID id = player.getUUID();
        Transfers state = transfers(context.player().server);
        Upload upload = state.uploads.get(id);
        if (upload == null || !upload.transfer().equals(payload.transfer())) {
            // Chunks of a transfer that was replaced or already finished. Nothing to answer.
            return;
        }
        if (state.jobs.containsKey(upload)) return;
        upload.lastActivity().set(System.nanoTime());
        try {
            if (!upload.assembler().accept(payload.bytes())) {
                finishUpload(state, id);
                fail(player, payload.transfer(), Failure.CORRUPT);
                return;
            }
        } catch (IOException e) {
            finishUpload(state, id);
            AddurDisc.LOGGER.error("Could not receive the upload from {}", describe(player), e);
            fail(player, payload.transfer(), Failure.STORAGE_FAILED);
            return;
        }
        if (!upload.assembler().isComplete()) {
            return;
        }
        // Keep the size reservation through conversion so a busy store thread cannot accumulate
        // an unbounded pile of source files on disk.
        try {
            store(context, upload, upload.assembler().finish());
        } catch (RuntimeException e) {
            // The client is waiting for an answer either way, and a screen stuck on "uploading"
            // gives no clue why.
            AddurDisc.LOGGER.error("Could not finish the import from {}.", describe(player), e);
            closeUpload(upload);
            releaseUploadBudget(upload);
            state.uploads.remove(id, upload);
            fail(player, upload.transfer(), Failure.STORAGE_FAILED);
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not finish receiving the upload from {}.", describe(player), e);
            closeUpload(upload);
            releaseUploadBudget(upload);
            state.uploads.remove(id, upload);
            fail(player, upload.transfer(), Failure.STORAGE_FAILED);
        }
    }

    /**
     * Validates the received bytes on the server thread, then hands the disk work to the store
     * thread. The track binds to the disc only once it is safely on disk, back on the server
     * thread, where the hand check runs again for the same reason it always did.
     */
    private static void store(ServerPlayNetworking.Context context, Upload upload, Path source) {
        ServerPlayer player = context.player();
        MinecraftServer server = context.player().server;
        // The hand is re-read because a transfer takes seconds and the player could have switched
        // stacks meanwhile; the identity check is what stops the track landing on whatever is being
        // held now instead of on the disc the file was picked for.
        ItemStack held = player.getItemInHand(upload.hand());
        if (held != upload.expectedStack() || !held.is(ModInitialize.CUSTOM_DISC)) {
            deleteSource(source);
            releaseUploadBudget(upload);
            upload.state().uploads.remove(player.getUUID(), upload);
            fail(player, upload.transfer(), Failure.NO_DISC);
            return;
        }
        Future<?> task = STORE.submit(() -> storeOnDisk(server, player, upload, source));
        upload.state().jobs.put(upload, task);
    }

    /** Runs conversion and storage off the server tick. */
    private static void storeOnDisk(MinecraftServer server, ServerPlayer player, Upload upload, Path source) {
        SongInfo stored;
        try {
            if (!upload.state().active || player.hasDisconnected()) return;
            if (!SongStore.sha1(source).equals(upload.sha1())) {
                server.execute(() -> fail(player, upload.transfer(), Failure.CORRUPT));
                return;
            }
            AudioImporter.Converted converted = AudioImporter.convertFile(source,
                    upload.assembler().name(), upload.title());
            if (!upload.state().active || Thread.currentThread().isInterrupted()) return;
            byte[] ogg = converted.ogg();
            if (ogg.length > ConfigUtil.getMaxSongBytes()) {
                server.execute(() -> fail(player, upload.transfer(), Failure.TOO_LARGE));
                return;
            }
            if (SongStore.count() >= ConfigUtil.getMaxTracks()) {
                server.execute(() -> fail(player, upload.transfer(), Failure.TOO_MANY_TRACKS));
                return;
            }
            if (ogg.length > Math.max(0L, ConfigUtil.getMaxStoreBytes() - SongStore.totalBytes())) {
                server.execute(() -> fail(player, upload.transfer(), Failure.STORE_FULL));
                return;
            }
            String name = SongStore.allocateName(converted.suggestedName());
            stored = SongStore.write(name, ogg);
            if (stored == null) {
                throw new IOException(name + " vanished while it was being written.");
            }
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not store the track uploaded by {}", describe(player), e);
            server.execute(() -> fail(player, upload.transfer(), Failure.STORAGE_FAILED));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AddurDisc.LOGGER.warn("Conversion was interrupted for an upload from {}.", describe(player));
            server.execute(() -> fail(player, upload.transfer(), Failure.CONVERSION_FAILED));
            return;
        } catch (ImportFailedException e) {
            AddurDisc.LOGGER.warn("Could not convert the upload from {}: {}", describe(player), e.getMessage());
            Failure failure = ImportFailedException.TOO_LARGE_KEY.equals(e.key())
                    ? Failure.TOO_LARGE : Failure.CONVERSION_FAILED;
            server.execute(() -> fail(player, upload.transfer(), failure));
            return;
        } catch (RuntimeException e) {
            AddurDisc.LOGGER.error("Could not finish storing the upload from {}", describe(player), e);
            server.execute(() -> fail(player, upload.transfer(), Failure.STORAGE_FAILED));
            return;
        } finally {
            deleteSource(source);
            releaseUploadBudget(upload);
            server.execute(() -> {
                upload.state().uploads.remove(player.getUUID(), upload);
                upload.state().jobs.remove(upload);
            });
        }
        if (!upload.state().active) return;
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
        if (!upload.state().active) return;
        ItemStack held = player.getItemInHand(upload.hand());
        if (!player.hasDisconnected() && held == upload.expectedStack() && held.is(ModInitialize.CUSTOM_DISC)) {
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
        Transfers state = transfers(context.player().server);
        DownloadThrottle throttle = state.throttles.computeIfAbsent(player, key -> new DownloadThrottle());
        Download transfer = new Download(throttle);
        // The connection object owns the reservation: an old worker cannot release a reconnect's slot.
        if (state.downloads.putIfAbsent(player, transfer) != null) return;
        long delay = requestDelay(state, player.getUUID());
        MinecraftServer server = context.player().server;
        Util.ioPool().execute(() -> streamTrack(server, state, player, payload.name(), transfer, delay));
    }

    /**
     * Streams a track off disk from a background thread, paced by the player's download budget, so
     * neither the server thread nor the network ever has to take a whole song at once. Every packet
     * goes out through {@code server.execute}, because {@code Connection.send} walks unsynchronised
     * state and is only safe from the server's own thread; the encoding itself then still happens
     * on the netty event loop, off both.
     */
    private static void streamTrack(MinecraftServer server, Transfers state, ServerPlayer player,
                                    String name, Download transfer, long delay) {
        try {
            if (delay > 0) Thread.sleep(delay);
            if (!state.active || player.hasDisconnected() || state.downloads.get(player) != transfer) return;
            SongInfo wanted = SongStore.describe(name);
            if (wanted == null || wanted.size() > ConfigUtil.getMaxSongBytes()) return;
            server.execute(() -> {
                if (state.active && state.downloads.get(player) == transfer) {
                    send(player, new DownloadStart(wanted.name(), wanted.size(), wanted.sha1(), wanted.lengthInSeconds()));
                }
            });
            try (InputStream stream = SongStore.open(wanted.name())) {
                byte[] buffer = new byte[DiscProtocol.DOWNLOAD_CHUNK_BYTES];
                long sent = 0;
                int read;
                while ((read = stream.read(buffer)) > 0) {
                    if (!state.active || player.hasDisconnected() || state.downloads.get(player) != transfer) {
                        return;
                    }
                    sent += read;
                    final long left = Math.max(wanted.size() - sent, 0);
                    final byte[] chunk = read == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, read);
                    transfer.throttle.acquire(chunk.length);
                    // At most one chunk waits for the server tick per download, bounding the
                    // executor queue even when a server tick stalls.
                    var delivery = server.submit(() -> {
                        if (state.active && state.downloads.get(player) == transfer) {
                            // A client can ask again before this worker resumes after the last send.
                            if (left == 0) state.downloads.remove(player, transfer);
                            send(player, new DownloadChunk(wanted.name(), chunk, (int) left));
                        }
                    });
                    while (state.active && !player.hasDisconnected()) {
                        try {
                            delivery.get(1, java.util.concurrent.TimeUnit.SECONDS);
                            break;
                        } catch (java.util.concurrent.TimeoutException ignored) {
                            // Recheck cancellation if the server stopped before running the callback.
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AddurDisc.LOGGER.debug("Stopped streaming {} to {}.", name, describe(player));
        } catch (IOException | java.util.concurrent.ExecutionException e) {
            AddurDisc.LOGGER.warn("Could not send the track {} to {}", name, describe(player), e);
        } finally {
            state.downloads.remove(player, transfer);
        }
    }

    private static void finishUpload(Transfers state, UUID id) {
        Upload removed = state.uploads.remove(id);
        if (removed != null) {
            Future<?> job = state.jobs.remove(removed);
            if (job != null) job.cancel(true);
            releaseUploadBudget(removed);
            // A cancelled queued job never enters storeOnDisk's finally block.
            closeUpload(removed);
        }
    }

    private static void releaseUploadBudget(Upload upload) {
        if (upload.released().compareAndSet(false, true)) {
            upload.state().bytesInFlight.addAndGet(-upload.assembler().expectedBytes());
        }
    }

    private static void closeUpload(Upload upload) {
        try {
            upload.assembler().close();
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not remove a temporary upload file.", e);
        }
    }

    private static void deleteSource(Path source) {
        try {
            Files.deleteIfExists(source);
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not remove temporary upload {}.", source, e);
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
        if (payload instanceof PlaybackState && !ServerPlayNetworking.canSend(player, PlaybackState.TYPE)) return;
        if (payload instanceof StopSong && !ServerPlayNetworking.canSend(player, StopSong.TYPE)) return;
        ServerPlayNetworking.send(player, payload);
    }

    private static String describe(ServerPlayer player) {
        return player.getScoreboardName();
    }

    /** Pace legitimate sequential requests instead of dropping the ninth track for fifteen seconds. */
    private static long requestDelay(Transfers state, UUID id) {
        long now = System.nanoTime() / 1_000_000L;
        RequestLog log = state.requests.computeIfAbsent(id, key -> new RequestLog(now, 0));
        if (now - log.windowStart() >= REQUEST_WINDOW_MILLIS) {
            state.requests.put(id, new RequestLog(now, 1));
            return 0;
        }
        if (log.count() >= REQUESTS_PER_SECOND) {
            long nextWindow = log.windowStart() + REQUEST_WINDOW_MILLIS;
            state.requests.put(id, new RequestLog(nextWindow, 1));
            return Math.max(0, nextWindow - now);
        }
        state.requests.put(id, new RequestLog(log.windowStart(), log.count() + 1));
        return Math.max(0, log.windowStart() - now);
    }

    private record Upload(UUID transfer, InteractionHand hand, ItemStack expectedStack, String sha1,
                          String title, UploadAssembler assembler, Transfers state,
                          AtomicBoolean released, AtomicLong lastActivity) {
    }

    private static final class Transfers {
        private final Map<UUID, Upload> uploads = new HashMap<>();
        private final Map<UUID, RequestLog> requests = new HashMap<>();
        private final Map<Upload, Future<?>> jobs = new HashMap<>();
        private final Map<ServerPlayer, Download> downloads = new ConcurrentHashMap<>();
        private final Map<ServerPlayer, DownloadThrottle> throttles = new HashMap<>();
        private final AtomicLong bytesInFlight = new AtomicLong();
        private volatile boolean active = true;
    }

    private static final class Download {
        private final DownloadThrottle throttle;

        private Download(DownloadThrottle throttle) {
            this.throttle = throttle;
        }
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
                long elapsed = Math.max(0L, now - lastRefillNanos);
                // Once a full bucket could refill, multiplying a long idle interval is unnecessary
                // and would overflow after roughly nine minutes.
                long refilled = elapsed >= 1_000_000_000L
                        ? DOWNLOAD_BURST_BYTES : elapsed * DOWNLOAD_BYTES_PER_SECOND / 1_000_000_000L;
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
