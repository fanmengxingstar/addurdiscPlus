package nya.tuyw.addurdisc.disc.net;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.store.SongInfo;
import io.netty.handler.codec.DecoderException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
/** Bounded legacy custom-channel transport, with the same transfer and playback messages. */
public final class DiscPayloads {
    public interface Payload { Type<? extends Payload> type(); }
    public record Type<T extends Payload>(ResourceLocation id, Function<FriendlyByteBuf,T> decoder, BiConsumer<FriendlyByteBuf,T> encoder) {
        public T read(FriendlyByteBuf buffer) {
            T value = decoder.apply(buffer);
            if (buffer.isReadable()) throw new DecoderException("Trailing AddurDisc packet data");
            return value;
        }
        @SuppressWarnings("unchecked") public void write(FriendlyByteBuf buffer, Payload value) { encoder.accept(buffer, (T)value); }
    }
    private static String text(FriendlyByteBuf b, int max) {
        int size = b.readVarInt();
        if (size < 0 || size > max || size > b.readableBytes()) throw new DecoderException("Invalid AddurDisc string length");
        return b.readCharSequence(size, StandardCharsets.UTF_8).toString();
    }
    private static void text(FriendlyByteBuf b, String value, int max) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) throw new IllegalArgumentException("AddurDisc string too long");
        b.writeVarInt(bytes.length); b.writeBytes(bytes);
    }
    private static List<SongInfo> songs(FriendlyByteBuf b) {
        int size = b.readVarInt();
        if (size < 0 || size > DiscProtocol.SONG_PAGE_SIZE) throw new DecoderException("Invalid AddurDisc song page");
        List<SongInfo> songs = new ArrayList<>(size);
        for(int i=0;i<size;i++) songs.add(new SongInfo(text(b,DiscProtocol.NAME_CHARS),b.readVarLong(),text(b,DiscProtocol.SHA1_CHARS),b.readFloat()));
        return songs;
    }
    private static void songs(FriendlyByteBuf b,List<SongInfo> songs) {
        if (songs.size()>DiscProtocol.SONG_PAGE_SIZE) throw new IllegalArgumentException("Song page too long");
        b.writeVarInt(songs.size());
        for(SongInfo song:songs) { text(b,song.name(),DiscProtocol.NAME_CHARS);b.writeVarLong(song.size());text(b,song.sha1(),DiscProtocol.SHA1_CHARS);b.writeFloat(song.lengthInSeconds()); }
    }
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

    public record UploadStart(UUID transfer, String suggestedName, String title, long size, String sha1, InteractionHand hand) implements Payload {
        public static final Type<UploadStart> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"upload_start"), b -> new UploadStart(b.readUUID(), text(b, DiscProtocol.NAME_CHARS), text(b, DiscProtocol.TITLE_CHARS), b.readVarLong(), text(b, DiscProtocol.SHA1_CHARS), b.readEnum(InteractionHand.class)), (b,v) -> {b.writeUUID(v.transfer()); text(b,v.suggestedName(),DiscProtocol.NAME_CHARS); text(b,v.title(),DiscProtocol.TITLE_CHARS); b.writeVarLong(v.size()); text(b,v.sha1(),DiscProtocol.SHA1_CHARS); b.writeEnum(v.hand());});
        @Override public Type<UploadStart> type() { return TYPE; }
    }
    public record UploadChunk(UUID transfer, byte[] bytes) implements Payload {
        public static final Type<UploadChunk> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"upload_chunk"), b -> new UploadChunk(b.readUUID(), b.readByteArray(DiscProtocol.UPLOAD_CHUNK_BYTES)), (b,v) -> {b.writeUUID(v.transfer()); b.writeByteArray(v.bytes());});
        @Override public Type<UploadChunk> type() { return TYPE; }
    }
    public record UploadResult(UUID transfer, Failure failure, String name, float lengthInSeconds) implements Payload {
        public static final Type<UploadResult> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"upload_result"), b -> new UploadResult(b.readUUID(), Failure.byId(b.readByte()), text(b,DiscProtocol.NAME_CHARS), b.readFloat()), (b,v) -> {b.writeUUID(v.transfer()); b.writeByte(v.failure().id()); text(b,v.name(),DiscProtocol.NAME_CHARS); b.writeFloat(v.lengthInSeconds());});
        public boolean success() { return !failure.isFailure(); }
        @Override public Type<UploadResult> type() { return TYPE; }
    }
    public record SongList(List<SongInfo> songs) implements Payload {
        public static final Type<SongList> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"song_list"), b -> new SongList(DiscPayloads.songs(b)), (b,v) -> {DiscPayloads.songs(b,v.songs());});
        @Override public Type<SongList> type() { return TYPE; }
    }
    public record RequestSong(String name) implements Payload {
        public static final Type<RequestSong> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"request_song"), b -> new RequestSong(text(b,DiscProtocol.NAME_CHARS)), (b,v) -> {text(b,v.name(),DiscProtocol.NAME_CHARS);});
        @Override public Type<RequestSong> type() { return TYPE; }
    }
    public record DownloadStart(String name, long size, String sha1, float lengthInSeconds) implements Payload {
        public static final Type<DownloadStart> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"download_start"), b -> new DownloadStart(text(b,DiscProtocol.NAME_CHARS), b.readVarLong(), text(b,DiscProtocol.SHA1_CHARS), b.readFloat()), (b,v) -> {text(b,v.name(),DiscProtocol.NAME_CHARS); b.writeVarLong(v.size()); text(b,v.sha1(),DiscProtocol.SHA1_CHARS); b.writeFloat(v.lengthInSeconds());});
        @Override public Type<DownloadStart> type() { return TYPE; }
    }
    public record DownloadChunk(String name, byte[] bytes, int bytesLeft) implements Payload {
        public static final Type<DownloadChunk> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"download_chunk"), b -> new DownloadChunk(text(b,DiscProtocol.NAME_CHARS), b.readByteArray(DiscProtocol.DOWNLOAD_CHUNK_BYTES), b.readVarInt()), (b,v) -> {text(b,v.name(),DiscProtocol.NAME_CHARS); b.writeByteArray(v.bytes()); b.writeVarInt(v.bytesLeft());});
        @Override public Type<DownloadChunk> type() { return TYPE; }
    }
    public record PlaySong(ResourceLocation sound, BlockPos pos) implements Payload {
        public static final Type<PlaySong> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"play_song"), b -> new PlaySong(b.readResourceLocation(), b.readBlockPos()), (b,v) -> {b.writeResourceLocation(v.sound()); b.writeBlockPos(v.pos());});
        @Override public Type<PlaySong> type() { return TYPE; }
    }
    public record PlaybackState(ResourceLocation sound, BlockPos pos, ResourceLocation dimension, long elapsedTicks) implements Payload {
        public static final Type<PlaybackState> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"playback_state"), b -> new PlaybackState(b.readResourceLocation(), b.readBlockPos(), b.readResourceLocation(), b.readVarLong()), (b,v) -> {b.writeResourceLocation(v.sound()); b.writeBlockPos(v.pos()); b.writeResourceLocation(v.dimension()); b.writeVarLong(v.elapsedTicks());});
        @Override public Type<PlaybackState> type() { return TYPE; }
    }
    public record StopSong(BlockPos pos, ResourceLocation dimension) implements Payload {
        public static final Type<StopSong> TYPE = new Type<>(new ResourceLocation(AddurDisc.MODID,"stop_song"), b -> new StopSong(b.readBlockPos(), b.readResourceLocation()), (b,v) -> {b.writeBlockPos(v.pos()); b.writeResourceLocation(v.dimension());});
        @Override public Type<StopSong> type() { return TYPE; }
    }
    public static List<SongList> pagesOf(List<SongInfo> library) {
        List<SongList> pages = new ArrayList<>();
        for (int from=0;from<library.size();from+=DiscProtocol.SONG_PAGE_SIZE)
            pages.add(new SongList(List.copyOf(library.subList(from,Math.min(from+DiscProtocol.SONG_PAGE_SIZE,library.size())))));
        if (pages.isEmpty()) pages.add(new SongList(List.of()));
        return pages;
    }
    private DiscPayloads() { }
}
