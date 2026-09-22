package nya.tuyw.addurdisc.disc.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server half of the disc protocol. The server owns the sounds folder, so an imported track only
 * becomes real once it has been written here, and every client is served the same bytes from here.
 *
 * <p>Handlers run on the server thread, which is what lets a plain map hold the transfers in flight
 * without any locking.</p>
 */
public final class DiscNetwork {
    /** Bytes every open upload together is allowed to hold in memory. */
    private static final long UPLOAD_BUDGET = 64L * 1024 * 1024;
    /** How often one player may pull a track; a client asks once per missing song. */
    private static final int REQUESTS_PER_SECOND = 8;
    private static final long REQUEST_WINDOW_MILLIS = 1000L;

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, RequestLog> REQUESTS = new HashMap<>();
    private static long bytesInFlight;

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

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                DiscPayloads.pagesOf(SongStore.scan()).forEach(sender::sendPacket));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            finishUpload(id);
            REQUESTS.remove(id);
        });
    }

    private static void onUploadStart(UploadStart payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
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
        long free = ConfigUtil.getMaxStoreBytes() - SongStore.totalBytes();
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
            // Storing a track touches the disk and the generated pack, so it can fail. The client is
            // waiting for an answer either way, and a screen stuck on "uploading" gives no clue why.
            AddurDisc.LOGGER.error("Could not finish the import from {}.", describe(player), e);
            fail(player, upload.transfer(), Failure.STORAGE_FAILED);
        }
    }

    private static void store(ServerPlayNetworking.Context context, Upload upload) {
        ServerPlayer player = context.player();
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
        SongInfo stored;
        try {
            String name = SongStore.allocateName(upload.assembler().name());
            stored = SongStore.write(name, ogg);
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not store the track uploaded by {}", describe(player), e);
            fail(player, upload.transfer(), Failure.STORAGE_FAILED);
            return;
        }
        DiscResources.refresh();
        held.set(ModInitialize.DISC_SONG, new SongBinding(stored.name(), stored.lengthInSeconds(), upload.title()));
        player.inventoryMenu.broadcastChanges();
        ServerPlayNetworking.send(player, new UploadResult(upload.transfer(), Failure.NONE,
                stored.name(), stored.lengthInSeconds()));
        AddurDisc.LOGGER.info("{} imported {} ({} bytes).", describe(player), stored.name(), stored.size());
        // The importing client only learned bytes so far; the same announcement it would send to any
        // other player is what lets it keep a local copy of the track.
        context.server().getPlayerList().getPlayers().forEach(other ->
                ServerPlayNetworking.send(other, new SongList(List.of(stored))));
    }

    private static void onRequestSong(RequestSong payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        if (!allowRequest(player.getUUID())) {
            AddurDisc.LOGGER.warn("{} is asking for tracks faster than a sync should.", describe(player));
            return;
        }
        SongInfo wanted = find(payload.name());
        if (wanted == null || wanted.size() > ConfigUtil.getMaxSongBytes()) {
            AddurDisc.LOGGER.warn("{} asked for the unknown track {}.", describe(player), payload.name());
            return;
        }
        sendTrack(player, wanted);
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

    /**
     * Streams a track straight off disk. A whole file per player in memory would let a handful of
     * joins at once cost hundreds of megabytes for no reason.
     */
    private static void sendTrack(ServerPlayer player, SongInfo wanted) {
        ServerPlayNetworking.send(player, new DownloadStart(wanted.name(), wanted.size(),
                wanted.sha1(), wanted.lengthInSeconds()));
        byte[] buffer = new byte[DiscProtocol.DOWNLOAD_CHUNK_BYTES];
        try (InputStream stream = SongStore.open(wanted.name())) {
            int read;
            long sent = 0;
            while ((read = stream.read(buffer)) > 0) {
                sent += read;
                long left = Math.max(wanted.size() - sent, 0);
                byte[] chunk = read == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, read);
                ServerPlayNetworking.send(player, new DownloadChunk(wanted.name(), chunk, (int) left));
            }
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not send the track {} to {}", wanted.name(), describe(player), e);
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
        ServerPlayNetworking.send(player, new UploadResult(transfer, failure, "", 0));
    }

    private static String describe(ServerPlayer player) {
        return player.getScoreboardName();
    }

    private record Upload(UUID transfer, InteractionHand hand, ItemStack expectedStack, String sha1,
                          String title, ChunkAssembler assembler) {
    }

    private record RequestLog(long windowStart, int count) {
    }

    private DiscNetwork() {
    }
}
