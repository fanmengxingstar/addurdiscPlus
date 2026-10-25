package nya.tuyw.addurdisc.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the same blank-disc rule to hoppers. */
@Mixin(JukeboxBlockEntity.class)
public class JukeboxBlockEntityMixin {
    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
    private void addurdisc$rejectBlankDisc(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.is(ModInitialize.CUSTOM_DISC)) {
            SongBinding binding = stack.get(ModInitialize.DISC_SONG);
            if (binding == null || !binding.isBound()) {
                cir.setReturnValue(false);
            }
        }
    }

}
