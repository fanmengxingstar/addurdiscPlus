package nya.tuyw.addurdisc.Config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import nya.tuyw.addurdisc.AddurDisc;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ConfigHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("AddurDisc-common.json").toFile();
    private static Config config;

    static {
        if (!CONFIG_FILE.exists()) {
            writeDefaultConfig();
        } else {
            readConfig();
        }
    }

    private static void writeDefaultConfig() {
        config = new Config();
        try (Writer writer = Files.newBufferedWriter(CONFIG_FILE.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        } catch (Exception e) {
            AddurDisc.LOGGER.error("Failed to write the AddurDisc config, using defaults", e);
        }
    }

    private static void readConfig() {
        Config loaded = null;
        try (Reader reader = Files.newBufferedReader(CONFIG_FILE.toPath(), StandardCharsets.UTF_8)) {
            loaded = GSON.fromJson(reader, Config.class);
        } catch (Exception e) {
            AddurDisc.LOGGER.error("Failed to read the AddurDisc config, rewriting it", e);
        }

        if (loaded == null) {
            // Empty / malformed file: fall back to a freshly written default config.
            config = new Config();
            try (Writer writer = Files.newBufferedWriter(CONFIG_FILE.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            } catch (Exception e) {
                AddurDisc.LOGGER.error("Failed to rewrite the AddurDisc config", e);
            }
        } else {
            config = loaded;
        }
    }

    public static Config getConfig() {
        return config;
    }
}
