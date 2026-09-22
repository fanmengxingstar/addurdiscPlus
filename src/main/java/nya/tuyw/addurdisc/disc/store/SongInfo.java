package nya.tuyw.addurdisc.disc.store;

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
}
