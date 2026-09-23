package nya.tuyw.addurdisc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import nya.tuyw.addurdisc.AddurDisc;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Reads {@code config/AddurDisc-common.json} and keeps the result in a cache that is keyed on the
 * file's size and modification time, so an edit is picked up on the next access without a restart
 * while a hot loop does not re-parse the file.
 */
public final class ConfigHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("AddurDisc-common.json");

    private static volatile String stamp;
    private static volatile Config cached;

    public static Config get() {
        String current = fileStamp();
        Config local = cached;
        if (local != null && current.equals(stamp)) {
            return local;
        }
        synchronized (ConfigHandler.class) {
            if (cached != null && current.equals(stamp)) {
                return cached;
            }
            Config config = load();
            cached = config;
            stamp = fileStamp();
            return config;
        }
    }

    private static Config load() {
        Config loaded = null;
        boolean firstRun = false;
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
            loaded = GSON.fromJson(reader, Config.class);
        } catch (NoSuchFileException e) {
            firstRun = true;
            AddurDisc.LOGGER.debug("No AddurDisc config yet, writing the defaults.");
        } catch (Exception e) {
            // A broken file is the player's own data: it stays on disk so they can fix it, instead
            // of being silently overwritten by the defaults, and only the in memory values fall
            // back. The next edit re-triggers a load through the file stamp either way.
            AddurDisc.LOGGER.error("Failed to read {}, using defaults without touching the file. Fix or delete it to silence this.", CONFIG_FILE, e);
        }

        if (loaded == null) {
            Config defaults = new Config();
            if (firstRun) {
                try (Writer writer = Files.newBufferedWriter(CONFIG_FILE, StandardCharsets.UTF_8)) {
                    GSON.toJson(defaults, writer);
                } catch (Exception e) {
                    AddurDisc.LOGGER.error("Failed to write the AddurDisc config", e);
                }
            }
            return defaults;
        }
        return clamp(loaded);
    }

    /**
     * Replaces anything out of range with the default. An unclamped zero used to turn every track
     * into a silent one, because the song length is what the jukebox counts down.
     */
    private static Config clamp(Config config) {
        Config fallback = new Config();
        if (config.customPathName == null || !isSingleSegmentName(config.customPathName)) {
            config.customPathName = fallback.customPathName;
        }
        if (config.customPath == null) {
            config.customPath = "";
        }
        if (!Float.isFinite(config.defaultSongLengthSeconds)
                || config.defaultSongLengthSeconds < 1.0F
                || config.defaultSongLengthSeconds > 3600.0F) {
            config.defaultSongLengthSeconds = fallback.defaultSongLengthSeconds;
        }
        if (config.ffmpegPath == null) {
            config.ffmpegPath = "";
        }
        config.maxSongMegabytes = clampToInt(config.maxSongMegabytes, fallback.maxSongMegabytes, 1, 128);
        config.maxStoreMegabytes = clampToInt(config.maxStoreMegabytes, fallback.maxStoreMegabytes,
                config.maxSongMegabytes, 8192);
        config.maxTracks = clampToInt(config.maxTracks, fallback.maxTracks, 1, 4096);
        return config;
    }

    private static int clampToInt(int value, int fallback, int min, int max) {
        return value < min || value > max ? fallback : value;
    }

    /** @return whether {@code name} is one plain folder name rather than a path that escapes. */
    private static boolean isSingleSegmentName(String name) {
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            return false;
        }
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0
                && name.indexOf(':') < 0;
    }

    private static String fileStamp() {
        try {
            BasicFileAttributes attributes = Files.readAttributes(CONFIG_FILE, BasicFileAttributes.class);
            return attributes.size() + ":" + attributes.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return "missing";
        }
    }

    private ConfigHandler() {
    }
}
