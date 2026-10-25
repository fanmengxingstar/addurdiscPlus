package nya.tuyw.addurdisc.init;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.DiscProtocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Writes the files the generated pack is made of. Every write goes to a temporary file that is then
 * moved over the target, because the client and the integrated server refresh this pack from
 * different threads and a reader must never see a half-written {@code sounds.json}.
 */
public final class Generator {

    /**
     * Writes the descriptor the game reads a pack by. The body is produced by vanilla's own section
     * codec rather than a hand-written string, because the shape of {@code supportedFormats} is the
     * part of a pack that moves between versions, and a file that no longer parses reads as a pack
     * that simply is not there.
     */
    public static void writePackMcmeta(Path packRoot) {
        PackMetadataSection section = new PackMetadataSection(Component.translatable("addurdisc.pack.generated"),
                SharedConstants.RESOURCE_PACK_FORMAT, Optional.of(new InclusiveRange<>(
                SharedConstants.RESOURCE_PACK_FORMAT, SharedConstants.RESOURCE_PACK_FORMAT)));
        Optional<JsonElement> body = PackMetadataSection.CODEC.encodeStart(JsonOps.INSTANCE, section).result();
        if (body.isEmpty()) {
            AddurDisc.LOGGER.error("Could not encode the generated resource pack metadata.");
            return;
        }
        JsonObject json = new JsonObject();
        json.add("pack", body.get());
        writeAtomically(packRoot.resolve("pack.mcmeta"), new Gson().toJson(json));
    }

    /**
     * Maps every track to a streamed sound entry. {@code stream} keeps the decoder out of memory,
     * which matters because these are full length songs rather than effect samples, and
     * {@code attenuation_distance} states the carrying range out loud instead of leaving it to
     * whatever vanilla's default happens to be in a future version.
     */
    public static void writeSoundsJson(Path soundsJson, List<String> names) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            String name = names.get(i);
            String escaped = new Gson().toJson(new JsonPrimitive(name));
            json.append(escaped)
                    .append(":{\"category\":\"record\",\"sounds\":[{\"name\":\"addurdisc:")
                    .append(name).append("\",\"stream\":true,\"attenuation_distance\":")
                    .append(DiscProtocol.AUDIBLE_BLOCKS).append("}]}");
        }
        json.append('}');
        writeAtomically(soundsJson, json.toString());
    }

    private static void writeAtomically(Path target, String content) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AddurDisc.LOGGER.error("Could not write {}", target, e);
            deleteQuietly(temp);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover .tmp is invisible to the pack reader and gets overwritten next time.
        }
    }

    private Generator() {
    }
}
