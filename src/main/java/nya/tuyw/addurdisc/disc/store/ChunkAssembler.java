package nya.tuyw.addurdisc.disc.store;

import java.io.ByteArrayOutputStream;

/**
 * Collects the chunks of one transfer, which always arrive in order on the single thread that owns
 * a connection. The promised byte count is what tells a complete file from a truncated one.
 */
public final class ChunkAssembler {
    private final String name;
    private final long expectedBytes;
    private final ByteArrayOutputStream buffer;

    public ChunkAssembler(String name, long expectedBytes) {
        this.name = name;
        this.expectedBytes = expectedBytes;
        this.buffer = new ByteArrayOutputStream((int) Math.min(expectedBytes, 1 << 20));
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
            return buffer.size() == expectedBytes;
        }
        if ((long) buffer.size() + chunk.length > expectedBytes) {
            return false;
        }
        buffer.writeBytes(chunk);
        return true;
    }

    public boolean isComplete() {
        return buffer.size() == expectedBytes;
    }

    public byte[] bytes() {
        return buffer.toByteArray();
    }
}
