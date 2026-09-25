package nya.tuyw.addurdisc.disc.net;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.disc.DiscClientSounds;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.audio.ImportFailedException;
import nya.tuyw.addurdisc.disc.audio.TrackMetadata;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;

/**
 * Client half of the disc protocol: uploads source media for server conversion, pulls what it does
 * not have, and reloads the generated pack once a track is in place.
 */
public final class DiscClientNetwork {
    /** How long a track may stay silent before the download is written off and tried again. */
    private static final long DOWNLOAD_TIMEOUT_MILLIS = 15_000L;
    private static final int DOWNLOAD_ATTEMPTS = 3;
    /**
     * Upload chunks that may leave in one client tick. Sending a whole track at once used to
     * encode and allocate every packet inside a single frame; a 32 MB import is two thousand
     * packets, so a per tick budget keeps the client smooth while still finishing in seconds.
     */
    private static final int UPLOAD_CHUNKS_PER_TICK = 16;

    /**
     * Whoever is watching an import. The import screen claims this while it is open and hands it
     * back when it closes, so a late packet can never report into a screen that has gone away.
     */
    public interface ImportListener {
        void report(Component message, boolean failed);

        void onImported();
    }

    private static final ImportListener IDLE = new ImportListener() {
        @Override
        public void report(Component message, boolean failed) {
        }

        @Override
        public void onImported() {
        }
    };

    private static ImportListener listener = IDLE;

    private static final Deque<String> WANTED = new ArrayDeque<>();
    /** Source files waiting for their turn to leave on the wire. */
    private static final Deque<PendingUpload> OUTBOX = new ArrayDeque<>();

