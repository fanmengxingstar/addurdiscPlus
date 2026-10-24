package nya.tuyw.addurdisc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * Keeps the jukebox playable component honest for these discs.
 *
 * <p>Suppresses its tooltip line and blocks the insertion of a disc that has no track, because the
 * component every one of these carries is only a placeholder the item had to name at registration
 * time; an imported track lives in its own component instead.</p>
 */
@Mixin(JukeboxPlayable.class)
public class JukeboxPlayableTooltipMixin {

    /** @return whether a stored binding names a track that can actually be played */
    private static boolean isPlayable(SongBinding binding) {
        return binding != null && binding.isBound();
    }

    /**
     * Turns away a disc that carries no track yet.
     *
     * <p>Vanilla only asks whether the stack has the jukebox playable component, which every one of
     * these discs has, so a blank one would otherwise be swallowed by the block: its song is the
     * zero length placeholder, which the player treats as finished on the very first tick, leaving
     * the jukebox silent with the record stuck inside until someone hits it.</p>
     */
    @Inject(method = "tryInsertIntoJukebox", at = @At("HEAD"), cancellable = true)
    private static void addurdisc$rejectBlankDisc(Level level, BlockPos pos, ItemStack stack, Player player,
                                                  CallbackInfoReturnable<InteractionResult> cir) {
        if (stack.is(ModInitialize.CUSTOM_DISC) && !isPlayable(stack.get(ModInitialize.DISC_SONG))) {
            cir.setReturnValue(InteractionResult.TRY_WITH_EMPTY_HAND);
        }
    }
}
