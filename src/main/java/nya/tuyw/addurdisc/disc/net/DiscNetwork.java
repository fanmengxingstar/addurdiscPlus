package nya.tuyw.addurdisc.disc.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.disc.audio.AudioImporter;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.DownloadChunk;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.DownloadStart;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server half of the disc protocol. The server owns the sounds folder, so an imported track only
 * becomes real once it has been written here, and every client is served the same bytes from here.
 *
 * <p>Handlers run on the server thread, which is what lets a plain map hold the transfer in flight
 * without any locking.</p>
 */
public final class DiscNetwork {
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

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
                sender.sendPacket(new SongList(SongStore.scan())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                UPLOADS.remove(handler.getPlayer().getUUID()));
    }

    private static void onUploadStart(UploadStart payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        UUID id = player.getUUID();
        if (payload.size() <= 0 || payload.size() > AudioImporter.MAX_OGG_BYTES) {
            fail(player, payload.transfer(), "addurdisc.screen.failed");
            return;
        }
        // Writing into the server's sounds folder is what makes a track exist for everyone, so on a
        // shared server only a trusted player may do it. An integrated server is the player's own.
        if (context.server().isDedicatedServer()
                && !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            fail(player, payload.transfer(), "addurdisc.message.denied");
            return;
        }
        UPLOADS.put(id, new Upload(payload.transfer(), payload.hand(), payload.sha1(), readableTitle(payload.title()),
                new ChunkAssembler(payload.suggestedName(), payload.size())));
    }

    /** @return a client supplied title that is safe to show to other players */
    private static String readableTitle(String title) {
        String flat = title.replaceAll("\\p{Cntrl}", " ").strip();
        return flat.length() <= 64 ? flat : flat.substring(0, 64).strip();
    }

    private static void onUploadChunk(UploadChunk payload, ServerPlayNetworking.Context context) {
        Upload upload = UPLOADS.get(context.player().getUUID());
        if (upload == null || !upload.transfer().equals(payload.transfer())) {
            // Chunks of a transfer that was replaced or already finished. Nothing to answer.
            return;
        }
        if (!upload.assembler().accept(payload.bytes())) {
            UPLOADS.remove(context.player().getUUID());
            fail(context.player(), payload.transfer(), "addurdisc.screen.failed");
            return;
        }
        if (!upload.assembler().isComplete()) {
            return;
        }
        UPLOADS.remove(context.player().getUUID());
        try {
            store(context, upload);
        } catch (RuntimeException e) {
            // Storing a track touches the disk and the generated pack, so it can fail. The client is
            // waiting for an answer either way, and a screen stuck on "uploading" gives no clue why.
            AddurDisc.LOGGER.error("Could not finish the import from {}.", describe(context.player()), e);
            fail(context.player(), upload.transfer(), "addurdisc.screen.failed");
        }
    }

    private static void store(ServerPlayNetworking.Context context, Upload upload) {
        ServerPlayer player = context.player();
        byte[] ogg = upload.assembler().bytes();
        if (!upload.sha1().isEmpty() && !SongStore.sha1(ogg).equals(upload.sha1())) {
            UPLOADS.remove(player.getUUID());
            fail(player, upload.transfer(), "addurdisc.screen.failed");
            return;
        }
        SongInfo stored;
        try {
            String name = SongStore.allocateName(upload.assembler().name());
            stored = SongStore.write(name, ogg);
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not store the track uploaded by {}", describe(player), e);
            fail(player, upload.transfer(), "addurdisc.screen.failed");
            return;
        }
        DiscResources.refresh();
        bind(player, upload.hand(), stored, upload.title());
        ServerPlayNetworking.send(player, new UploadResult(upload.transfer(), true, stored.name(), stored.lengthInSeconds(), ""));
        AddurDisc.LOGGER.info("{} imported {} ({} bytes).", describe(player), stored.name(), stored.size());
        // The importing client only learned bytes so far; the same announcement it would send to any
        // other player is what lets it keep a local copy of the track.
        context.server().getPlayerList().getPlayers().forEach(other ->
                ServerPlayNetworking.send(other, new SongList(List.of(stored))));
    }

    /** Puts the track on the disc the player was holding when they picked the file. */
    private static void bind(ServerPlayer player, InteractionHand hand, SongInfo stored, String title) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.is(ModInitialize.CUSTOM_DISC)) {
            AddurDisc.LOGGER.warn("{} uploaded a track without holding a disc.", describe(player));
            return;
        }
        stack.set(ModInitialize.DISC_SONG, new SongBinding(stored.name(), stored.lengthInSeconds(), title));
        player.inventoryMenu.broadcastChanges();
    }

    private static void onRequestSong(RequestSong payload, ServerPlayNetworking.Context context) {
        ServerPlayer player = context.player();
        SongInfo wanted = null;
        for (SongInfo song : SongStore.scan()) {
            if (song.name().equals(payload.name())) {
                wanted = song;
                break;
            }
        }
        if (wanted == null || wanted.size() > AudioImporter.MAX_OGG_BYTES) {
            AddurDisc.LOGGER.warn("{} asked for the unknown track {}.", describe(player), payload.name());
            return;
        }
        byte[] data;
        try {
            data = SongStore.readBytes(wanted.name());
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not read the track {}", wanted.name(), e);
            return;
        }
        ServerPlayNetworking.send(player, new DownloadStart(wanted.name(), data.length, wanted.sha1(), wanted.lengthInSeconds()));
        for (int offset = 0; offset < data.length; offset += DiscPayloads.CHUNK_BYTES) {
            int end = Math.min(offset + DiscPayloads.CHUNK_BYTES, data.length);
            ServerPlayNetworking.send(player, new DownloadChunk(wanted.name(), Arrays.copyOfRange(data, offset, end), data.length - end));
        }
    }

    private static void fail(ServerPlayer player, UUID transfer, String key) {
        AddurDisc.LOGGER.warn("Refused an import from {}.", describe(player));
        ServerPlayNetworking.send(player, new UploadResult(transfer, false, "", 0, key));
    }

    private static String describe(ServerPlayer player) {
        return player.getScoreboardName();
    }

    private record Upload(UUID transfer, InteractionHand hand, String sha1, String title, ChunkAssembler assembler) {
    }

    private DiscNetwork() {
    }
}
