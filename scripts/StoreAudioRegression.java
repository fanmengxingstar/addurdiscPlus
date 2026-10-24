import java.nio.file.*;
import java.util.*;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.disc.store.SongStore;
import nya.tuyw.addurdisc.disc.audio.AudioImporter;
import nya.tuyw.addurdisc.disc.audio.ImportFailedException;
import nya.tuyw.addurdisc.util.OggVorbis;

public final class StoreAudioRegression {
    private static final List<String> failures = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        DiscResources.directory = Files.createTempDirectory(work, "library-");
        ConfigUtil.ffmpeg = args[1];
        check("64-character duplicate name", () -> {
            String base = "a".repeat(64);
            Files.write(SongStore.resolve(base), new byte[] {1});
            String result = SongStore.allocateName(base);
            require(DiscResources.isValidName(result) && !SongStore.exists(result), result);
        });
        check("more than 999 duplicate names", () -> {
            Files.write(SongStore.resolve("repeat"), new byte[] {1});
            for (int i = 2; i <= 1100; i++) Files.write(SongStore.resolve("repeat-" + i), new byte[] {1});
            require("repeat-1101".equals(SongStore.allocateName("repeat")), "did not allocate the next name");
        });
        check("single-track description", () -> {
            var describe = SongStore.class.getMethod("describe", String.class);
            require(describe.invoke(null, "repeat") != null, "existing track missing");
            require(describe.invoke(null, "missing") == null, "absent track found");
            require(describe.invoke(null, "../outside") == null, "illegal name accepted");
        });
        if (args.length < 3 || !args[2].equals("names-only")) {
            Path normal = work.resolve("normal.wav");
            run(ConfigUtil.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:sample_rate=44100:duration=1", normal.toString());
            check("normal audio still converts", () -> {
                var converted = AudioImporter.convertFile(normal, "normal", "normal");
                Path ogg = work.resolve("normal.ogg");
                Files.write(ogg, converted.ogg());
                require(OggVorbis.isPlayableVorbis(ogg), "normal audio was damaged");
            });
            // A compressed source that expands past the configured 1 MiB output quota.
            Path longAudio = work.resolve("long.flac");
            run(ConfigUtil.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                "anoisesrc=sample_rate=44100:duration=240:seed=1", "-ac", "1", "-c:a", "flac", longAudio.toString());
            check("oversized conversion is rejected and disk output stays bounded", () -> {
                Path temp = Path.of(System.getProperty("java.io.tmpdir"));
                Set<Path> original;
                try (var files = Files.list(temp)) { original = new HashSet<>(files.toList()); }
                long[] largest = {0};
                Thread sampler = Thread.ofPlatform().daemon().start(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try (var files = Files.list(temp)) {
                            for (Path file : files.filter(path -> !original.contains(path)
                                    && path.getFileName().toString().startsWith("addurdisc-out-")
                                    && path.getFileName().toString().endsWith(".ogg")).toList()) {
                                try { largest[0] = Math.max(largest[0], Files.size(file)); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                        try { Thread.sleep(1); } catch (InterruptedException done) { return; }
                    }
                });
                boolean rejected = false;
                try {
                    AudioImporter.convertFile(longAudio, "long", "long");
                } catch (ImportFailedException failure) {
                    rejected = ImportFailedException.TOO_LARGE_KEY.equals(failure.key());
                } finally { sampler.interrupt(); sampler.join(); }
                require(rejected, "truncated conversion accepted or wrong failure");
                require(largest[0] <= ConfigUtil.maxSongBytes + 512 * 1024,
                    "output grew beyond quota: " + largest[0]);
            });
        }
        try (var files = Files.list(DiscResources.directory)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.delete(DiscResources.directory);
        if (!failures.isEmpty()) throw new AssertionError(String.join("; ", failures));
    }
    private static void check(String name, Checked test) {
        try { test.run(); System.out.println("PASS " + name); }
        catch (Throwable failure) { failures.add(name + ": " + failure); System.out.println("FAIL " + name + ": " + failure); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) throw new AssertionError("ffmpeg fixture generation failed");
    }
    private interface Checked { void run() throws Exception; }
}
