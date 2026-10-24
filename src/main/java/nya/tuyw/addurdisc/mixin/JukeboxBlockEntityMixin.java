package nya.tuyw.addurdisc.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.disc.net.DiscPlayback;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the same blank-disc rule to hoppers and closes playback on chunk unload. */
@Mixin(JukeboxBlockEntity.class)
public class JukeboxBlockEntityMixin {
    @Inject(method = "canPlaceItem", at = @At("HEAD"), cancellable = true)
    private void addurdisc$rejectBlankDisc(int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (stack.is(ModInitialize.CUSTOM_DISC)) {
            SongBinding binding = SongBinding.fromStack(stack);
            if (binding == null || !binding.isBound()) {
                cir.setReturnValue(false);
            }
        }
    }

    @org.spongepowered.asm.mixin.Shadow private long tickCount;
    @org.spongepowered.asm.mixin.Shadow private long recordStartedTick;
    @org.spongepowered.asm.mixin.Unique private boolean addurdisc$announced;
    @Inject(method = "startPlaying", at = @At("TAIL"))
    private void addurdisc$start(CallbackInfo ci) {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity)(Object)this;
        if (jukebox.getLevel() instanceof ServerLevel level) {
            DiscPlayback.stop(level,jukebox.getBlockPos());
            addurdisc$sync(jukebox,level);
        }
    }
    @Inject(method = "tick", at = @At("TAIL"))
    private void addurdisc$tick(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
                               net.minecraft.world.level.block.state.BlockState state, CallbackInfo ci) {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity)(Object)this;
        if (level instanceof ServerLevel serverLevel && jukebox.isRecordPlaying()
                && (!addurdisc$announced || (tickCount-recordStartedTick)%20 == 0)) addurdisc$sync(jukebox,serverLevel);
    }
    @Inject(method = "stopPlaying", at = @At("HEAD"))
    private void addurdisc$stop(CallbackInfo ci) {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity)(Object)this;
        if (jukebox.getLevel() instanceof ServerLevel level) DiscPlayback.stop(level,jukebox.getBlockPos());
        addurdisc$announced = false;
    }
    @Inject(method = "shouldRecordStopPlaying", at = @At("HEAD"), cancellable = true)
    private void addurdisc$duration(net.minecraft.world.item.RecordItem record, CallbackInfoReturnable<Boolean> cir) {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity)(Object)this;
        if (!jukebox.getItem(0).is(ModInitialize.CUSTOM_DISC)) return;
        SongBinding song = SongBinding.fromStack(jukebox.getItem(0));
        cir.setReturnValue(song == null || !song.isBound() || tickCount >= recordStartedTick + (long)(song.lengthInSeconds()*20) + 20);
    }
    @org.spongepowered.asm.mixin.Unique private void addurdisc$sync(JukeboxBlockEntity jukebox,ServerLevel level) {
        if (!jukebox.getItem(0).is(ModInitialize.CUSTOM_DISC)) return;
        SongBinding song = SongBinding.fromStack(jukebox.getItem(0));
        if (song != null && song.isBound()) DiscPlayback.update(level,jukebox.getBlockPos(),song.soundId(),Math.max(0L,tickCount-recordStartedTick));
        addurdisc$announced = true;
    }
}
