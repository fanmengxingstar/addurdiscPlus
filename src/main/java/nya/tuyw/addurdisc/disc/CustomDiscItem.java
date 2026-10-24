package nya.tuyw.addurdisc.disc;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import nya.tuyw.addurdisc.init.ModInitialize;
import java.util.List;
/** RecordItem provides legacy insertion, comparator output, particles and hopper compatibility. */
public class CustomDiscItem extends RecordItem {
    public CustomDiscItem(Properties properties) {
        super(SongBinding.COMPARATOR_OUTPUT, ModInitialize.PLACEHOLDER, properties, 7200);
    }
    @Override public Component getName(ItemStack stack) {
        SongBinding binding = SongBinding.fromStack(stack);
        return binding == null || !binding.isBound() ? Component.translatable("item.addurdisc.custom_disc") : binding.description();
    }
    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        SongBinding binding = SongBinding.fromStack(stack);
        if (binding == null || !binding.isBound()) lines.add(Component.translatable("item.addurdisc.custom_disc.desc"));
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        SongBinding binding = SongBinding.fromStack(context.getItemInHand());
        if (binding == null || !binding.isBound()) return InteractionResult.PASS;
        return super.useOn(context);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide) return InteractionResultHolder.pass(player.getItemInHand(hand));
        DiscPickers.open.accept(player, hand);
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}
