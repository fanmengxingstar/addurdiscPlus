"""Run production DiscClientSounds against a sound manager with observable lifecycle effects."""
from pathlib import Path
import os
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
work = root / 'build/review-check/sounds-harness'
src = work / 'src'
classes = work / 'classes'
src.mkdir(parents=True, exist_ok=True)
classes.mkdir(parents=True, exist_ok=True)
stubs = {
'net/minecraft/resources/Identifier.java': '''package net.minecraft.resources; public record Identifier(String namespace,String path) { public String getNamespace(){return namespace;} public String getPath(){return path;} public static Identifier fromNamespaceAndPath(String a,String b){return new Identifier(a,b);} }''',
'net/minecraft/core/BlockPos.java': '''package net.minecraft.core; public record BlockPos(int x,int y,int z) { public BlockPos immutable(){return this;} }''',
'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys; import net.minecraft.core.BlockPos; public class Vec3 { public final double x,y,z; public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;} public static Vec3 atCenterOf(BlockPos p){return new Vec3(p.x()+.5,p.y()+.5,p.z()+.5);} }''',
'net/minecraft/sounds/SoundEvent.java': '''package net.minecraft.sounds; import net.minecraft.resources.Identifier; public record SoundEvent(Identifier location) { public static SoundEvent createVariableRangeEvent(Identifier id){return new SoundEvent(id);} }''',
'net/minecraft/sounds/SoundSource.java': '''package net.minecraft.sounds; public enum SoundSource { RECORDS }''',
'net/minecraft/util/RandomSource.java': '''package net.minecraft.util; public class RandomSource {}''',
'it/unimi/dsi/fastutil/floats/FloatConsumer.java': '''package it.unimi.dsi.fastutil.floats; public interface FloatConsumer {void accept(float value);}''',
'net/minecraft/util/Util.java': '''package net.minecraft.util; public class Util { public static java.util.concurrent.Executor nonCriticalIoPool(){return Runnable::run;} }''',
'net/minecraft/util/valueproviders/SampledFloat.java': '''package net.minecraft.util.valueproviders; public interface SampledFloat {}''',
'net/minecraft/util/valueproviders/ConstantFloat.java': '''package net.minecraft.util.valueproviders; public record ConstantFloat(float value) implements SampledFloat { public static ConstantFloat of(float value){return new ConstantFloat(value);} }''',
'net/minecraft/client/sounds/AudioStream.java': '''package net.minecraft.client.sounds; public interface AudioStream extends java.io.Closeable { javax.sound.sampled.AudioFormat getFormat(); java.nio.ByteBuffer read(int bytes) throws java.io.IOException; }''',
'net/minecraft/client/sounds/JOrbisAudioStream.java': '''package net.minecraft.client.sounds; public class JOrbisAudioStream implements AudioStream { public static long skippedSamples; public static int channels=1,opened,closed; public static Runnable onChunk,onRead; private final java.io.InputStream in; private boolean isClosed; public JOrbisAudioStream(java.io.InputStream in) throws java.io.IOException {this.in=in;if(in.read()!=79)throw new java.io.IOException("bad header");opened++;} public javax.sound.sampled.AudioFormat getFormat(){return new javax.sound.sampled.AudioFormat(44100,16,channels,true,false);} public java.nio.ByteBuffer read(int bytes){if(onRead!=null)onRead.run();if(isClosed)throw new AssertionError("close raced with decoder read");return java.nio.ByteBuffer.allocate(2);} public boolean readChunk(it.unimi.dsi.fastutil.floats.FloatConsumer sink){if(onChunk!=null)onChunk.run();for(int i=0;i<1024*channels;i++)sink.accept(0);skippedSamples+=1024*channels;return true;} public void close() throws java.io.IOException {if(!isClosed){isClosed=true;closed++;in.close();}} }''',
'net/minecraft/client/sounds/SoundBufferLibrary.java': '''package net.minecraft.client.sounds; public class SoundBufferLibrary {}''',
'net/minecraft/client/sounds/WeighedSoundEvents.java': '''package net.minecraft.client.sounds; import net.minecraft.resources.Identifier; import net.minecraft.client.resources.sounds.Sound; public class WeighedSoundEvents { public WeighedSoundEvents(Identifier id,String subtitle){} public void addSound(Sound sound){} }''',
'net/minecraft/client/resources/sounds/Sound.java': '''package net.minecraft.client.resources.sounds; import net.minecraft.resources.Identifier; import net.minecraft.util.valueproviders.SampledFloat; public class Sound { public enum Type { FILE } public final boolean stream; public final int distance; public Sound(Identifier id,SampledFloat volume,SampledFloat pitch,int weight,Type type,boolean stream,boolean preload,int distance){this.stream=stream;this.distance=distance;} }''',
'net/minecraft/client/resources/sounds/SoundInstance.java': '''package net.minecraft.client.resources.sounds; import net.minecraft.util.RandomSource; public interface SoundInstance { enum Attenuation { LINEAR } static RandomSource createUnseededRandom(){return new RandomSource();} }''',
'net/minecraft/client/resources/sounds/AbstractSoundInstance.java': '''package net.minecraft.client.resources.sounds; import net.minecraft.resources.Identifier; import net.minecraft.sounds.SoundSource; import net.minecraft.util.RandomSource; import net.minecraft.client.sounds.*; public abstract class AbstractSoundInstance implements SoundInstance { protected Sound sound; protected WeighedSoundEvents soundEvent; protected float volume=1,pitch=1; protected double x,y,z; protected boolean looping,relative; protected int delay; protected Attenuation attenuation=Attenuation.LINEAR; protected AbstractSoundInstance(Identifier id,SoundSource source,RandomSource random){} public WeighedSoundEvents getOrResolve(SoundManager manager){return null;} public java.util.concurrent.CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary library,Identifier id,boolean loop){throw new UnsupportedOperationException();} public Sound getSound(){return sound;} public float getVolume(){return volume;} public boolean canStartSilent(){return false;} }''',
'net/minecraft/client/resources/sounds/SimpleSoundInstance.java': '''package net.minecraft.client.resources.sounds; import net.minecraft.sounds.SoundEvent; import net.minecraft.world.phys.Vec3; public class SimpleSoundInstance implements SoundInstance { public static SoundInstance forJukeboxSong(SoundEvent e,Vec3 v){return new SimpleSoundInstance();} }''',
'net/minecraft/client/sounds/SoundEngine.java': '''package net.minecraft.client.sounds; public class SoundEngine { public enum PlayResult { NOT_STARTED,STARTED_SILENTLY,STARTED } }''',
'net/minecraft/server/packs/resources/PreparableReloadListener.java': '''package net.minecraft.server.packs.resources; public interface PreparableReloadListener { record SharedState(Object manager) {} }''',
'net/minecraft/client/sounds/SoundManager.java': '''package net.minecraft.client.sounds; import java.util.*; import java.util.concurrent.*; import java.util.function.*; import net.minecraft.resources.Identifier; import net.minecraft.client.resources.sounds.SoundInstance; import net.minecraft.server.packs.resources.PreparableReloadListener; public class SoundManager { public final Set<SoundInstance> active=new HashSet<>(); public int reloads; public WeighedSoundEvents getSoundEvent(Identifier id){return new WeighedSoundEvents(id,null);} public SoundEngine.PlayResult play(SoundInstance sound){active.add(sound);return SoundEngine.PlayResult.STARTED;} public boolean isActive(SoundInstance sound){return active.contains(sound);} public void stop(SoundInstance sound){active.remove(sound);} public CompletableFuture<Void> reload(PreparableReloadListener.SharedState state,Executor prep,Function<Object,CompletableFuture<Object>> barrier,Executor apply){reloads++;active.clear();return CompletableFuture.completedFuture(null);} }''',
'net/minecraft/client/Minecraft.java': '''package net.minecraft.client; import net.minecraft.client.sounds.SoundManager; public class Minecraft { private static final Minecraft INSTANCE=new Minecraft(); private final SoundManager sounds=new SoundManager(); public static Minecraft getInstance(){return INSTANCE;} public SoundManager getSoundManager(){return sounds;} public Object getResourceManager(){return new Object();} }''',
'net/minecraft/client/multiplayer/ClientLevel.java': '''package net.minecraft.client.multiplayer; public class ClientLevel {}''',
'net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents.java': '''package net.fabricmc.fabric.api.client.event.lifecycle.v1; import net.minecraft.client.Minecraft; public class ClientTickEvents { public interface EndTick {void endTick(Minecraft client);} public static class Event { public EndTick listener; public void register(EndTick listener){this.listener=listener;} } public static final Event END_CLIENT_TICK=new Event(); }''',
'net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLevelEvents.java': '''package net.fabricmc.fabric.api.client.event.lifecycle.v1; import net.minecraft.client.Minecraft; import net.minecraft.client.multiplayer.ClientLevel; public class ClientLevelEvents { public interface Changed {void changed(Minecraft client,ClientLevel level);} public static class Event { public Changed listener; public void register(Changed listener){this.listener=listener;} } public static final Event AFTER_CLIENT_LEVEL_CHANGE=new Event(); }''',
'nya/tuyw/addurdisc/AddurDisc.java': '''package nya.tuyw.addurdisc; public class AddurDisc { public static final String MODID="addurdisc"; public static final Log LOGGER=new Log(); public static class Log { public void warn(String s,Object... args){} public void debug(String s,Object... args){} } }''',
'nya/tuyw/addurdisc/disc/DiscProtocol.java': '''package nya.tuyw.addurdisc.disc; public class DiscProtocol { public static final int AUDIBLE_BLOCKS=16; }''',
'nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java': '''package nya.tuyw.addurdisc.disc.net; public class DiscClientNetwork { public static int requests; public static final java.util.Set<String> BLOCKED=new java.util.HashSet<>(); public static void request(String name){requests++;} public static boolean isReady(String name){return !BLOCKED.contains(name)&&nya.tuyw.addurdisc.disc.store.SongStore.exists(name);} }''',
'nya/tuyw/addurdisc/disc/store/SongStore.java': '''package nya.tuyw.addurdisc.disc.store; import java.nio.file.*; import java.util.*; public class SongStore { public static final Map<String,Path> FILES=new HashMap<>(); public static boolean exists(String name){return FILES.containsKey(name)&&Files.isRegularFile(FILES.get(name));} public static Path resolve(String name){return FILES.get(name);} }''',
'nya/tuyw/addurdisc/init/DiscResources.java': '''package nya.tuyw.addurdisc.init; public class DiscResources { public static void refresh(){} public static boolean isValidName(String name){return name!=null&&name.matches("[a-z0-9][a-z0-9._-]{0,63}");} }''',
'nya/tuyw/addurdisc/disc/SoundsHarness.java': '''package nya.tuyw.addurdisc.disc;
import java.nio.file.*; import java.lang.reflect.*; import java.util.*;
import net.minecraft.client.*; import net.minecraft.client.sounds.*; import net.minecraft.client.resources.sounds.*; import net.minecraft.core.*; import net.minecraft.resources.*;
import nya.tuyw.addurdisc.disc.store.*; import nya.tuyw.addurdisc.disc.net.*; import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
public class SoundsHarness {
 static void check(boolean truth,String message){if(!truth)throw new AssertionError(message);}
 static Map<?,?> state(String field)throws Exception{Field f=DiscClientSounds.class.getDeclaredField(field);f.setAccessible(true);return (Map<?,?>)f.get(null);}
 public static void main(String[] args)throws Exception{
  SoundManager manager=Minecraft.getInstance().getSoundManager();
  SoundInstance external=new SimpleSoundInstance();manager.play(external);
  DiscClientSounds.reloadSoundDefinitions();check(manager.isActive(external),"Refreshing a track stopped an unrelated sound");
  DiscClientSounds.register();
  Path song=Files.createTempFile("disc-check", ".ogg");Files.write(song,new byte[]{79,103,103,83});SongStore.FILES.put("one",song);
  Identifier one=new Identifier("addurdisc","one");BlockPos pos=new BlockPos(0,64,0);DiscClientSounds.play(one,pos);
  check(state("PLAYING").size()==1,"Present track did not start");
  SoundInstance imported=(SoundInstance)state("PLAYING").get(pos);DiscClientSounds.reloadSoundDefinitions();check(manager.isActive(imported)&&manager.isActive(external),"Sync interrupted existing streams");check(manager.reloads==0,"Sync called the complete sound engine reload");
  ImportedDiscSoundInstance stream=(ImportedDiscSoundInstance)imported;check(stream.getOrResolve(manager)!=null,"No synthetic sound event");check(stream.getSound().stream&&stream.getSound().distance==16&&stream.getVolume()==4,"Vanilla record parameters changed");
  stream.getAudioStream(new SoundBufferLibrary(),one,false).join().close();
  JOrbisAudioStream.skippedSamples=0; ImportedDiscSoundInstance late=new ImportedDiscSoundInstance(one,pos,song,100L,System.nanoTime());late.getAudioStream(new SoundBufferLibrary(),one,false).join().close();check(JOrbisAudioStream.skippedSamples>=220500&&JOrbisAudioStream.skippedSamples<230000,"Late mono playback did not seek five seconds");
  JOrbisAudioStream.channels=2;JOrbisAudioStream.skippedSamples=0;ImportedDiscSoundInstance stereo=new ImportedDiscSoundInstance(one,pos,song,100L,System.nanoTime());stereo.getAudioStream(new SoundBufferLibrary(),one,false).join().close();check(JOrbisAudioStream.skippedSamples>=441000&&JOrbisAudioStream.skippedSamples<460000,"Late stereo playback did not use the channel count");JOrbisAudioStream.channels=1;
  late.cancelStream();boolean cancelled=false;try{late.getAudioStream(new SoundBufferLibrary(),one,false).join();}catch(java.util.concurrent.CompletionException e){cancelled=e.getCause() instanceof java.util.concurrent.CancellationException;}check(cancelled,"Stopped instance opened a late stream");
  ImportedDiscSoundInstance concurrent=new ImportedDiscSoundInstance(one,pos,song,0L,System.nanoTime());AudioStream managed=concurrent.getAudioStream(new SoundBufferLibrary(),one,false).join();var reading=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);JOrbisAudioStream.onRead=()->{reading.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}};var read=java.util.concurrent.CompletableFuture.runAsync(()->{try{managed.read(16);}catch(java.io.IOException e){throw new RuntimeException(e);}});check(reading.await(2,java.util.concurrent.TimeUnit.SECONDS),"Read did not start");var close=java.util.concurrent.CompletableFuture.runAsync(concurrent::cancelStream);boolean blocked=false;try{close.get(50,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(java.util.concurrent.TimeoutException e){blocked=true;}check(blocked,"Closing raced with the active decoder read");release.countDown();read.join();close.join();JOrbisAudioStream.onRead=null;check(managed.read(16).remaining()==0,"Cancelled stream did not return EOF");
  ImportedDiscSoundInstance seeking=new ImportedDiscSoundInstance(one,pos,song,100L,System.nanoTime());int openedBefore=JOrbisAudioStream.opened,closedBefore=JOrbisAudioStream.closed;JOrbisAudioStream.onChunk=seeking::cancelStream;cancelled=false;try{seeking.getAudioStream(new SoundBufferLibrary(),one,false).join();}catch(java.util.concurrent.CompletionException e){cancelled=e.getCause() instanceof java.util.concurrent.CancellationException;}JOrbisAudioStream.onChunk=null;check(cancelled&&JOrbisAudioStream.opened==openedBefore+1&&JOrbisAudioStream.closed==closedBefore+1,"Seek cancellation leaked an open stream");
  BlockPos delayedPos=new BlockPos(2,64,0);Identifier delayed=new Identifier("addurdisc","delayed");SongStore.FILES.put("delayed",song);DiscClientNetwork.BLOCKED.add("delayed");DiscClientSounds.play(delayed,delayedPos,50L);check(state("WAITING").containsKey(delayedPos)&&!state("PLAYING").containsKey(delayedPos),"An unverified local file played before the server manifest check");Object pending=state("WAITING").get(delayedPos);Field received=pending.getClass().getDeclaredField("announcedAtNanos");received.setAccessible(true);long packetTime=received.getLong(pending);DiscClientNetwork.BLOCKED.remove("delayed");DiscClientSounds.playWaiting();ImportedDiscSoundInstance afterDownload=(ImportedDiscSoundInstance)state("PLAYING").get(delayedPos);Field actualTime=ImportedDiscSoundInstance.class.getDeclaredField("announcedAtNanos");actualTime.setAccessible(true);check(actualTime.getLong(afterDownload)==packetTime,"Downloading reset the packet receipt timestamp");DiscClientSounds.stop(delayedPos);DiscClientNetwork.requests=0;
  DiscClientSounds.stop(pos);check(!manager.isActive(imported)&&manager.isActive(external),"Stopping one jukebox affected others");
  BlockPos waiting=new BlockPos(1,64,0);DiscClientSounds.play(new Identifier("addurdisc","missing"),waiting);check(state("WAITING").size()==1&&DiscClientNetwork.requests==1,"Missing track did not wait/request");
  ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.listener.changed(Minecraft.getInstance(),new net.minecraft.client.multiplayer.ClientLevel());check(state("WAITING").isEmpty(),"Dimension change retained pending jukebox");
  DiscClientSounds.play(one,pos);imported=(SoundInstance)state("PLAYING").get(pos);manager.stop(imported);ClientTickEvents.END_CLIENT_TICK.listener.endTick(Minecraft.getInstance());check(state("PLAYING").isEmpty(),"Ended sound remained tracked");
  DiscClientSounds.play(one,pos);imported=(SoundInstance)state("PLAYING").get(pos);DiscClientSounds.clear();check(!manager.isActive(imported)&&manager.isActive(external),"Leaving world did not stop the owned sound only");
  Files.delete(song);System.out.println("PASS: no engine reload; streamed path; record parameters; isolated stop; pending dimension cleanup; natural completion; clear.");
 }
}''',
}
# The first run intentionally excludes new API references to observe the old implementation's failure.
if '--baseline' in __import__('sys').argv:
    test = stubs['nya/tuyw/addurdisc/disc/SoundsHarness.java']
    test = test[:test.index('  DiscClientSounds.register();')] + '\n }\n}'
    stubs['nya/tuyw/addurdisc/disc/SoundsHarness.java'] = test
    stubs['nya/tuyw/addurdisc/disc/DiscClientSounds.java'] = subprocess.check_output(
        ['git', 'show', 'HEAD:src/client/java/nya/tuyw/addurdisc/disc/DiscClientSounds.java'], cwd=root, text=True)
