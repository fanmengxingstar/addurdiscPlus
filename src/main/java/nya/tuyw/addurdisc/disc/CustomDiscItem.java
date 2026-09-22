package nya.tuyw.addurdisc.disc;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import nya.tuyw.addurdisc.init.ModInitialize;

/**
 * The disc the player crafts and imports music into.
 *
 * <p>Right clicking a jukebox with it needs no code of its own: vanilla lets any stack carrying the
 * jukebox playable component into the block, which is what {@link nya.tuyw.addurdisc.init.ModInitialize}
 * registers it with. The import screen and the name an imported track gives it are ours.</p>
 */
public class CustomDiscItem extends Item {
    public CustomDiscItem(Properties properties) {
        super(properties);
    }

    /** A disc with a track on it is called after that track; a blank one keeps its own name. */
    @Override
    public Component getName(ItemStack stack) {
        SongBinding binding = stack.get(ModInitialize.DISC_SONG);
        return binding == null || !binding.isBound() ? super.getName(stack) : binding.description();
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide()) {
            return InteractionResult.PASS;
        }
        DiscPickers.open.accept(player, hand);
        return InteractionResult.SUCCESS;
    }
}
