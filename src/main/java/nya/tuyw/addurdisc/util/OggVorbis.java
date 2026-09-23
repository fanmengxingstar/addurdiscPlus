package nya.tuyw.addurdisc.util;

import nya.tuyw.addurdisc.AddurDisc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Minimal Ogg Vorbis header reader.
 *
 * <p>Since 1.21.2 a music disc is backed by a {@code JukeboxSong} which has to know how long the
 * track is, so AddurDisc needs the length of every user supplied .ogg file. Only two pieces of
 * information are required, both of which live in the container itself: the sample rate from the
 * identification header and the absolute sample count of the last page (its granule position).</p>
 *
 * <p>Both are read straight from the channel. A track is up to tens of megabytes and the length of
 * every one of them is asked for whenever the library is listed, so the old habit of loading whole
 * files into memory showed up as a stutter on join rather than as a bug.</p>
 */
public final class OggVorbis {
    private OggVorbis() {
    }

    private static final int PAGE_HEADER_SIZE = 27;
    private static final int GRANULE_POSITION_OFFSET = 6;
    private static final int SEGMENT_COUNT_OFFSET = 26;
    private static final int SAMPLE_RATE_OFFSET_IN_ID_HEADER = 12;
    /** Format version this reader understands; anything else is not a page we can trust. */
    private static final byte OGG_VERSION = 0;
    /** First page of a Vorbis stream, which is where the identification header lives. */
    private static final int HEAD_WINDOW = 4096;
    /**
     * Enough of the tail to hold the last few pages. A page is capped at 65535 bytes of payload by
     * its own segment table, so one window covers several pages of back tracking.
     */
    private static final int TAIL_WINDOW = 256 * 1024;

    /**
     * @return whether the file parses as an Ogg Vorbis stream with a positive sample count.
     *
     * <p>The server checks this before letting an upload into the shared library: the digest a
     * client announces only proves the bytes are the ones the client sent, not that they are audio,
     * and a garbage track would be served to every other client. The check is bounded to the head
     * and tail of the file, so it is cheap even for large tracks.</p>
     */
    public static boolean isPlayableVorbis(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < PAGE_HEADER_SIZE) {
                return false;
            }
            if (readSampleRate(channel, fileSize) <= 0L) {
                return false;
            }
            return readLastGranulePosition(channel, fileSize) > 0L;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @return the duration of the file in seconds, or {@code fallbackSeconds} when the file is not
     * a readable Ogg Vorbis stream.
     */
    public static float readDurationSeconds(Path file, float fallbackSeconds) {        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < PAGE_HEADER_SIZE) {
                return fallbackSeconds;
            }
            long sampleRate = readSampleRate(channel, fileSize);
            if (sampleRate <= 0L) {
                AddurDisc.LOGGER.warn("{} is not an Ogg Vorbis file (no identification header), using {} seconds",
                        file.getFileName(), fallbackSeconds);
                return fallbackSeconds;
            }
            long samples = readLastGranulePosition(channel, fileSize);
            if (samples <= 0L) {
                AddurDisc.LOGGER.warn("Could not determine the length of {}, using {} seconds",
                        file.getFileName(), fallbackSeconds);
                return fallbackSeconds;
            }
            return samples / (float) sampleRate;
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not read {}, using {} seconds", file.getFileName(), fallbackSeconds, e);
            return fallbackSeconds;
        }
    }

    private static long readSampleRate(FileChannel channel, long fileSize) throws IOException {
        ByteBuffer head = readAt(channel, 0, (int) Math.min(fileSize, HEAD_WINDOW));
        if (pageLength(head, 0) < 0) {
            return -1L;
        }
        int segments = head.get(SEGMENT_COUNT_OFFSET) & 0xFF;
        int packet = PAGE_HEADER_SIZE + segments;
        if (packet + 16 > head.limit() || head.get(packet) != 1 || !matches(head, packet + 1, "vorbis")) {
            return -1L;
        }
        long rate = readUnsignedIntLE(head, packet + SAMPLE_RATE_OFFSET_IN_ID_HEADER);
        return rate >= 1L && rate <= 384000L ? rate : -1L;
    }

    /**
     * Walks the page chain backwards from the end of the file.
     *
     * <p>Guessing at the last {@code "OggS"} bytes would happily land on a run of compressed audio
     * that happens to spell the capture pattern, so a candidate is only accepted once its own stated
     * length lands exactly where the previous page was found to end. That chain has to reach the
     * file's end, which a random byte sequence will not.</p>
     */
    private static long readLastGranulePosition(FileChannel channel, long fileSize) throws IOException {
        int window = (int) Math.min(fileSize, TAIL_WINDOW);
        long windowStart = fileSize - window;
        ByteBuffer tail = readAt(channel, windowStart, window);
        // Chained from the end: a page counts only once its own length lands exactly on the start of
        // the page before it, and the first of those to carry a real granule is the last one.
        long expectedEnd = tail.limit();
        for (int i = tail.limit() - PAGE_HEADER_SIZE; i >= 0; i--) {
            int pageLength = pageLength(tail, i);
            if (pageLength < 0 || i + pageLength != expectedEnd) {
                continue;
            }
            expectedEnd = i;
            long granule = readLongLE(tail, i + GRANULE_POSITION_OFFSET);
            // -1 marks a page on which no packet finished; step further back.
            if (granule > 0L) {
                return granule;
            }
        }
        return -1L;
    }

    /**
     * @return the total length of the page starting at {@code offset}, or -1 when there is no
     * well formed page header there
     */
    private static int pageLength(ByteBuffer data, int offset) {
        if (offset < 0 || offset + PAGE_HEADER_SIZE > data.limit()
                || !matches(data, offset, "OggS") || data.get(offset + 4) != OGG_VERSION) {
            return -1;
        }
        int segments = data.get(offset + SEGMENT_COUNT_OFFSET) & 0xFF;
        int headerWithSegments = PAGE_HEADER_SIZE + segments;
        if (offset + headerWithSegments > data.limit()) {
            return -1;
        }
        int payload = 0;
        for (int i = 0; i < segments; i++) {
            payload += data.get(offset + PAGE_HEADER_SIZE + i) & 0xFF;
        }
        return headerWithSegments + payload;
    }

    private static ByteBuffer readAt(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        long read = 0;
        while (read < length) {
            int step = channel.read(buffer, position + read);
            if (step < 0) {
                break;
            }
            read += step;
        }
        buffer.flip();
        return buffer;
    }

    private static boolean matches(ByteBuffer data, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > data.limit()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (data.get(offset + i) != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long readUnsignedIntLE(ByteBuffer data, int offset) {
        if (offset < 0 || offset + 4 > data.limit()) {
            return -1L;
        }
        return data.getInt(offset) & 0xFFFFFFFFL;
    }

    private static long readLongLE(ByteBuffer data, int offset) {
        if (offset < 0 || offset + 8 > data.limit()) {
            return -1L;
        }
        return data.getLong(offset);
    }
}
