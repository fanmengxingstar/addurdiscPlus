package nya.tuyw.addurdisc.disc.store;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import nya.tuyw.addurdisc.disc.DiscProtocol;

/**
 * One track as the server sees it. The digest is what lets a client tell "I already have this"
 * from "mine is a different take of the same song name".
 *
 * @param name            track name without the .ogg extension
 * @param size            file size in bytes
 * @param sha1            lowercase hex digest of the ogg file
 * @param lengthInSeconds song length, read from the Vorbis headers
 */
public record SongInfo(String name, long size, String sha1, float lengthInSeconds) {
    public static final StreamCodec<RegistryFriendlyByteBuf, SongInfo> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(DiscProtocol.NAME_CHARS), SongInfo::name,
            ByteBufCodecs.VAR_LONG, SongInfo::size,
            ByteBufCodecs.stringUtf8(DiscProtocol.SHA1_CHARS), SongInfo::sha1,
            ByteBufCodecs.FLOAT, SongInfo::lengthInSeconds,
            SongInfo::new
    );
}
