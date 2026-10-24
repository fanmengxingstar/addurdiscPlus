package nya.tuyw.addurdisc.disc;

import it.unimi.dsi.fastutil.floats.FloatConsumer;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import nya.tuyw.addurdisc.AddurDisc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** A single imported record, independent of the resource reload's sound registry and file cache. */
final class ImportedDiscSoundInstance extends AbstractSoundInstance {
    private static final long MAX_ELAPSED_TICKS = 7200L * 20L;
    private static final long TICK_NANOS = 50_000_000L;
    private static final int MAX_CATCH_UP_PASSES = 3;

    private final WeighedSoundEvents soundEvent;
    private final Path source;
    private final long elapsedNanos;
    private final long announcedAtNanos;
    private volatile boolean cancelled;
    private AudioStream openedStream;

    ImportedDiscSoundInstance(Identifier id, BlockPos pos, Path source, long elapsedTicks, long announcedAtNanos) {
        super(id, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.source = source;
        this.elapsedNanos = Math.clamp(elapsedTicks, 0L, MAX_ELAPSED_TICKS) * TICK_NANOS;
        this.announcedAtNanos = announcedAtNanos;
        Vec3 center = Vec3.atCenterOf(pos);
        x = center.x;
        y = center.y;
        z = center.z;
        volume = 4.0F;
        sound = new Sound(id, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1,
                Sound.Type.FILE, true, false, DiscProtocol.AUDIBLE_BLOCKS);
        soundEvent = new WeighedSoundEvents(id, null);
        soundEvent.addSound(sound);
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        return soundEvent;
    }

    /** Fabric routes streamed instances through this hook before consulting vanilla's file cache. */
    @Override
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary library, Identifier id, boolean loop) {
        return CompletableFuture.supplyAsync(() -> {
            checkCancelled();
            try {
                InputStream input = Files.newInputStream(source);
                JOrbisAudioStream stream;
                try {
                    stream = new JOrbisAudioStream(input);
                } catch (IOException | RuntimeException e) {
                    input.close();
                    throw e;
                }
                try {
                    skipElapsed(stream);
                    AudioStream managed = new ManagedAudioStream(stream);
                    synchronized (this) {
                        checkCancelled();
                        openedStream = managed;
                    }
                    return managed;
                } catch (IOException | RuntimeException e) {
                    stream.close();
                    throw e;
                }
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    /**
     * Discards decoded packets off-thread, with no whole-song PCM allocation. JOrbis emits interleaved
     * samples; counting channels therefore keeps mono and stereo offsets correct. A packet boundary
     * can lead the exact offset by a few milliseconds, and catch-up is capped so decoding never chases
     * the clock indefinitely. The elapsed value includes the time spent downloading the file.
     */
    private void skipElapsed(JOrbisAudioStream stream) throws IOException {
        int channels = stream.getFormat().getChannels();
        float rate = stream.getFormat().getSampleRate();
        long[] skippedSamples = {0L};
        FloatConsumer discard = sample -> skippedSamples[0]++;
        for (int pass = 0; pass < MAX_CATCH_UP_PASSES; pass++) {
            long ageNanos = Math.min(MAX_ELAPSED_TICKS * TICK_NANOS,
                    elapsedNanos + Math.max(0L, System.nanoTime() - announcedAtNanos));
            long targetSamples = (long) (ageNanos / 1_000_000_000.0 * rate) * channels;
            while (skippedSamples[0] < targetSamples) {
                checkCancelled();
                if (!stream.readChunk(discard)) {
                    return;
                }
            }
        }
    }

    private void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("The jukebox stopped before its stream was ready.");
        }
    }

    /** Also cancels an open/seek that has not handed its stream to the sound engine yet. */
    synchronized void cancelStream() {
        cancelled = true;
        if (openedStream != null) {
            try {
                openedStream.close();
            } catch (IOException e) {
                AddurDisc.LOGGER.debug("Could not close the imported record stream at {}.", source, e);
            }
            openedStream = null;
        }
    }

    /** The audio thread reads this while a world-change callback may close it on the client thread. */
    private static final class ManagedAudioStream implements AudioStream {
        private final AudioStream delegate;
        private boolean closed;

        private ManagedAudioStream(AudioStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public javax.sound.sampled.AudioFormat getFormat() {
            return delegate.getFormat();
        }

        @Override
        public synchronized ByteBuffer read(int bytes) throws IOException {
            return closed ? ByteBuffer.allocate(0) : delegate.read(bytes);
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                delegate.close();
            }
        }
    }
}
