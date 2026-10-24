import java.lang.reflect.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Runs against the compiled production network classes without launching a game. */
public final class NetworkRegression {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("nya.tuyw.addurdisc.disc.net.DiscNetwork$DownloadThrottle");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object throttle = constructor.newInstance();
        Field available = type.getDeclaredField("available");
        Field lastRefill = type.getDeclaredField("lastRefillNanos");
        available.setAccessible(true);
        lastRefill.setAccessible(true);
        available.setLong(throttle, 0L);
        lastRefill.setLong(throttle, System.nanoTime() - 600_000_000_000L);
        Method acquire = type.getDeclaredMethod("acquire", long.class);
        acquire.setAccessible(true);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> result = worker.submit(() -> {
            try {
                acquire.invoke(throttle, 128L * 1024);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            result.get(300, TimeUnit.MILLISECONDS);
            System.out.println("PASS: download resumes immediately after ten minutes idle");
        } catch (TimeoutException e) {
            throw new AssertionError("Download throttle overflowed after ten minutes idle", e);
        } finally {
            result.cancel(true);
            worker.shutdownNow();
        }
        Class<?> network = Class.forName("nya.tuyw.addurdisc.disc.net.DiscNetwork");
        Class<?> stateType = Class.forName("nya.tuyw.addurdisc.disc.net.DiscNetwork$Transfers");
        Constructor<?> stateConstructor = stateType.getDeclaredConstructor();
        stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();
        Method pace = network.getDeclaredMethod("requestDelay", stateType, UUID.class);
        pace.setAccessible(true);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 8; i++) {
            if ((long) pace.invoke(null, state, player) != 0) throw new AssertionError("Initial requests delayed");
        }
        long delay = (long) pace.invoke(null, state, player);
        if (delay <= 0 || delay > 1000) throw new AssertionError("Ninth request must be paced within one second");
        System.out.println("PASS: sequential requests are paced instead of dropped");

        Class<?> assemblerType = Class.forName("nya.tuyw.addurdisc.disc.store.UploadAssembler");
        Object assembler = assemblerType.getConstructor(String.class, long.class).newInstance("regression", 1024L);
        try {
            Field budget = stateType.getDeclaredField("bytesInFlight");
            budget.setAccessible(true);
            ((AtomicLong) budget.get(state)).set(1024L);
            Class<?> uploadType = Class.forName("nya.tuyw.addurdisc.disc.net.DiscNetwork$Upload");
            Constructor<?> uploadConstructor = uploadType.getDeclaredConstructors()[0];
            uploadConstructor.setAccessible(true);
            Object upload = uploadConstructor.newInstance(UUID.randomUUID(), null, null, "", "", assembler,
                    state, new AtomicBoolean(), new AtomicLong());
            Method release = network.getDeclaredMethod("releaseUploadBudget", uploadType);
            release.setAccessible(true);
            release.invoke(null, upload);
            release.invoke(null, upload);
            if (((AtomicLong) budget.get(state)).get() != 0) throw new AssertionError("Budget released twice");
            Object nextState = stateConstructor.newInstance();
            if (((AtomicLong) budget.get(nextState)).get() != 0) throw new AssertionError("Budget carried between worlds");
            System.out.println("PASS: upload budget release is idempotent and belongs to its server");

            // A conversion cancelled while still queued never runs its cleanup finally block.
            byte[] sourceBytes = new byte[1024];
            assemblerType.getMethod("accept", byte[].class).invoke(assembler, (Object) sourceBytes);
            java.nio.file.Path source = (java.nio.file.Path) assemblerType.getMethod("finish").invoke(assembler);
            Field uploads = stateType.getDeclaredField("uploads");
            Field jobs = stateType.getDeclaredField("jobs");
            uploads.setAccessible(true);
            jobs.setAccessible(true);
            UUID owner = UUID.randomUUID();
            ((java.util.Map) uploads.get(state)).put(owner, upload);
            FutureTask<Void> queued = new FutureTask<>(() -> null);
            ((java.util.Map) jobs.get(state)).put(upload, queued);
            Method finish = network.getDeclaredMethod("finishUpload", stateType, UUID.class);
            finish.setAccessible(true);
            finish.invoke(null, state, owner);
            if (!queued.isCancelled() || java.nio.file.Files.exists(source)) {
                throw new AssertionError("Cancelled queued conversion leaked its source file");
            }
            System.out.println("PASS: cancelling a queued conversion removes its source file");
        } finally {
            ((AutoCloseable) assembler).close();
        }
    }
}
