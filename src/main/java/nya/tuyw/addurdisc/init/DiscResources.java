package nya.tuyw.addurdisc.init;

import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Owns everything that lives inside the generated resource folder: which track names are legal,
 * which tracks are currently on disk, and the {@code pack.mcmeta} and {@code sounds.json} that
 * describe them.
 *
 * <p>Tracks are the only thing generated per song. The disc item, its model and its artwork ship
 * with the mod, so an imported track adds sound data and nothing else.</p>
 */
public final class DiscResources {
    private static final String OG = ".ogg";
    /**
     * Lower case, no separators other than dot / dash / underscore, and never starting with a dot
     * so an imported file can not hide itself or escape the sounds folder.
     */
    private static final Pattern SOUND_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    /** The client and the integrated server rewrite the same two files. */
    private static final ReentrantLock WRITE_LOCK = new ReentrantLock();

    public static Path root() {
        return ConfigUtil.getResourcePath();
    }

    public static Path soundDir() {
        return root().resolve("assets/addurdisc/sounds");
    }

    public static boolean isValidName(String name) {
        return name != null && SOUND_NAME.matcher(name).matches();
    }

    /** Creates the folder layout and writes both pack files for whatever is already on disk. */
    public static void bootstrap() {
        try {
            Files.createDirectories(soundDir());
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not create the music folder at {}", root(), e);
            return;
        }
        WRITE_LOCK.lock();
        try {
            Generator.writePackMcmeta(root());
            Generator.writeSoundsJson(root().resolve("assets/addurdisc/sounds.json"), scanSoundNames());
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Makes sure the folder still looks like a pack, which is what the resource repository needs to
     * read it. The metadata only ever changes with the game, so a rewrite is a last resort.
     */
    public static void ensurePackMetadata() {
        Path packRoot = root();
        if (Files.isRegularFile(packRoot.resolve("pack.mcmeta"))) {
            return;
        }
        WRITE_LOCK.lock();
        try {
            Files.createDirectories(soundDir());
            Generator.writePackMcmeta(packRoot);
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not create the music folder at {}", packRoot, e);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    /** @return every usable track name (file name without the .ogg extension), sorted. */
    public static ArrayList<String> scanSoundNames() {
        ArrayList<String> names = new ArrayList<>();
        Path dir = soundDir();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
            for (Path file : sorted) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(OG)) {
                    continue;
                }
                String name = fileName.substring(0, fileName.length() - OG.length());
                if (isValidName(name)) {
                    names.add(name);
                } else {
                    AddurDisc.LOGGER.warn("{} contains illegal characters. Ignoring.", fileName);
                }
            }
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not list {}", dir, e);
        }
        return names;
    }

    /**
     * Rewrites {@code sounds.json} so it describes exactly the tracks that are on disk.
     *
     * <p>A failure is only logged: the track itself is already on disk, and the answer an importing
     * client is waiting for must not depend on these generated files.</p>
     */
    public static void refresh() {
        WRITE_LOCK.lock();
        try {
            Generator.writeSoundsJson(root().resolve("assets/addurdisc/sounds.json"), scanSoundNames());
        } catch (RuntimeException e) {
            AddurDisc.LOGGER.error("Could not refresh the generated disc files.", e);
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private DiscResources() {
    }
}
