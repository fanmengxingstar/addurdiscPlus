"""Run production client packet handlers with isolated game, networking, and disk seams.

Use --baseline to compile the committed HEAD client implementation instead of the working tree.
All generated Java sources, classes, and fixture data stay under build/review-check/client-network.
"""
import argparse
import os
from pathlib import Path
import subprocess
import shutil


STUBS = {
    "net/minecraft/client/multiplayer/ClientPacketListener.java": """
package net.minecraft.client.multiplayer;
public final class ClientPacketListener {}
""",
    "net/minecraft/world/InteractionHand.java": """
package net.minecraft.world;
public enum InteractionHand { MAIN_HAND, OFF_HAND }
""",
    "net/minecraft/network/chat/Component.java": """
package net.minecraft.network.chat;
public final class Component {
    public static Component translatable(String key) { return new Component(); }
    public Component append(String value) { return this; }
}
""",
    "net/minecraft/client/Minecraft.java": """
package net.minecraft.client;
import java.util.concurrent.*;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
public final class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public Object player = new Object();
    public ClientPacketListener connection = new ClientPacketListener();
    public Level level = new Level();
    public Gui gui = new Gui();
    public final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
    public static Minecraft getInstance() { return INSTANCE; }
    public ClientPacketListener getConnection() { return connection; }
    public void executeIfPossible(Runnable task) { callbacks.add(task); }
    public void drain() { Runnable next; while ((next = callbacks.poll()) != null) next.run(); }
    public static final class Level { public Dimension dimension() { return new Dimension(); } }
    public static final class Dimension { public Object identifier() { return "overworld"; } }
    public static final class Gui { public Chat chatListener() { return new Chat(); } }
    public static final class Chat { public void handleSystemMessage(Component text, boolean overlay) {} }
}
""",
    "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents.java": """
package net.fabricmc.fabric.api.client.event.lifecycle.v1;
import net.minecraft.client.Minecraft;
public final class ClientTickEvents {
    public static final Event END_CLIENT_TICK = new Event();
    public interface Tick { void tick(Minecraft client); }
    public static final class Event { public Tick handler; public void register(Tick tick) { handler = tick; } }
}
""",
    "net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents.java": """
package net.fabricmc.fabric.api.client.networking.v1;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
public final class ClientPlayConnectionEvents {
    public static final Event DISCONNECT = new Event();
    public interface Disconnect { void disconnect(ClientPacketListener handler, Minecraft client); }
    public static final class Event {
        public Disconnect handler;
        public void register(Disconnect disconnect) { handler = disconnect; }
        public void fire() { handler.disconnect(Minecraft.getInstance().connection, Minecraft.getInstance()); }
    }
}
""",
    "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking.java": """
package net.fabricmc.fabric.api.client.networking.v1;
import java.util.*;
import net.minecraft.client.Minecraft;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.Type;
public final class ClientPlayNetworking {
    public interface Receiver<T> { void receive(T payload, Context context); }
    public record Context(Minecraft client) {}
    public static final Map<Type<?>, Receiver<?>> RECEIVERS = new HashMap<>();
    public static final List<Object> SENT = Collections.synchronizedList(new ArrayList<>());
    public static <T> void registerGlobalReceiver(Type<T> type, Receiver<T> receiver) { RECEIVERS.put(type, receiver); }
    public static boolean canSend(Type<?> type) { return true; }
    public static void send(Object payload) { SENT.add(payload); }
    @SuppressWarnings("unchecked")
    public static <T> void receive(Type<T> type, T payload) {
        ((Receiver<T>) RECEIVERS.get(type)).receive(payload, new Context(Minecraft.getInstance()));
    }
}
""",
    "nya/tuyw/addurdisc/AddurDisc.java": """
package nya.tuyw.addurdisc;
public final class AddurDisc {
    public static final Logger LOGGER = new Logger();
    public static final class Logger {
        public void debug(String text, Object... args) {}
        public void warn(String text, Object... args) {}
        public void error(String text, Object... args) {}
    }
}
""",
    "nya/tuyw/addurdisc/config/ConfigUtil.java": """
package nya.tuyw.addurdisc.config;
public final class ConfigUtil {
    public static long maxSongBytes = 1024 * 1024;
    public static long getMaxSongBytes() { return maxSongBytes; }
}
""",
    "nya/tuyw/addurdisc/init/DiscResources.java": """
package nya.tuyw.addurdisc.init;
public final class DiscResources {
    public static boolean isValidName(String name) {
        return name != null && name.matches("^[a-z0-9][a-z0-9._-]{0,63}$");
    }
    public static void refresh() {}
}
""",
    "nya/tuyw/addurdisc/disc/DiscClientSounds.java": """
package nya.tuyw.addurdisc.disc;
public final class DiscClientSounds {
    public static void play(Object sound, Object pos) {}
    public static void play(Object sound, Object pos, long elapsed) {}
    public static void stop(Object pos) {}
    public static void clear() {}
    public static void reloadSoundDefinitions() {}
    public static void playWaiting() {}
}
""",
    "nya/tuyw/addurdisc/disc/store/SongInfo.java": """
package nya.tuyw.addurdisc.disc.store;
public record SongInfo(String name, long size, String sha1, float lengthInSeconds) {}
""",
    "nya/tuyw/addurdisc/disc/store/SongStore.java": """
package nya.tuyw.addurdisc.disc.store;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
public final class SongStore {
    public static volatile Thread hashThread, writeThread, checkThread;
    public static volatile CountDownLatch writeEntered, writeRelease, checkEntered, checkRelease;
    public static volatile int writes;
    public static volatile boolean present;
    public static String sha1(byte[] data) {
        hashThread = Thread.currentThread();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    public static SongInfo write(String name, byte[] data) throws IOException {
        writeThread = Thread.currentThread();
        if (writeEntered != null) writeEntered.countDown();
        await(writeRelease);
        writes++;
        return new SongInfo(name, data.length, sha1(data), 1);
    }
    public static boolean hasUpToDate(SongInfo song) {
        checkThread = Thread.currentThread();
        if (checkEntered != null) checkEntered.countDown();
        await(checkRelease);
        return present;
    }
    private static void await(CountDownLatch latch) {
        if (latch == null) return;
        try { if (!latch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test seam timed out"); }
        catch (InterruptedException failure) { throw new RuntimeException(failure); }
    }
    public static void reset() {
        hashThread = writeThread = checkThread = null;
        writeEntered = writeRelease = checkEntered = checkRelease = null;
        writes = 0; present = false;
    }
}
""",
    "nya/tuyw/addurdisc/disc/net/DiscPayloads.java": """
package nya.tuyw.addurdisc.disc.net;
import java.util.*;
import net.minecraft.world.InteractionHand;
import nya.tuyw.addurdisc.disc.store.SongInfo;
public final class DiscPayloads {
    public static final class Type<T> {}
    public enum Failure {
        NONE, BUSY;
        public String langKey() { return "failure"; }
    }
    public record UploadStart(UUID transfer, String suggestedName, String title, long size, String sha1, InteractionHand hand) {
        public static final Type<UploadStart> TYPE = new Type<>();
    }
    public record UploadChunk(UUID transfer, byte[] bytes) { public static final Type<UploadChunk> TYPE = new Type<>(); }
    public record UploadResult(UUID transfer, Failure failure, String name, float lengthInSeconds) {
        public static final Type<UploadResult> TYPE = new Type<>();
        public boolean success() { return failure == Failure.NONE; }
    }
    public record SongList(List<SongInfo> songs) { public static final Type<SongList> TYPE = new Type<>(); }
    public record RequestSong(String name) { public static final Type<RequestSong> TYPE = new Type<>(); }
    public record DownloadStart(String name, long size, String sha1, float lengthInSeconds) {
        public static final Type<DownloadStart> TYPE = new Type<>();
    }
    public record DownloadChunk(String name, byte[] bytes, int bytesLeft) { public static final Type<DownloadChunk> TYPE = new Type<>(); }
    public record PlaySong(Object sound, Object pos) { public static final Type<PlaySong> TYPE = new Type<>(); }
    public record PlaybackState(Object dimension, Object sound, Object pos, long elapsedTicks) {
        public static final Type<PlaybackState> TYPE = new Type<>();
    }
    public record StopSong(Object dimension, Object pos) { public static final Type<StopSong> TYPE = new Type<>(); }
}
""",
}


