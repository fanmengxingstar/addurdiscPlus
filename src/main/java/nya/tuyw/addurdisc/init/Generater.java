package nya.tuyw.addurdisc.init;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Generater {
    /**
     * Pack formats of Minecraft 26.2 (version.json -> pack_version):
     * resource_major 88.0 and data_major 107.1. The generated folder carries both "assets" and
     * "data", so the declared range has to cover the resource format (client side) as well as the
     * data format (server side). Bump these whenever the mod is updated to a newer Minecraft version.
     */
    private static final String RESOURCE_FORMAT = "[88,0]";
    private static final String DATA_FORMAT = "[107,1]";

    private static void writein(String path, String content) {
        try (BufferedWriter bufferedWriter = Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8)) {
            bufferedWriter.write(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void GenTexturepng(InputStream inputStream, Path path, List<String> names) {
        try {
            BufferedImage image = ImageIO.read(inputStream);
            names.forEach((name) -> {
                File f = path.resolve("disc_" + name + ".png").toFile();
                if (f.exists()) {
                    return;
                }
                try {
                    ImageIO.write(image, "PNG", f);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Writes both halves of a modern item model:
     * <ul>
     *     <li>{@code models/item/disc_<name>.json} – the model itself, reusing vanilla's music disc template</li>
     *     <li>{@code items/disc_<name>.json} – the item model definition pointing at that model</li>
     * </ul>
     */
    public static void GenModelsjson(Path modelsPath, Path itemDefinitionsPath, List<String> names) {
        names.forEach((name) -> {
            String modelJson = "{\"parent\":\"minecraft:item/template_music_disc\",\"textures\":{\"layer0\":\"addurdisc:item/disc_"
                    + name + "\"}}";
            writein(modelsPath.resolve("disc_" + name + ".json").toString(), modelJson);

            String definitionJson = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"addurdisc:item/disc_"
                    + name + "\"}}";
            writein(itemDefinitionsPath.resolve("disc_" + name + ".json").toString(), definitionJson);
        });
    }

    public static void GenLangjson(Path path, List<String> names) {
        File f = path.resolve("en_us.json").toFile();
        StringBuilder langjson = new StringBuilder("{");
        if (names != null && !names.isEmpty()) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                langjson.append("\"item.addurdisc.disc_").append(name).append("\":\"MusicDisc-").append(name)
                        .append("\",\"item.addurdisc.disc_").append(name).append(".desc\":\"").append(name).append(" - added by 'addurdisc'\"");
                if (i < names.size() - 1) {
                    langjson.append(",\n");
                }
            }
        }
        langjson.append("}");
        writein(f.toString(), langjson.toString());
    }

    public static void GenPackmcmeta(Path path) {
        File f = path.resolve("pack.mcmeta").toFile();
        String json = "{\"pack\":{\"description\":\"addurdisc's resources\","
                + "\"min_format\":" + RESOURCE_FORMAT + ","
                + "\"max_format\":" + DATA_FORMAT + "}}";
        writein(f.toString(), json);
    }

    public static void GenSoundsjson(Path path, List<String> names) {
        File f = path.resolve("sounds.json").toFile();
        StringBuilder jsonBuilder = new StringBuilder("{");
        if (names != null && !names.isEmpty()) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                jsonBuilder.append("\"").append(name).append("\":{\"category\":\"record\",\"sounds\":[{\"name\":\"addurdisc:").append(name).append("\",\"stream\":true}]}");
                if (i < names.size() - 1) {
                    jsonBuilder.append("\n,");
                }
            }
        }
        jsonBuilder.append("}");
        writein(f.toString(), jsonBuilder.toString());
    }

    public static void GenItemTags(Path path, List<String> names) {
        File f = path.toFile();
        StringBuilder datajson = new StringBuilder("{\"values\":[");
        if (names != null && !names.isEmpty()) {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                datajson.append("\"addurdisc:disc_").append(name).append("\"");
                if (i < names.size() - 1) {
                    datajson.append("\n,");
                }
            }
        }
        datajson.append("]}");
        writein(f.toString(), datajson.toString());
    }
}
