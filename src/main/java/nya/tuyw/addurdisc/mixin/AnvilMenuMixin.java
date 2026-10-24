package nya.tuyw.addurdisc.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies a bound disc's song onto a blank disc without consuming the source disc. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin extends ItemCombinerMenu {
    @Shadow @Final private DataSlot cost;
    @Shadow private int repairItemCountCost;
    @Shadow private boolean onlyRenaming;
    @Shadow private String itemName;

    private AnvilMenuMixin() {
        super(null, 0, null, null, null);
    }

    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true)
    private void addurdisc$copySong(CallbackInfo ci) {
        Container inputs = this.inputSlots;
        ItemStack blank = inputs.getItem(0);
        ItemStack source = inputs.getItem(1);
        if (!blank.is(ModInitialize.CUSTOM_DISC) || !source.is(ModInitialize.CUSTOM_DISC)) {
            return;
        }

        SongBinding existing = blank.get(ModInitialize.DISC_SONG);
        SongBinding song = source.get(ModInitialize.DISC_SONG);
        if ((existing != null && existing.isBound()) || song == null || !song.isBound()) {
            return;
        }

        ItemStack output = blank.copy();
        output.set(ModInitialize.DISC_SONG, song);
        if (StringUtil.isBlank(this.itemName)) {
            output.remove(DataComponents.CUSTOM_NAME);
        } else if (!this.itemName.equals(blank.getHoverName().getString())) {
            output.set(DataComponents.CUSTOM_NAME, Component.literal(this.itemName));
        }
        this.resultSlots.setItem(0, output);
        this.cost.set(1);
        this.repairItemCountCost = 0;
        // Vanilla's onTake keeps the second input when it is only renaming and no
        // repair materials are owed. The same path preserves our source disc.
        this.onlyRenaming = true;
        this.broadcastChanges();
        ci.cancel();
    }
}
