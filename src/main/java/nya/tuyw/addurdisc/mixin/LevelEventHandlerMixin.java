package nya.tuyw.addurdisc.mixin;

import net.minecraft.client.renderer.LevelEventHandler;
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
@Mixin(LevelEventHandler.class)
public class LevelEventHandlerMixin {

    @Inject(method = "stopJukeboxSong", at = @At("HEAD"))
    private void addurdisc$stopImportedSong(BlockPos pos, CallbackInfo ci) {
        DiscClientSounds.stop(pos);
    }
}
