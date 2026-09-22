package nya.tuyw.addurdisc.disc.audio;

import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.Config.ConfigUtil;
import nya.tuyw.addurdisc.init.DiscResources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.text.Normalizer;

/**
 * Turns whatever the player picked into the mono Ogg Vorbis stream a music disc needs.
 *
 * <p>Minecraft ships no Vorbis encoder, so the conversion is delegated to ffmpeg. The result is
 * returned as bytes instead of being written to the sounds folder: the server owns that folder and
 * the final file name, so even a local import goes through the same upload path.</p>
 */
public final class AudioImporter {
    /** Raw files are read into memory before conversion, hence the hard cap. */
    public static final long MAX_SOURCE_BYTES = 200L * 1024 * 1024;
    public static final long MAX_OGG_BYTES = 32L * 1024 * 1024;
    /** libvorbis quality mode; 4 is roughly 128 kbps, transparent enough for a music disc. */
    private static final String VORBIS_QUALITY = "4";
    private static final long TIMEOUT_SECONDS = 180;

    /**
     * @param suggestedName a legal track name derived from the picked file, for the server to keep or replace
     * @param title the file name as the player wrote it, which is what their disc should be called
     */
    public record Converted(String suggestedName, String title, byte[] ogg) {
    }

    /** @return whether the configured ffmpeg can actually be executed */
    public static boolean isAvailable() {
        return resolveExecutable() != null;
    }

    public static Converted convertFile(Path source) throws ImportFailedException, InterruptedException {
        long size = sizeOf(source);
        if (size == 0) {
            throw new ImportFailedException(source.getFileName() + " does not exist or is empty.");
        }
        if (size > MAX_SOURCE_BYTES) {
            throw new ImportFailedException(tooLarge(size, MAX_SOURCE_BYTES));
        }
        String fileName = source.getFileName().toString();
        return new Converted(suggestedName(fileName), title(fileName), run(source));
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
        for (String dir : pathVariable.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir).resolve(command + (windows ? ".exe" : ""));
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static byte[] run(Path source) throws ImportFailedException, InterruptedException {
        Path executable = resolveExecutable();
        if (executable == null) {
            throw new ImportFailedException("addurdisc.message.no_ffmpeg",
                    ConfigUtil.getFfmpegCommand() + " was not found on this computer.");
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
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ImportFailedException("ffmpeg did not finish within " + TIMEOUT_SECONDS + "s.");
            }
            if (process.exitValue() != 0) {
                throw new ImportFailedException(describeFailure(source, log, process.exitValue()));
            }
            byte[] ogg = Files.readAllBytes(output);
            if (ogg.length == 0) {
                throw new ImportFailedException("ffmpeg produced no audio for " + source.getFileName() + ".");
            }
            if (ogg.length > MAX_OGG_BYTES) {
                throw new ImportFailedException(tooLarge(ogg.length, MAX_OGG_BYTES));
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
    static String suggestedName(String fileName) {
        String base = fileName;
        String extension = extensionOf(fileName);
        if (!extension.isEmpty()) {
            base = base.substring(0, base.length() - extension.length() - 1);
        }
        StringBuilder name = new StringBuilder(base.length());
        for (char c : Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).toCharArray()) {
            boolean legal = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (legal) {
                name.append(c);
            } else if (name.length() > 0 && name.charAt(name.length() - 1) != '_') {
                name.append('_');
            }
        }
        trimSeparators(name);
        if (name.length() > 48) {
            name.setLength(48);
            trimSeparators(name);
        }
        // Names of fully non-latin files collapse to nothing, and a name built from the original
        // keeps two different imports apart instead of piling up behind one "song".
        if (name.length() == 0) {
            name.append("disc-").append(shortHash(fileName));
        }
        String candidate = name.toString();
        return DiscResources.isValidName(candidate) ? candidate : "disc-" + shortHash(fileName);
    }

    /**
     * @return the picked file name as the player wrote it, less the extension and any control
     * characters, which is what the disc is called. Bounded because other players read it too.
     */
    static String title(String fileName) {
        String extension = extensionOf(fileName);
        String base = extension.isEmpty() ? fileName : fileName.substring(0, fileName.length() - extension.length() - 1);
        String flat = base.replaceAll("\\p{Cntrl}", " ").strip();
        return flat.length() <= 64 ? flat : flat.substring(0, 64).strip();
    }

    private static void trimSeparators(StringBuilder name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == '_' || name.charAt(end - 1) == '-')) {
            end--;
        }
        name.setLength(end);
        int start = 0;
        while (start < name.length() && (name.charAt(start) == '.' || name.charAt(start) == '_' || name.charAt(start) == '-')) {
            start++;
        }
        name.delete(0, start);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,8}") ? extension : "";
    }

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