HARNESS = r"""
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.fabricmc.fabric.api.client.networking.v1.*;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.disc.net.*;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.*;
import nya.tuyw.addurdisc.disc.store.*;

public final class ClientNetworkRegression {
    private static final Minecraft client = Minecraft.getInstance();
    private static final byte[] DATA = {1, 2, 3, 4};
    private static final String SHA = SongStore.sha1(DATA);
    private static final List<String> failures = new ArrayList<>();
    private static Path source;
    public static void main(String[] args) throws Exception {
        source = Path.of(args[0]).resolve("upload.wav");
        Files.write(source, DATA);
        DiscClientNetwork.register();
        check("waiting request is deduplicated", () -> {
            DiscClientNetwork.request("alpha");
            DiscClientNetwork.request("alpha");
            require(wanted().isEmpty(), "requested track was queued again while awaiting DownloadStart");
        });
        check("active download is deduplicated", () -> {
            begin();
            DiscClientNetwork.request("alpha");
            require(wanted().isEmpty(), "active track was queued again");
        });
        check("completed download hashes and writes off the client handler thread", () -> {
            begin();
            Thread handler = Thread.ofPlatform().name("regression-client-handler").unstarted(() -> end());
            handler.start(); handler.join(1000); idle();
            require(!handler.isAlive(), "handler did not finish");
            require(SongStore.writes == 1, "completed file was not written exactly once");
            require(SongStore.hashThread != handler && SongStore.writeThread != handler,
                "digest or disk write ran inside packet handler");
        });
        check("saving download is deduplicated and does not block the handler", () -> {
            begin();
            SongStore.writeEntered = new CountDownLatch(1);
            SongStore.writeRelease = new CountDownLatch(1);
            Thread handler = Thread.ofPlatform().name("regression-client-handler").start(() -> end());
            try {
                require(SongStore.writeEntered.await(1, TimeUnit.SECONDS), "write never began");
                handler.join(100);
                require(!handler.isAlive(), "disk write blocked the packet handler");
                DiscClientNetwork.request("alpha");
                require(wanted().isEmpty() && requests("alpha") == 1, "saving track was requested again");
            } finally { SongStore.writeRelease.countDown(); handler.join(1000); idle(); }
        });
        check("manifest cache validation runs off the client handler thread", () -> {
            SongStore.checkEntered = new CountDownLatch(1);
            SongStore.checkRelease = new CountDownLatch(1);
            Thread handler = Thread.ofPlatform().name("regression-client-handler").start(() -> manifest());
            try {
                require(SongStore.checkEntered.await(1, TimeUnit.SECONDS), "cache validation never began");
                handler.join(100);
                require(!handler.isAlive() && SongStore.checkThread != handler, "manifest disk check blocked packet handler");
            } finally { SongStore.checkRelease.countDown(); handler.join(1000); idle(); }
        });
        check("server manifest accepts a legal track above the client store quota", () -> {
            ConfigUtil.maxSongBytes = 1024 * 1024;
            ClientPlayNetworking.receive(SongList.TYPE,
                new SongList(List.of(new SongInfo("large", 2L * 1024 * 1024, SHA, 1))));
            idle();
            require(requests("large") == 1, "client local storage quota rejected the server's legal track");
        });
        check("server download offer accepts a legal track above the client store quota", () -> {
            ConfigUtil.maxSongBytes = 1024 * 1024;
            DiscClientNetwork.request("large");
            ClientPlayNetworking.receive(DownloadStart.TYPE,
                new DownloadStart("large", 2L * 1024 * 1024, SHA, 1));
            Field field = DiscClientNetwork.class.getDeclaredField("download"); field.setAccessible(true);
            ChunkAssembler transfer = (ChunkAssembler) field.get(null);
            require(transfer != null && transfer.expectedBytes() == 2L * 1024 * 1024,
                "client local storage quota rejected the server's legal DownloadStart");
        });
        check("offers beyond the protocol memory bound are rejected", () -> {
            long oversized = 129L * 1024 * 1024;
            ClientPlayNetworking.receive(SongList.TYPE,
                new SongList(List.of(new SongInfo("oversized", oversized, SHA, 1))));
            idle();
            require(requests("oversized") == 0, "oversized manifest was requested");
            DiscClientNetwork.request("oversized");
            ClientPlayNetworking.receive(DownloadStart.TYPE,
                new DownloadStart("oversized", oversized, SHA, 1));
            Field field = DiscClientNetwork.class.getDeclaredField("download"); field.setAccessible(true);
            require(field.get(null) == null, "oversized offer allocated a download buffer");
        });
        check("prepared upload from an expired connection is not sent", () -> {
            ClientPacketListener previous = client.getConnection();
            upload(previous);
            ClientPlayConnectionEvents.DISCONNECT.fire();
            client.connection = new ClientPacketListener();
            client.drain();
            require(ClientPlayNetworking.SENT.stream().noneMatch(packet -> packet instanceof UploadStart),
                "old file preparation sent an UploadStart to the new connection");
        });
        check("late UploadResult does not notify a newer import listener", () -> {
            Listener previous = new Listener();
            DiscClientNetwork.setListener(previous);
            upload(client.getConnection()); client.drain();
            UploadStart sent = (UploadStart) ClientPlayNetworking.SENT.stream()
                .filter(packet -> packet instanceof UploadStart).findFirst().orElseThrow();
            DiscClientNetwork.clearListener(previous);
            Listener current = new Listener();
            DiscClientNetwork.setListener(current);
            ClientPlayNetworking.receive(UploadResult.TYPE, new UploadResult(sent.transfer(), Failure.NONE, "alpha", 1));
            require(current.imported == 0 && current.reports == 0, "old result notified the new screen");
        });
        check("late upload preparation retains its initiating listener", () -> {
            Listener previous = new Listener();
            DiscClientNetwork.setListener(previous);
            upload(client.getConnection()); // Its queueUpload callback has not reached the main thread yet.
            DiscClientNetwork.clearListener(previous);
            Listener current = new Listener();
            DiscClientNetwork.setListener(current);
            client.drain();
            UploadStart sent = (UploadStart) ClientPlayNetworking.SENT.stream()
                .filter(packet -> packet instanceof UploadStart).findFirst().orElseThrow();
            ClientPlayNetworking.receive(UploadResult.TYPE, new UploadResult(sent.transfer(), Failure.NONE, "alpha", 1));
            require(current.imported == 0, "old preparation was associated with the new screen");
        });
        check("disconnect invalidates queued manifest completion", () -> {
            ExecutorService sync = sync();
            if (sync == null) throw new Skipped("baseline has no background completion queue");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            sync.execute(() -> { entered.countDown(); await(release); });
            require(entered.await(1, TimeUnit.SECONDS), "sync blocker never started");
            manifest();
            ClientPlayConnectionEvents.DISCONNECT.fire();
            client.connection = new ClientPacketListener();
            ClientPlayNetworking.SENT.clear();
            release.countDown(); idle();
            require(ClientPlayNetworking.SENT.isEmpty(), "old cache completion requested a song after reconnect");
        });
        check("disconnect discards a queued download save", () -> {
            ExecutorService sync = sync();
            if (sync == null) throw new Skipped("baseline has no background completion queue");
            begin();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            sync.execute(() -> { entered.countDown(); await(release); });
            require(entered.await(1, TimeUnit.SECONDS), "sync blocker never started");
            end();
            ClientPlayConnectionEvents.DISCONNECT.fire();
            client.connection = new ClientPacketListener();
            release.countDown(); idle();
            require(SongStore.writes == 0 && !isReady("alpha"), "disconnected session wrote or marked a track ready");
        });
        check("disconnect discards the completion of a running save", () -> {
            ExecutorService sync = sync();
            if (sync == null) throw new Skipped("baseline has no background completion queue");
            begin();
            SongStore.writeEntered = new CountDownLatch(1);
            SongStore.writeRelease = new CountDownLatch(1);
            end();
            try {
                require(SongStore.writeEntered.await(1, TimeUnit.SECONDS), "write never began");
                ClientPlayConnectionEvents.DISCONNECT.fire();
                client.connection = new ClientPacketListener();
            } finally { SongStore.writeRelease.countDown(); idle(); }
            require(!isReady("alpha") && requests("alpha") == 1, "old save completion changed reconnect state");
        });
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }
    private static void check(String name, Checked test) throws Exception {
        idle(); ClientPlayConnectionEvents.DISCONNECT.fire(); client.connection = new ClientPacketListener();
        client.drain(); ClientPlayNetworking.SENT.clear(); SongStore.reset(); DiscClientNetwork.setListener(null);
        try { test.run(); System.out.println("PASS " + name); }
        catch (Skipped skipped) { System.out.println("SKIP " + name + ": " + skipped.getMessage()); }
        catch (Throwable failure) { failures.add(name + ": " + failure); System.out.println("FAIL " + name + ": " + failure); }
    }
    private static void begin() {
        DiscClientNetwork.request("alpha");
        ClientPlayNetworking.receive(DownloadStart.TYPE, new DownloadStart("alpha", DATA.length, SHA, 1));
    }
    private static void end() {
        ClientPlayNetworking.receive(DownloadChunk.TYPE, new DownloadChunk("alpha", DATA, 0));
    }
    private static void manifest() {
        ClientPlayNetworking.receive(SongList.TYPE, new SongList(List.of(new SongInfo("alpha", DATA.length, SHA, 1))));
    }
    private static void upload(ClientPacketListener connection) throws Exception {
        try {
            Field field = DiscClientNetwork.class.getDeclaredField("listener"); field.setAccessible(true);
            DiscClientNetwork.class.getMethod("upload", Path.class, InteractionHand.class, ClientPacketListener.class,
                DiscClientNetwork.ImportListener.class)
                .invoke(null, source, InteractionHand.MAIN_HAND, connection, field.get(null));
        } catch (NoSuchMethodException baseline) {
            try {
                DiscClientNetwork.class.getMethod("upload", Path.class, InteractionHand.class, ClientPacketListener.class)
                    .invoke(null, source, InteractionHand.MAIN_HAND, connection);
            } catch (NoSuchMethodException olderBaseline) {
                DiscClientNetwork.class.getMethod("upload", Path.class, InteractionHand.class)
                    .invoke(null, source, InteractionHand.MAIN_HAND);
            }
        }
    }
    private static Deque<?> wanted() throws Exception {
        Field field = DiscClientNetwork.class.getDeclaredField("WANTED"); field.setAccessible(true);
        return (Deque<?>) field.get(null);
    }
    private static ExecutorService sync() throws Exception {
        try {
            Field field = DiscClientNetwork.class.getDeclaredField("SYNC"); field.setAccessible(true);
            return (ExecutorService) field.get(null);
        } catch (NoSuchFieldException baseline) { return null; }
    }
    private static boolean isReady(String name) throws Exception {
        return (boolean) DiscClientNetwork.class.getMethod("isReady", String.class).invoke(null, name);
    }
    private static void idle() throws Exception {
        ExecutorService sync = sync();
        if (sync != null) sync.submit(() -> {}).get(2, TimeUnit.SECONDS);
        client.drain();
    }
    private static int requests(String name) {
        return (int) ClientPlayNetworking.SENT.stream().filter(packet -> packet instanceof RequestSong request
            && request.name().equals(name)).count();
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException failure) { throw new RuntimeException(failure); }
    }
    private static void require(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    private interface Checked { void run() throws Exception; }
    private static final class Skipped extends RuntimeException { Skipped(String detail) { super(detail); } }
    private static final class Listener implements DiscClientNetwork.ImportListener {
        int imported, reports;
        public void report(Component message, boolean failed) { reports++; }
        public void onImported() { imported++; }
    }
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--baseline", action="store_true")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    work = repo / "build/review-check/client-network" / ("baseline" if args.baseline else "current")
    classes = work / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    sources = []
    for relative, content in {**STUBS, "ClientNetworkRegression.java": HARNESS}.items():
        target = work / "src" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        sources.append(str(target))
    production = [
        "src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java",
        "src/main/java/nya/tuyw/addurdisc/disc/DiscProtocol.java",
        "src/main/java/nya/tuyw/addurdisc/disc/store/ChunkAssembler.java",
        "src/main/java/nya/tuyw/addurdisc/disc/audio/ImportFailedException.java",
        "src/main/java/nya/tuyw/addurdisc/disc/audio/TrackMetadata.java",
        "src/main/java/nya/tuyw/addurdisc/util/Utf8.java",
    ]
    for relative in production:
        if args.baseline:
            target = work / "production" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            result = subprocess.run(["git", "show", "HEAD:" + relative], cwd=repo, capture_output=True, check=True)
            target.write_bytes(result.stdout)
        else:
            target = repo / relative
        sources.append(str(target))
    extension = ".exe" if os.name == "nt" else ""
    java = Path(args.java_home) / "bin" if args.java_home else None
    javac = str(java / ("javac" + extension)) if java else shutil.which("javac")
    runtime = str(java / ("java" + extension)) if java else shutil.which("java")
    if not javac or not runtime:
        raise SystemExit("Set JAVA_HOME or supply --java-home pointing to Java 25 or later.")
    subprocess.run([javac, "-d", str(classes), *sources], cwd=repo, check=True)
    subprocess.run([runtime, "--class-path", str(classes),
                    "ClientNetworkRegression", str(work)], cwd=repo, check=True)


if __name__ == "__main__":
    main()
