# Minecraft 1.21.11 适配

基线为完整 26.3 快照 `56dd853`，使用新建的 `1.21.11` 分支。保留原有导入、服务端 ffmpeg 转码、多人传输与播放进度、唱片复制及限制逻辑。

## 版本与接口

- Minecraft 1.21.11、Java 21 字节码、Fabric Loader 0.19.5、Fabric API 0.141.6+1.21.11、Loom 1.17.21、Gradle 9.7.1。
- 使用 `net.fabricmc.fabric-loom-remap`、官方 Mojang mappings、`modImplementation`，`build` 包含发布用 `remapJar`；26.1 起的不重映射插件不适用此版本。
- 将 GUI 迁移到 `GuiGraphics` / `render`；文件选择复用游戏提供的 TinyFileDialogs（IO 线程）及 AWT 后备，保留拖放入口。
- 声音实例自己持有 `WeighedSoundEvents`，实现目标版本的 `resolve`，保留原有 JOrbis 流式解码、跳过已播放进度及取消关闭。
- 客户端维度切换使用 Fabric `ClientWorldEvents`；聊天使用 `Minecraft.getChatListener()`。
- 网络消息注册使用 `PayloadTypeRegistry.playC2S/playS2C`，手部枚举使用校验范围的 VarInt 编解码。
- `JukeboxPlayable` 使用 `EitherHolder`；`JukeboxSong.fromStack` 注入含前置 `HolderLookup.Provider` 参数。后者通过编译并不足以发现签名问题，已按官方映射与真实服务端核实。
- 配方解锁条件改用本版 codec 对应的 `recipe` 字段，奖励仍为 `recipes` 列表。README 同步版本要求和 remap 产物说明。

官方资料：[Fabric 版本元数据](https://meta.fabricmc.net/v2/versions/game)、[Fabric API Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)、[Fabric 1.21.11 迁移说明](https://fabricmc.net/2025/12/05/12111.html)。具体游戏类与方法签名按 Mojang 发布的 client mappings 和 Loom 映射 jar 核实。

## 已完成验证

- `gradlew.bat build --console=plain --max-workers=2`：成功，公共/客户端代码和 remap 发布包均生成；没有自动单元测试源。
- 现有 `scripts/review-network.ps1` 四项回归通过：空闲后下载恢复、连续请求排队、上传预算幂等释放、排队转换取消后清理文件。
- 真实 Fabric 隔离服务器（复用本项目已接受的测试 EULA）通过：`blank/hopper/bound/vanilla/play/tick/stop/removal/anvil`，五个公共 Mixin 实际加载，服务器正常关闭。
- 服务端实测覆盖空白片拒绝插入、漏斗、已绑定和原版唱片、播放与停止、恢复 237 tick 的播放进度、移除时清理、铁砧重命名复制、一级经验和一张空白片消耗、保留原片，以及原版铁砧行为。
- `git diff --check` 通过。产物为 `build/libs/AddurDisc-Fabric-1.21.11-1.4.jar` 和对应 sources jar。

## 验证边界

客户端类已按目标依赖编译并静态核对；操作系统文件窗口、实际音频输出及多人游戏内交互仍需人工回归。

## 修改文件

- `build.gradle`、`gradle.properties`、`README.md`：构建工具链、目标依赖及使用说明。
- `src/main/resources/fabric.mod.json`、`addurdisc.mixins.json`、`data/addurdisc/advancement/recipes/custom_disc.json`：依赖范围、Java 级别及解锁 codec。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscNetwork.java`、`DiscPayloads.java`：旧版 Fabric 消息注册及手部编码。
- `src/main/java/nya/tuyw/addurdisc/init/ModInitialize.java`、`mixin/JukeboxSongMixin.java`：歌曲 Holder 和注入参数。
- `src/client/java/nya/tuyw/addurdisc/disc/DiscClientSounds.java`、`ImportedDiscSoundInstance.java`、`net/DiscClientNetwork.java`、`ui/MusicPickerScreen.java`、`ui/NativeFileDialog.java`：目标客户端 API。
- `docs/porting.md`：本记录。
