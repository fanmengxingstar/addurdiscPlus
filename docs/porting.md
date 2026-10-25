# Minecraft 1.21.9 适配记录

基线为26.3完整快照56dd853，使用新建同名分支。Minecraft 1.21.9、Java21、Fabric Loader 0.19.5、Fabric API 0.134.1+1.21.9、Loom1.17.21、Gradle9.7.1。

## 改动

- 混淆版本使用net.fabricmc.fabric-loom-remap、Mojang官方映射、modImplementation与remapJar发布任务。
- 适配ResourceLocation、net.minecraft.Util、ResourceKey.location，以及GuiGraphics/render、Minecraft.getChatListener、SoundInstance.resolve和自己持有的声音事件。
- 本地选择使用游戏提供的TinyFileDialogs后台调用与AWT后备；拖拽入口保留。
- Fabric网络消息注册使用playC2S/playS2C，手部枚举采用拒绝非法值的codec；世界变化通过ClientWorldEvents即时清理。
- JukeboxSong.fromStack注入含HolderLookup.Provider；本版JukeboxBlockEntity自己声明setRemoved，注入目标与较早版本分开核对。空白片、漏斗、时长、播放进度和铁砧复制限制保持。
- 配方解锁条件使用本版codec的recipe字段，奖励仍为recipes列表。

## 实际验证

- 完整Gradle build成功：公共与客户端代码均编译，remapJar和sourcesJar生成。
- 主agent独立复核生产源码差异，运行真实生产类路径网络回归四项均通过。
- 主agent实际Fabric隔离服务器通过blank/hopper/bound/vanilla/play/tick/stop/removal/anvil：恢复播放进度、移除清理、原版行为与铁砧一级复制保留原片均核验，五个公共Mixin实际加载，正常关闭。
- Mixin目标及字段按本版本Mojang官方映射和Loom实际jar核对。测试日志位于build/review-check/server-test/console.log；测试输出不提交。
- 发布产物build/libs/AddurDisc-Fabric-1.21.9-1.4.jar不含测试mod类。未交互实测可见客户端、实际音频设备和真人多人联机。

官方依据：[Fabric版本元数据](https://meta.fabricmc.net/v2/versions/game)、[Fabric Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)，以及Mojang对应正式版jar/mappings。

## 全部修改文件

- `README.md`：目标依赖、使用和验证说明。
- `build.gradle`：目标构建工具链及依赖。
- `docs/porting.md`：目标依赖、使用和验证说明。
- `gradle.properties`：目标构建工具链及依赖。
- `src/client/java/nya/tuyw/addurdisc/disc/DiscClientSounds.java`：目标客户端声音、网络和界面接口。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：目标客户端声音、网络和界面接口。
- `src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`：目标客户端声音、网络和界面接口。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/MusicPickerScreen.java`：目标客户端声音、网络和界面接口。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：目标客户端声音、网络和界面接口。
- `src/main/java/nya/tuyw/addurdisc/disc/SongBinding.java`：目标公共组件、网络及Mixin签名。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscNetwork.java`：目标公共组件、网络及Mixin签名。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPayloads.java`：目标公共组件、网络及Mixin签名。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPlayback.java`：目标公共组件、网络及Mixin签名。
- `src/main/java/nya/tuyw/addurdisc/init/ModInitialize.java`：目标公共组件、网络及Mixin签名。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongMixin.java`：目标公共组件、网络及Mixin签名。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongPlayerMixin.java`：目标公共组件、网络及Mixin签名。
- `src/main/resources/addurdisc.mixins.json`：目标版本元数据、Mixin级别和资源codec。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：目标版本元数据、Mixin级别和资源codec。
- `src/main/resources/fabric.mod.json`：目标版本元数据、Mixin级别和资源codec。
