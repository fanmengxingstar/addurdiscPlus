package nya.tuyw.addurdisc.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Read-only view of {@link ConfigHandler} with the values the rest of the mod works in. Every
 * getter goes back to the config, so nothing here can go stale.
 */
public final class ConfigUtil {
    private static final Path GAME_DIR = FabricLoader.getInstance().getGameDir();

    /** Root the mod writes to; {@code customPath} is resolved against the game directory. */
    public static Path getNormalPath() {
        String customPath = ConfigHandler.get().customPath;
        return customPath.isEmpty() ? GAME_DIR : GAME_DIR.resolve(customPath);
    }

    public static String getResourceName() {
        return ConfigHandler.get().customPathName;
    }

    public static Path getResourcePath() {
        return getNormalPath().resolve(getResourceName());
    }

    public static float getDefaultSongLengthSeconds() {
        return ConfigHandler.get().defaultSongLengthSeconds;
    }

    public static String getFfmpegCommand() {
        String ffmpegPath = ConfigHandler.get().ffmpegPath;
        return ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath.trim();
    }

    public static long getMaxSongBytes() {
        return megabytesToBytes(ConfigHandler.get().maxSongMegabytes);
    }

    public static long getMaxStoreBytes() {
        return megabytesToBytes(ConfigHandler.get().maxStoreMegabytes);
    }

    public static int getMaxTracks() {
        return ConfigHandler.get().maxTracks;
    }

    private static long megabytesToBytes(int megabytes) {
        return megabytes * 1024L * 1024L;
    }

    private ConfigUtil() {
    }
}
