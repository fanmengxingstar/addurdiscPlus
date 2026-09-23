package nya.tuyw.addurdisc.disc.store;

import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.util.OggVorbis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sounds folder, seen as the single source of truth for the available tracks. Both sides use
 * it: the server writes imported tracks and serves them, a client writes what it downloaded.
 *
 * <p>The digest and the length of a track only change when its bytes change, so they are remembered
 * against the file's size and timestamp. Listing the library happens on every join and on every
 * import, and hashing every file again each time turned a folder of songs into a stall.</p>
 */
public final class SongStore {
    private static final String OGG = ".ogg";
    /** Files with this prefix are never treated as tracks, so a half written import is invisible. */
    private static final String TEMP_PREFIX = "_tmp_";
    private static final int DIGEST_BUFFER = 16 * 1024;

    private static final Map<Path, Cached> INDEX = new ConcurrentHashMap<>();

    private record Cached(long size, long modified, String sha1, float length) {
    }

    /** @return every legal track on disk, sorted by name */
    public static List<SongInfo> scan() {
        List<SongInfo> songs = new ArrayList<>();
        Set<Path> present = new HashSet<>();
        for (String name : DiscResources.scanSoundNames()) {
            Path file = resolve(name);
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            present.add(file);
            SongInfo song = describe(name, file);
            if (song != null) {
                songs.add(song);
            }
        }
        INDEX.keySet().retainAll(present);
        return songs;
    }

    /** @return the track, or null when the file disappeared or shrank to nothing mid read */
    private static SongInfo describe(String name, Path file) {
        long size;
        long modified;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
        if (size <= 0) {
            return null;
        }
        Cached cached = INDEX.get(file);
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return new SongInfo(name, size, cached.sha1(), cached.length());
        }
        String sha1 = sha1(file);
        float length = OggVorbis.readDurationSeconds(file, ConfigUtil.getDefaultSongLengthSeconds());
        INDEX.put(file, new Cached(size, modified, sha1, length));
        return new SongInfo(name, size, sha1, length);
    }

    /** @return the track file, or null when the name is not a legal track name */
    public static Path resolve(String name) {
        if (!DiscResources.isValidName(name)) {
            AddurDisc.LOGGER.warn("Rejected the illegal track name {}", name);
            return null;
        }
        return DiscResources.soundDir().resolve(name + OGG).toAbsolutePath().normalize();
    }

    /** @return a name that is not taken yet, derived from {@code suggested} */
    public static String allocateName(String suggested) {
        String base = DiscResources.isValidName(suggested) ? suggested : "song";
        if (!exists(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + "-" + i;
            if (DiscResources.isValidName(candidate) && !exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free track name for " + base);
    }

    public static boolean exists(String name) {
        Path file = resolve(name);
        return file != null && Files.isRegularFile(file);
    }

    public static int count() {
        return DiscResources.scanSoundNames().size();
    }

    /** @return bytes the whole library takes, which is what the store quota is measured against */
    public static long totalBytes() {
        long total = 0;
        for (SongInfo song : scan()) {
            total += song.size();
        }
        return total;
    }

    /** Writes a track, replacing nothing: the caller picked the name with {@link #allocateName}. */
    public static SongInfo write(String name, byte[] data) throws IOException {
        Path file = resolve(name);
        if (file == null) {
            throw new IOException("Illegal track name " + name);
        }
        Files.createDirectories(file.getParent());
        // The temporary file carries a unique suffix because a track can be written from the store
        // thread of the server and from the client thread of an integrated server at the same time.
        Path temp = file.getParent().resolve(TEMP_PREFIX + name + "-" + UUID.randomUUID() + OGG);
        try {
            Files.write(temp, data);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        // The moved file is new bytes at a path the index may still hold under the old track.
        INDEX.remove(file);
        return describe(name, file);
    }

    /** @return an open stream over a track, so a download never holds the whole file in memory */
    public static InputStream open(String name) throws IOException {
        Path file = resolve(name);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Unknown track " + name);
        }
        return Files.newInputStream(file);
    }

    /**
     * @return whether this side already holds a file matching the announced size and digest
     *
     * <p>Goes through {@link #describe}, so the digest comes out of {@link #INDEX} whenever the
     * file's size and timestamp are unchanged. Hashing the whole library here instead was a stall
     * on the thread that runs the packet handler: a client joining a server with a full library
     * read every byte of it again on the render thread.</p>
     */
    public static boolean hasUpToDate(SongInfo expected) {
        Path file = resolve(expected.name());
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        SongInfo actual = describe(expected.name(), file);
        return actual != null && actual.size() == expected.size() && actual.sha1().equals(expected.sha1());
    }

    /** @return whether the track was on disk and is now gone */
    public static boolean remove(String name) {
        Path file = resolve(name);
        if (file == null) {
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(file);
            INDEX.remove(file);
            return deleted;
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not delete {}", file, e);
            return false;
        }
    }

    /** @return lowercase hex sha1 of a file, or an empty string when it could not be read */
    public static String sha1(Path file) {
        MessageDigest digest = digest();
        try (InputStream stream = new DigestInputStream(Files.newInputStream(file), digest)) {
            byte[] buffer = new byte[DIGEST_BUFFER];
            while (stream.read(buffer) >= 0) {
                // The digest is fed by the stream itself; only the read has to happen.
            }
        } catch (IOException e) {
            return "";
        }
        return hex(digest.digest());
    }

    public static String sha1(byte[] data) {
        return hex(digest().digest(data));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required to compare synced tracks", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private SongStore() {
    }
}
