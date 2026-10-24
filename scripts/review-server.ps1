#requires -Version 7.0
param([string]$JavaHome = $env:JAVA_HOME, [int]$TimeoutSeconds = 120)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskWork = Join-Path $taskRepo 'build/review-check/server-test'
$taskClasspathFile = Join-Path $taskRepo 'build/review-check/network/classpath.txt'
if (!(Test-Path -LiteralPath $taskClasspathFile)) {
    throw 'Run review-network.ps1 after building the project to prepare the production classpath.'
}
if (!$JavaHome) { throw 'Supply -JavaHome pointing to Java 25 or later.' }
$taskJava = Join-Path $JavaHome 'bin/java.exe'
$taskJavac = Join-Path $JavaHome 'bin/javac.exe'
$taskJar = Join-Path $JavaHome 'bin/jar.exe'
$taskClasspath = (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
$taskGameJar = $taskClasspath.Split(';') | Where-Object { $_ -match 'minecraft-common-[^\\/]+-26\.3\.jar$' } | Select-Object -First 1
if (!$taskGameJar) { throw 'Minecraft 26.3 dedicated-server jar is absent from the production classpath.' }
New-Item -ItemType Directory -Force -Path $taskWork | Out-Null
$taskEula = Join-Path $taskRepo 'build/upgrade-check/server/eula.txt'
if (!(Test-Path -LiteralPath $taskEula) -or (Get-Content -LiteralPath $taskEula -Raw) -notmatch '(?m)^eula=true\s*$') {
    throw 'An already accepted isolated test-server EULA was not found; the script does not accept it for you.'
}
Copy-Item -LiteralPath $taskEula -Destination (Join-Path $taskWork 'eula.txt') -Force
$taskConfig = Join-Path $taskRepo 'build/review-check/server/config/AddurDisc-common.json'
if (!(Test-Path -LiteralPath $taskConfig)) { $taskConfig = Join-Path $taskRepo 'build/upgrade-check/server/config/AddurDisc-common.json' }
if (Test-Path -LiteralPath $taskConfig) {
    New-Item -ItemType Directory -Force -Path (Join-Path $taskWork 'config') | Out-Null
    Copy-Item -LiteralPath $taskConfig -Destination (Join-Path $taskWork 'config/AddurDisc-common.json') -Force
}
@'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-name=review-test-world
enable-query=false
enable-rcon=false
view-distance=2
simulation-distance=2
spawn-protection=0
max-players=1
level-type=minecraft:flat
'@ | Set-Content -LiteralPath (Join-Path $taskWork 'server.properties') -Encoding utf8
$taskSource = Join-Path $taskWork 'ReviewServer.java'
@'
package nya.tuyw.addurdisc.review;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.disc.net.DiscPlayback;
import nya.tuyw.addurdisc.init.ModInitialize;
import com.mojang.authlib.GameProfile;
import java.util.UUID;

public final class ReviewServer implements ModInitializer {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> server.execute(() -> {
            try {
                verify(server);
                System.out.println("REVIEW_SERVER_PASS blank/hopper/bound/vanilla/play/tick/stop/removal/anvil");
            } catch (Throwable failure) {
                System.err.println("REVIEW_SERVER_FAIL " + failure);
                failure.printStackTrace();
            } finally {
                server.halt(false);
            }
        }));
    }

    private static void verify(MinecraftServer server) throws Exception {
        // Force all common mixins through the actual loader, not stub compilation.
        Class.forName(AnvilMenu.class.getName());
        ServerLevel level = server.overworld();
        BlockPos pos = new BlockPos(0, 80, 0);
        level.setBlock(pos, Blocks.JUKEBOX.defaultBlockState(), 3);
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) level.getBlockEntity(pos);
        check(jukebox != null, "jukebox block entity missing");

        ItemStack blank = new ItemStack(ModInitialize.CUSTOM_DISC);
        ItemStack bound = blank.copy();
        bound.set(ModInitialize.DISC_SONG, new SongBinding("review_track", 180.0F, "Review track"));
        ItemStack vanilla = new ItemStack(Items.MUSIC_DISC_13);
        check(JukeboxSong.fromStack(blank).isEmpty(), "blank disc resolved to the placeholder song");
        check(!jukebox.canPlaceItem(0, blank), "hopper can insert blank disc");
        check(jukebox.canPlaceItem(0, bound), "hopper rejects bound custom disc");
        check(jukebox.canPlaceItem(0, vanilla), "hopper rejects vanilla disc");
        check(JukeboxSong.fromStack(bound).isPresent(), "bound disc has no song");
        check(JukeboxSong.fromStack(vanilla).isPresent(), "vanilla resolution changed");

        jukebox.setTheItem(bound.copy());
        check(jukebox.getSongPlayer().isPlaying(), "custom disc did not start");
        JukeboxBlockEntity.tick(level, pos, level.getBlockState(pos), jukebox);
        check(jukebox.getSongPlayer().getTicksSinceSongStarted() == 1, "jukebox tick failed");
        jukebox.getSongPlayer().stop(level, level.getBlockState(pos));
        check(!jukebox.getSongPlayer().isPlaying(), "stop failed");

        jukebox.getSongPlayer().setSongWithoutPlaying(JukeboxSong.fromStack(bound).orElseThrow(), 237);
        JukeboxBlockEntity.tick(level, pos, level.getBlockState(pos), jukebox);
        check(jukebox.getSongPlayer().getTicksSinceSongStarted() == 238, "restored song lost progress");
        jukebox.setRemoved();
        java.lang.reflect.Field playback = DiscPlayback.class.getDeclaredField("PLAYING");
        playback.setAccessible(true);
        check(((java.util.Map<?, ?>) playback.get(null)).isEmpty(), "chunk removal retained playback references");

        jukebox.clearRemoved();
        jukebox.setTheItem(vanilla);
        check(jukebox.getSongPlayer().isPlaying(), "vanilla disc did not start");
        JukeboxBlockEntity.tick(level, pos, level.getBlockState(pos), jukebox);
        jukebox.getSongPlayer().stop(level, level.getBlockState(pos));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        DiscPlayback.clear(server);
        verifyAnvil(server, level, bound);
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
        check(!result.isEmpty() && result.getHoverName().getString().equals("Copied title"), "actual anvil copy ignored rename");
        check(result.get(ModInitialize.DISC_SONG).equals(bound.get(ModInitialize.DISC_SONG)), "actual anvil lost song binding");
        check(menu.getCost() == 1, "actual anvil copy cost changed");
        menu.getSlot(2).onTake(player, result);
        check(menu.getSlot(0).getItem().isEmpty(), "actual anvil kept consumed blank disc");
        check(ItemStack.isSameItemSameComponents(menu.getSlot(1).getItem(), bound), "actual anvil consumed original source disc");
        check(player.experienceLevel == 9, "actual anvil did not consume exactly one experience level");

        AnvilMenu vanilla = new AnvilMenu(2, player.getInventory());
        vanilla.getSlot(0).set(new ItemStack(Items.IRON_SWORD));
        vanilla.setItemName("Vanilla title");
        check(vanilla.getSlot(2).getItem().getHoverName().getString().equals("Vanilla title"), "vanilla anvil rename changed");
        check(vanilla.getCost() == 1, "vanilla anvil rename cost changed");
    }
}
'@ | Set-Content -LiteralPath $taskSource -Encoding utf8
$taskClasses = Join-Path $taskWork 'testmod-classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskCompileArgs = Join-Path $taskWork 'compile.args'
@('-proc:none', '-classpath', ('"' + $taskClasspath.Replace('\', '/') + '"'), '-d',
    ('"' + $taskClasses.Replace('\', '/') + '"'), ('"' + $taskSource.Replace('\', '/') + '"')) |
    Set-Content -LiteralPath $taskCompileArgs -Encoding utf8
& $taskJavac "@$taskCompileArgs"
if ($LASTEXITCODE -ne 0) { throw 'Real server testmod compilation failed.' }
@'
{"schemaVersion":1,"id":"addurdisc_review","version":"1.0.0","environment":"server","entrypoints":{"main":["nya.tuyw.addurdisc.review.ReviewServer"]},"depends":{"addurdisc":"*"}}
'@ | Set-Content -LiteralPath (Join-Path $taskClasses 'fabric.mod.json') -Encoding utf8
$taskTestMod = Join-Path $taskWork 'review-testmod.jar'
& $taskJar --create --file $taskTestMod -C $taskClasses .
if ($LASTEXITCODE -ne 0) { throw 'Testmod packaging failed.' }
$taskLaunchArgs = Join-Path $taskWork 'server.args'
@('-Xmx768M', '-cp', ('"' + $taskClasspath.Replace('\', '/') + '"'), '-Dfabric.development=true',
    ('"-Dfabric.gameJarPath=' + $taskGameJar.Replace('\', '/') + '"'),
    ('"-Dfabric.addMods=' + $taskTestMod.Replace('\', '/') + '"'), '-Dmixin.debug.verbose=true',
    'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui') |
    Set-Content -LiteralPath $taskLaunchArgs -Encoding utf8
$taskStart = [System.Diagnostics.ProcessStartInfo]::new()
$taskStart.FileName = $taskJava
$taskStart.ArgumentList.Add("@$taskLaunchArgs")
$taskStart.WorkingDirectory = $taskWork
$taskStart.UseShellExecute = $false
$taskStart.CreateNoWindow = $true
$taskStart.RedirectStandardOutput = $true
$taskStart.RedirectStandardError = $true
$taskProcess = [System.Diagnostics.Process]::new()
$taskProcess.StartInfo = $taskStart
$taskStarted = $false
try {
    [void]$taskProcess.Start()
    $taskStarted = $true
    Write-Output 'Running real Fabric server test in build/review-check/server-test (127.0.0.1, ephemeral port).'
    $taskOutput = $taskProcess.StandardOutput.ReadToEndAsync()
    $taskErrors = $taskProcess.StandardError.ReadToEndAsync()
    $taskDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (!$taskProcess.WaitForExit(1000)) {
        if ([DateTime]::UtcNow -ge $taskDeadline) {
            $taskProcess.Kill($true)
            $taskProcess.WaitForExit()
            throw "Server test exceeded $TimeoutSeconds seconds."
        }
    }
    $taskLog = $taskOutput.GetAwaiter().GetResult() + $taskErrors.GetAwaiter().GetResult()
    [System.IO.File]::WriteAllText((Join-Path $taskWork 'console.log'), $taskLog, [System.Text.UTF8Encoding]::new($false))
    if ($taskProcess.ExitCode -ne 0 -or $taskLog -notmatch 'REVIEW_SERVER_PASS' -or $taskLog -notmatch 'Done \(' -or
            $taskLog -match 'REVIEW_SERVER_FAIL|InvalidMixinException|MixinApplyError|InjectionError') {
        $taskLog -split "`n" | Where-Object { $_ -match 'REVIEW_SERVER_|ERROR|Exception|MixinApplyError|InjectionError' } | Select-Object -Last 20
        throw 'Real server regression failed; inspect build/review-check/server-test/console.log.'
    }
    foreach ($taskMixin in @('AnvilMenuMixin', 'JukeboxBlockEntityMixin', 'JukeboxPlayableTooltipMixin', 'JukeboxSongMixin', 'JukeboxSongPlayerMixin')) {
        if ($taskLog -notmatch "Mixing $taskMixin from addurdisc.mixins.json") {
            throw "Common mixin application missing in real server log: $taskMixin"
        }
    }
    Write-Output 'PASS real server: blank/hopper/bound/vanilla/play/tick/stop/removal/anvil; 5 common mixins applied; clean shutdown.'
} finally {
    if ($taskStarted -and !$taskProcess.HasExited) { $taskProcess.Kill($true) }
    $taskProcess.Dispose()
}
