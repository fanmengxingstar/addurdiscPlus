package nya.tuyw.addurdisc.mixin;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies a bound disc's song onto a blank disc without consuming the source disc. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin extends ItemCombinerMenu {
    @Shadow @Final private DataSlot cost;
    @Shadow private int repairItemCountCost;
    @Shadow private String itemName;

    @Unique
    private ItemStack addurdisc$sourceToRestore = ItemStack.EMPTY;
    @Unique
    private boolean addurdisc$handlingTake;

    private AnvilMenuMixin() {
        super(null, 0, null, null);
    }

    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true)
    private void addurdisc$copySong(CallbackInfo ci) {
        if (!this.addurdisc$handlingTake) {
            this.addurdisc$sourceToRestore = ItemStack.EMPTY;
        }
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
        this.addurdisc$sourceToRestore = source.copy();
        this.cost.set(1);
        this.repairItemCountCost = 0;
        this.broadcastChanges();
        ci.cancel();
    }

    @Inject(method = "onTake", at = @At("HEAD"))
    private void addurdisc$beginTaking(Player player, ItemStack output, CallbackInfo ci) {
        this.addurdisc$handlingTake = true;
    }

    @Inject(method = "onTake", at = @At("TAIL"))
    private void addurdisc$restoreCopiedSource(Player player, ItemStack output, CallbackInfo ci) {
        ItemStack source = this.addurdisc$sourceToRestore;
        this.addurdisc$sourceToRestore = ItemStack.EMPTY;
        this.addurdisc$handlingTake = false;
        if (!source.isEmpty()) {
            // 1.21.1 clears input 1 when repairItemCountCost is zero. Restore the exact source stack
            // after vanilla has charged the one-level cost and consumed the blank disc.
            this.inputSlots.setItem(1, source);
            this.broadcastChanges();
        }
    }
}
