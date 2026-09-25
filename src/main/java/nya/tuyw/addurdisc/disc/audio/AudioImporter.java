package nya.tuyw.addurdisc.disc.audio;

import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.disc.DiscProtocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Converts an uploaded source into the mono Ogg Vorbis stream a music disc needs.
 *
 * <p>Minecraft ships no Vorbis encoder, so the conversion is delegated to ffmpeg. The result is
 * returned as bytes instead of being written to the sounds folder: the server owns that folder and
 * the final file name, so even a local import goes through the same upload path.</p>
 */
public final class AudioImporter {
    /** libvorbis quality mode; 4 is roughly 128 kbps, transparent enough for a music disc. */
    private static final String VORBIS_QUALITY = "4";
    private static final long TIMEOUT_SECONDS = 180;

    /**
     * @param suggestedName a legal track name derived from the picked file, for the server to keep or replace
     * @param title the file name as the player wrote it, which is what their disc should be called
     */
    public record Converted(String suggestedName, String title, byte[] ogg) {
    }

    public static Converted convertFile(Path source, String suggestedName, String title)
            throws ImportFailedException, InterruptedException {
        long size = sizeOf(source);
        if (size == 0) {
            throw new ImportFailedException(source.getFileName() + " does not exist or is empty.");
        }
        if (size > DiscProtocol.MAX_UPLOAD_SOURCE_BYTES) {
            throw new ImportFailedException(ImportFailedException.TOO_LARGE_KEY,
                    tooLarge(size, DiscProtocol.MAX_UPLOAD_SOURCE_BYTES));
        }
        return new Converted(suggestedName, title, run(source));
    }

    /**
     * @return the requested command as an existing executable, or null when it cannot be found
     */
    private static Path resolveExecutable() {
        String command = ConfigUtil.getFfmpegCommand();
        Path direct = Path.of(command);
        if (direct.isAbsolute() || command.contains("/") || command.contains("\\")) {
            return Files.isExecutable(direct) ? direct : null;
        }
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> suffixes = executableSuffixes(windows);
        for (String dir : pathVariable.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path folder = Path.of(dir);
            for (String suffix : suffixes) {
                Path candidate = folder.resolve(command + suffix);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * @return the extensions a bare command name can carry, taken from {@code PATHEXT} on Windows.
     * Appending only {@code .exe} used to miss the {@code .bat} and {@code .cmd} wrappers that
     * package managers install, and reported a working ffmpeg as missing.
     */
    private static List<String> executableSuffixes(boolean windows) {
        if (!windows) {
            return List.of("");
        }
        List<String> suffixes = new ArrayList<>();
        String pathExt = System.getenv("PATHEXT");
        if (pathExt != null) {
            for (String extension : pathExt.split(";")) {
                if (!extension.isBlank()) {
                    suffixes.add(extension.startsWith(".") ? extension : "." + extension);
                }
            }
        }
        if (suffixes.isEmpty()) {
            suffixes.add(".exe");
        }
        return suffixes;
    }

    private static byte[] run(Path source) throws ImportFailedException, InterruptedException {
        Path executable = resolveExecutable();
        if (executable == null) {
            throw new ImportFailedException("addurdisc.upload.conversion_failed",
                    ConfigUtil.getFfmpegCommand() + " was not found on the server.");
        }
        Path output;
        try {
            output = Files.createTempFile("addurdisc-out-", ".ogg");
        } catch (IOException e) {
            throw new ImportFailedException("Could not create a temporary file: " + e.getMessage());
        }
        Path log = output.resolveSibling(output.getFileName() + ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    executable.toString(),
                    "-hide_banner",
                    "-loglevel", "error",
                    "-y",
                    // -vn drops the video stream, -map 0:a:0 keeps a single audio stream even when
                    // the container holds several, -ac 1 folds stereo down to mono.
                    "-i", source.toAbsolutePath().toString(),
                    "-vn", "-map", "0:a:0",
                    "-ac", "1",
                    "-c:a", "libvorbis", "-q:a", VORBIS_QUALITY,
                    output.toAbsolutePath().toString());
            builder.redirectError(ProcessBuilder.Redirect.to(log.toFile()));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            try {
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new ImportFailedException("ffmpeg did not finish within " + TIMEOUT_SECONDS + "s.");
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw e;
            }
            if (process.exitValue() != 0) {
                throw new ImportFailedException(describeFailure(source, log, process.exitValue()));
            }
            byte[] ogg = Files.readAllBytes(output);
            if (ogg.length == 0) {
                throw new ImportFailedException("ffmpeg produced no audio for " + source.getFileName() + ".");
            }
            long maxSongBytes = ConfigUtil.getMaxSongBytes();
            if (ogg.length > maxSongBytes) {
                throw new ImportFailedException(ImportFailedException.TOO_LARGE_KEY,
                        tooLarge(ogg.length, maxSongBytes));
            }
            AddurDisc.LOGGER.debug("Converted {} to a {} byte mono ogg.", source.getFileName(), ogg.length);
            return ogg;
        } catch (IOException e) {
            throw new ImportFailedException("Could not run ffmpeg: " + e.getMessage());
        } finally {
            deleteQuietly(output);
            deleteQuietly(log);
        }
    }

    private static String describeFailure(Path source, Path log, int exitCode) {
        String detail = "";
        try {
            if (Files.exists(log)) {
                detail = Files.readString(log).strip();
            }
        } catch (IOException ignored) {
            // The exit code alone is still a useful report.
        }
        if (detail.length() > 400) {
            detail = detail.substring(0, 400) + "...";
        }
        // ffmpeg reports "Output file #0 does not contain any stream" for video-only files.
        if (detail.contains("does not contain any stream") || detail.contains("Stream map '0:a:0' matches no streams")) {
            return String.format("%s has no audio stream to extract.", source.getFileName());
        }
        return String.format("ffmpeg exited with %d: %s", exitCode, detail);
    }

    /**
     * Strips the extension from a picked file name and makes it a legal track name.
     */
    private static long sizeOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static String tooLarge(long size, long limit) {
        return String.format("%s is too large (limit %s MB).", formatSize(size), limit / (1024 * 1024));
    }

    private static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temp file is not worth failing an import over.
        }
    }

    private AudioImporter() {
    }
}
