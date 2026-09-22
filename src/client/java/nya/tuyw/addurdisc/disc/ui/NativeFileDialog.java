package nya.tuyw.addurdisc.disc.ui;

import net.minecraft.network.chat.Component;
import nya.tuyw.addurdisc.AddurDisc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
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
            // One disc holds one track, so ask for a single result rather than a file list.
            return TinyFileDialogs.tinyfd_openFileDialog(title, "", extensions, FILTER_DESCRIPTION, true);
        }
    }

    private static Optional<Path> toPath(String picked) {
        // Some platforms still hand back a comma separated list even for a single result.
        int comma = picked.indexOf(',');
        String first = (comma < 0 ? picked : picked.substring(0, comma)).strip();
        if (first.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(first));
        } catch (InvalidPathException e) {
            AddurDisc.LOGGER.warn("The file dialog returned an unusable path: {}", picked);
            return Optional.empty();
        }
    }

    private static Optional<Path> awtDialog(String title) {
        FileDialog dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
        dialog.setFile("*.*");
        dialog.setVisible(true);
        String file = dialog.getFile();
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(new File(dialog.getDirectory(), file).toPath());
    }

    private NativeFileDialog() {
    }
}
