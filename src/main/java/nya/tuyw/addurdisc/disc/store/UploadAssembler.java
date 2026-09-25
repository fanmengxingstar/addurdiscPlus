package nya.tuyw.addurdisc.disc.store;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Receives an upload to a temporary file so large source media does not occupy server memory. */
public final class UploadAssembler implements AutoCloseable {
    private static final int BUFFER_BYTES = 64 * 1024;

    private final String name;
    private final long expectedBytes;
    private final Path file;
    private OutputStream output;
    private long received;

    public UploadAssembler(String name, long expectedBytes) throws IOException {
        if (expectedBytes <= 0L) {
            throw new IllegalArgumentException("Transfer size must be positive");
        }
        this.name = name;
        this.expectedBytes = expectedBytes;
        this.file = Files.createTempFile("addurdisc-upload-", ".tmp");
        try {
            this.output = new BufferedOutputStream(Files.newOutputStream(file), BUFFER_BYTES);
        } catch (IOException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    public String name() {
        return name;
    }

    public long expectedBytes() {
        return expectedBytes;
    }

    public long receivedBytes() {
        return received;
    }

    public boolean accept(byte[] chunk) throws IOException {
        if (chunk == null || chunk.length == 0) {
            return received == expectedBytes;
        }
        if (received + chunk.length > expectedBytes) {
            return false;
        }
        output.write(chunk);
        received += chunk.length;
        return true;
    }

    public boolean isComplete() {
        return received == expectedBytes;
    }

    /** Flushes the completed source and returns it for background conversion. */
    public Path finish() throws IOException {
        output.close();
        output = null;
        return file;
    }

    @Override
    public void close() throws IOException {
        try {
            if (output != null) {
                output.close();
                output = null;
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
