package nya.tuyw.addurdisc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.net.DiscPlayback;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Announces runtime songs and keeps their listeners in sync with the jukebox lifecycle. */
@Mixin(net.minecraft.world.item.JukeboxSongPlayer.class)
public class JukeboxSongPlayerMixin {
    @Shadow @Final private BlockPos blockPos;
    @Shadow private Holder<JukeboxSong> song;
    @Shadow private long ticksSinceSongStarted;
    @Unique private boolean addurdisc$announced;

    @Inject(method = "play", at = @At("TAIL"))
    private void addurdisc$announceImportedSong(LevelAccessor level, Holder<JukeboxSong> song, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            // Vanilla play also restarts the same song, so its previous subscription must end.
            DiscPlayback.stop(serverLevel, this.blockPos);
            addurdisc$updateListeners(serverLevel, song);
            this.addurdisc$announced = true;
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void addurdisc$syncListeners(LevelAccessor level, BlockState state, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel && this.song != null
                && (!this.addurdisc$announced || this.ticksSinceSongStarted % 20 == 0)) {
            addurdisc$updateListeners(serverLevel, this.song);
            this.addurdisc$announced = true;
        }
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void addurdisc$stopListeners(LevelAccessor level, BlockState state, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            DiscPlayback.stop(serverLevel, this.blockPos);
            this.addurdisc$announced = false;
        }
    }

    @Unique
    private void addurdisc$updateListeners(ServerLevel level, Holder<JukeboxSong> song) {
        ResourceLocation sound = song.value().soundEvent().value().location();
        if (AddurDisc.MODID.equals(sound.getNamespace())
                && level.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).getId(song.value()) < 0) {
            DiscPlayback.update(level, this.blockPos, sound, this.ticksSinceSongStarted);
        }
    }
}
