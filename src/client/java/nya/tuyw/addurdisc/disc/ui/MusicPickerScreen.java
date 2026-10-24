package nya.tuyw.addurdisc.disc.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.audio.ImportFailedException;
import nya.tuyw.addurdisc.disc.net.DiscClientNetwork;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The screen a blank disc opens: it hands the player an operating system file chooser, then uploads
 * their selection for server-side conversion through {@link DiscClientNetwork}.
 *
 * <p>The chooser is asynchronous; file reading and hashing run on the shared io pool and report back
 * through {@link net.minecraft.client.Minecraft#executeIfPossible}. The layout follows the same idea
 * as camerapture's upload screen: a title, one line of status, an underlined browse link, and the
 * whole window accepting a dropped file.</p>
 */
public class MusicPickerScreen extends Screen implements DiscClientNetwork.ImportListener {
    private final InteractionHand hand;
    private final ClientPacketListener connection;
    private Component message = Component.translatable("addurdisc.screen.hint");
    private boolean failed;
    /** True while the file chooser or source upload is being prepared. */
    private boolean busy;

    public MusicPickerScreen(InteractionHand hand) {
        super(Component.translatable("addurdisc.screen.title").withStyle(ChatFormatting.BOLD));
        this.hand = hand;
        this.connection = net.minecraft.client.Minecraft.getInstance().getConnection();
    }

    @Override
    protected void init() {
        super.init();
        Component label = Component.translatable("addurdisc.screen.browse").withStyle(ChatFormatting.UNDERLINE);
        int labelWidth = font.width(label);
        addRenderableWidget(new PlainTextButton(
                width / 2 - labelWidth / 2, height / 2 + font.lineHeight + 4, labelWidth, font.lineHeight,
                label, button -> pickFile(), font));
        DiscClientNetwork.setListener(this);
    }

    @Override
    public void removed() {
        // Only releases the callback if this screen is still the one holding it, so a screen that is
        // replaced before its import lands cannot switch off the one that took its place.
        DiscClientNetwork.clearListener(this);
        super.removed();
    }

    /** A transfer finished while this screen was open; there is nothing left to look at. */
    @Override
    public void onImported() {
        onClose();
    }

    /** Runs on the client thread, from inside a packet handler. */
    @Override
    public void report(Component message, boolean failed) {
        if (failed) busy = false;
        show(message, failed);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(font, this.title, width / 2, height / 2 - font.lineHeight * 2 - 14, 0xFFFFFF);
        graphics.drawCenteredString(font, message, width / 2, height / 2 - font.lineHeight - 4,
                failed ? 0xFF5555 : 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("addurdisc.screen.supported"),
                width / 2, height / 2 + font.lineHeight * 3 + 8, 0xAAAAAA);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (paths.isEmpty() || !beginWork()) {
            return;
        }
        Path picked = paths.get(0);
        runAsync("upload preparation", () -> uploadSource(picked));
    }

    /** An import keeps the world running; pausing to browse a folder would only annoy the player. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void pickFile() {
        if (!beginWork()) {
            return;
        }
        NativeFileDialog.pickMusicFile().whenComplete((picked, error) -> {
            if (error != null) {
                AddurDisc.LOGGER.error("The file chooser failed.", error);
                finish(new ImportFailedException(String.valueOf(error.getMessage())).toComponent(), true);
            } else if (picked.isEmpty()) {
                finish(Component.translatable("addurdisc.screen.hint"), false);
            } else {
                runAsync("upload preparation", () -> uploadSource(picked.get()));
            }
        });
    }

    /**
     * Runs the blocking half of an import off the client thread, and always tells the screen when it
     * is over. Anything that escaped unnoticed here used to leave the screen stuck on "converting"
     * with no way to start again short of reopening it.
     */
    private void runAsync(String what, Step body) {
        CompletableFuture.runAsync(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    finish(new ImportFailedException("Conversion was interrupted.").toComponent(), true);
                    return;
                }
                AddurDisc.LOGGER.error("The {} step of an import failed.", what, e);
                finish(new ImportFailedException(String.valueOf(e.getMessage())).toComponent(), true);
            }
        }, Util.ioPool());
    }

    /** A blocking import step allowed to report the exceptions it is written to throw. */
    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private void uploadSource(Path source) {
        try {
            DiscClientNetwork.upload(source, hand, connection, this);
        } catch (ImportFailedException | java.io.IOException e) {
            AddurDisc.LOGGER.warn("Could not import {}: {}", source.getFileName(), e.getMessage());
            finish(e instanceof ImportFailedException importFailed
                    ? importFailed.toComponent()
                    : new ImportFailedException(String.valueOf(e.getMessage())).toComponent(), true);
        }
    }

    /** @return false when a pick or upload is already in flight */
    private boolean beginWork() {
        if (busy) {
            return false;
        }
        busy = true;
        show(Component.translatable("addurdisc.screen.processing"), false);
        return true;
    }

    /** Applies a result that arrived off the client thread, once that thread picks it up. */
    private void finish(Component message, boolean failed) {
        minecraft.execute(() -> {
            busy = false;
            show(message, failed);
        });
    }

    private void show(Component message, boolean failed) {
        this.message = message;
        this.failed = failed;
    }
}
