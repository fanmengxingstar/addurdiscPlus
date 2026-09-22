package nya.tuyw.addurdisc.disc.net;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.AddurDisc;
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
    /** Chunk payload size, kept below the vanilla packet limit so no packet is ever oversized. */
    public static final int CHUNK_BYTES = 320 * 1024;

    private static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(AddurDisc.MODID, name);
    }

    private static StreamCodec<RegistryFriendlyByteBuf, List<SongInfo>> songListCodec() {
        return ByteBufCodecs.collection(ArrayList::new, songInfoCodec(), 512);
    }

    private static StreamCodec<RegistryFriendlyByteBuf, SongInfo> songInfoCodec() {
        return StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), SongInfo::name,
                ByteBufCodecs.VAR_LONG, SongInfo::size,
                ByteBufCodecs.stringUtf8(40), SongInfo::sha1,
                ByteBufCodecs.FLOAT, SongInfo::lengthInSeconds,
                SongInfo::new
        );
    }

    /** Client asks the server to take a converted track; {@code hand} is the disc to bind it to. */
    public record UploadStart(UUID transfer, String suggestedName, String title, long size, String sha1, InteractionHand hand)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UploadStart> TYPE = new CustomPacketPayload.Type<>(id("upload_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadStart> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, UploadStart::transfer,
                ByteBufCodecs.stringUtf8(64), UploadStart::suggestedName,
                ByteBufCodecs.stringUtf8(128), UploadStart::title,
                ByteBufCodecs.VAR_LONG, UploadStart::size,
                ByteBufCodecs.stringUtf8(40), UploadStart::sha1,
                InteractionHand.STREAM_CODEC, UploadStart::hand,
                UploadStart::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client sends the converted ogg in order; the last chunk closes the transfer. */
    public record UploadChunk(UUID transfer, byte[] bytes) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UploadChunk> TYPE = new CustomPacketPayload.Type<>(id("upload_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunk> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, UploadChunk::transfer,
                ByteBufCodecs.byteArray(CHUNK_BYTES), UploadChunk::bytes,
                UploadChunk::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server accepts or rejects an upload; a success also carries the name the server chose. */
    public record UploadResult(UUID transfer, boolean success, String name, float lengthInSeconds, String messageKey)
            implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<UploadResult> TYPE = new CustomPacketPayload.Type<>(id("upload_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadResult> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, UploadResult::transfer,
                ByteBufCodecs.BOOL, UploadResult::success,
                ByteBufCodecs.stringUtf8(64), UploadResult::name,
                ByteBufCodecs.FLOAT, UploadResult::lengthInSeconds,
                ByteBufCodecs.stringUtf8(64), UploadResult::messageKey,
                UploadResult::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent on join, and reused by the client to ask for one specific track. */
    public record SongList(List<SongInfo> songs) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<SongList> TYPE = new CustomPacketPayload.Type<>(id("song_list"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SongList> STREAM_CODEC =
                StreamCodec.composite(songListCodec(), SongList::songs, SongList::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client asks for a track it is missing. */
    public record RequestSong(String name) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<RequestSong> TYPE = new CustomPacketPayload.Type<>(id("request_song"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RequestSong> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(64), RequestSong::name, RequestSong::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server announces a download before streaming it. */
    public record DownloadStart(String name, long size, String sha1, float lengthInSeconds) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<DownloadStart> TYPE = new CustomPacketPayload.Type<>(id("download_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownloadStart> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), DownloadStart::name,
                ByteBufCodecs.VAR_LONG, DownloadStart::size,
                ByteBufCodecs.stringUtf8(40), DownloadStart::sha1,
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
        public static final CustomPacketPayload.Type<DownloadChunk> TYPE = new CustomPacketPayload.Type<>(id("download_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DownloadChunk> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(64), DownloadChunk::name,
                ByteBufCodecs.byteArray(CHUNK_BYTES), DownloadChunk::bytes,
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

    private DiscPayloads() {
    }
}
