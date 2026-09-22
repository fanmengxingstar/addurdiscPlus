package nya.tuyw.addurdisc.disc.store;

import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.Config.ConfigUtil;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.init.ModInitialize;
import nya.tuyw.addurdisc.util.OggVorbis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The sounds folder, seen as the single source of truth for the available tracks. Both sides use
 * it: the server writes imported tracks and serves them, a client writes what it downloaded.
 */
public final class SongStore {
    private static final String OGG = ".ogg";
    /** Files with this prefix are never treated as tracks, so a half written import is invisible. */
    private static final String TEMP_PREFIX = "_tmp_";

    /** @return every legal track on disk, sorted by name */
    public static List<SongInfo> scan() {
        List<SongInfo> songs = new ArrayList<>();
        for (String name : DiscResources.scanSoundNames()) {
            Path file = resolve(name);
            if (file != null && Files.isRegularFile(file)) {
                songs.add(describe(name, file));
            }
        }
        return songs;
    }

    public static SongInfo describe(String name, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        return new SongInfo(name, size, sha1(file), OggVorbis.readDurationSeconds(file.toFile(), ConfigUtil.getDefaultSongLengthSeconds()));
    }

    /** @return the track file, or null when the name is not a legal track name */
    public static Path resolve(String name) {
        if (!DiscResources.isValidName(name)) {
            AddurDisc.LOGGER.warn("Rejected the illegal track name {}", name);
            return null;
        }
        return ModInitialize.sounds.resolve(name + OGG).toAbsolutePath().normalize();
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

    /** Writes a track, replacing nothing: the caller picked the name with {@link #allocateName}. */
    public static SongInfo write(String name, byte[] data) throws IOException {
        Path file = resolve(name);
        if (file == null) {
            throw new IOException("Illegal track name " + name);
        }
        Files.createDirectories(file.getParent());
        Path temp = file.getParent().resolve(TEMP_PREFIX + name + OGG);
        try {
            Files.write(temp, data);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return describe(name, file);
    }

    public static byte[] readBytes(String name) throws IOException {
        Path file = resolve(name);
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("Unknown track " + name);
        }
        return Files.readAllBytes(file);
    }

    /** @return whether this side already holds a file matching the announced size and digest */
    public static boolean hasUpToDate(SongInfo expected) {
        Path file = resolve(expected.name());
        if (file == null || !Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.size(file) == expected.size() && sha1(file).equals(expected.sha1());
        } catch (IOException e) {
            return false;
        }
    }

    /** @return lowercase hex sha1 of a file, or an empty string when it could not be read */
    public static String sha1(Path file) {
        try {
            return hex(digest().digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            return "";
        }
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
