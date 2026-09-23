package nya.tuyw.addurdisc.disc.ui;

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

/**
 * Opens the operating system's file chooser.
 *
 * <p>Both implementations block, so this is only ever called from a worker thread. Minecraft's window
 * stays visible while the dialog is up; the picker simply cannot be reached until it comes back.</p>
 */
public final class NativeFileDialog {
    /** Filter shown in the chooser. ffmpeg reads far more than this, but these are what we promise. */
    private static final String[] FILTERS =
            {"*.mp3", "*.wav", "*.ogg", "*.flac", "*.m4a", "*.aac", "*.opus", "*.mp4", "*.mkv", "*.webm", "*.avi", "*.mov"};
    private static final String FILTER_DESCRIPTION = "Music and video";

    public static Optional<Path> pickMusicFile() {
        String title = Component.translatable("addurdisc.screen.title").getString();
        String picked;
        try {
            picked = tinyfdDialog(title);
        } catch (Throwable e) {
            // TinyFileDialogs needs its natives; AWT ships with the JRE.
            AddurDisc.LOGGER.debug("tinyfd was unavailable, falling back to the AWT dialog", e);
            return awtDialog(title);
        }
        return picked == null ? Optional.empty() : toPath(picked);
    }

    private static String tinyfdDialog(String title) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer extensions = stack.mallocPointer(FILTERS.length);
            for (String filter : FILTERS) {
                extensions.put(stack.UTF8(filter));
            }
            extensions.flip();
            // One disc holds one track, so ask for a single result — which also means the answer
            // is the whole path: a file whose own name contains a comma must not be cut at it,
            // the way a comma separated list from a multi select dialog would be.
            return TinyFileDialogs.tinyfd_openFileDialog(title, "", extensions, FILTER_DESCRIPTION, false);
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
        try {
            // The modal dialog lives on the AWT event thread: showing it from any other thread
            // deadlocks on some platforms, and without always-on-top it opens behind the game
            // window, where it looks exactly like such a deadlock.
            FileDialog[] holder = new FileDialog[1];
            EventQueue.invokeAndWait(() -> {
                FileDialog dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
                dialog.setFile("*.*");
                dialog.setAlwaysOnTop(true);
                dialog.setVisible(true);
                holder[0] = dialog;
            });
            FileDialog dialog = holder[0];
            if (dialog == null) {
                return Optional.empty();
            }
            String file = dialog.getFile();
            if (file == null) {
                return Optional.empty();
            }
            return Optional.of(new File(dialog.getDirectory(), file).toPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (InvocationTargetException e) {
            AddurDisc.LOGGER.debug("The AWT file dialog failed", e);
            return Optional.empty();
        }
    }

    private NativeFileDialog() {
    }
}
