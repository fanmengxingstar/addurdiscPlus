package nya.tuyw.addurdisc.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import nya.tuyw.addurdisc.disc.DiscClientSounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops an imported track when its jukebox goes quiet.
 *
 * <p>Vanilla stops what it tracks itself, which is only songs it could resolve by registry id.</p>
 */
@Mixin(LevelRenderer.class)
public class LevelEventHandlerMixin {

    @Inject(method = "playStreamingMusic", at = @At("HEAD"), cancellable = true)
    private void addurdisc$stopImportedSong(net.minecraft.sounds.SoundEvent sound, BlockPos pos, CallbackInfo ci) {
        if (sound == null) DiscClientSounds.stop(pos);
        else if (sound == nya.tuyw.addurdisc.init.ModInitialize.PLACEHOLDER) ci.cancel();
    }
}
