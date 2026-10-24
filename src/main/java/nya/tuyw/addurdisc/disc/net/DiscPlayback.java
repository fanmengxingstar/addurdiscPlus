package nya.tuyw.addurdisc.disc.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.PlaybackState;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.PlaySong;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.StopSong;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Server-thread playback subscriptions; a jukebox sends only starts and stops to each listener. */
public final class DiscPlayback {
    private static final Map<Jukebox, Playback> PLAYING = new HashMap<>();
    private static final double RANGE_SQUARED = (double) DiscProtocol.ANNOUNCE_BLOCKS * DiscProtocol.ANNOUNCE_BLOCKS;

    /** Called at playback start, on the first loaded tick, and once per second afterwards. */
    public static void update(ServerLevel level, BlockPos pos, ResourceLocation sound, long elapsedTicks) {
        Jukebox key = new Jukebox(level, pos.immutable());
        Playback playback = PLAYING.get(key);
        if (playback != null && (!playback.sound.equals(sound) || elapsedTicks < playback.elapsedTicks)) {
            stop(level, pos);
            playback = null;
        }
        if (playback == null) {
            playback = new Playback(sound);
            PLAYING.put(key, playback);
        }
        playback.elapsedTicks = elapsedTicks;
        Iterator<ServerPlayer> listeners = playback.listeners.iterator();
        while (listeners.hasNext()) {
            ServerPlayer player = listeners.next();
            if (!isNearby(player, key)) {
                sendStop(player, key);
                listeners.remove();
            }
        }
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
                new AABB(pos).inflate(DiscProtocol.ANNOUNCE_BLOCKS))) {
            if (!isNearby(player, key) || playback.listeners.contains(player)) {
                continue;
            }
            if (LegacyServerNetworking.canSend(player, PlaybackState.TYPE)) {
                LegacyServerNetworking.send(player, new PlaybackState(sound, key.pos,
                        level.dimension().location(), elapsedTicks));
            } else if (LegacyServerNetworking.canSend(player, PlaySong.TYPE)) {
                LegacyServerNetworking.send(player, new PlaySong(sound, key.pos));
            } else {
                continue;
            }
            playback.listeners.add(player);
        }
    }

    /** Stops everyone who received a start, including players now outside the vanilla event range. */
    public static void stop(ServerLevel level, BlockPos pos) {
        Jukebox key = new Jukebox(level, pos.immutable());
        Playback playback = PLAYING.remove(key);
        if (playback != null) {
            playback.listeners.forEach(player -> sendStop(player, key));
        }
    }

    /** Removes references to a stopped server so later integrated-server sessions start cleanly. */
    public static void clear(MinecraftServer server) {
        Iterator<Map.Entry<Jukebox, Playback>> entries = PLAYING.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Jukebox, Playback> entry = entries.next();
            if (entry.getKey().level.getServer() == server) {
                entry.getValue().listeners.forEach(player -> sendStop(player, entry.getKey()));
                entries.remove();
            }
        }
    }

    private static boolean isNearby(ServerPlayer player, Jukebox key) {
        return !player.hasDisconnected() && player.level() == key.level
                && player.distanceToSqr(key.pos.getX() + 0.5, key.pos.getY() + 0.5, key.pos.getZ() + 0.5)
                < RANGE_SQUARED;
    }

    private static void sendStop(ServerPlayer player, Jukebox key) {
        if (player.hasDisconnected()) {
            return;
        }
        if (LegacyServerNetworking.canSend(player, StopSong.TYPE)) {
            LegacyServerNetworking.send(player, new StopSong(key.pos, key.level.dimension().location()));
        } else if (player.level() == key.level && LegacyServerNetworking.canSend(player, PlaySong.TYPE)) {
            // Legacy clients already stop imported tracks on vanilla event 1011. Sending directly
            // reaches listeners outside its usual radius without affecting another dimension.
            player.connection.send(new ClientboundLevelEventPacket(1011, key.pos, 0, false));
        }
    }

    private record Jukebox(ServerLevel level, BlockPos pos) { }

    private static final class Playback {
        private final ResourceLocation sound;
        private final Set<ServerPlayer> listeners = new HashSet<>();
        private long elapsedTicks;

        private Playback(ResourceLocation sound) {
            this.sound = sound;
        }
    }

    private DiscPlayback() { }
}
