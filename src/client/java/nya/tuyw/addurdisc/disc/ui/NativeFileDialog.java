package nya.tuyw.addurdisc.disc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import nya.tuyw.addurdisc.AddurDisc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import net.minecraft.Util;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Opens an asynchronous OS file chooser using the legacy game's bundled TinyFD library. */
public final class NativeFileDialog {
    /** Filter shown in the chooser; ffmpeg on the server can read more formats than these. */
    private static final String FILTERS = "mp3;wav;ogg;flac;m4a;aac;opus;mp4;mkv;webm;avi;mov";
    private static final String FILTER_DESCRIPTION = "Music and video";

    /** Called from the file-picker screen on the client thread. Empty means the user canceled. */
    public static CompletableFuture<Optional<Path>> pickMusicFile() {
        String title = Component.translatable("addurdisc.screen.title").getString();
        return CompletableFuture.supplyAsync(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                String[] extensions = FILTERS.split(";");
                PointerBuffer filters = stack.mallocPointer(extensions.length);
                for (String extension : extensions) filters.put(stack.UTF8("*." + extension));
                filters.flip();
                String picked = TinyFileDialogs.tinyfd_openFileDialog(title, null, filters, FILTER_DESCRIPTION, false);
                return picked == null ? Optional.<Path>empty() : toPath(picked);
            }
        }, Util.ioPool()).exceptionallyCompose(error -> {
            AddurDisc.LOGGER.debug("TinyFD file dialog failed, falling back to AWT", error);
            return awtDialog(title);
        });
    }

    private static Optional<Path> toPath(String picked) {
        if (picked.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(picked));
        } catch (InvalidPathException e) {
            AddurDisc.LOGGER.warn("The file dialog returned an unusable path: {}", picked);
            return Optional.empty();
        }
    }

    private static CompletableFuture<Optional<Path>> awtDialog(String title) {
        CompletableFuture<Optional<Path>> result = new CompletableFuture<>();
        EventQueue.invokeLater(() -> {
            try {
                FileDialog dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
                try {
                    dialog.setFile("*.*");
                    dialog.setAlwaysOnTop(true);
                    dialog.setVisible(true);
                    String file = dialog.getFile();
                    result.complete(file == null ? Optional.empty()
                            : Optional.of(new File(dialog.getDirectory(), file).toPath()));
                } finally {
                    dialog.dispose();
                }
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private NativeFileDialog() {
    }
}
