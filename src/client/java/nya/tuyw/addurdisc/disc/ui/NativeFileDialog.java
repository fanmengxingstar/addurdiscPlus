package nya.tuyw.addurdisc.disc.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import nya.tuyw.addurdisc.AddurDisc;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.lwjgl.sdl.SDLDialog.*;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
import static org.lwjgl.sdl.SDLProperties.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * Opens the operating system's file chooser.
 *
 * <p>SDL must open the dialog on the game thread, but delivers its result asynchronously. The AWT
 * fallback uses its own event thread. Neither implementation waits on the game thread.</p>
 */
public final class NativeFileDialog {
    /** Filter shown in the chooser. ffmpeg reads far more than this, but these are what we promise. */
    private static final String FILTERS = "mp3;wav;ogg;flac;m4a;aac;opus;mp4;mkv;webm;avi;mov";
    private static final String FILTER_DESCRIPTION = "Music and video";

    /** Called from the file-picker screen on the client thread. Empty means the user canceled. */
    public static CompletableFuture<Optional<Path>> pickMusicFile() {
        String title = Component.translatable("addurdisc.screen.title").getString();
        CompletableFuture<Optional<Path>> result = new CompletableFuture<>();
        try {
            new SdlDialog(result).open(title, Minecraft.getInstance().getWindow().handle());
        } catch (RuntimeException | LinkageError e) {
            result.completeExceptionally(e);
        }
        return result.exceptionallyCompose(error -> {
            AddurDisc.LOGGER.debug("SDL file dialog failed, falling back to AWT", error);
            return awtDialog(title);
        });
    }

    /** Owns native data until SDL delivers its one callback, including cancellation and failure. */
    private static final class SdlDialog extends SDL_DialogFileCallback {
        private final CompletableFuture<Optional<Path>> result;
        private final ByteBuffer name = memUTF8(FILTER_DESCRIPTION);
        private final ByteBuffer pattern = memUTF8(FILTERS);
        private final SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);

        private SdlDialog(CompletableFuture<Optional<Path>> result) {
            this.result = result;
            filters.get(0).name(name).pattern(pattern);
        }

        private void open(String title, long window) {
            int properties = SDL_CreateProperties();
            try {
                if (properties == 0
                        || !SDL_SetPointerProperty(properties, SDL_PROP_FILE_DIALOG_WINDOW_POINTER, window)
                        || !SDL_SetPointerProperty(properties, SDL_PROP_FILE_DIALOG_FILTERS_POINTER, filters.address())
                        || !SDL_SetNumberProperty(properties, SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, 1)
                        || !SDL_SetBooleanProperty(properties, SDL_PROP_FILE_DIALOG_MANY_BOOLEAN, false)
                        || !SDL_SetStringProperty(properties, SDL_PROP_FILE_DIALOG_TITLE_STRING, title)) {
                    throw new IllegalStateException(SDL_GetError());
                }
                SDL_ShowFileDialogWithProperties(SDL_FILEDIALOG_OPENFILE, this, NULL, properties);
            } catch (RuntimeException | LinkageError e) {
                release();
                throw e;
            } finally {
                // SDL copies the properties; the pointed-to filters remain ours until the callback.
                SDL_DestroyProperties(properties);
            }
        }

        @Override
        public void invoke(long userdata, long filelist, int filter) {
            try {
                if (filelist == NULL) {
                    result.completeExceptionally(new IllegalStateException(SDL_GetError()));
                } else {
                    long first = memGetAddress(filelist);
                    // SDL owns the list and frees it on return. Copy the UTF-8 path now.
                    result.complete(first == NULL ? Optional.empty() : toPath(memUTF8(first)));
                }
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            } finally {
                release();
            }
        }

        private void release() {
            filters.free();
            memFree(pattern);
            memFree(name);
            free();
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
