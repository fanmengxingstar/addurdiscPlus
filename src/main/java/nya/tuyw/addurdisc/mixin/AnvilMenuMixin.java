package nya.tuyw.addurdisc.mixin;

import net.minecraft.network.chat.Component;
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

    @Shadow private String itemName;

    private AnvilMenuMixin() {
        super(null, 0, null, null);
    }


    @Inject(method = "createResult", at = @At("HEAD"), cancellable = true)
    private void addurdisc$copySong(CallbackInfo ci) {
        Container inputs = this.inputSlots;
        ItemStack blank = inputs.getItem(0);
        ItemStack source = inputs.getItem(1);
        if (!blank.is(ModInitialize.CUSTOM_DISC) || !source.is(ModInitialize.CUSTOM_DISC)) {
            return;
        }

        SongBinding existing = SongBinding.fromStack(blank);
        SongBinding song = SongBinding.fromStack(source);
        if ((existing != null && existing.isBound()) || song == null || !song.isBound()) {
            return;
        }

        ItemStack output = blank.copy();
        song.applyTo(output);
        if (this.itemName == null || this.itemName.isBlank()) {
            output.remove(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        } else if (!this.itemName.equals(blank.getHoverName().getString())) {
            output.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal(this.itemName));
        }
        this.resultSlots.setItem(0, output);
        this.cost.set(1);
        this.repairItemCountCost = 0;
        // onTake below preserves the original source while vanilla consumes the blank and one level.

        this.broadcastChanges();
        ci.cancel();
    }
    @org.spongepowered.asm.mixin.Unique private boolean addurdisc$copying;
    @Inject(method = "onTake", at = @At("HEAD"))
    private void addurdisc$preserveSource(net.minecraft.world.entity.player.Player player, ItemStack stack, CallbackInfo ci) {
        SongBinding blank = SongBinding.fromStack(this.inputSlots.getItem(0));
        SongBinding source = SongBinding.fromStack(this.inputSlots.getItem(1));
        this.addurdisc$copying = this.inputSlots.getItem(0).is(ModInitialize.CUSTOM_DISC)
                && this.inputSlots.getItem(1).is(ModInitialize.CUSTOM_DISC)
                && (blank == null || !blank.isBound()) && source != null && source.isBound();
    }
    @org.spongepowered.asm.mixin.injection.Redirect(method = "onTake", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/Container;setItem(ILnet/minecraft/world/item/ItemStack;)V"))
    private void addurdisc$keepOriginal(Container container, int slot, ItemStack stack) {
        if (slot != 1 || !this.addurdisc$copying) container.setItem(slot, stack);
    }
    @Inject(method = "onTake", at = @At("TAIL"))
    private void addurdisc$finishCopy(net.minecraft.world.entity.player.Player player, ItemStack stack, CallbackInfo ci) {
        this.addurdisc$copying = false;
    }
}
