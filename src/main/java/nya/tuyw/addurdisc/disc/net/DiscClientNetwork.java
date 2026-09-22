package nya.tuyw.addurdisc.disc.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.DiscClientSounds;
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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Client half of the disc protocol: uploads what it converted, pulls whatever the server has that it
 * does not, and reloads the generated pack once a track is in place.
 */
public final class DiscClientNetwork {
    /** Reported back to whoever is watching the import; the picker screen replaces and restores this.
     * The boolean says whether the message is bad news. */
    public static final BiConsumer<Component, Boolean> IDLE = (message, failed) -> {
    };
    public static BiConsumer<Component, Boolean> status = IDLE;
    /** Runs on the client thread once a track this side uploaded is bound to the disc and playable. */
    public static final Runnable NOTHING = () -> {
    };
    public static Runnable imported = NOTHING;

    /** Bytes of the track currently in flight, so its own importer can skip downloading it back. */
    private static byte[] pendingUpload;
    private static final Deque<String> WANTED = new ArrayDeque<>();
    private static ChunkAssembler download;
    private static String downloadSha1 = "";

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(UploadResult.TYPE, DiscClientNetwork::onUploadResult);
        ClientPlayNetworking.registerGlobalReceiver(SongList.TYPE, DiscClientNetwork::onSongList);
        ClientPlayNetworking.registerGlobalReceiver(DownloadStart.TYPE, DiscClientNetwork::onDownloadStart);
        ClientPlayNetworking.registerGlobalReceiver(DownloadChunk.TYPE, DiscClientNetwork::onDownloadChunk);
        ClientPlayNetworking.registerGlobalReceiver(PlaySong.TYPE, DiscClientNetwork::onPlaySong);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WANTED.clear();
            pendingUpload = null;
            download = null;
            DiscClientSounds.clear();
        });
    }

    /**
     * Streams a converted track to the server, which owns the sounds folder and the final name.
     * Runs on the client thread, which is where a payload has to be sent from anyway.
     */
    public static void upload(String suggestedName, String title, byte[] ogg, InteractionHand hand) {
        UUID transfer = UUID.randomUUID();
        pendingUpload = ogg;
        ClientPlayNetworking.send(new UploadStart(transfer, suggestedName, title, ogg.length, SongStore.sha1(ogg), hand));
        for (int offset = 0; offset < ogg.length; offset += DiscPayloads.CHUNK_BYTES) {
            int end = Math.min(offset + DiscPayloads.CHUNK_BYTES, ogg.length);
            ClientPlayNetworking.send(new UploadChunk(transfer, Arrays.copyOfRange(ogg, offset, end)));
        }
        say(Component.translatable("addurdisc.screen.uploading"));
    }

    private static void say(Component message) {
        status.accept(message, false);
    }

    private static void warn(Component message) {
        status.accept(message, true);
    }

    private static void onUploadResult(UploadResult payload, ClientPlayNetworking.Context context) {
        byte[] ownCopy = pendingUpload;
        pendingUpload = null;
        if (!payload.success()) {
            warn(Component.translatable(payload.messageKey()));
            return;
        }
        keepLocalCopy(payload.name(), ownCopy);
        DiscResources.refresh();
        reload();
        tellPlayer(Component.translatable("addurdisc.screen.imported"));
        imported.run();
    }

    /**
     * Holds on to the track this side converted. A local server wrote into this very folder, so the
     * file is already here; on a remote one the bytes we just sent are the track, and keeping them
     * saves downloading them back.
     */
    private static void keepLocalCopy(String name, byte[] ownCopy) {
        if (ownCopy == null || SongStore.exists(name)) {
            return;
        }
        try {
            SongStore.write(name, ownCopy);
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not keep a local copy of {}", name, e);
        }
    }

    /** Says something in the chat, which like the disc itself outlives the import screen. */
    private static void tellPlayer(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.gui.chatListener().handleSystemMessage(message, false);
        }
    }

    private static void onSongList(SongList payload, ClientPlayNetworking.Context context) {
        for (SongInfo song : payload.songs()) {
            if (song.size() <= AudioImporter.MAX_OGG_BYTES && !SongStore.hasUpToDate(song)) {
                request(song.name());
            }
        }
    }

    /**
     * Queues a track this side does not have. One download runs at a time, so a name that arrives
     * while the link is busy simply waits for its turn.
     */
    public static void request(String name) {
        if (DiscResources.isValidName(name) && !WANTED.contains(name)) {
            WANTED.add(name);
        }
        requestNext();
    }

    private static void requestNext() {
        if (download != null) {
            return;
        }
        String next = WANTED.poll();
        if (next == null) {
            return;
        }
        ClientPlayNetworking.send(new RequestSong(next));
    }

    private static void onDownloadStart(DownloadStart payload, ClientPlayNetworking.Context context) {
        download = new ChunkAssembler(payload.name(), payload.size());
        downloadSha1 = payload.sha1();
        say(Component.translatable("addurdisc.message.sync_started"));
    }

    private static void onDownloadChunk(DownloadChunk payload, ClientPlayNetworking.Context context) {
        if (download == null || !download.name().equals(payload.name())) {
            return;
        }
        if (!download.accept(payload.bytes())) {
            giveUpDownload();
            return;
        }
        if (payload.bytesLeft() > 0) {
            return;
        }
        byte[] ogg = download.bytes();
        download = null;
        if (!downloadSha1.isEmpty() && !SongStore.sha1(ogg).equals(downloadSha1)) {
            giveUpDownload();
            return;
        }
        try {
            SongStore.write(payload.name(), ogg);
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not store the synced track {}", payload.name(), e);
            giveUpDownload();
            return;
        }
        DiscResources.refresh();
        say(Component.translatable("addurdisc.message.sync_done"));
        if (WANTED.isEmpty()) {
            // One re-read for the whole batch, which is what a join with several tracks to fetch costs.
            reload();
        } else {
            requestNext();
        }
    }

    private static void giveUpDownload() {
        AddurDisc.LOGGER.warn("Could not sync a music disc from the server.");
        download = null;
        warn(Component.translatable("addurdisc.message.sync_failed"));
        requestNext();
    }

    /**
     * Lets the sound engine hear the tracks that arrived since it last read the pack, and starts the
     * songs that were announced while their track was still on its way.
     */
    private static void reload() {
        DiscClientSounds.reloadSoundDefinitions();
        DiscClientSounds.playWaiting();
    }

    private static void onPlaySong(PlaySong payload, ClientPlayNetworking.Context context) {
        DiscClientSounds.play(payload.sound(), payload.pos());
    }

    private DiscClientNetwork() {
    }
}
