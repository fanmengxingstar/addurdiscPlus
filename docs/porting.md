# Minecraft 26.2 适配记录

适配基线为 26.3 完整快照 `56dd8534f6caf7cc0a53b796d1830a86e7146094`。由主 agent 完成最终核验后提交。

## 目标依赖

| 项目 | 版本 | 核实方式 |
| --- | --- | --- |
| Minecraft | 26.2 | Fabric Loom 实际解析并完成编译 |
| Java | 25.0.2 | `D:/Java/jdk-25.0.2` |
| Fabric Loom | 1.17.21 | Fabric Maven 官方版本目录；Gradle 配置输出确认加载 |
| Fabric Loader | 0.19.5 | Fabric Maven 实际依赖解析 |
| Fabric API | 0.161.0+26.2 | Fabric Maven 官方发布目录；本机构建解析成功 |
| Gradle | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |

官方版本目录：[Fabric API Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)、[Fabric Loom Maven](https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/)。

## 迁移内容

- `gradle.properties` 与 `src/main/resources/fabric.mod.json`：目标 Minecraft 和 Fabric API 版本改为 26.2 对应的发布版本。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：目标 `RecipeUnlockedTrigger.TriggerInstance` codec 使用 `recipe` 字段；奖励列表仍使用 `recipes`。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：改用游戏依赖提供的 TinyFileDialogs，并放入 IO 池异步运行；native 对话框不可用时回退到 AWT。原有文件拖放与异步上传流程保留。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：按 26.2 声音接口实现 `resolve(SoundManager)` 和 `getSound()`，声音事件由播放实例持有；保留流式 Ogg 播放和下载期间的进度追赶。
- README 与模组元数据中的前置版本同步到 26.2。

`DiscClientNetwork` 已使用 26.2 兼容的 `client.gui.chatListener()`，无需改动；26.1.x 分支使用的 `Minecraft.getChatListener()` 在本版不存在。

上传、本地文件选择后的服务端 ffmpeg 转码、配方物品与提示、传输限制、多人同步播放及中途加入追进度、停播与换维度清理、铁砧 1 级复制且保留原片逻辑均保留。

## 验证

- 使用 Java 25.0.2 执行标准 `gradlew.bat build --no-daemon`：`BUILD SUCCESSFUL`；`:test` 与 `:validateAccessWidener` 为 `NO-SOURCE`。
- 在 Minecraft 26.2 实际 Loom clientonly/common jar 上用 `javap -p -s` 核对 Mixin 目标及字段：

| Mixin | 目标成员（26.2） |
| --- | --- |
| `AnvilMenuMixin` | `AnvilMenu.createResult()V`；字段 `cost: DataSlot`、`repairItemCountCost: int`、`onlyRenaming: boolean`、`itemName: String` |
| `JukeboxSongMixin` | 静态 `JukeboxSong.fromStack(ItemStack): Optional` |
| `JukeboxPlayableTooltipMixin` | `addToTooltip(Item.TooltipContext, Consumer<Component>, TooltipFlag, DataComponentGetter): void`；`tryInsertIntoJukebox(Level, BlockPos, ItemStack, Player): InteractionResult` |
| `JukeboxSongPlayerMixin` | `play(LevelAccessor, Holder<JukeboxSong>): void`；`tick(LevelAccessor, BlockState): void`；`stop(LevelAccessor, BlockState): void`；字段 `blockPos: BlockPos`、`song: Holder<JukeboxSong>`、`ticksSinceSongStarted: long` |
| `JukeboxBlockEntityMixin` | `canPlaceItem(int, ItemStack): boolean`；`setRemoved(): void` |
| `LevelEventHandlerMixin` | 目标类 `net.minecraft.client.renderer.LevelEventHandler`；私有 `stopJukeboxSong(BlockPos): void` |
| `MixinPackRepository` | `reload(): void`；字段 `sources: Set<RepositorySource>` |

- `RecipeUnlockedTrigger.TriggerInstance` 的目标 codec 确认使用 `Recipe.KEY_CODEC` 读取字符串字段 `recipe`。
- 目标 `SoundInstance` 接口确认要求 `resolve(SoundManager)` 与 `getSound()`；目标 `Gui` 存在 `chatListener()`。
- `jar tf` 确认主产物包含模组元数据、配方进度、客户端声音和文件对话框实现及 Mixin 类。

## 产物与风险

- 主产物：`build/libs/AddurDisc-Fabric-26.2-1.4.jar`（163794 bytes）。
- 源码包：`build/libs/AddurDisc-Fabric-26.2-1.4-sources.jar`（95604 bytes）。
- 未启动游戏；TinyFileDialogs/AWT 跨平台表现、拖放及完整多人播放仍需游戏内回归。标准构建和静态目标签名核对不覆盖运行时 Mixin 注入行为。

## 本工作树修改文件

`gradle.properties`、`README.md`、`src/main/resources/fabric.mod.json`、`src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`、`src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`、`src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`、`docs/porting.md`。

## 主 agent 独立复核（2026-10-24）

主 agent 已检查本版源差异，并运行真实网络四项及Fabric隔离服务器回归，全部通过：blank/hopper/bound/vanilla/play/tick/stop/removal/anvil；五个公共Mixin加载，正常关闭。首次offline classpath检查缺少新版LWJGL运行时原生库，联网解析官方依赖后复跑通过，没有修改业务代码来规避测试。
