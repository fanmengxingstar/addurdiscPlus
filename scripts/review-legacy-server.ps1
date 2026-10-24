#requires -Version 7.0
param([string]$JavaHome = $env:JAVA_HOME, [int]$TimeoutSeconds = 180, [Parameter(Mandatory=$true)][string]$AcceptedEula)
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskWork = Join-Path $taskRepo 'build/review-check/server-test'
$taskClasspathFile = Join-Path $taskRepo 'build/review-check/network/classpath.txt'
if (!(Test-Path -LiteralPath $taskClasspathFile)) {
    $taskClasspathDir = Split-Path -Parent $taskClasspathFile
    New-Item -ItemType Directory -Force -Path $taskClasspathDir | Out-Null
    $taskInit = Join-Path $taskClasspathDir 'classpath.gradle'
    @'
allprojects {
    tasks.register('legacyReviewClasspath') {
        dependsOn 'classes', 'clientClasses'
        doLast { new File(project.property('reviewClasspathFile')).text =
            (sourceSets.main.runtimeClasspath + sourceSets.client.runtimeClasspath).asPath }
    }
}
'@ | Set-Content -LiteralPath $taskInit -Encoding utf8
    Push-Location $taskRepo
    try {
        & .\gradlew.bat --offline -I $taskInit "-PreviewClasspathFile=$taskClasspathFile" legacyReviewClasspath
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the legacy runtime classpath; build first.' }
    } finally { Pop-Location }
}
if (!$JavaHome) { throw 'Supply -JavaHome pointing to a JDK capable of running this Gradle wrapper.' }
$taskJava = Join-Path $JavaHome 'bin/java.exe'
$taskJavac = Join-Path $JavaHome 'bin/javac.exe'
$taskJar = Join-Path $JavaHome 'bin/jar.exe'
$taskClasspath = (Get-Content -LiteralPath $taskClasspathFile -Raw).Trim()
$taskGameJar = $taskClasspath.Split(';') | Where-Object { $_ -match 'minecraft-common-[^\\/]+\.jar$' -and $_ -notmatch 'sources|intermediary' } | Select-Object -First 1
if (!$taskGameJar) { throw 'Legacy Minecraft dedicated-server jar is absent from the production classpath.' }
New-Item -ItemType Directory -Force -Path $taskWork | Out-Null
$taskEula = $AcceptedEula
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        Class.forName(AnvilMenu.class.getName());
        ServerLevel level = server.overworld();
        BlockPos pos = new BlockPos(0,80,0);
        level.setBlock(pos,Blocks.JUKEBOX.defaultBlockState(),3);
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity)level.getBlockEntity(pos);
        check(jukebox != null,"missing jukebox");
        ItemStack blank = new ItemStack(ModInitialize.CUSTOM_DISC);
        ItemStack bound = blank.copy();
        SongBinding song = new SongBinding("review_track",180.0F,"Review track");
        song.applyTo(bound);
        ItemStack vanilla = new ItemStack(Items.MUSIC_DISC_13);
        check(SongBinding.fromStack(blank)==null,"blank disc unexpectedly bound");
        check(!jukebox.canPlaceItem(0,blank),"hopper accepted blank");
        check(jukebox.canPlaceItem(0,bound),"hopper rejected bound");
        check(jukebox.canPlaceItem(0,vanilla),"hopper rejected vanilla");
        check(SongBinding.fromStack(bound).equals(song),"NBT binding changed");
        net.minecraft.nbt.Tag savedStack=bound.save(level.registryAccess());
        check(SongBinding.fromStack(ItemStack.parseOptional(level.registryAccess(),(net.minecraft.nbt.CompoundTag)savedStack)).equals(song),"item NBT round trip lost song");
        jukebox.setItem(0,bound.copy());
        check(jukebox.isRecordPlaying(),"custom record did not play");
        for(int i=0;i<237;i++) JukeboxBlockEntity.playRecordTick(level,pos,level.getBlockState(pos),jukebox);
        net.minecraft.nbt.CompoundTag saved=jukebox.saveWithFullMetadata(level.registryAccess());
        JukeboxBlockEntity restored=new JukeboxBlockEntity(pos,level.getBlockState(pos));
        restored.setLevel(level);
        restored.loadWithComponents(saved,level.registryAccess());
        JukeboxBlockEntity.playRecordTick(level,pos,level.getBlockState(pos),restored);
        check(restored.saveWithFullMetadata(level.registryAccess()).getLong("TickCount")==238,"restored jukebox lost elapsed ticks");
        check(restored.isRecordPlaying(),"restored playback stopped early");
        restored.setRemoved();
        java.lang.reflect.Field playback=DiscPlayback.class.getDeclaredField("PLAYING");
        playback.setAccessible(true);
        check(((java.util.Map<?,?>)playback.get(null)).isEmpty(),"removal retained playback references");
        jukebox.removeItem(0,1);
        check(!jukebox.isRecordPlaying(),"record removal did not stop");
        ItemStack shortDisc=blank.copy();new SongBinding("short_track",1.0F,"Short").applyTo(shortDisc);
        jukebox.setItem(0,shortDisc);
        for(int i=0;i<41;i++) JukeboxBlockEntity.playRecordTick(level,pos,level.getBlockState(pos),jukebox);
        check(!jukebox.isRecordPlaying(),"dynamic track duration was ignored");
        jukebox.removeItem(0,1);jukebox.setItem(0,vanilla);
        check(jukebox.isRecordPlaying(),"vanilla disc did not start");
        jukebox.removeItem(0,1);level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);DiscPlayback.clear(server);
        verifyAnvil(server,level,bound);
    }
    private static void verifyAnvil(MinecraftServer server,ServerLevel level,ItemStack bound) {
        ServerPlayer player=new ServerPlayer(server,level,new GameProfile(UUID.randomUUID(),"ReviewAnvil"),net.minecraft.server.level.ClientInformation.createDefault());
        player.giveExperienceLevels(10);
        AnvilMenu menu=new AnvilMenu(1,player.getInventory());
        menu.getSlot(0).set(new ItemStack(ModInitialize.CUSTOM_DISC));menu.getSlot(1).set(bound.copy());
        menu.setItemName("Copied title");
        ItemStack result=menu.getSlot(2).getItem();
        check(!result.isEmpty() && result.getHoverName().getString().equals("Copied title"),"anvil rename missing");
        check(SongBinding.fromStack(result).equals(SongBinding.fromStack(bound)),"copy lost binding");
        check(menu.getCost()==1,"copy cost differs from one level");
        menu.getSlot(2).onTake(player,result);
        check(menu.getSlot(0).getItem().isEmpty(),"blank not consumed");
        check(ItemStack.isSameItemSameComponents(menu.getSlot(1).getItem(),bound),"source consumed or modified");
        check(player.experienceLevel==9,"copy charged incorrect XP");
        AnvilMenu vanilla=new AnvilMenu(2,player.getInventory());
        vanilla.getSlot(0).set(new ItemStack(Items.IRON_SWORD));vanilla.setItemName("Vanilla title");
        check(vanilla.getSlot(2).getItem().getHoverName().getString().equals("Vanilla title"),"vanilla rename changed");
        check(vanilla.getCost()==1,"vanilla rename cost changed");
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
    foreach ($taskMixin in @('AnvilMenuMixin', 'JukeboxBlockEntityMixin', 'JukeboxRemovalMixin')) {
        if ($taskLog -notmatch "Mixing $taskMixin from addurdisc.mixins.json") {
            throw "Common mixin application missing in real server log: $taskMixin"
        }
    }
    Write-Output 'PASS real server: blank/hopper/bound/vanilla/play/tick/stop/removal/anvil; 3 common mixins applied; clean shutdown.'
} finally {
    if ($taskStarted -and !$taskProcess.HasExited) { $taskProcess.Kill($true) }
    $taskProcess.Dispose()
}
