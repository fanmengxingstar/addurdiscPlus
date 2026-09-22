package nya.tuyw.addurdisc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.AABB;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.PlaySong;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells nearby clients to play a song that vanilla cannot name.
 *
 * <p>Vanilla announces a playback as an index into the {@code jukebox_song} datapack registry, which
 * every client resolves from the copy it was sent at login. A disc imported during a session has no
 * such index, so its playback has to be announced by sound name instead, and clients that have the
 * file will find it through their own resource pack.</p>
 */
@Mixin(net.minecraft.world.item.JukeboxSongPlayer.class)
public class JukeboxSongPlayerMixin {
    /** How far around the jukebox the playback is announced, matching a record's own carrying range. */
    private static final double ANNOUNCE_RANGE = 64.0;

    @Shadow
    @Final
    private BlockPos blockPos;

    @Inject(method = "play", at = @At("TAIL"))
    private void addurdisc$announceImportedSong(LevelAccessor level, net.minecraft.core.Holder<JukeboxSong> song,
                                                CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG).getId(song.value()) >= 0) {
            return;
        }
        Identifier sound = song.value().soundEvent().value().location();
        if (!AddurDisc.MODID.equals(sound.getNamespace())) {
            return;
        }
        for (ServerPlayer player : serverLevel.getEntitiesOfClass(ServerPlayer.class,
                new AABB(this.blockPos).inflate(ANNOUNCE_RANGE))) {
            ServerPlayNetworking.send(player, new PlaySong(sound, this.blockPos));
        }
    }
}
