package nya.tuyw.addurdisc.disc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.net.DiscClientNetwork;
import nya.tuyw.addurdisc.disc.store.SongStore;
import nya.tuyw.addurdisc.init.DiscResources;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Plays the tracks that were imported during this session on the client.
 *
 * <p>These are heard by sound name rather than by a registry entry, which is what lets a track work
 * the moment it lands in the sounds folder. The name still has to mean something to the sound engine,
 * and a track that arrived after the game read its sounds does not until the generated pack is re-read
 * here. A track that is not on this computer yet is fetched and played as soon as it is.</p>
 */
public final class DiscClientSounds {
    /**
     * The instance, not just the name, because stopping by name and category stops every jukebox
     * playing that track rather than only the one that went quiet.
     */
    private static final Map<BlockPos, SoundInstance> PLAYING = new HashMap<>();
    /** Jukeboxes whose song was announced before this side could hear it. */
    private static final Map<BlockPos, Identifier> WAITING = new HashMap<>();

    public static void play(Identifier sound, BlockPos pos) {
        stop(pos);
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        if (manager.getSoundEvent(sound) == null) {
            // A track that arrived after the game read its sounds is a name the engine does not know,
            // and the pack describing it can be behind the file it is meant to list.
            DiscResources.refresh();
            reloadSoundDefinitions();
            if (manager.getSoundEvent(sound) == null) {
                await(sound, pos);
                return;
            }
        }
        SoundEvent event = SoundEvent.createVariableRangeEvent(sound);
        SoundInstance instance = SimpleSoundInstance.forJukeboxSong(event, Vec3.atCenterOf(pos));
        SoundEngine.PlayResult result = manager.play(instance);
        if (result == SoundEngine.PlayResult.NOT_STARTED) {
            AddurDisc.LOGGER.warn("The track {} could not be started on this client.", sound);
            return;
        }
        if (result == SoundEngine.PlayResult.STARTED_SILENTLY) {
            // The track is playing, just under a category this player has turned down.
            AddurDisc.LOGGER.warn("The track {} started on a silenced sound category.", sound);
        }
        PLAYING.put(pos.immutable(), instance);
    }

    /**
     * Re-reads the sound definitions of the generated pack, which is the sound half of a resource
     * reload without the loading screen a whole one costs. Both halves run on the calling thread, so
     * the tracks that were just written are known the moment this returns. That is a deliberate
     * trade-off: the cost is logged, because on a large instance it is visible as a stutter.
     */
    public static void reloadSoundDefinitions() {
        Minecraft client = Minecraft.getInstance();
        long startNanos = System.nanoTime();
        Executor inline = command -> command.run();
        client.getSoundManager()
                .reload(new PreparableReloadListener.SharedState(client.getResourceManager()),
                        inline, CompletableFuture::completedFuture, inline)
                .join();
        AddurDisc.LOGGER.debug("Reloaded the sound definitions in {} ms.",
                (System.nanoTime() - startNanos) / 1_000_000L);
    }

    /** Remembers the jukebox and pulls the track, so stopping the disc also drops the pending play. */
    private static void await(Identifier sound, BlockPos pos) {
        if (!SongStore.exists(sound.getPath())) {
            WAITING.put(pos.immutable(), sound);
            DiscClientNetwork.request(sound.getPath());
            return;
        }
        AddurDisc.LOGGER.warn("The track {} is on disk but the sound engine still cannot name it.", sound);
    }

    /** Starts the songs that were announced before their track arrived, once a sync has landed. */
    public static void playWaiting() {
        for (Map.Entry<BlockPos, Identifier> waiting : Map.copyOf(WAITING).entrySet()) {
            if (SongStore.exists(waiting.getValue().getPath())) {
                play(waiting.getValue(), waiting.getKey());
            }
        }
    }

    public static void stop(BlockPos pos) {
        WAITING.remove(pos.immutable());
        SoundInstance instance = PLAYING.remove(pos.immutable());
        if (instance != null) {
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    /** Forgets the jukeboxes being tracked, which is what leaving a world means for these maps. */
    public static void clear() {
        PLAYING.clear();
        WAITING.clear();
    }

    private DiscClientSounds() {
    }
}
