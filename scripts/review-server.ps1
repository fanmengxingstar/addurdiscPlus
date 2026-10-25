#requires -Version 7.0
param(
    [string]$JavaHome = 'D:\Zulu\zulu21',
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$taskRepo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskBuild = Join-Path $taskRepo 'build/review-server'
$taskServer = Join-Path $taskBuild 'server'
$taskInit = Join-Path $taskBuild 'classpath.init.gradle'
$taskClasspathFile = Join-Path $taskBuild 'classpath.txt'
$taskTestSource = Join-Path $taskBuild 'testmod-src/nya/tuyw/addurdisc/review/ReviewServer.java'
$taskTestClasses = Join-Path $taskBuild 'testmod-classes'
$taskTestMod = Join-Path $taskBuild 'review-testmod.jar'
$taskJava = Join-Path $JavaHome 'bin/java.exe'
$taskJavac = Join-Path $JavaHome 'bin/javac.exe'
$taskJar = Join-Path $JavaHome 'bin/jar.exe'

foreach ($taskExe in @($taskJava, $taskJavac, $taskJar)) {
    if (!(Test-Path -LiteralPath $taskExe)) { throw "Java 21 tool was not found: $taskExe" }
}
New-Item -ItemType Directory -Force -Path $taskBuild, $taskServer, (Split-Path -Parent $taskTestSource) | Out-Null

@'
gradle.projectsEvaluated {
    def targetPath = gradle.startParameter.projectProperties.get("reviewClasspathFile")
    if (targetPath != null) {
        rootProject.tasks.register("writeReviewClasspath") {
            doLast {
                def cp = rootProject.sourceSets.main.runtimeClasspath.asPath
                def target = new File(targetPath)
                target.parentFile.mkdirs()
                target.setText(cp, "UTF-8")
            }
        }
    }
}
'@ | Set-Content -LiteralPath $taskInit -Encoding utf8

$taskOldJavaHome = $env:JAVA_HOME
$taskOldPath = $env:Path
$taskOldJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$taskOldPath"
    if ($env:JAVA_TOOL_OPTIONS -notmatch 'https\.proxyHost') {
        $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9674 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9674').Trim())
    }

    Push-Location $taskRepo
    try {
        & .\gradlew.bat --no-daemon -I $taskInit "-PreviewClasspathFile=$taskClasspathFile" classes writeReviewClasspath --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle classes/classpath preparation failed.' }
    } finally {
        Pop-Location
    }

    $taskClasspath = (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
    if (!$taskClasspath) { throw 'The Loom runtime classpath is empty.' }
    $taskEntries = $taskClasspath.Split(';')
    $taskGameJar = $taskEntries | Where-Object { $_ -match 'minecraft-common-.*\.jar$' } | Select-Object -First 1
    if (!$taskGameJar) { throw 'The named Minecraft 1.21.1 common jar is absent from the Loom runtime classpath.' }

    $taskRootRepo = (Resolve-Path (Join-Path $taskRepo '..\..\..')).Path
    $taskAcceptedEula = Join-Path $taskRootRepo 'build/upgrade-check/server/eula.txt'
    if (!(Test-Path -LiteralPath $taskAcceptedEula) -or (Get-Content -LiteralPath $taskAcceptedEula -Raw) -notmatch '(?m)^eula=true\s*$') {
        throw 'An already accepted isolated test-server EULA was not found; this script does not accept it for you.'
    }
    Copy-Item -LiteralPath $taskAcceptedEula -Destination (Join-Path $taskServer 'eula.txt') -Force

    @'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-name=review-test-world-normal
enable-query=false
enable-rcon=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=1
level-type=minecraft:normal
'@ | Set-Content -LiteralPath (Join-Path $taskServer 'server.properties') -Encoding utf8

    @'
package nya.tuyw.addurdisc.review;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.InteractionHand;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.disc.net.DiscPlayback;
import nya.tuyw.addurdisc.disc.net.DiscPayloads;
import nya.tuyw.addurdisc.init.ModInitialize;

import java.util.Map;
import java.util.UUID;

public final class ReviewServer implements ModInitializer {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> {
            try {
                verify(server);
                System.out.println("REVIEW_SERVER_PASS hand-codec/blank/hopper/bound/vanilla/save-load/play/tick/stop/removal/anvil");
            } catch (Throwable failure) {
                System.err.println("REVIEW_SERVER_FAIL " + failure);
                failure.printStackTrace();
            } finally {
                server.halt(false);
            }
        }));
    }

    private static void verify(MinecraftServer server) throws Exception {
        // Force common mixin targets through Fabric Loader before exercising their behavior.
        Class.forName(AnvilMenu.class.getName());
        check(server.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("addurdisc", "custom_disc")).isPresent(), "custom disc recipe missing");
        check(server.getAdvancements().get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("addurdisc", "recipes/custom_disc")) != null, "custom disc advancement missing");
        verifyHandCodec();
        ServerLevel level = server.overworld();
        BlockPos pos = new BlockPos(0, 80, 0);
        level.setBlock(pos, Blocks.JUKEBOX.defaultBlockState(), 3);
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) level.getBlockEntity(pos);
        check(jukebox != null, "jukebox block entity missing");

        ItemStack blank = new ItemStack(ModInitialize.CUSTOM_DISC);
        ItemStack bound = blank.copy();
        bound.set(ModInitialize.DISC_SONG, new SongBinding("review_track", 180.0F, "Review track"));
        ItemStack vanilla = new ItemStack(Items.MUSIC_DISC_13);
        check(JukeboxSong.fromStack(level.registryAccess(), blank).isEmpty(), "blank disc resolved to the placeholder song");
        check(!jukebox.canPlaceItem(0, blank), "hopper can insert blank disc");
        check(jukebox.canPlaceItem(0, bound), "hopper rejects bound custom disc");
        check(jukebox.canPlaceItem(0, vanilla), "hopper rejects vanilla disc");
        check(JukeboxSong.fromStack(level.registryAccess(), bound).isPresent(), "bound disc has no song");
        check(JukeboxSong.fromStack(level.registryAccess(), vanilla).isPresent(), "vanilla song resolution changed");

        ItemStack restored = ItemStack.parse(level.registryAccess(), bound.save(level.registryAccess())).orElseThrow();
        check(!restored.isEmpty(), "bound disc failed item-stack save/load");
        check(bound.get(ModInitialize.DISC_SONG).equals(restored.get(ModInitialize.DISC_SONG)),
                "song binding changed across item-stack save/load");
        check(JukeboxSong.fromStack(level.registryAccess(), restored).isPresent(), "jukebox playable holder failed item-stack save/load");

        jukebox.setTheItem(bound.copy());
        check(jukebox.getSongPlayer().isPlaying(), "custom disc did not start");
        JukeboxBlockEntity.tick(level, pos, level.getBlockState(pos), jukebox);
        check(jukebox.getSongPlayer().getTicksSinceSongStarted() == 1, "jukebox tick failed");
        jukebox.getSongPlayer().stop(level, level.getBlockState(pos));
        check(!jukebox.getSongPlayer().isPlaying(), "stop failed");

        jukebox.getSongPlayer().setSongWithoutPlaying(JukeboxSong.fromStack(level.registryAccess(), bound).orElseThrow(), 237);
        JukeboxBlockEntity.tick(level, pos, level.getBlockState(pos), jukebox);
        check(jukebox.getSongPlayer().getTicksSinceSongStarted() == 238, "restored song lost progress");
        jukebox.setRemoved();
        var playbackField = DiscPlayback.class.getDeclaredField("PLAYING");
        playbackField.setAccessible(true);
        check(((Map<?, ?>) playbackField.get(null)).isEmpty(), "block removal retained playback references");

        jukebox.clearRemoved();
        jukebox.setTheItem(vanilla);
        check(jukebox.getSongPlayer().isPlaying(), "vanilla disc did not start");
        JukeboxBlockEntity.tick(level, pos, level.getBlockState(pos), jukebox);
        jukebox.getSongPlayer().stop(level, level.getBlockState(pos));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        DiscPlayback.clear(server);
        verifyAnvil(server, level, bound);
    }

    @SuppressWarnings("unchecked")
    private static void verifyHandCodec() throws Exception {
        var field = DiscPayloads.class.getDeclaredField("INTERACTION_HAND_CODEC");
        field.setAccessible(true);
        StreamCodec<ByteBuf, InteractionHand> codec = (StreamCodec<ByteBuf, InteractionHand>) field.get(null);
        for (InteractionHand hand : InteractionHand.values()) {
            ByteBuf encoded = Unpooled.buffer(1);
            try {
                codec.encode(encoded, hand);
                check(codec.decode(encoded) == hand, "valid interaction hand codec round-trip failed");
            } finally {
                encoded.release();
            }
        }
        ByteBuf invalid = Unpooled.buffer(1).writeByte(2);
        try {
            codec.decode(invalid);
            throw new AssertionError("invalid interaction hand id was accepted");
        } catch (DecoderException expected) {
            // The upload-start payload must reject unknown enum values from the wire.
        } finally {
            invalid.release();
        }
    }

    private static void verifyAnvil(MinecraftServer server, ServerLevel level, ItemStack bound) {
        ServerPlayer player = new ServerPlayer(server, level,
                new GameProfile(UUID.fromString("7174df91-8df6-41e3-9b7b-e1e32aa4a421"), "ReviewAnvil"),
                ClientInformation.createDefault());
        player.giveExperienceLevels(10);
        AnvilMenu menu = new AnvilMenu(1, player.getInventory());
        menu.getSlot(0).set(new ItemStack(ModInitialize.CUSTOM_DISC));
        menu.getSlot(1).set(bound.copy());
        menu.setItemName("Copied title");
        ItemStack result = menu.getSlot(2).getItem();
        check(!result.isEmpty() && result.getHoverName().getString().equals("Copied title"), "anvil copy ignored rename");
        check(result.get(ModInitialize.DISC_SONG).equals(bound.get(ModInitialize.DISC_SONG)), "anvil copy lost song binding");
        check(menu.getCost() == 1, "anvil copy cost changed");
        menu.getSlot(2).onTake(player, result);
        check(menu.getSlot(0).getItem().isEmpty(), "anvil kept consumed blank disc");
        check(ItemStack.isSameItemSameComponents(menu.getSlot(1).getItem(), bound), "anvil consumed original source disc");
        check(player.experienceLevel == 9, "anvil did not consume exactly one experience level");

        AnvilMenu vanilla = new AnvilMenu(2, player.getInventory());
        vanilla.getSlot(0).set(new ItemStack(Items.IRON_SWORD));
        vanilla.setItemName("Vanilla title");
        check(vanilla.getSlot(2).getItem().getHoverName().getString().equals("Vanilla title"), "vanilla anvil rename changed");
        check(vanilla.getCost() == 1, "vanilla anvil rename cost changed");
    }
}
'@ | Set-Content -LiteralPath $taskTestSource -Encoding utf8

    $taskBuildRoot = [System.IO.Path]::GetFullPath($taskBuild + [System.IO.Path]::DirectorySeparatorChar)
    $taskClassesFull = [System.IO.Path]::GetFullPath($taskTestClasses)
    if (!$taskClassesFull.StartsWith($taskBuildRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to clear test classes outside this worktree build directory.'
    }
    if (Test-Path -LiteralPath $taskClassesFull) { Remove-Item -LiteralPath $taskClassesFull -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $taskClassesFull | Out-Null
    & $taskJavac -proc:none -classpath $taskClasspath -d $taskClassesFull $taskTestSource
    if ($LASTEXITCODE -ne 0) { throw 'Real-server testmod compilation failed.' }
    @'
{"schemaVersion":1,"id":"addurdisc_review","version":"1.0.0","environment":"server","entrypoints":{"main":["nya.tuyw.addurdisc.review.ReviewServer"]},"depends":{"addurdisc":"*"}}
'@ | Set-Content -LiteralPath (Join-Path $taskClassesFull 'fabric.mod.json') -Encoding utf8
    if (Test-Path -LiteralPath $taskTestMod) { Remove-Item -LiteralPath $taskTestMod -Force }
    & $taskJar --create --file $taskTestMod -C $taskClassesFull .
    if ($LASTEXITCODE -ne 0) { throw 'Real-server testmod packaging failed.' }

    $taskStart = [System.Diagnostics.ProcessStartInfo]::new()
    $taskStart.FileName = $taskJava
    foreach ($taskArg in @(
        '-Xmx1G', '-cp', $taskClasspath, '-Dfabric.development=true',
        "-Dfabric.gameJarPath=$taskGameJar", "-Dfabric.addMods=$taskTestMod", '-Dmixin.debug.verbose=true',
        'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui'
    )) { $taskStart.ArgumentList.Add($taskArg) }
    $taskStart.WorkingDirectory = $taskServer
    $taskStart.UseShellExecute = $false
    $taskStart.CreateNoWindow = $true
    $taskStart.RedirectStandardOutput = $true
    $taskStart.RedirectStandardError = $true
    foreach ($taskName in @('JAVA_HOME', 'JAVA_TOOL_OPTIONS', 'Path')) {
        if ($null -ne [Environment]::GetEnvironmentVariable($taskName)) {
            $taskStart.Environment[$taskName] = [Environment]::GetEnvironmentVariable($taskName)
        }
    }

    $taskProcess = [System.Diagnostics.Process]::new()
    $taskProcess.StartInfo = $taskStart
    $taskStarted = $false
    try {
        [void]$taskProcess.Start()
        $taskStarted = $true
        $taskStdout = $taskProcess.StandardOutput.ReadToEndAsync()
        $taskStderr = $taskProcess.StandardError.ReadToEndAsync()
        $taskDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while (!$taskProcess.WaitForExit(1000)) {
            if ([DateTime]::UtcNow -ge $taskDeadline) {
                $taskProcess.Kill($true)
                $taskProcess.WaitForExit()
                throw "Real server regression exceeded $TimeoutSeconds seconds."
            }
        }
        $taskLog = $taskStdout.GetAwaiter().GetResult() + $taskStderr.GetAwaiter().GetResult()
        [System.IO.File]::WriteAllText((Join-Path $taskServer 'console.log'), $taskLog, [System.Text.UTF8Encoding]::new($false))
        if ($taskProcess.ExitCode -ne 0 -or $taskLog -notmatch 'REVIEW_SERVER_PASS' -or $taskLog -notmatch 'Done \(' -or
                $taskLog -match 'REVIEW_SERVER_FAIL|InvalidMixinException|MixinApplyError|InjectionError') {
            $taskLog -split "`n" | Where-Object { $_ -match 'REVIEW_SERVER_|ERROR|Exception|MixinApplyError|InjectionError' } | Select-Object -Last 20
            throw "Real server regression failed; inspect $taskServer\console.log."
        }
        foreach ($taskMixin in @('AnvilMenuMixin', 'JukeboxBlockEntityMixin', 'JukeboxPlayableTooltipMixin', 'JukeboxRemovalMixin', 'JukeboxSongMixin', 'JukeboxSongPlayerMixin')) {
            if ($taskLog -notmatch "Mixing $taskMixin from addurdisc.mixins.json") {
                throw "Common mixin application missing in real server log: $taskMixin"
            }
        }
        Write-Output 'PASS real Fabric server: hand-codec/blank/hopper/bound/vanilla/save-load/play/tick/stop/removal/anvil; six common mixins applied.'
        Write-Output "Test mod: $taskTestMod"
        Write-Output "Server log: $(Join-Path $taskServer 'console.log')"
    } finally {
        if ($taskStarted -and !$taskProcess.HasExited) { $taskProcess.Kill($true) }
        $taskProcess.Dispose()
    }
} finally {
    $env:JAVA_HOME = $taskOldJavaHome
    $env:Path = $taskOldPath
    $env:JAVA_TOOL_OPTIONS = $taskOldJavaToolOptions
}
