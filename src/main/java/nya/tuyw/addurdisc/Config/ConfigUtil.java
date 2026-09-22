package nya.tuyw.addurdisc.Config;

import net.fabricmc.loader.api.FabricLoader;
import nya.tuyw.addurdisc.AddurDisc;

import java.nio.file.Path;

public class ConfigUtil {
    private static String path;
    private static String pathname;
    private static boolean dropbycreeper;
    private static boolean refreshlang;
    private static float defaultSongLengthSeconds;
    private static String ffmpegCommand = "ffmpeg";

    public ConfigUtil() {
        refreshConfig();
    }

    public static void refreshConfig() {
        Config config = ConfigHandler.getConfig();
        pathname = config.customPathName;
        dropbycreeper = config.canDropByCreeper;
        refreshlang = config.refreshLang;
        defaultSongLengthSeconds = config.defaultSongLengthSeconds;
        ffmpegCommand = config.ffmpegPath == null || config.ffmpegPath.isBlank()
                ? "ffmpeg"
                : config.ffmpegPath.trim();
        if (config.customPath == null || config.customPath.isEmpty()) {
            path = FabricLoader.getInstance().getGameDir().toString();
        } else {
            path = FabricLoader.getInstance().getGameDir().resolve(config.customPath).toString();
        }
        AddurDisc.LOGGER.debug(path);
    }

    public static Path getNormalPath() {
        return Path.of(path);
    }

    public static String getResourceName() {
        return pathname;
    }

    public static Path getResourcePath() {
        return getNormalPath().resolve(getResourceName());
    }

    public static boolean getDropbycreeper() {
        return dropbycreeper;
    }

    public static boolean getrefreshlang() {
        return refreshlang;
    }

    public static float getDefaultSongLengthSeconds() {
        return defaultSongLengthSeconds;
    }

    public static String getFfmpegCommand() {
        return ffmpegCommand;
    }
}
