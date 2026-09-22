package nya.tuyw.addurdisc.disc.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.audio.AudioImporter;
import nya.tuyw.addurdisc.disc.audio.ImportFailedException;
import nya.tuyw.addurdisc.disc.net.DiscClientNetwork;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The screen a blank disc opens: it hands the player an operating system file chooser, converts
 * whatever they pick, and leaves the result to {@link DiscClientNetwork}.
 *
 * <p>The chooser and the conversion both block, so they run on a worker thread and report back
 * through {@link net.minecraft.client.Minecraft#executeIfPossible}. The layout follows the same idea
 * as camerapture's upload screen: a title, one line of status, an underlined browse link, and the
 * whole window accepting a dropped file.</p>
 */
public class MusicPickerScreen extends Screen {
    private final InteractionHand hand;
    private Component message = Component.translatable("addurdisc.screen.hint");
    private boolean failed;
    /** True while the file chooser or ffmpeg is busy, which keeps the player from starting twice. */
    private boolean busy;

    public MusicPickerScreen(InteractionHand hand) {
        super(Component.translatable("addurdisc.screen.title").withStyle(ChatFormatting.BOLD));
        this.hand = hand;
    }

    @Override
    protected void init() {
        super.init();
        Component label = Component.translatable("addurdisc.screen.browse").withStyle(ChatFormatting.UNDERLINE);
        int labelWidth = font.width(label);
        addRenderableWidget(new PlainTextButton(
                width / 2 - labelWidth / 2, height / 2 + font.lineHeight + 4, labelWidth, font.lineHeight,
                label, button -> pickFile(), font));
        DiscClientNetwork.status = (text, isFailure) -> report(text, isFailure);
        // Once the track is on the disc there is nothing left to look at, and the disc in hand says so.
        DiscClientNetwork.imported = this::onClose;
    }

    @Override
    public void removed() {
        DiscClientNetwork.status = DiscClientNetwork.IDLE;
        DiscClientNetwork.imported = DiscClientNetwork.NOTHING;
        super.removed();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, this.title, width / 2, height / 2 - font.lineHeight * 2 - 14, CommonColors.WHITE);
        graphics.centeredText(font, message, width / 2, height / 2 - font.lineHeight - 4,
                failed ? CommonColors.RED : CommonColors.WHITE);
        graphics.centeredText(font, Component.translatable("addurdisc.screen.supported"),
                width / 2, height / 2 + font.lineHeight * 3 + 8, CommonColors.GRAY);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (paths.isEmpty() || !beginWork()) {
            return;
        }
        Path picked = paths.get(0);
        new Thread(() -> convert(picked), "addurdisc-import").start();
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
        new Thread(this::browse, "addurdisc-picker").start();
    }

    /** Waits on a blocking dialog, then converts whatever the player chose. */
    private void browse() {
        try {
            Optional<Path> picked = NativeFileDialog.pickMusicFile();
            if (picked.isEmpty()) {
                report(Component.translatable("addurdisc.screen.hint"), false);
                return;
            }
            convert(picked.get());
        } catch (Throwable e) {
            AddurDisc.LOGGER.error("The file chooser failed.", e);
            report(new ImportFailedException(String.valueOf(e.getMessage())).toComponent(), true);
        }
    }

    private void convert(Path source) {
        try {
            AudioImporter.Converted converted = AudioImporter.convertFile(source);
            minecraft.executeIfPossible(() -> {
                busy = false;
                DiscClientNetwork.upload(converted.suggestedName(), converted.title(), converted.ogg(), hand);
            });
        } catch (ImportFailedException e) {
            AddurDisc.LOGGER.warn("Could not import {}: {}", source.getFileName(), e.getMessage());
            report(e.toComponent(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report(new ImportFailedException("Conversion was interrupted.").toComponent(), true);
        }
    }

    /** @return false when a pick is already in flight or the converter is missing */
    private boolean beginWork() {
        if (busy) {
            return false;
        }
        if (!AudioImporter.isAvailable()) {
            show(Component.translatable("addurdisc.message.no_ffmpeg"), true);
            return false;
        }
        busy = true;
        show(Component.translatable("addurdisc.screen.processing"), false);
        return true;
    }

    /** Applies a result that arrived off the client thread, once that thread picks it up. */
    private void report(Component message, boolean failed) {
        minecraft.executeIfPossible(() -> {
            busy = false;
            show(message, failed);
        });
    }

    private void show(Component message, boolean failed) {
        this.message = message;
        this.failed = failed;
    }
}
