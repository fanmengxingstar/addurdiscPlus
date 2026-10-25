package nya.tuyw.addurdisc.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Makes an imported disc resolve to its track.
 *
 * <p>{@link JukeboxSong#fromStack(HolderLookup.Provider, ItemStack)} is the single place vanilla asks
 * a stack which song it carries, so the jukebox insertion check, comparator output, song length, and
 * tooltip all follow whatever is returned here.</p>
 */
@Mixin(JukeboxSong.class)
public class JukeboxSongMixin {

    @Inject(method = "fromStack", at = @At("HEAD"), cancellable = true)
    private static void addurdisc$useBoundSong(HolderLookup.Provider registries, ItemStack stack,
                                               CallbackInfoReturnable<Optional<Holder<JukeboxSong>>> cir) {
        SongBinding binding = stack.get(ModInitialize.DISC_SONG);
        if (binding != null && binding.isBound()) {
            cir.setReturnValue(Optional.of(binding.asSong()));
        } else if (stack.is(ModInitialize.CUSTOM_DISC)) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
