package nya.tuyw.addurdisc.disc.ui;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;
import nya.tuyw.addurdisc.AddurDisc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Opens the operating system's file chooser without blocking the client thread. */
public final class NativeFileDialog {
    /** ffmpeg on the server can read more formats than these. */
    private static final String[] FILTERS = {
            "*.mp3", "*.wav", "*.ogg", "*.flac", "*.m4a", "*.aac", "*.opus",
            "*.mp4", "*.mkv", "*.webm", "*.avi", "*.mov"
    };
    private static final String FILTER_DESCRIPTION = "Music and video";

    /** Called by the picker screen; the blocking native dialog runs on Minecraft's IO pool. */
    public static CompletableFuture<Optional<Path>> pickMusicFile() {
        String title = Component.translatable("addurdisc.screen.title").getString();
        return CompletableFuture.supplyAsync(() -> pickMusicFileBlocking(title), Util.ioPool());
    }

    private static Optional<Path> pickMusicFileBlocking(String title) {
        String picked;
        try {
            picked = tinyfdDialog(title);
        } catch (RuntimeException | LinkageError e) {
            AddurDisc.LOGGER.debug("TinyFileDialogs failed, falling back to AWT", e);
            return awtDialog(title);
        }
        return picked == null ? Optional.empty() : toPath(picked);
    }

    private static String tinyfdDialog(String title) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(FILTERS.length);
            for (String filter : FILTERS) {
                filters.put(stack.UTF8(filter));
            }
            filters.flip();
            // A disc stores one track. A single selection also preserves paths containing commas.
            return TinyFileDialogs.tinyfd_openFileDialog(title, "", filters, FILTER_DESCRIPTION, false);
        }
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

    private static Optional<Path> awtDialog(String title) {
        AtomicReference<Optional<Path>> result = new AtomicReference<>(Optional.empty());
        try {
            // AWT dialogs need their event thread; this method is already off Minecraft's client thread.
            EventQueue.invokeAndWait(() -> {
                FileDialog dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
                try {
                    dialog.setFile("*.*");
                    dialog.setAlwaysOnTop(true);
                    dialog.setVisible(true);
                    String file = dialog.getFile();
                    if (file != null) {
                        result.set(Optional.of(new File(dialog.getDirectory(), file).toPath()));
                    }
                } finally {
                    dialog.dispose();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException | RuntimeException e) {
            AddurDisc.LOGGER.debug("The AWT file dialog failed", e);
        }
        return result.get();
    }

    private NativeFileDialog() {
    }
}
