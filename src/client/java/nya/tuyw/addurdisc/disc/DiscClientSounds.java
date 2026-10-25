package nya.tuyw.addurdisc.disc;

import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.net.DiscClientNetwork;
import nya.tuyw.addurdisc.disc.store.SongStore;
import nya.tuyw.addurdisc.init.DiscResources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Plays the tracks that were imported during this session on the client.
 *
 * <p>Each instance supplies its streamed sound directly, so a newly downloaded track can play without
 * restarting the sound engine. A track that is not on this computer yet is fetched and then starts at
 * the jukebox's current position rather than at the beginning.</p>
 */
public final class DiscClientSounds {
    /**
     * The instance, not just the name, because stopping by name and category stops every jukebox
     * playing that track rather than only the one that went quiet.
     */
    private static final Map<BlockPos, ImportedDiscSoundInstance> PLAYING = new HashMap<>();
    /** Jukeboxes whose song was announced before this side could hear it. */
    private static final Map<BlockPos, PendingSong> WAITING = new HashMap<>();
    private static ClientLevel trackedLevel;
    private static boolean registered;

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        trackedLevel = Minecraft.getInstance().level;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level != trackedLevel) {
                clear();
                trackedLevel = client.level;
            }
            SoundManager manager = client.getSoundManager();
            PLAYING.values().removeIf(instance -> {
                if (manager.isActive(instance)) {
                    return false;
                }
                instance.cancelStream();
                return true;
            });
        });
    }

    public static void play(ResourceLocation sound, BlockPos pos) {
        play(sound, pos, 0L);
    }

    public static void play(ResourceLocation sound, BlockPos pos, long elapsedTicks) {
        playAt(sound, pos, elapsedTicks, System.nanoTime());
    }

    private static void playAt(ResourceLocation sound, BlockPos pos, long elapsedTicks, long announcedAtNanos) {
        stop(pos);
        if (!AddurDisc.MODID.equals(sound.getNamespace()) || !DiscResources.isValidName(sound.getPath())) {
            return;
        }
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        Path source = SongStore.resolve(sound.getPath());
        if (!DiscClientNetwork.isReady(sound.getPath()) || source == null || !Files.isRegularFile(source)) {
            WAITING.put(pos.immutable(), new PendingSong(sound, elapsedTicks, announcedAtNanos));
            DiscClientNetwork.request(sound.getPath());
            return;
        }
        ImportedDiscSoundInstance instance =
                new ImportedDiscSoundInstance(sound, pos, source, elapsedTicks, announcedAtNanos);
        manager.play(instance);
        PLAYING.put(pos.immutable(), instance);
    }

    /** Starts the songs that were announced before their track arrived, once a sync has landed. */
    public static void playWaiting() {
        for (Map.Entry<BlockPos, PendingSong> waiting : Map.copyOf(WAITING).entrySet()) {
            PendingSong pending = waiting.getValue();
            if (DiscClientNetwork.isReady(pending.sound().getPath())) {
                playAt(pending.sound(), waiting.getKey(), pending.elapsedTicks(), pending.announcedAtNanos());
            }
        }
    }

    public static void stop(BlockPos pos) {
        WAITING.remove(pos.immutable());
        ImportedDiscSoundInstance instance = PLAYING.remove(pos.immutable());
        if (instance != null) {
            instance.cancelStream();
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    /** Forgets the jukeboxes being tracked, which is what leaving a world means for these maps. */
    public static void clear() {
        SoundManager manager = Minecraft.getInstance().getSoundManager();
        for (ImportedDiscSoundInstance instance : PLAYING.values()) {
            instance.cancelStream();
            manager.stop(instance);
        }
        PLAYING.clear();
        WAITING.clear();
    }

    private record PendingSong(ResourceLocation sound, long elapsedTicks, long announcedAtNanos) {
    }

    private DiscClientSounds() {
    }
}
