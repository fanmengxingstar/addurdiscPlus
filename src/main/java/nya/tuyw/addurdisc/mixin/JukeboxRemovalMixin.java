package nya.tuyw.addurdisc.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import nya.tuyw.addurdisc.disc.net.DiscPlayback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In these versions the jukebox inherits setRemoved from BlockEntity. */
@Mixin(BlockEntity.class)
public class JukeboxRemovalMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void addurdisc$stopOnRemoval(CallbackInfo ci) {
        if ((Object) this instanceof JukeboxBlockEntity jukebox
                && jukebox.getLevel() instanceof ServerLevel level) {
            DiscPlayback.stop(level, jukebox.getBlockPos());
        }
    }
}
