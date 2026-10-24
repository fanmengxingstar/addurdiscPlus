param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Ffmpeg = 'ffmpeg',
    [switch]$NamesOnly
)

$ErrorActionPreference = 'Stop'
if (!$JavaHome) { throw 'Set JAVA_HOME or supply -JavaHome pointing to Java 25 or later.' }
$repository = Split-Path $PSScriptRoot -Parent
$work = Join-Path $repository 'build/review-check/store-audio'
$sources = Join-Path $work 'src'
$classes = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $sources, $classes -Force | Out-Null

# Only Minecraft/Fabric initialization is replaced. Storage and conversion run from the real sources.
$stubs = @{
    'net/minecraft/network/chat/Component.java' = @'
package net.minecraft.network.chat;
public final class Component {
    public static Component translatable(String key) { return new Component(); }
    public Component append(String value) { return this; }
}
'@
    'nya/tuyw/addurdisc/AddurDisc.java' = @'
package nya.tuyw.addurdisc;
public final class AddurDisc {
    public static final Logger LOGGER = new Logger();
    public static final class Logger {
        public void debug(String text, Object... args) {}
        public void warn(String text, Object... args) {}
        public void error(String text, Object... args) {}
    }
}
'@
    'nya/tuyw/addurdisc/config/ConfigUtil.java' = @'
package nya.tuyw.addurdisc.config;
public final class ConfigUtil {
    public static String ffmpeg;
    public static long maxSongBytes = 1024 * 1024;
    public static int getMaxTracks() { return 4096; }
    public static float getDefaultSongLengthSeconds() { return 180; }
    public static long getMaxSongBytes() { return maxSongBytes; }
    public static String getFfmpegCommand() { return ffmpeg; }
}
'@
    'nya/tuyw/addurdisc/init/DiscResources.java' = @'
package nya.tuyw.addurdisc.init;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class DiscResources {
    public static Path directory;
    public static Path soundDir() { return directory; }
    public static boolean isValidName(String name) {
        return name != null && name.matches("^[a-z0-9][a-z0-9._-]{0,63}$");
    }
    public static ArrayList<String> scanSoundNames() {
        try (var files = Files.list(directory)) {
            return new ArrayList<>(files.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".ogg"))
                .map(name -> name.substring(0, name.length() - 4))
                .filter(DiscResources::isValidName).sorted().toList());
        } catch (IOException failure) { throw new RuntimeException(failure); }
    }
}
'@
    'nya/tuyw/addurdisc/disc/store/SongInfo.java' = @'
package nya.tuyw.addurdisc.disc.store;
public record SongInfo(String name, long size, String sha1, float lengthInSeconds) {}
'@
}

$sourceFiles = @(Join-Path $PSScriptRoot 'StoreAudioRegression.java')
foreach ($relative in $stubs.Keys) {
    $target = Join-Path $sources $relative
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    [IO.File]::WriteAllText($target, $stubs[$relative])
    $sourceFiles += $target
}
$sourceFiles += @(
    'src/main/java/nya/tuyw/addurdisc/disc/store/SongStore.java',
    'src/main/java/nya/tuyw/addurdisc/disc/audio/AudioImporter.java',
    'src/main/java/nya/tuyw/addurdisc/disc/audio/ImportFailedException.java',
    'src/main/java/nya/tuyw/addurdisc/disc/DiscProtocol.java',
    'src/main/java/nya/tuyw/addurdisc/util/OggVorbis.java'
) | ForEach-Object { Join-Path $repository $_ }
& (Join-Path $JavaHome 'bin/javac.exe') -d $classes @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Regression harness compilation failed.' }
$testArguments = @('--class-path', $classes, 'StoreAudioRegression', $work, $Ffmpeg)
if ($NamesOnly) { $testArguments += 'names-only' }
& (Join-Path $JavaHome 'bin/java.exe') @testArguments
if ($LASTEXITCODE -ne 0) { throw 'Store/audio regressions failed.' }
