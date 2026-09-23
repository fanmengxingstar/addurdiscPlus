package nya.tuyw.addurdisc.disc.store;

/**
 * Collects the chunks of one transfer, which always arrive in order on the single thread that owns
 * a connection. The promised byte count is what tells a complete file from a truncated one.
 *
 * <p>The buffer is sized by the announced length up front, which the caller has already clamped to
 * its budget, instead of growing behind the data: a growing buffer holds up to twice the track in
 * its capacity and {@code toByteArray()} copies it again, so a 32 MB song used to cost almost
 * 100 MB while it landed.</p>
 */
public final class ChunkAssembler {
    private final String name;
    private final long expectedBytes;
    private final byte[] buffer;
    private int filled;

    public ChunkAssembler(String name, long expectedBytes) {
        if (expectedBytes < 0L || expectedBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("Unreasonable transfer size " + expectedBytes);
        }
        this.name = name;
        this.expectedBytes = expectedBytes;
        this.buffer = new byte[(int) expectedBytes];
    }

    public String name() {
        return name;
    }

    /** @return the size the transfer announced up front, which is what its budget was reserved on */
    public long expectedBytes() {
        return expectedBytes;
    }

    /** @return false when the peer sent more bytes than announced, which ends the transfer */
    public boolean accept(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            return (long) filled == expectedBytes;
        }
        if ((long) filled + chunk.length > expectedBytes) {
            return false;
        }
        System.arraycopy(chunk, 0, buffer, filled, chunk.length);
        filled += chunk.length;
        return true;
    }

    public boolean isComplete() {
        return (long) filled == expectedBytes;
    }

    public long receivedBytes() {
        return filled;
    }

    /**
     * @return the assembled track, which is the backing buffer itself: the assembler is discarded
     * afterwards, and handing the array over without copying is the point of sizing it exactly.
     */
    public byte[] bytes() {
        return buffer;
    }
}
