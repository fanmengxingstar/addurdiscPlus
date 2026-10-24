param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$GradleUserHome = $env:GRADLE_USER_HOME,
    [ValidateSet('Config', 'Playback', 'Gameplay')][string[]]$Checks = @('Config', 'Playback', 'Gameplay'),
    [string]$BaselineRef = ''
)

# Logic regression fixtures compile the production methods against small boundary stubs.
# Minecraft startup / Mixin application still require review-server.ps1.
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskRoot = Join-Path $taskRepo 'build/review-check/gameplay'
if ($JavaHome) {
    $taskJavac = Join-Path $JavaHome 'bin/javac.exe'
    $taskJava = Join-Path $JavaHome 'bin/java.exe'
} else {
    $taskJavac = (Get-Command javac.exe -ErrorAction Stop).Source
    $taskJava = (Get-Command java.exe -ErrorAction Stop).Source
}
$taskVersion = (& $taskJavac -version 2>&1).ToString()
if ($LASTEXITCODE -ne 0 -or $taskVersion -notmatch 'javac (\d+)' -or [int]$Matches[1] -lt 25) {
    throw 'Use Java 25 or later; supply -JavaHome when the default JDK is older.'
}
if (!$GradleUserHome) { $GradleUserHome = Join-Path $env:USERPROFILE '.gradle' }
$taskCache = Join-Path $GradleUserHome 'caches/modules-2/files-2.1'
function Find-TestJar([string]$group, [string]$artifact) {
    $root = Join-Path $taskCache "$group/$artifact"
    $jar = Get-ChildItem -LiteralPath $root -Recurse -Filter "$artifact-*.jar" |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (!$jar) { throw "Dependency missing: $group/$artifact; run the project build first." }
    return $jar.FullName
}
function Write-TestFile([string]$relative, [string]$contents) {
    $path = Join-Path $taskRoot $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [System.IO.File]::WriteAllText($path, $contents, [System.Text.UTF8Encoding]::new($false))
    return $path
}
function Read-TestSource([string]$relative) {
    if ($BaselineRef) {
        $lines = & git -C $taskRepo show "${BaselineRef}:$relative"
        if ($LASTEXITCODE -ne 0) { throw "Cannot read $relative at $BaselineRef" }
        return $lines -join "`n"
    }
    return [System.IO.File]::ReadAllText((Join-Path $taskRepo $relative))
}
$taskFixtures = @{
    'config-src/ConfigCheck.java' = @'
import java.lang.reflect.Method;
public class ConfigCheck {
    public static void main(String[] args) throws Exception {
        Class<?> handler = Class.forName("nya.tuyw.addurdisc.config.ConfigHandler");
        Method valid = handler.getDeclaredMethod("isSingleSegmentName", String.class);
        valid.setAccessible(true);
        for (String name : new String[] { ".addurdisc", "中文 音乐", "music-1" }) {
            if (!(Boolean) valid.invoke(null, name)) throw new AssertionError("Rejected legal folder " + name);
        }
        for (String name : new String[] { "", " ", ".", "..", "../escape", "a/b", "a\\b", "C:music", "bad?folder", "bad*folder", "bad<folder", "bad|folder", "bad\"folder" }) {
            if ((Boolean) valid.invoke(null, name)) throw new AssertionError("Invalid folder accepted, startup would fail: " + name);
        }
        Method relative = handler.getDeclaredMethod("isSafeRelativePath", String.class);
        relative.setAccessible(true);
        for (String path : new String[] { "", "music", "parent/music", "中文/音乐" }) {
            if (!(Boolean) relative.invoke(null, path)) throw new AssertionError("Legal relative parent rejected: " + path);
        }
        for (String path : new String[] { "C:music", "\\music", "C:/music", "../music", "bad?folder", " " }) {
            if ((Boolean) relative.invoke(null, path)) throw new AssertionError("Unsafe parent accepted: " + path);
        }
        System.out.println("PASS ConfigCheck: legal names retained; invalid Windows paths rejected");
    }
}
'@
    'config-src/net/fabricmc/loader/api/FabricLoader.java' = @'
package net.fabricmc.loader.api;
import java.nio.file.Path;
public class FabricLoader {
    public static FabricLoader getInstance() { return new FabricLoader(); }
    public Path getConfigDir() { return Path.of("build/review-check/gameplay-config/config"); }
}
'@
    'config-src/nya/tuyw/addurdisc/AddurDisc.java' = @'
package nya.tuyw.addurdisc;
public final class AddurDisc {
    public static final Log LOGGER = new Log();
    public static class Log {
        public void debug(String text) { }
        public void error(String text, Object... arguments) { }
    }
}
'@
    'playback-src/GameplayCheck.java' = @'
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import nya.tuyw.addurdisc.disc.*;
import nya.tuyw.addurdisc.init.*;
import nya.tuyw.addurdisc.mixin.*;
import org.spongepowered.asm.mixin.injection.callback.*;

public class GameplayCheck {
    static final SongBinding SONG=new SongBinding("track");
    static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    static class Slots implements Container {
        final ItemStack[] items={ItemStack.EMPTY,ItemStack.EMPTY};
        public ItemStack getItem(int slot) { return items[slot]; }
        public void setItem(int slot,ItemStack item) { items[slot]=item; }
    }
    static Object field(Object target,String name,Object value,boolean write) throws Exception {
        Field field=target.getClass().getDeclaredField(name); field.setAccessible(true);
        if(write) field.set(target,value); return field.get(target);
    }
    static ItemStack copy(String oldName,String inputName,boolean expectCustom) throws Exception {
        AnvilMenuMixin menu=new AnvilMenuMixin(); Slots inputs=new Slots(),results=new Slots();
        ItemStack blank=new ItemStack(ModInitialize.CUSTOM_DISC), source=new ItemStack(ModInitialize.CUSTOM_DISC);
        if(oldName!=null) blank.set(DataComponents.CUSTOM_NAME,Component.literal(oldName));
        source.set(ModInitialize.DISC_SONG,SONG); inputs.setItem(0,blank); inputs.setItem(1,source);
        menu.inputSlots=inputs; menu.resultSlots=results;
        field(menu,"cost",new DataSlot(),true); field(menu,"itemName",inputName,true);
        Method copy=AnvilMenuMixin.class.getDeclaredMethod("addurdisc$copySong",CallbackInfo.class); copy.setAccessible(true);
        CallbackInfo callback=new CallbackInfo("createResult",true); copy.invoke(menu,callback);
        ItemStack result=results.getItem(0); Component custom=result.get(DataComponents.CUSTOM_NAME);
        check(callback.isCancelled(),"copy branch did not run");
        check(result.get(ModInitialize.DISC_SONG)==SONG,"song not copied");
        check(expectCustom?custom!=null && custom.getString().equals(inputName):custom==null,"copy loses entered name or invents default rename: " + inputName);
        check(((DataSlot)field(menu,"cost",null,false)).get()==1,"copy experience cost must remain 1");
        check((Integer)field(menu,"repairItemCountCost",null,false)==0 && (Boolean)field(menu,"onlyRenaming",null,false),"vanilla source-preservation path changed");
        check(inputs.getItem(0)==blank && inputs.getItem(1)==source && blank.get(ModInitialize.DISC_SONG)==null && source.get(ModInitialize.DISC_SONG)==SONG,"createResult mutated input discs");
        return result;
    }
    static void copying() throws Exception {
        copy(null,"New title",true); copy(null,"Blank Disc",false); copy("Old title","Old title",true); copy("Old title","",false);
        Method copy=AnvilMenuMixin.class.getDeclaredMethod("addurdisc$copySong",CallbackInfo.class); copy.setAccessible(true);
        AnvilMenuMixin menu=new AnvilMenuMixin(); Slots inputs=new Slots(); menu.inputSlots=inputs; menu.resultSlots=new Slots();
        inputs.setItem(0,new ItemStack(new Object())); inputs.setItem(1,new ItemStack(ModInitialize.CUSTOM_DISC));
        CallbackInfo vanilla=new CallbackInfo("createResult",true); copy.invoke(menu,vanilla); check(!vanilla.isCancelled(),"ordinary anvil items must keep vanilla handling");
        ItemStack bound=new ItemStack(ModInitialize.CUSTOM_DISC); bound.set(ModInitialize.DISC_SONG,SONG); inputs.setItem(0,bound); inputs.setItem(1,bound.copy());
        CallbackInfo twoBound=new CallbackInfo("createResult",true); copy.invoke(menu,twoBound); check(!twoBound.isCancelled(),"already bound target must not be overwritten");
        System.out.println("PASS Anvil copy branch: rename, default/old/removed name, cost/source path, unmodified inputs, vanilla items");
    }
    static class HopperHooks extends JukeboxBlockEntityMixin {
        final ServerLevel level=new ServerLevel(new MinecraftServer(),"overworld");
        public Level getLevel() { return level; }
        public BlockPos getBlockPos() { return new BlockPos(0,0,0); }
    }
    static void blankDiscs() throws Exception {
        Method insertion=JukeboxBlockEntityMixin.class.getDeclaredMethod("addurdisc$rejectBlankDisc",int.class,ItemStack.class,CallbackInfoReturnable.class); insertion.setAccessible(true);
        Method resolve=JukeboxSongMixin.class.getDeclaredMethod("addurdisc$useBoundSong",ItemStack.class,CallbackInfoReturnable.class); resolve.setAccessible(true);
        ItemStack blank=new ItemStack(ModInitialize.CUSTOM_DISC), bound=new ItemStack(ModInitialize.CUSTOM_DISC), vanilla=new ItemStack(new Object());
        bound.set(ModInitialize.DISC_SONG,SONG);
        for(ItemStack stack:List.of(blank,bound,vanilla)) {
            CallbackInfoReturnable<Boolean> insert=new CallbackInfoReturnable<>("canPlaceItem",true); insertion.invoke(new HopperHooks(),0,stack,insert);
            check(stack==blank?insert.isCancelled() && !insert.getReturnValue():!insert.isCancelled(),"hopper handling changed for wrong stack");
            CallbackInfoReturnable<Optional<Holder<JukeboxSong>>> song=new CallbackInfoReturnable<>("fromStack",true); resolve.invoke(null,stack,song);
            check(stack==blank?song.isCancelled() && song.getReturnValue().isEmpty():stack==bound?song.isCancelled() && song.getReturnValue().isPresent():!song.isCancelled(),"song resolution changed for wrong stack");
        }
        System.out.println("PASS Blank disc lifecycle: hopper rejection, empty song, bound song, vanilla passthrough");
    }
    public static void main(String[] args) throws Exception { copying(); blankDiscs(); }
}
'@
    'playback-src/net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking.java' = @'
package net.fabricmc.fabric.api.networking.v1;
import net.minecraft.server.level.ServerPlayer;
public class ServerPlayNetworking {
    public static boolean canSend(ServerPlayer player, Object type) { return player.channels.contains(type); }
    public static void send(ServerPlayer player, Object payload) { player.messages.add(payload); }
}
'@
    'playback-src/net/minecraft/core/BlockPos.java' = @'
package net.minecraft.core;
public record BlockPos(int x, int y, int z) {
    public BlockPos immutable() { return this; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
}
'@
    'playback-src/net/minecraft/core/component/DataComponents.java' = @'
package net.minecraft.core.component;
public class DataComponents { public static final Object CUSTOM_NAME = new Object(); }
'@
    'playback-src/net/minecraft/core/Holder.java' = @'
package net.minecraft.core;
public record Holder<T>(T value) { public static <T> Holder<T> direct(T value) { return new Holder<>(value); } }
'@
    'playback-src/net/minecraft/core/registries/Registries.java' = @'
package net.minecraft.core.registries;
public class Registries { public static final Object JUKEBOX_SONG = new Object(); }
'@
    'playback-src/net/minecraft/core/RegistryAccess.java' = @'
package net.minecraft.core;
public class RegistryAccess {
    public Registry lookupOrThrow(Object key) { return new Registry(); }
    public static class Registry { public int getId(Object value) { return -1; } }
}
'@
    'playback-src/net/minecraft/network/chat/Component.java' = @'
package net.minecraft.network.chat;
public record Component(String value) { public static Component literal(String value) { return new Component(value); } public String getString() { return value; } }
'@
    'playback-src/net/minecraft/resources/Identifier.java' = @'
package net.minecraft.resources;
public record Identifier(String namespace, String path) {
    public String getNamespace() { return namespace; }
    public String getPath() { return path; }
}
'@
    'playback-src/net/minecraft/resources/ResourceKey.java' = @'
package net.minecraft.resources;
public record ResourceKey(Identifier identifier) { }
'@
    'playback-src/net/minecraft/server/level/ServerLevel.java' = @'
package net.minecraft.server.level;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
public class ServerLevel extends Level {
    public final List<ServerPlayer> players = new ArrayList<>();
    private final MinecraftServer server;
    private final ResourceKey dimension;
    public ServerLevel(MinecraftServer server, String name) { this.server = server; dimension = new ResourceKey(new Identifier("minecraft", name)); }
    public MinecraftServer getServer() { return server; }
    public ResourceKey dimension() { return dimension; }
    public RegistryAccess registryAccess() { return new RegistryAccess(); }
    public <T> List<T> getEntitiesOfClass(Class<T> type, AABB box) {
        return players.stream().filter(player -> player.level() == this && !player.disconnected && box.contains(player.x, player.y, player.z)).map(type::cast).toList();
    }
}
'@
    'playback-src/net/minecraft/server/level/ServerPlayer.java' = @'
package net.minecraft.server.level;
import java.util.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
public class ServerPlayer {
    public final Set<Object> channels = new HashSet<>();
    public final List<Object> messages = new ArrayList<>();
    public boolean disconnected;
    public double x, y, z;
    public ServerLevel level;
    public final ServerGamePacketListenerImpl connection = new ServerGamePacketListenerImpl(this);
    public ServerPlayer(ServerLevel level, double x, double y, double z) { this.level=level; this.x=x; this.y=y; this.z=z; level.players.add(this); }
    public ServerLevel level() { return level; }
    public boolean hasDisconnected() { return disconnected; }
    public double distanceToSqr(double x, double y, double z) { return Math.pow(this.x-x,2)+Math.pow(this.y-y,2)+Math.pow(this.z-z,2); }
}
'@
    'playback-src/net/minecraft/server/network/ServerGamePacketListenerImpl.java' = @'
package net.minecraft.server.network;
import net.minecraft.server.level.ServerPlayer;
public class ServerGamePacketListenerImpl {
    private final ServerPlayer player;
    public ServerGamePacketListenerImpl(ServerPlayer player) { this.player = player; }
    public void send(Object packet) { player.messages.add(packet); }
}
'@
    'playback-src/net/minecraft/network/protocol/game/ClientboundLevelEventPacket.java' = @'
package net.minecraft.network.protocol.game;
import net.minecraft.core.BlockPos;
public record ClientboundLevelEventPacket(int type, BlockPos pos, int data, boolean globalEvent) {
    public int getType() { return type; }
    public BlockPos getPos() { return pos; }
}
'@
    'playback-src/net/minecraft/server/MinecraftServer.java' = @'
package net.minecraft.server;
public class MinecraftServer { }
'@
    'playback-src/net/minecraft/sounds/SoundEvent.java' = @'
package net.minecraft.sounds;
import net.minecraft.resources.Identifier;
public record SoundEvent(Identifier location) { }
'@
    'playback-src/net/minecraft/util/StringUtil.java' = @'
package net.minecraft.util;
public class StringUtil { public static boolean isBlank(String value) { return value==null || value.isBlank(); } }
'@
    'playback-src/net/minecraft/world/Container.java' = @'
package net.minecraft.world;
import net.minecraft.world.item.ItemStack;
public interface Container { ItemStack getItem(int slot); void setItem(int slot, ItemStack item); }
'@
    'playback-src/net/minecraft/world/inventory/AnvilMenu.java' = @'
package net.minecraft.world.inventory;
public class AnvilMenu { }
'@
    'playback-src/net/minecraft/world/inventory/DataSlot.java' = @'
package net.minecraft.world.inventory;
public class DataSlot { private int value; public void set(int value) { this.value=value; } public int get() { return value; } }
'@
    'playback-src/net/minecraft/world/inventory/ItemCombinerMenu.java' = @'
package net.minecraft.world.inventory;
import net.minecraft.world.Container;
public class ItemCombinerMenu {
    public Container inputSlots, resultSlots;
    public ItemCombinerMenu(Object a, int b, Object c, Object d, Object e) { }
    public void broadcastChanges() { }
}
'@
    'playback-src/net/minecraft/world/item/ItemStack.java' = @'
package net.minecraft.world.item;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.init.ModInitialize;
public class ItemStack {
    public static final ItemStack EMPTY=new ItemStack(new Object());
    public final Object item;
    private final Map<Object,Object> components=new HashMap<>();
    public ItemStack(Object item) { this.item=item; }
    public boolean is(Object item) { return this.item==item; }
    @SuppressWarnings("unchecked") public <T> T get(Object key) { return (T)components.get(key); }
    public void set(Object key, Object value) { components.put(key,value); }
    public void remove(Object key) { components.remove(key); }
    public ItemStack copy() { ItemStack copy=new ItemStack(item); copy.components.putAll(components); return copy; }
    public Component getHoverName() {
        Component custom=get(DataComponents.CUSTOM_NAME); if(custom!=null) return custom;
        SongBinding song=get(ModInitialize.DISC_SONG); return Component.literal(song!=null && song.isBound()?song.sound():"Blank Disc");
    }
}
'@
    'playback-src/net/minecraft/world/item/JukeboxSong.java' = @'
package net.minecraft.world.item;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
public record JukeboxSong(Holder<SoundEvent> soundEvent) { }
'@
    'playback-src/net/minecraft/world/item/JukeboxSongPlayer.java' = @'
package net.minecraft.world.item;
public class JukeboxSongPlayer { }
'@
    'playback-src/net/minecraft/world/level/block/entity/JukeboxBlockEntity.java' = @'
package net.minecraft.world.level.block.entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
public class JukeboxBlockEntity {
    public Level getLevel() { return null; }
    public BlockPos getBlockPos() { return new BlockPos(0,0,0); }
}
'@
    'playback-src/net/minecraft/world/level/block/state/BlockState.java' = @'
package net.minecraft.world.level.block.state;
public class BlockState { }
'@
    'playback-src/net/minecraft/world/level/Level.java' = @'
package net.minecraft.world.level;
public class Level implements LevelAccessor { }
'@
    'playback-src/net/minecraft/world/level/LevelAccessor.java' = @'
package net.minecraft.world.level;
public interface LevelAccessor { }
'@
    'playback-src/net/minecraft/world/phys/AABB.java' = @'
package net.minecraft.world.phys;
import net.minecraft.core.BlockPos;
public class AABB {
    private final BlockPos pos;
    private double range;
    public AABB(BlockPos pos) { this.pos = pos; }
    public AABB inflate(double range) { this.range = range; return this; }
    public boolean contains(double x, double y, double z) {
        return x >= pos.x()-range && x < pos.x()+1+range
                && y >= pos.y()-range && y < pos.y()+1+range
                && z >= pos.z()-range && z < pos.z()+1+range;
    }
}
'@
    'playback-src/nya/tuyw/addurdisc/AddurDisc.java' = @'
package nya.tuyw.addurdisc;
public final class AddurDisc { public static final String MODID = "addurdisc"; }
'@
    'playback-src/nya/tuyw/addurdisc/disc/net/DiscPayloads.java' = @'
package nya.tuyw.addurdisc.disc.net;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
public class DiscPayloads {
    public record PlaySong(Identifier sound, BlockPos pos) { public static final Object TYPE = new Object(); }
    public record PlaybackState(Identifier sound, BlockPos pos, Identifier dimension, long elapsedTicks) { public static final Object TYPE = new Object(); }
    public record StopSong(BlockPos pos, Identifier dimension) { public static final Object TYPE = new Object(); }
}
'@
    'playback-src/nya/tuyw/addurdisc/disc/SongBinding.java' = @'
package nya.tuyw.addurdisc.disc;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.JukeboxSong;
public record SongBinding(String sound) {
    public boolean isBound() { return sound != null && !sound.isEmpty(); }
    public Holder<JukeboxSong> asSong() { return Holder.direct(new JukeboxSong(Holder.direct(new SoundEvent(new Identifier("addurdisc",sound))))); }
}
'@
    'playback-src/nya/tuyw/addurdisc/init/ModInitialize.java' = @'
package nya.tuyw.addurdisc.init;
public class ModInitialize { public static final Object CUSTOM_DISC=new Object(), DISC_SONG=new Object(); }
'@
    'playback-src/PlaybackCheck.java' = @'
import java.lang.reflect.*;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.sounds.*;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.world.item.*;
import nya.tuyw.addurdisc.disc.net.*;
import nya.tuyw.addurdisc.disc.net.DiscPayloads.*;
import nya.tuyw.addurdisc.mixin.JukeboxSongPlayerMixin;

public class PlaybackCheck {
    static final BlockPos POS = new BlockPos(0,0,0);
    static final Identifier SOUND = new Identifier("addurdisc", "track");
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static ServerPlayer player(ServerLevel level, double x, double z) {
        ServerPlayer player = new ServerPlayer(level,x,0.5,z);
        player.channels.add(PlaySong.TYPE); player.channels.add(PlaybackState.TYPE); player.channels.add(StopSong.TYPE);
        return player;
    }
    static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
    static boolean invoke(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name)) { method.setAccessible(true); method.invoke(target,arguments); return true; }
        }
        return false;
    }
    static void lifecycle(boolean baseline) throws Exception {
        MinecraftServer server = new MinecraftServer(); ServerLevel level = new ServerLevel(server,"overworld");
        ServerPlayer first = player(level,2,2); JukeboxSongPlayerMixin hooks = new JukeboxSongPlayerMixin();
        Holder<JukeboxSong> song = Holder.direct(new JukeboxSong(Holder.direct(new SoundEvent(SOUND))));
        set(hooks,"blockPos",POS);
        if (!baseline) { set(hooks,"song",song); set(hooks,"ticksSinceSongStarted",0L); }
        check(invoke(hooks,"addurdisc$announceImportedSong",level,song,null),"play hook missing");
        check(first.messages.size()==1,"initial player needs one start");
        ServerPlayer late = player(level,3,3);
        if (!baseline) set(hooks,"ticksSinceSongStarted",20L);
        invoke(hooks,"addurdisc$syncListeners",level,null,null);
        check(late.messages.size()==1,"Late joining player receives no playback while jukebox keeps playing");
        check(((PlaybackState) late.messages.getFirst()).elapsedTicks()==20L,"late start must carry elapsed ticks");
        invoke(hooks,"addurdisc$syncListeners",level,null,null);
        check(first.messages.size()==1 && late.messages.size()==1,"periodic updates must not restart listeners");
        invoke(hooks,"addurdisc$stopListeners",level,null,null);
        check(first.messages.getLast() instanceof StopSong && late.messages.getLast() instanceof StopSong,"stop hook must stop every listener");
        JukeboxSongPlayerMixin loaded = new JukeboxSongPlayerMixin();
        set(loaded,"blockPos",POS); set(loaded,"song",song); set(loaded,"ticksSinceSongStarted",237L);
        invoke(loaded,"addurdisc$syncListeners",level,null,null);
        check(first.messages.getLast() instanceof PlaybackState restored && restored.elapsedTicks()==237,"loaded player must announce on its first tick even between intervals");
        int loadedCount=first.messages.size(); set(loaded,"ticksSinceSongStarted",238L);
        invoke(loaded,"addurdisc$syncListeners",level,null,null);
        check(first.messages.size()==loadedCount,"loaded player must not run discovery every tick");
        invoke(loaded,"addurdisc$stopListeners",level,null,null);
        System.out.println("PASS Playback lifecycle hooks: late join, progress, no repeat, stop");
    }
    static void subscriptions() {
        MinecraftServer server = new MinecraftServer(); ServerLevel level = new ServerLevel(server,"overworld");
        ServerLevel nether = new ServerLevel(server,"the_nether");
        ServerPlayer near = player(level,1,1), corner = player(level,60,60), far = player(level,100,100);
        DiscPlayback.update(level,POS,SOUND,0);
        check(near.messages.size()==1 && corner.messages.isEmpty() && far.messages.isEmpty(),"range must be a sphere");
        far.x=2; far.z=2; DiscPlayback.update(level,POS,SOUND,40);
        check(far.messages.size()==1 && ((PlaybackState)far.messages.getFirst()).elapsedTicks()==40,"walking in must join at current tick");
        near.x=100; DiscPlayback.update(level,POS,SOUND,60);
        check(near.messages.getLast() instanceof StopSong,"walking out must stop");
        far.level=nether; DiscPlayback.update(level,POS,SOUND,80);
        check(far.messages.getLast() instanceof StopSong stop && stop.dimension().equals(level.dimension().identifier()),"dimension change stop needs source dimension");
        near.x=2; DiscPlayback.update(level,POS,SOUND,100);
        near.x=100; DiscPlayback.stop(level,POS);
        check(near.messages.getLast() instanceof StopSong,"stop must reach previously announced player beyond vanilla radius");
        ServerPlayer loaded = player(level,3,3); DiscPlayback.update(level,POS,SOUND,237);
        check(((PlaybackState)loaded.messages.getFirst()).elapsedTicks()==237,"loaded playback needs stored progress");
        loaded.disconnected=true; DiscPlayback.update(level,POS,SOUND,240);
        int disconnectedCount=loaded.messages.size(); DiscPlayback.stop(level,POS);
        check(loaded.messages.size()==disconnectedCount,"disconnected players receive no payload");
        loaded.disconnected=false; loaded.messages.clear();
        ServerPlayer legacy=player(level,5,5); legacy.channels.clear(); legacy.channels.add(PlaySong.TYPE);
        DiscPlayback.update(level,POS,SOUND,300);
        check(legacy.messages.getFirst() instanceof PlaySong,"old channel must remain available");
        legacy.x=100; DiscPlayback.update(level,POS,SOUND,320);
        check(legacy.messages.getLast() instanceof ClientboundLevelEventPacket stop && stop.getType()==1011 && stop.getPos().equals(POS),"old client outside vanilla radius needs a direct stop event");
        legacy.x=5; DiscPlayback.update(level,POS,SOUND,340);
        check(legacy.messages.getLast() instanceof PlaySong,"old client walking back must be subscribed again");
        legacy.level=nether; int legacyCount=legacy.messages.size(); DiscPlayback.update(level,POS,SOUND,360);
        check(legacy.messages.size()==legacyCount,"old stop event must not target same coordinates in a new dimension");
        legacy.level=level; DiscPlayback.update(level,POS,SOUND,380);
        ServerPlayer otherDimension=player(nether,3,3); DiscPlayback.update(nether,POS,SOUND,20);
        check(otherDimension.messages.size()==1,"identical positions in different dimensions must be independent");
        DiscPlayback.stop(level,POS);
        check(otherDimension.messages.size()==1,"stopping overworld must not stop nether");
        DiscPlayback.clear(server);
        check(otherDimension.messages.getLast() instanceof StopSong,"server cleanup stops owned subscriptions");
        int count=otherDimension.messages.size(); DiscPlayback.update(nether,POS,SOUND,40);
        check(otherDimension.messages.size()==count+1,"server cleanup removes old listener references");
        DiscPlayback.clear(server);
        System.out.println("PASS Playback subscriptions: sphere, enter/leave, source dimension, reliable stop, restored progress, disconnect, legacy, cleanup");
    }
    public static void main(String[] args) throws Exception {
        lifecycle(args.length>0); if(args.length==0) subscriptions();
    }
}
'@
}
Push-Location $taskRepo
try {
    foreach ($fixture in $taskFixtures.GetEnumerator()) {
        Write-TestFile $fixture.Key $fixture.Value | Out-Null
    }
    if ($Checks -contains 'Config') {
        $taskGson = Find-TestJar 'com.google.code.gson' 'gson'
        $taskOut = Join-Path $taskRoot 'config-classes'
        New-Item -ItemType Directory -Force -Path $taskOut | Out-Null
        $taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'config-src') -Recurse -Filter '*.java' | ForEach-Object FullName)
        $taskSources += Join-Path $taskRepo 'src/main/java/nya/tuyw/addurdisc/config/Config.java'
        $taskSources += Write-TestFile 'source/nya/tuyw/addurdisc/config/ConfigHandler.java' (Read-TestSource 'src/main/java/nya/tuyw/addurdisc/config/ConfigHandler.java')
        & $taskJavac -classpath $taskGson -d $taskOut $taskSources
        if ($LASTEXITCODE -ne 0) { throw 'Config fixture compilation failed' }
        & $taskJava -classpath "$taskOut;$taskGson" ConfigCheck
        if ($LASTEXITCODE -ne 0) { throw 'ConfigCheck failed' }
    }
    if (($Checks -contains 'Playback') -or ($Checks -contains 'Gameplay')) {
        $taskMixin = Find-TestJar 'net.fabricmc' 'sponge-mixin'
        $taskOut = Join-Path $taskRoot 'playback-classes'
        New-Item -ItemType Directory -Force -Path $taskOut | Out-Null
        $taskSources = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'playback-src') -Recurse -Filter '*.java' | ForEach-Object FullName)
        $taskSources += Join-Path $taskRepo 'src/main/java/nya/tuyw/addurdisc/disc/DiscProtocol.java'
        $taskSources += Join-Path $taskRepo 'src/main/java/nya/tuyw/addurdisc/disc/net/DiscPlayback.java'
        $taskSources += Join-Path $taskRepo 'src/main/java/nya/tuyw/addurdisc/mixin/JukeboxBlockEntityMixin.java'
        foreach ($mixin in @('JukeboxSongPlayerMixin', 'JukeboxSongMixin')) {
            $taskSources += Write-TestFile "source/nya/tuyw/addurdisc/mixin/$mixin.java" (Read-TestSource "src/main/java/nya/tuyw/addurdisc/mixin/$mixin.java")
        }
        # Only fixture instantiation changes: the injected copy method remains byte-for-byte.
        $taskAnvil = (Read-TestSource 'src/main/java/nya/tuyw/addurdisc/mixin/AnvilMenuMixin.java').Replace('public abstract class AnvilMenuMixin', 'public class AnvilMenuMixin').Replace('private AnvilMenuMixin()', 'public AnvilMenuMixin()')
        if ($taskAnvil -notmatch 'private String itemName;') {
            # The real target has this field even before our mixin starts shadowing it.
            $taskAnvil = $taskAnvil.Replace('public class AnvilMenuMixin extends ItemCombinerMenu {', "public class AnvilMenuMixin extends ItemCombinerMenu {`n    private String itemName;")
        }
        $taskSources += Write-TestFile 'source/nya/tuyw/addurdisc/mixin/AnvilMenuMixin.java' $taskAnvil
        & $taskJavac -proc:none -classpath $taskMixin -d $taskOut $taskSources
        if ($LASTEXITCODE -ne 0) { throw 'Gameplay fixture compilation failed' }
        if ($Checks -contains 'Playback') {
            if ($BaselineRef) { & $taskJava -classpath "$taskOut;$taskMixin" PlaybackCheck baseline }
            else { & $taskJava -classpath "$taskOut;$taskMixin" PlaybackCheck }
            if ($LASTEXITCODE -ne 0) { throw 'PlaybackCheck failed' }
        }
        if ($Checks -contains 'Gameplay') {
            & $taskJava -classpath "$taskOut;$taskMixin" GameplayCheck
            if ($LASTEXITCODE -ne 0) { throw 'GameplayCheck failed' }
        }
    }
} finally {
    Pop-Location
}
