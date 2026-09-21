package nya.tuyw.addurdisc.util;

import nya.tuyw.addurdisc.AddurDisc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Minimal Ogg Vorbis header reader.
 *
 * <p>Since 1.21.2 a music disc is backed by a {@code JukeboxSong} which has to know how long the
 * track is, so AddurDisc needs the length of every user supplied .ogg file. Only two pieces of
 * information are required, both of which live in the container itself: the sample rate from the
 * identification header and the absolute sample count of the last page (its granule position).</p>
 */
public final class OggVorbis {
    private OggVorbis() {
    }

    private static final int PAGE_HEADER_SIZE = 27;
    private static final int GRANULE_POSITION_OFFSET = 6;
    private static final int SAMPLE_RATE_OFFSET_IN_ID_HEADER = 12;

    /**
     * @return the duration of the file in seconds, or {@code fallbackSeconds} when the file is not
     * a readable Ogg Vorbis stream.
     */
    public static float readDurationSeconds(File file, float fallbackSeconds) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());

            long sampleRate = readSampleRate(data);
            if (sampleRate <= 0L) {
                AddurDisc.LOGGER.warn("{} is not an Ogg Vorbis file (no identification header), using {} seconds",
                        file.getName(), fallbackSeconds);
                return fallbackSeconds;
            }

            long samples = readLastGranulePosition(data);
            if (samples <= 0L) {
                AddurDisc.LOGGER.warn("Could not determine the length of {}, using {} seconds",
                        file.getName(), fallbackSeconds);
                return fallbackSeconds;
            }

            return samples / (float) sampleRate;
        } catch (IOException e) {
            AddurDisc.LOGGER.warn("Could not read {}, using {} seconds", file.getName(), fallbackSeconds, e);
            return fallbackSeconds;
        }
    }

    private static long readSampleRate(byte[] data) {
        int page = indexOf(data, "OggS", 0);
        if (page < 0 || page + PAGE_HEADER_SIZE > data.length) {
            return -1L;
        }

        // The first page of a Vorbis stream always carries the identification header packet.
        int segments = data[page + 26] & 0xFF;
        int packet = page + PAGE_HEADER_SIZE + segments;
        if (packet + 16 > data.length || data[packet] != 1 || !matches(data, packet + 1, "vorbis")) {
            return -1L;
        }

        return readUnsignedIntLE(data, packet + SAMPLE_RATE_OFFSET_IN_ID_HEADER);
    }

    private static long readLastGranulePosition(byte[] data) {
        for (int i = data.length - PAGE_HEADER_SIZE; i >= 0; i--) {
            if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
                long granule = readLongLE(data, i + GRANULE_POSITION_OFFSET);
                // -1 marks a page on which no packet finished; keep looking further back.
                if (granule >= 0L) {
                    return granule;
                }
            }
        }
        return -1L;
    }

    private static int indexOf(byte[] data, String needle, int from) {
        int last = data.length - needle.length();
        for (int i = Math.max(from, 0); i <= last; i++) {
            if (matches(data, i, needle)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matches(byte[] data, int offset, String ascii) {
        if (offset < 0 || offset + ascii.length() > data.length) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if (data[offset + i] != (byte) ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static long readUnsignedIntLE(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return -1L;
        }
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static long readLongLE(byte[] data, int offset) {
        if (offset < 0 || offset + 8 > data.length) {
            return -1L;
        }
        long value = 0L;
        for (int i = 7; i >= 0; i--) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }
}
