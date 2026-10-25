package nya.tuyw.addurdisc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.Level;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks blank discs before vanilla inserts the hidden registered song used to satisfy its gate. */
@Mixin(JukeboxPlayable.class)
public class JukeboxPlayableTooltipMixin {

    /**
     * Turns away a disc that carries no track yet. Its component is a valid vanilla song holder only
     * to satisfy the presence check; JukeboxSongMixin provides real songs for bound records.
     */
    @Inject(method = "tryInsertIntoJukebox", at = @At("HEAD"), cancellable = true)
    private static void addurdisc$rejectBlankDisc(Level level, BlockPos pos, ItemStack stack, Player player,
                                                  CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (stack.is(ModInitialize.CUSTOM_DISC)) {
            var binding = stack.get(ModInitialize.DISC_SONG);
            if (binding == null || !binding.isBound()) {
                cir.setReturnValue(ItemInteractionResult.FAIL);
            }
        }
    }
}
