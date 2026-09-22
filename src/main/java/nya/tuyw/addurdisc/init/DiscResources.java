package nya.tuyw.addurdisc.init;

import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.Config.ConfigUtil;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Owns everything that lives inside the generated resource folder: which track names are legal,
 * which tracks are currently on disk, and the pack files describing them.
 *
 * <p>Bootstrapping ({@link ModInitialize}) and a runtime import both go through here, the only
 * difference being that a runtime import cannot register anything and therefore only refreshes the
 * files, which the game then picks up through a resource reload.</p>
 */
public final class DiscResources {
    /**
     * Lower case, no separators other than dot / dash / underscore, and never starting with a dot
     * so an imported file can not hide itself or escape the sounds folder.
     */
    private static final Pattern SOUND_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    public static boolean isValidName(String name) {
        return name != null && SOUND_NAME.matcher(name).matches();
    }

    /** @return every usable track name (file name without the .ogg extension), sorted. */
    public static ArrayList<String> scanSoundNames() {
        ArrayList<String> names = new ArrayList<>();
        File[] files = ModInitialize.sounds.toFile().listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            if (!file.getName().endsWith(".ogg")) {
                AddurDisc.LOGGER.debug("{} is not a sound file, ignoring.", file.getName());
                continue;
            }
            String name = file.getName().substring(0, file.getName().length() - ".ogg".length());
            if (isValidName(name)) {
                names.add(name);
            } else {
                AddurDisc.LOGGER.warn("{} contains illegal characters. Ignoring.", file.getName());
            }
        }
        names.sort(String::compareTo);
        return names;
    }

    /** Rewrites the generated pack so it describes exactly the tracks that are on disk. */
    public static void writePackFiles(List<String> names) {
        Generater.GenSoundsjson(ModInitialize.path, names);
        writeTextures(names);
        Generater.GenModelsjson(ModInitialize.models, ModInitialize.itemDefinitions, names);
        if (ConfigUtil.getrefreshlang()) {
            Generater.GenLangjson(ModInitialize.lang, names);
        } else {
            AddurDisc.LOGGER.debug("refreshLang:false, will not refresh the en_us.json");
        }
        Path selected = tagsTarget();
        File other = selected.getFileName().toString().equals("music_discs.json")
                ? ModInitialize.data.resolve("creeper_drop_music_discs.json").toFile()
                : ModInitialize.data.resolve("music_discs.json").toFile();
        if (other.exists() && !other.delete()) {
            AddurDisc.LOGGER.warn("Could not remove the stale tag file {}", other.getName());
        }
        Generater.GenItemTags(selected, names);
    }

    private static Path tagsTarget() {
        return ConfigUtil.getDropbycreeper()
                ? ModInitialize.data.resolve("creeper_drop_music_discs.json")
                : ModInitialize.data.resolve("music_discs.json");
    }

    /** The artwork new disc textures are stamped from, read once and kept in memory. */
    private static byte[] discArtwork;

    /**
     * Writes the disc artwork of every track that has none yet.
     *
     * <p>{@link ModInitialize#Stream} is spent once bootstrapping has read it, and an exhausted
     * stream decodes to no image at all, so each call gets a stream of its own over the bytes that
     * were read the first time.</p>
     */
    private static void writeTextures(List<String> names) {
        if (discArtwork == null) {
            discArtwork = readArtwork();
        }
        if (discArtwork.length == 0) {
            return;
        }
        try {
            Generater.GenTexturepng(new ByteArrayInputStream(discArtwork), ModInitialize.textures, names);
        } catch (RuntimeException e) {
            AddurDisc.LOGGER.warn("Could not write the disc artwork.", e);
        }
    }

    private static byte[] readArtwork() {
        try (InputStream artwork = DiscResources.class.getResourceAsStream("/custom.png")) {
            if (artwork == null) {
                AddurDisc.LOGGER.warn("custom.png is missing, imported tracks get no artwork.");
                return new byte[0];
            }
            return artwork.readAllBytes();
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not read custom.png, imported tracks get no artwork.", e);
            return new byte[0];
        }
    }

    /**
     * Refreshes the generated files after an import. The other half of the job is on the client,
     * which has to reload its resources before the new track can be heard.
     *
     * <p>A failure is only logged: the track itself is already on disk, and the answer an importing
     * client is waiting for must not depend on these generated files.</p>
     */
    public static void refresh() {
        try {
            writePackFiles(scanSoundNames());
        } catch (RuntimeException e) {
            AddurDisc.LOGGER.error("Could not refresh the generated disc files.", e);
        }
    }

    private DiscResources() {
    }
}
