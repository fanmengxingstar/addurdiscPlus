# Minecraft 26.1.2 适配记录

适配基线为 26.3 完整快照 `56dd8534f6caf7cc0a53b796d1830a86e7146094`。由主 agent 完成最终核验后提交。

## 目标依赖

| 项目 | 版本 | 核实方式 |
| --- | --- | --- |
| Minecraft | 26.1.2 | Fabric Loom 实际解析目标版本并完成编译 |
| Java | 25.0.2 | `D:/Java/jdk-25.0.2` |
| Fabric Loom | 1.17.21 | Fabric Maven 官方版本目录；Gradle 配置输出确认加载 |
| Fabric Loader | 0.19.5 | Fabric Maven 实际依赖解析 |
| Fabric API | 0.155.3+26.1.2 | Fabric Maven 发布目录中该目标的版本；本机构建解析成功 |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |

官方版本目录：[Fabric API Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)、[Fabric Loom Maven](https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/)。

## 迁移内容

- `gradle.properties`：目标 Minecraft 改为 26.1.2，Fabric API 改为 `0.155.3+26.1.2`。
- `src/main/resources/fabric.mod.json`：Minecraft 和 Fabric API 最低依赖改为匹配的 26.1.2 依赖。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：将 `recipe_unlocked` 条件字段设为 `recipe`。目标游戏 jar 中 `RecipeUnlockedTrigger.TriggerInstance` 的 codec 明确读取 `recipe`；奖励列表仍使用 `recipes`。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：目标版本不提供 `org.lwjgl.sdl` 对话框类，改用游戏随附的 TinyFileDialogs，阻塞选择器在 Minecraft IO 池运行，选择单个文件，并保留 AWT 后备窗口。`MusicPickerScreen` 的异步状态处理和 `onFilesDrop` 拖放入口保持工作。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：26.1.2 的 `SoundInstance` 使用 `resolve(SoundManager)` 与 `getSound()`，`AbstractSoundInstance` 不再提供 26.3 使用的 `soundEvent` 字段。实例现在自行持有 `WeighedSoundEvents`，并返回原有流式 `Sound`，保留 Ogg 解码、延迟追播和停止时关闭流。
- `src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`：系统提示改经 `Minecraft.getChatListener()` 显示。

上传分块与服务端 ffmpeg 转码、权限和容量限制、唱片数据组件、配方、播放状态同步及中途加入追进度、维度变化清理、空白唱片拒绝插入和铁砧复制逻辑均未改动。

## 验证

- 在 Java 25.0.2 下运行标准 `gradlew.bat build --no-daemon`：`BUILD SUCCESSFUL`。公共代码和客户端代码均编译成功；`:test`、`:validateAccessWidener` 为 `NO-SOURCE`。
- 使用本机 Fabric Loom 生成的 Minecraft 26.1.2 合并 jar 对 Mixin 目标执行 `javap -p -s` 核查。以下目标方法及参数描述符均存在：

| Mixin | 目标成员（26.1.2） |
| --- | --- |
| `AnvilMenuMixin` | `AnvilMenu.createResult()V`；字段 `cost: DataSlot`、`repairItemCountCost: int`、`onlyRenaming: boolean`、`itemName: String` |
| `JukeboxSongMixin` | `JukeboxSong.fromStack(ItemStack): Optional` |
| `JukeboxPlayableTooltipMixin` | `addToTooltip(Item.TooltipContext, Consumer<Component>, TooltipFlag, DataComponentGetter): void`；`tryInsertIntoJukebox(Level, BlockPos, ItemStack, Player): InteractionResult` |
| `JukeboxSongPlayerMixin` | `play(LevelAccessor, Holder<JukeboxSong>): void`；`tick(LevelAccessor, BlockState): void`；`stop(LevelAccessor, BlockState): void`；字段 `blockPos: BlockPos`、`song: Holder<JukeboxSong>`、`ticksSinceSongStarted: long` |
| `JukeboxBlockEntityMixin` | `canPlaceItem(int, ItemStack): boolean`；`setRemoved(): void` |
| `LevelEventHandlerMixin` | `stopJukeboxSong(BlockPos): void` |
| `MixinPackRepository` | `reload(): void`；字段 `sources: Set<RepositorySource>` |

- `jar tf` 确认产物内包含客户端声音和文件选择类、服务端 Mixin、`fabric.mod.json` 及自定义配方进度 JSON。

## 产物与风险

- 主产物：`build/libs/AddurDisc-Fabric-26.1.2-1.4.jar`
- 源码包：`build/libs/AddurDisc-Fabric-26.1.2-1.4-sources.jar`
- 本次未启动游戏，因此 TinyFileDialogs/AWT 在各平台的实际表现、拖放和完整多人声音播放流程仍需游戏内回归。Mixin 方法和字段已按目标 jar 签名核实，构建本身不替代运行时行为验证。

## 本工作树修改文件

`gradle.properties`、`README.md`、`src/main/resources/fabric.mod.json`、`src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`、`src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`、`src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`、`src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`、`docs/porting.md`。

## 主 agent 独立复核（2026-10-24）

- 对实际工作树执行 `scripts/review-network.ps1 -JavaHome D:/Java/jdk-25.0.2`，四项通过：长时间空闲后下载恢复、连续请求排队、上传预算幂等释放、取消排队转换后清理文件。
- 复用现有服务器回归脚本，使用项目此前已接受的隔离服务器 EULA，针对 26.1.2 真实目标 jar 启动 Fabric 服务端：`PASS real server: blank/hopper/bound/vanilla/play/tick/stop/removal/anvil; 5 common mixins applied; clean shutdown.`
- 真实验证了原版唱片不受影响、空白/已绑定唱片及漏斗入口、播放和停止、存档恢复的播放进度、移除清理、铁砧复制与重命名、消耗一张空白唱片和一级经验且保留原片。
- 客户端文件选择器的操作系统窗口及实际多人音频输出尚未交互实测。
