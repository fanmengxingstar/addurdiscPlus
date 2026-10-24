package nya.tuyw.addurdisc.mixin;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.server.level.ServerLevel;
import nya.tuyw.addurdisc.disc.net.DiscPlayback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** setRemoved is inherited in legacy jukeboxes, so inject its declaring class. */
@Mixin(BlockEntity.class)
public class JukeboxRemovalMixin {
    @Inject(method="setRemoved",at=@At("HEAD"))
    private void addurdisc$unload(CallbackInfo ci) {
        if ((Object)this instanceof JukeboxBlockEntity jukebox && jukebox.getLevel() instanceof ServerLevel level)
            DiscPlayback.stop(level,jukebox.getBlockPos());
    }
}