else:
    stubs['nya/tuyw/addurdisc/disc/SoundsHarness.java'] = stubs['nya/tuyw/addurdisc/disc/SoundsHarness.java'].replace(
        'DiscClientSounds.reloadSoundDefinitions();', '')
for name, code in stubs.items():
    target = src / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(code, encoding='utf-8')
production = root / 'src/client/java/nya/tuyw/addurdisc/disc'
sources = list(src.rglob('*.java'))
if '--baseline' not in __import__('sys').argv:
    sources = [source for source in sources if source.name != 'DiscClientSounds.java']
    sources.append(production / 'DiscClientSounds.java')
if '--baseline' not in __import__('sys').argv and (production / 'ImportedDiscSoundInstance.java').exists():
    sources.append(production / 'ImportedDiscSoundInstance.java')
suffix = '.exe' if os.name == 'nt' else ''
jdk = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
javac = str(jdk / ('javac' + suffix)) if jdk else shutil.which('javac')
java = str(jdk / ('java' + suffix)) if jdk else shutil.which('java')
subprocess.run([javac, '-encoding', 'UTF-8', '-d', str(classes), *map(str,sources)], check=True)
result = subprocess.run([java, '-cp', str(classes), 'nya.tuyw.addurdisc.disc.SoundsHarness'])
if '--baseline' in __import__('sys').argv:
    if result.returncode == 0:
        raise AssertionError('The original engine reload should interrupt the unrelated sound.')
    print('PASS baseline reproduction: HEAD interrupted an unrelated sound.')
else:
    result.check_returncode()
