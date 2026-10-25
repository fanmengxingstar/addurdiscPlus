import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.net.DiscPayloads;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.*;
import nya.tuyw.addurdisc.disc.store.SongInfo;
import java.util.*;

/** Exercises the real legacy wire codec, including allocation limits and UTF-8 byte bounds. */
public final class PayloadNetworkRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> void roundTrip(T payload,StreamCodec<RegistryFriendlyByteBuf,T> codec) {
        RegistryFriendlyByteBuf encoded = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        RegistryFriendlyByteBuf repeated = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            codec.encode(encoded,payload);
            byte[] before = new byte[encoded.readableBytes()];
            encoded.getBytes(0, before);
            T restored = codec.decode(encoded);
            check(!encoded.isReadable(),"codec left bytes behind");
            codec.encode(repeated,restored);
            byte[] after = new byte[repeated.readableBytes()];
            repeated.readBytes(after);
            check(Arrays.equals(before, after), "round trip changed " + payload.type().id());
        } finally {
            encoded.release(); repeated.release();
        }
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | io.netty.handler.codec.CodecException expected) { return; }
        throw new AssertionError(message);
    }

    private static void verifyHandBounds(UUID id, String hash) {
        for (InteractionHand hand : InteractionHand.values()) {
            RegistryFriendlyByteBuf encoded = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                UploadStart.STREAM_CODEC.encode(encoded, new UploadStart(id, "track", "", 1, hash, hand));
                check(encoded.getUnsignedByte(encoded.writerIndex() - 1) == hand.ordinal(),
                        "legal hand wire value changed: " + hand);
                check(UploadStart.STREAM_CODEC.decode(encoded).hand() == hand,
                        "legal hand failed to decode: " + hand);
            } finally { encoded.release(); }
        }
        List<Integer> accepted = new ArrayList<>();
        for (int hand : new int[]{2, 255}) {
            RegistryFriendlyByteBuf malformed = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                UploadStart.STREAM_CODEC.encode(malformed,
                        new UploadStart(id, "track", "", 1, hash, InteractionHand.MAIN_HAND));
                // Change only the final hand byte of an otherwise valid production payload.
                malformed.setByte(malformed.writerIndex() - 1, hand);
                try {
                    UploadStart.STREAM_CODEC.decode(malformed);
                    accepted.add(hand);
                } catch (io.netty.handler.codec.DecoderException expected) { }
            } finally { malformed.release(); }
        }
        check(accepted.isEmpty(), "invalid hand ids accepted: " + accepted);
    }

    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        String hash = "0123456789abcdef0123456789abcdef01234567";
        verifyHandBounds(id, hash);
        ResourceLocation sound = new ResourceLocation("addurdisc", "track");
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        BlockPos pos = new BlockPos(-12, 80, 42);
        roundTrip(new UploadStart(id, "track", "中文标题", 200L * 1024 * 1024, hash, InteractionHand.OFF_HAND),UploadStart.STREAM_CODEC);
        roundTrip(new UploadChunk(id, new byte[DiscProtocol.UPLOAD_CHUNK_BYTES]),UploadChunk.STREAM_CODEC);
        for (Failure failure : Failure.values()) roundTrip(new UploadResult(id, failure, "track", 180.25F),UploadResult.STREAM_CODEC);
        roundTrip(new SongList(List.of(new SongInfo("track", 1000, hash, 180.25F))),SongList.STREAM_CODEC);
        roundTrip(new RequestSong("track"),RequestSong.STREAM_CODEC);
        roundTrip(new DownloadStart("track", 1000, hash, 180.25F),DownloadStart.STREAM_CODEC);
        roundTrip(new DownloadChunk("track", new byte[DiscProtocol.DOWNLOAD_CHUNK_BYTES], 0),DownloadChunk.STREAM_CODEC);
        roundTrip(new PlaySong(sound, pos),PlaySong.STREAM_CODEC);
        roundTrip(new PlaybackState(sound, pos, dimension, 237),PlaybackState.STREAM_CODEC);
        roundTrip(new StopSong(pos, dimension),StopSong.STREAM_CODEC);

        List<SongInfo> library = new ArrayList<>();
        for (int i = 0; i < 130; i++) library.add(new SongInfo("track_" + i, 1000, hash, 180));
        List<SongList> pages = DiscPayloads.pagesOf(library);
        check(pages.size() == 3 && pages.get(0).songs().size() == 64 && pages.get(2).songs().size() == 2,
                "library pagination changed");
        pages.forEach(page -> roundTrip(page,SongList.STREAM_CODEC));
        check(DiscPayloads.pagesOf(List.of()).size() == 1, "empty library response missing");

        RegistryFriendlyByteBuf malformed = new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try {
            malformed.writeVarInt(65);
            rejects(() -> SongList.STREAM_CODEC.decode(malformed), "oversized page accepted");
            malformed.clear(); malformed.writeUUID(id); malformed.writeVarInt(DiscProtocol.UPLOAD_CHUNK_BYTES + 1);
            rejects(() -> UploadChunk.STREAM_CODEC.decode(malformed), "oversized upload chunk accepted");
            malformed.clear(); malformed.writeVarInt(-1);
            rejects(() -> RequestSong.STREAM_CODEC.decode(malformed), "negative string length accepted");
            malformed.clear();
            rejects(() -> UploadStart.STREAM_CODEC.encode(malformed,
                    new UploadStart(id, "track", "a".repeat(129), 1, hash, InteractionHand.MAIN_HAND)),
                    "title character limit ignored");
        } finally { malformed.release(); }
        System.out.println("PASS typed payloads: all messages, pages, string, allocation and hand enum bounds");
    }
}