    private static ChunkAssembler download;
    /** Track asked for whose {@code DownloadStart} has not arrived yet, or null once it has. */
    private static String waitingFor;
    private static String downloadSha1 = "";
    private static long deadline;
    private static int downloadAttempts;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(UploadResult.TYPE, DiscClientNetwork::onUploadResult);
        ClientPlayNetworking.registerGlobalReceiver(SongList.TYPE, DiscClientNetwork::onSongList);
        ClientPlayNetworking.registerGlobalReceiver(DownloadStart.TYPE, DiscClientNetwork::onDownloadStart);
        ClientPlayNetworking.registerGlobalReceiver(DownloadChunk.TYPE, DiscClientNetwork::onDownloadChunk);
        ClientPlayNetworking.registerGlobalReceiver(PlaySong.TYPE, DiscClientNetwork::onPlaySong);
        // A server that stops answering would otherwise leave this side waiting forever: the queue
        // would stay full and every later track would quietly never arrive. The same tick also
        // feeds the upload outbox, which is what keeps big imports off the frame budget.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickDownload();
            tickUpload();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            WANTED.clear();
            OUTBOX.clear();
            download = null;
            waitingFor = null;
            deadline = 0L;
            downloadAttempts = 0;
            DiscClientSounds.clear();
        });
    }

    public static void setListener(ImportListener watched) {
        listener = watched == null ? IDLE : watched;
    }

    /** Releases the listener only if it is still the one that claimed it. */
    public static void clearListener(ImportListener watched) {
        if (listener == watched) {
            listener = IDLE;
        }
    }

    /** Reads and hashes the picked source off-thread; the server performs the audio conversion. */
    public static void upload(Path source, InteractionHand hand) throws IOException, ImportFailedException {
        long size = Files.isRegularFile(source) ? Files.size(source) : 0L;
        if (size <= 0L) {
            throw new ImportFailedException(source.getFileName() + " does not exist or is empty.");
        }
        if (size > DiscProtocol.MAX_UPLOAD_SOURCE_BYTES) {
            throw new ImportFailedException(ImportFailedException.TOO_LARGE_KEY,
                    String.format("%.1f MB is larger than the %d MB source limit.",
                            size / (1024.0 * 1024.0), DiscProtocol.MAX_UPLOAD_SOURCE_BYTES / (1024 * 1024)));
        }
        byte[] sourceBytes = Files.readAllBytes(source);
        if (sourceBytes.length != size) {
            throw new IOException("The selected file changed while it was being read.");
        }
        String fileName = source.getFileName().toString();
        String suggestedName = TrackMetadata.suggestedName(fileName);
        String title = TrackMetadata.title(fileName);
        String sha1 = SongStore.sha1(sourceBytes);
        Minecraft.getInstance().executeIfPossible(() -> queueUpload(
                suggestedName, title, sourceBytes, sha1, hand));
    }

    private static void queueUpload(String suggestedName, String title, byte[] source, String sha1,
                                    InteractionHand hand) {
        UUID transfer = UUID.randomUUID();
        ClientPlayNetworking.send(new UploadStart(transfer, suggestedName, title, source.length,
                sha1, hand));
        OUTBOX.add(new PendingUpload(transfer, source));
        say(Component.translatable("addurdisc.screen.uploading"));
    }

    /** Sends a fixed number of queued upload chunks per client tick. */
    private static void tickUpload() {
        int budget = UPLOAD_CHUNKS_PER_TICK;
        while (budget > 0 && !OUTBOX.isEmpty() && Minecraft.getInstance().player != null) {
            PendingUpload pending = OUTBOX.peek();
            int from = pending.offset;
            int to = Math.min(from + DiscProtocol.UPLOAD_CHUNK_BYTES, pending.data.length);
            ClientPlayNetworking.send(new UploadChunk(pending.transfer, Arrays.copyOfRange(pending.data, from, to)));
            pending.offset = to;
            budget--;
            if (to >= pending.data.length) {
                OUTBOX.poll();
            }
        }
    }

    private static void say(Component message) {
        listener.report(message, false);
    }

    private static void warn(Component message) {
        listener.report(message, true);
    }

    private static void onUploadResult(UploadResult payload, ClientPlayNetworking.Context context) {
        // A transfer the server already answered has nothing left to send; the chunks of a refused
        // one would only be ignored on the far end anyway.
        OUTBOX.removeIf(pending -> pending.transfer.equals(payload.transfer()));
        if (!payload.success()) {
            warn(Component.translatable(payload.failure().langKey()));
            return;
        }
        DiscResources.refresh();
        reload();
        tellPlayer(Component.translatable("addurdisc.screen.imported"));
        listener.onImported();
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
            if (song.size() <= ConfigUtil.getMaxSongBytes() && !SongStore.hasUpToDate(song)) {
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
        if (download != null || waitingFor != null || Minecraft.getInstance().player == null) {
            return;
        }
        String next = WANTED.poll();
        if (next == null) {
            return;
        }
        downloadAttempts = 0;
        ClientPlayNetworking.send(new RequestSong(next));
        waitFor(next);
    }

    /** Starts, or restarts, the clock on the answer for one track. */
    private static void waitFor(String name) {
        waitingFor = name;
        deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MILLIS;
    }

    private static void onDownloadStart(DownloadStart payload, ClientPlayNetworking.Context context) {
        // Only ever the answer to a request this side sent: a name nobody asked for would replace the
        // transfer in flight, and an announced size is what later chunks are allowed to fill up.
        if (!payload.name().equals(waitingFor) || !DiscResources.isValidName(payload.name())
                || payload.size() <= 0 || payload.size() > ConfigUtil.getMaxSongBytes()
                || payload.sha1() == null || !payload.sha1().matches("[0-9a-fA-F]{40}")) {
            AddurDisc.LOGGER.warn("Ignored the sync offer for {}, which was not asked for.", payload.name());
            return;
        }
        download = new ChunkAssembler(payload.name(), payload.size());
        downloadSha1 = payload.sha1();
        waitingFor = null;
        deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MILLIS;
        say(Component.translatable("addurdisc.message.sync_started"));
    }

    private static void onDownloadChunk(DownloadChunk payload, ClientPlayNetworking.Context context) {
        if (download == null || !download.name().equals(payload.name())) {
            return;
        }
        deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MILLIS;
        if (!download.accept(payload.bytes())) {
            giveUpDownload(payload.name(), "sent more bytes than it announced");
            return;
        }
        long expectedLeft = download.expectedBytes() - download.receivedBytes();
        if (payload.bytesLeft() < 0 || payload.bytesLeft() != expectedLeft) {
            giveUpDownload(payload.name(), "invalid remaining byte count");
            return;
        }
        if (payload.bytesLeft() > 0) {
            return;
        }
        if (!download.isComplete()) {
            giveUpDownload(payload.name(), "download ended before the announced size");
            return;
        }
        byte[] ogg = download.bytes();
        download = null;
        if (!downloadSha1.isEmpty() && !SongStore.sha1(ogg).equals(downloadSha1)) {
            giveUpDownload(payload.name(), "did not match its announced digest");
            return;
        }
        try {
            SongStore.write(payload.name(), ogg);
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not store the synced track {}", payload.name(), e);
            giveUpDownload(payload.name(), "could not be written to disk");
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

    private static void giveUpDownload(String name, String reason) {
        download = null;
        waitingFor = null;
        AddurDisc.LOGGER.warn("Could not sync the track {}: {}.", name, reason);
        warn(Component.translatable("addurdisc.message.sync_failed"));
        downloadAttempts = 0;
        requestNext();
    }

    /**
     * The clock runs on the last packet that made sense, whether or not a download ever started. A
     * server that simply never answers a request would otherwise leave this side waiting forever,
     * with every track behind it quietly never arriving.
     */
    private static void tickDownload() {
        if (System.currentTimeMillis() < deadline || (download == null && waitingFor == null)) {
            return;
        }
        String name = download != null ? download.name() : waitingFor;
        download = null;
        waitingFor = null;
        if (++downloadAttempts < DOWNLOAD_ATTEMPTS && Minecraft.getInstance().player != null) {
            AddurDisc.LOGGER.warn("Nothing about {} yet, asking again.", name);
            ClientPlayNetworking.send(new RequestSong(name));
            waitFor(name);
            return;
        }
        giveUpDownload(name, "the server stopped answering");
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

    /** One upload whose bytes have not all left yet; {@code offset} walks as ticks go by. */
    private static final class PendingUpload {
        private final UUID transfer;
        private final byte[] data;
        private int offset;

        private PendingUpload(UUID transfer, byte[] data) {
            this.transfer = transfer;
            this.data = data;
        }
    }

    private DiscClientNetwork() {
    }
}
