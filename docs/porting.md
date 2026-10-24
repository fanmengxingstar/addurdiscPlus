# Minecraft 1.21.4 适配记录

基线为完整26.3快照 `56dd853`，使用全新同名分支 `1.21.4`。目标Java21，Fabric API `0.119.4+1.21.4`，Fabric Loader `0.19.5`，Loom `1.17.21`，Gradle9.7.1（本机JDK25运行构建）。

## 适配范围

- 混淆版本使用 `net.fabricmc.fabric-loom-remap`、Mojang官方映射和 `modImplementation`，`build` 生成发布用remapJar。
- 注册标识符、资源Key、Util包、GuiGraphics/render、原生文件选择器、聊天入口、网络注册、手部枚举codec与目标版本对应。
- 保留文件选择/拖拽、服务端ffmpeg转换、上传/下载/预算/容量限制、曲目绑定、播放进度追赶、换维度/断开/移除清理和铁砧复制行为。
- 本版JukeboxSong.fromStack注入含HolderLookup.Provider参数；setRemoved在BlockEntity中声明，因此由JukeboxRemovalMixin注入声明类，仅对唱片机清理。
- 动态资源包由目标版本的PackMetadataSection codec生成；recipe_unlocked条件使用recipe字段，奖励仍用recipes列表。
- 老版组件tooltip拿不到ItemStack时，隐藏默认占位歌曲提示并由CustomDiscItem仅为空白唱片补充原有导入提示；旧版play返回void时由既有tick钩子关闭未启动的流；无Fabric世界切换事件时使用Minecraft.setLevel入口立即清理。

官方来源：[Fabric版本元数据](https://meta.fabricmc.net/v2/versions/game)、[Fabric Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)，以及Mojang正式版client mappings与Loom生成的目标jar。按真实类签名分版迁移，没有更改业务功能来跳过编译错误。

## 验证

- 完整 `gradlew.bat build` 成功，公共与客户端源代码均编译，remapJar及sourcesJar生成；单元测试任务没有测试源。
- 现有真实生产类路径网络回归四项通过：空闲后下载恢复、连续请求排队、上传预算幂等释放、取消排队转换后清理。
- 实际Fabric隔离服务器（复用项目已接受的测试EULA）通过：blank/hopper/bound/vanilla/play/tick/stop/removal/anvil。覆盖原版唱片、空白/绑定唱片及漏斗、237tick恢复进度、播放停止、移除清理、铁砧复制/名称/恰好一级经验/原片保留。
- 核心Mixin在实际服务器加载并正常关闭。客户端注入目标按官方映射检查，未启动图形客户端；系统选择窗口、声音设备输出与真人多人交互仍需游戏内回归。
- 产物：`build/libs/AddurDisc-Fabric-1.21.4-1.4.jar`。真实服务器日志在 `build/review-check/server-test/console.log`，不提交生成日志。

## 全部修改文件

- `README.md`：版本使用说明和迁移验证记录。
- `build.gradle`：目标构建工具链与依赖。
- `docs/porting.md`：版本使用说明和迁移验证记录。
- `gradle.properties`：目标构建工具链与依赖。
- `src/client/java/nya/tuyw/addurdisc/AddurDiscClient.java`：目标客户端API与声音/界面生命周期适配。
- `src/client/java/nya/tuyw/addurdisc/disc/DiscClientSounds.java`：目标客户端API与声音/界面生命周期适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：目标客户端API与声音/界面生命周期适配。
- `src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`：目标客户端API与声音/界面生命周期适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/MusicPickerScreen.java`：目标客户端API与声音/界面生命周期适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：目标客户端API与声音/界面生命周期适配。
- `src/client/java/nya/tuyw/addurdisc/mixin/ClientLevelChangeMixin.java`：目标客户端API与声音/界面生命周期适配。
- `src/main/java/nya/tuyw/addurdisc/disc/CustomDiscItem.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/disc/SongBinding.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscNetwork.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPayloads.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPlayback.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/init/Generator.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/init/ModInitialize.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxBlockEntityMixin.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxPlayableTooltipMixin.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxRemovalMixin.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongMixin.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongPlayerMixin.java`：目标公共API、组件、网络或注入目标适配。
- `src/main/resources/addurdisc.mixins.json`：目标版本元数据、资源格式或Mixin配置。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：目标版本元数据、资源格式或Mixin配置。
- `src/main/resources/fabric.mod.json`：目标版本元数据、资源格式或Mixin配置。
