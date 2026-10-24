# Minecraft 26.1.1 适配记录

适配基线为 26.3 完整快照 `56dd8534f6caf7cc0a53b796d1830a86e7146094`。由主 agent 完成最终核验后提交。

## 目标依赖

| 项目 | 版本 | 核实方式 |
| --- | --- | --- |
| Minecraft | 26.1.1 | Fabric Loom 实际解析目标版本并完成编译 |
| Java | 25.0.2 | `D:/Java/jdk-25.0.2` |
| Fabric Loom | 1.17.21 | Fabric Maven 官方版本目录；Gradle 配置输出确认加载 |
| Fabric Loader | 0.19.5 | Fabric Maven 实际依赖解析 |
| Fabric API | 0.145.4+26.1.1 | Fabric Maven 官方发布目录；本机构建解析成功 |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |

官方版本目录：[Fabric API Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)、[Fabric Loom Maven](https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/)。

## 迁移内容

- `gradle.properties`：目标 Minecraft 改为 26.1.1，Fabric API 改为 `0.145.4+26.1.1`。
- `src/main/resources/fabric.mod.json`：Minecraft 和 Fabric API 最低依赖改为匹配的 26.1.1 依赖。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：`recipe_unlocked` 条件使用 `recipe` 字段；目标 jar codec 读取该字段，奖励列表仍使用 `recipes`。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：使用 Minecraft 随附的 TinyFileDialogs，在 IO 池异步打开单文件选择器，并在 native dialog 不可用时回退到 AWT。保留拖放与现有异步 UI 流程。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：使用目标版 `SoundInstance.resolve(SoundManager)` 和 `getSound()`，实例自行持有 `WeighedSoundEvents`，保留流式 Ogg 播放与进度追赶。
- `src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`：系统提示改用 `Minecraft.getChatListener()`。

上传、服务端 ffmpeg 转码、限制校验、唱片绑定、配方、播放同步和中途加入追进度、维度清理、提示与铁砧复制逻辑未改动。

## 验证

- Java 25.0.2 下执行标准 `gradlew.bat build --no-daemon`：`BUILD SUCCESSFUL`，公共与客户端代码均编译通过；`:test`、`:validateAccessWidener` 为 `NO-SOURCE`。
- 26.1.1 使用分离的 clientonly/common jar。对目标 Loom 缓存中的两份 jar 运行 `javap -p -s`，核对所有 Mixin 方法和影子字段：

| Mixin | 目标成员（26.1.1） |
| --- | --- |
| `AnvilMenuMixin` | `AnvilMenu.createResult()V`；字段 `cost: DataSlot`、`repairItemCountCost: int`、`onlyRenaming: boolean`、`itemName: String` |
| `JukeboxSongMixin` | `JukeboxSong.fromStack(ItemStack): Optional` |
| `JukeboxPlayableTooltipMixin` | `addToTooltip(Item.TooltipContext, Consumer<Component>, TooltipFlag, DataComponentGetter): void`；`tryInsertIntoJukebox(Level, BlockPos, ItemStack, Player): InteractionResult` |
| `JukeboxSongPlayerMixin` | `play(LevelAccessor, Holder<JukeboxSong>): void`；`tick(LevelAccessor, BlockState): void`；`stop(LevelAccessor, BlockState): void`；字段 `blockPos: BlockPos`、`song: Holder<JukeboxSong>`、`ticksSinceSongStarted: long` |
| `JukeboxBlockEntityMixin` | `canPlaceItem(int, ItemStack): boolean`；`setRemoved(): void` |
| `LevelEventHandlerMixin` | `stopJukeboxSong(BlockPos): void` |
| `MixinPackRepository` | `reload(): void`；字段 `sources: Set<RepositorySource>` |
- `RecipeUnlockedTrigger.TriggerInstance` 的 codec 从目标 common jar 核实为读取字符串字段 `recipe`。
- `jar tf` 确认主产物包含模组元数据、配方进度、客户端文件选择与声音类以及服务端 Mixin。

## 产物与风险

- 主产物：`build/libs/AddurDisc-Fabric-26.1.1-1.4.jar`（163770 bytes）。
- 源码包：`build/libs/AddurDisc-Fabric-26.1.1-1.4-sources.jar`（95627 bytes）。
- 未启动游戏；TinyFileDialogs/AWT 跨平台表现、拖放及完整多人声音播放流程仍需游戏内回归，编译和目标签名检查不覆盖运行时行为。

## 本工作树修改文件

`gradle.properties`、`README.md`、`src/main/resources/fabric.mod.json`、`src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`、`src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`、`src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`、`src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`、`docs/porting.md`。

## 主 agent 独立复核（2026-10-24）

主 agent 已复查源代码差异，并在此目标版本独立运行网络四项回归以及真实 Fabric 隔离服务器回归。结果均通过：blank/hopper/bound/vanilla/play/tick/stop/removal/anvil；五个公共 Mixin 实际加载，正常关闭。覆盖播放恢复进度、移除清理和复制后保留原片。客户端系统对话框及实际多人音频仍需游戏内回归。
