import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.net.DiscPayloads;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.*;
import nya.tuyw.addurdisc.disc.store.SongInfo;
import java.util.*;

/** Exercises the real legacy wire codec, including allocation limits and UTF-8 byte bounds. */
public final class LegacyNetworkRegression {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void roundTrip(Payload payload) {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf repeated = new FriendlyByteBuf(Unpooled.buffer());
        try {
            payload.type().write(encoded, payload);
            byte[] before = new byte[encoded.readableBytes()];
            encoded.getBytes(0, before);
            Payload restored = payload.type().read(encoded);
            restored.type().write(repeated, restored);
            byte[] after = new byte[repeated.readableBytes()];
            repeated.readBytes(after);
            check(Arrays.equals(before, after), "round trip changed " + payload.type().id());
        } finally {
            encoded.release(); repeated.release();
        }
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | io.netty.handler.codec.DecoderException expected) { return; }
        throw new AssertionError(message);
    }

    public static void main(String[] args) {
        UUID id = UUID.randomUUID();
        String hash = "0123456789abcdef0123456789abcdef01234567";
        ResourceLocation sound = new ResourceLocation("addurdisc", "track");
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        BlockPos pos = new BlockPos(-12, 80, 42);
        roundTrip(new UploadStart(id, "track", "中文标题", 200L * 1024 * 1024, hash, InteractionHand.OFF_HAND));
        roundTrip(new UploadChunk(id, new byte[DiscProtocol.UPLOAD_CHUNK_BYTES]));
        for (Failure failure : Failure.values()) roundTrip(new UploadResult(id, failure, "track", 180.25F));
        roundTrip(new SongList(List.of(new SongInfo("track", 1000, hash, 180.25F))));
        roundTrip(new RequestSong("track"));
        roundTrip(new DownloadStart("track", 1000, hash, 180.25F));
        roundTrip(new DownloadChunk("track", new byte[DiscProtocol.DOWNLOAD_CHUNK_BYTES], 0));
        roundTrip(new PlaySong(sound, pos));
        roundTrip(new PlaybackState(sound, pos, dimension, 237));
        roundTrip(new StopSong(pos, dimension));

        List<SongInfo> library = new ArrayList<>();
        for (int i = 0; i < 130; i++) library.add(new SongInfo("track_" + i, 1000, hash, 180));
        List<SongList> pages = DiscPayloads.pagesOf(library);
        check(pages.size() == 3 && pages.get(0).songs().size() == 64 && pages.get(2).songs().size() == 2,
                "library pagination changed");
        pages.forEach(LegacyNetworkRegression::roundTrip);
        check(DiscPayloads.pagesOf(List.of()).size() == 1, "empty library response missing");

        FriendlyByteBuf malformed = new FriendlyByteBuf(Unpooled.buffer());
        try {
            malformed.writeVarInt(65);
            rejects(() -> SongList.TYPE.read(malformed), "oversized page accepted");
            malformed.clear(); malformed.writeUUID(id); malformed.writeVarInt(DiscProtocol.UPLOAD_CHUNK_BYTES + 1);
            rejects(() -> UploadChunk.TYPE.read(malformed), "oversized upload chunk accepted");
            malformed.clear(); malformed.writeVarInt(-1);
            rejects(() -> RequestSong.TYPE.read(malformed), "negative string length accepted");
            malformed.clear(); malformed.writeVarInt(1); malformed.writeByte('x'); malformed.writeByte(0);
            rejects(() -> RequestSong.TYPE.read(malformed), "trailing packet data accepted");
            malformed.clear();
            rejects(() -> UploadStart.TYPE.write(malformed,
                    new UploadStart(id, "track", "中".repeat(43), 1, hash, InteractionHand.MAIN_HAND)),
                    "UTF-8 byte title limit ignored");
        } finally { malformed.release(); }
        System.out.println("PASS legacy network: all messages, pages, UTF-8 byte limits, allocation bounds, trailing data");
    }
}
