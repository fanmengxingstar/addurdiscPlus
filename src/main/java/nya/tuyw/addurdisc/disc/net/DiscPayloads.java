package nya.tuyw.addurdisc.disc.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.store.SongInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Messages between a client that holds a disc and the server that owns the sounds folder.
 *
 * <p>A transfer is a start message that announces the size and digest, followed by chunks in order.
 * One connection carries one transfer at a time, which is why the chunks only need the id to prove
 * they belong to the transfer that is currently open.</p>
 */
public final class DiscPayloads {

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(AddurDisc.MODID, name);
    }

    /** Why a transfer stopped. What the player reads is picked from this, never taken from the wire. */
    public enum Failure {
        NONE(""),
        TOO_LARGE("addurdisc.upload.too_large"),
        NO_DISC("addurdisc.upload.no_disc"),
        DENIED("addurdisc.message.denied"),
        STORE_FULL("addurdisc.upload.store_full"),
        TOO_MANY_TRACKS("addurdisc.upload.too_many_tracks"),
        CORRUPT("addurdisc.upload.corrupt"),
        STORAGE_FAILED("addurdisc.upload.storage_failed"),
        BUSY("addurdisc.upload.busy"),
        CONVERSION_FAILED("addurdisc.upload.conversion_failed");

        private static final Failure[] BY_ID = values();

        private final String langKey;

        Failure(String langKey) {
            this.langKey = langKey;
        }

        public String langKey() {
            return langKey;
        }

        public boolean isFailure() {
            return this != NONE;
        }

        private byte id() {
            return (byte) ordinal();
        }

        private static Failure byId(byte id) {
            int index = id & 0xFF;
            return index < BY_ID.length ? BY_ID[index] : STORAGE_FAILED;
        }
    }

    private static final StreamCodec<ByteBuf, Failure> FAILURE_CODEC =
            ByteBufCodecs.BYTE.map(Failure::byId, Failure::id);

    private static StreamCodec<RegistryFriendlyByteBuf, List<SongInfo>> songPageCodec() {
        return ByteBufCodecs.collection(ArrayList::new, SongInfo.STREAM_CODEC, DiscProtocol.SONG_PAGE_SIZE);
    }

    /** Client asks the server to import a source file; {@code hand} is the disc to bind it to. */
    public record UploadStart(UUID transfer, String suggestedName, String title, long size, String sha1, InteractionHand hand)
            implements CustomPacketPayload {
        public UploadStart {
            if (sha1 == null) sha1 = "";
            if (title == null) title = "";
        }
        public static final CustomPacketPayload.Type<UploadStart> TYPE = new CustomPacketPayload.Type<>(id("upload_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadStart> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, UploadStart::transfer,
                ByteBufCodecs.stringUtf8(DiscProtocol.NAME_CHARS), UploadStart::suggestedName,
                ByteBufCodecs.stringUtf8(DiscProtocol.TITLE_CHARS), UploadStart::title,
                ByteBufCodecs.VAR_LONG, UploadStart::size,
                ByteBufCodecs.stringUtf8(DiscProtocol.SHA1_CHARS), UploadStart::sha1,
                InteractionHand.STREAM_CODEC, UploadStart::hand,
                UploadStart::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client sends the source file in order; the last chunk closes the transfer. */
    public record UploadChunk(UUID transfer, byte[] bytes) implements CustomPacketPayload {
        public UploadChunk {
            if (bytes == null) bytes = new byte[0];
        }
        public static final CustomPacketPayload.Type<UploadChunk> TYPE = new CustomPacketPayload.Type<>(id("upload_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunk> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, UploadChunk::transfer,
                ByteBufCodecs.byteArray(DiscProtocol.UPLOAD_CHUNK_BYTES), UploadChunk::bytes,
                UploadChunk::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server answers a transfer; a success also carries the name the server chose. */
    public record UploadResult(UUID transfer, Failure failure, String name, float lengthInSeconds)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UploadResult> TYPE = new CustomPacketPayload.Type<>(id("upload_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadResult> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, UploadResult::transfer,
                FAILURE_CODEC, UploadResult::failure,
                ByteBufCodecs.stringUtf8(DiscProtocol.NAME_CHARS), UploadResult::name,
                ByteBufCodecs.FLOAT, UploadResult::lengthInSeconds,
                UploadResult::new
        );

        public UploadResult {
            failure = failure == null ? Failure.STORAGE_FAILED : failure;
            name = name == null ? "" : name;
        }

        public boolean success() {
            return !failure.isFailure();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One page of the library. Sent on join, and again whenever a track is added. */
    public record SongList(List<SongInfo> songs) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SongList> TYPE = new CustomPacketPayload.Type<>(id("song_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SongList> STREAM_CODEC =
                StreamCodec.composite(songPageCodec(), SongList::songs, SongList::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client asks for a track it is missing. */
    public record RequestSong(String name) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestSong> TYPE = new CustomPacketPayload.Type<>(id("request_song"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSong> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(DiscProtocol.NAME_CHARS), RequestSong::name, RequestSong::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server announces a download before streaming it. */
    public record DownloadStart(String name, long size, String sha1, float lengthInSeconds) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DownloadStart> TYPE = new CustomPacketPayload.Type<>(id("download_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownloadStart> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(DiscProtocol.NAME_CHARS), DownloadStart::name,
                ByteBufCodecs.VAR_LONG, DownloadStart::size,
                ByteBufCodecs.stringUtf8(DiscProtocol.SHA1_CHARS), DownloadStart::sha1,
                ByteBufCodecs.FLOAT, DownloadStart::lengthInSeconds,
                DownloadStart::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server sends a track in order. */
    public record DownloadChunk(String name, byte[] bytes, int bytesLeft) implements CustomPacketPayload {
        public DownloadChunk {
            if (bytes == null) bytes = new byte[0];
            if (bytesLeft < 0) bytesLeft = Integer.MAX_VALUE;
        }
        public static final CustomPacketPayload.Type<DownloadChunk> TYPE = new CustomPacketPayload.Type<>(id("download_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownloadChunk> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(DiscProtocol.NAME_CHARS), DownloadChunk::name,
                ByteBufCodecs.byteArray(DiscProtocol.DOWNLOAD_CHUNK_BYTES), DownloadChunk::bytes,
                ByteBufCodecs.VAR_INT, DownloadChunk::bytesLeft,
                DownloadChunk::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Vanilla announces a jukebox song as an index into the jukebox_song datapack registry, which a
     * runtime built song is not part of, so the server names the sound itself.
     */
    public record PlaySong(Identifier sound, BlockPos pos) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<PlaySong> TYPE = new CustomPacketPayload.Type<>(id("play_song"));
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaySong> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, PlaySong::sound,
                BlockPos.STREAM_CODEC, PlaySong::pos,
                PlaySong::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** @return the library cut into pages small enough for one {@link SongList} each */
    public static List<SongList> pagesOf(List<SongInfo> library) {
        List<SongList> pages = new ArrayList<>(library.size() / DiscProtocol.SONG_PAGE_SIZE + 1);
        for (int from = 0; from < library.size(); from += DiscProtocol.SONG_PAGE_SIZE) {
            int to = Math.min(from + DiscProtocol.SONG_PAGE_SIZE, library.size());
            pages.add(new SongList(new ArrayList<>(library.subList(from, to))));
        }
        // A brand new server has nothing to send, and the client still needs the "library is empty"
        // answer to know the sync is over rather than still on its way.
        if (pages.isEmpty()) {
            pages.add(new SongList(List.of()));
        }
        return pages;
    }

    private DiscPayloads() {
    }
}
