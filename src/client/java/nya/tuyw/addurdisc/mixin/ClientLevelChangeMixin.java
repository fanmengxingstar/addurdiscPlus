package nya.tuyw.addurdisc.mixin;

import net.minecraft.client.Minecraft;
import nya.tuyw.addurdisc.disc.DiscClientSounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears pending streams immediately when the game changes or leaves the client level. */
@Mixin(Minecraft.class)
public class ClientLevelChangeMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void addurdisc$clearOnLevelChange(CallbackInfo ci) {
        DiscClientSounds.clear();
    }
}
