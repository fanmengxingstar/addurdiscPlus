# Minecraft 1.21 适配记录

## 工具链

- Minecraft 1.21，Java 21，Fabric Loader 0.16.14，Fabric API 0.102.0+1.21。
- 使用 `net.fabricmc.fabric-loom-remap` 1.17.21、官方 Mojang mappings 和 `modImplementation`；`build` 通过 `remapJar` 生成可发布的混淆名 jar。
- 最终产物：`build/libs/AddurDisc-Fabric-1.21-1.4.jar`。

## 适配改动

- `build.gradle`、`gradle.properties`、`README.md`：切换到1.21专用 Loom remap、映射、依赖、Java 21和产物说明。
- 客户端：`AddurDiscClient.java`、`disc/DiscClientSounds.java`、`disc/ImportedDiscSoundInstance.java`、`disc/net/DiscClientNetwork.java`、`disc/ui/MusicPickerScreen.java`、`disc/ui/NativeFileDialog.java`、`mixin/LevelEventHandlerMixin.java`。更新1.21资源标识符、GUI `render`、声音流与typed payload API；原生文件选择器优先调用 Minecraft 随附的 TinyFileDialogs，并保留 AWT 后备和文件拖放。世界切换通过客户端tick识别 `ClientLevel` 实例变化并停止旧维度的本地声音。
- 通用逻辑：`disc/CustomDiscItem.java`、`disc/SongBinding.java`、`disc/net/DiscNetwork.java`、`disc/net/DiscPayloads.java`、`disc/net/DiscPlayback.java`、`init/Generator.java`、`init/ModInitialize.java`。迁移1.21的资源位置、数据组件、jukebox song和C2S/S2C typed payload接口；文件大小、分块、校验和与服务端转换限制沿用原值。
- Mixin：`mixin/AnvilMenuMixin.java`、`mixin/JukeboxBlockEntityMixin.java`、`mixin/JukeboxPlayableTooltipMixin.java`、`mixin/JukeboxSongMixin.java`、`mixin/JukeboxSongPlayerMixin.java`及新增 `mixin/JukeboxRemovalMixin.java`，并在 `src/main/resources/addurdisc.mixins.json` 注册。1.21的 `AnvilMenu` 没有 `onlyRenaming`；复制绑定唱片时由自定义结果设置1级成本，并在原版 `onTake` 扣除空白盘、经验后恢复右槽原片。jukebox实体从 `BlockEntity` 继承 `setRemoved`，所以清理注入放在 `BlockEntity` 并以 `instanceof JukeboxBlockEntity` 限定。
- 资源：删除 `assets/addurdisc/items/custom_disc.json`（新版独立物品定义尚未引入），配方使用 `data/addurdisc/recipe/` 单数目录与本版的物品输入对象结构；`advancement/recipes/custom_disc.json` 使用1.21的 `recipe` 条件字段；`fabric.mod.json` 更新环境入口与版本元数据。
- 新增 `scripts/review-server.ps1`：可重复运行的专服回归脚本；测试mod源码、编译输出和jar只写入 `build/review-server/`，没有加入生产源码或生产jar。

## 验证

在此工作树运行：

```powershell
$env:JAVA_HOME = 'D:\Zulu\zulu21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:JAVA_TOOL_OPTIONS = '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=9674 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=9674'
.\gradlew.bat --no-daemon clean build --console=plain
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/review-server.ps1 -JavaHome D:\Zulu\zulu21
```

- `clean build` 已完成 `compileJava`、`compileClientJava`、资源处理和 `remapJar`。测试mod通过命令行 `fabric.addMods` 加载到真实的Fabric 1.21服务端。
- 专服回归检查网络手值codec的合法往返与非法值拒绝、空白盘不播放且漏斗不能插入、绑定盘和原版唱片可解析、`ItemStack` registry-aware 保存/加载后绑定与jukebox playable组件保留、播放/逐tick进度/停止、中途进度恢复、BlockEntity移除清理、铁砧复制成本恰为1级且消耗空白盘而保留原片，以及原版铁砧改名。最近一次结果为 `PASS real Fabric server: hand-codec/blank/hopper/bound/vanilla/save-load/play/tick/stop/removal/anvil; six common mixins applied.`
- 使用1.21 Loom命名版 Minecraft jar 的 `javap -p` 核对了实际目标：`BlockEntity.setRemoved()`、`JukeboxBlockEntity.canPlaceItem(int, ItemStack)`、`AnvilMenu.createResult()`/`onTake(Player, ItemStack)`、`JukeboxSong.fromStack(HolderLookup.Provider, ItemStack)`、`JukeboxSongPlayer.setSongWithoutPlaying(Holder<JukeboxSong>, long)`/`stop(...)`。实际专服Mixin日志确认六个common mixin均应用。专服加载了1291个配方和1400个 advancement。
- 已核查发布包不包含 `nya/tuyw/addurdisc/review/` 测试类；测试类只在 `build/review-server/review-testmod.jar`。

## 风险和覆盖边界

- 真实专服覆盖了服务端初始化、配方加载、相关Mixin和核心唱片/铁砧行为；它未启动图形客户端做文件选择器手工点选，也未连接第二个真实客户端端到端验证上传、下载和多人同步。客户端源码已通过 `compileClientJava` 编译，分块/大小/哈希限制未改动。
- 1.21的 `EitherHolder(Holder)` 要求holder可提供注册表key，`Holder.direct(...)` 在 `CustomDiscItem` 初始化时会抛 `NoSuchElementException`。当前物品的 `JUKEBOX_PLAYABLE` 组件复用已注册的原版唱片holder并隐藏其提示；空白盘由物品提示显示导入说明，绑定盘实际曲目由 `JukeboxSongMixin` 从 `SongBinding` 解析。专服和ItemStack保存/加载往返均通过。


## 2026-10-25 主agent最终复核

对照Mojang正式版jar发现此版本要求recipe单数目录，之前recipes目录不会被加载。新增真实服务器配方存在断言先复现失败，再仅修正资源目录，增加解锁进度存在断言。清理构建后，生产类路径网络四项与真实Fabric专服全部通过；现在测试实际检查了配方、解锁进度、严格hand枚举、组件持久化、进度恢复、移除清理及铁砧一级复制保留原片。

所有测试mod只在build目录生成，发布jar没有review测试包。没有交互实测图形文件窗口、音频设备及真人多人端到端联机。

## 最终全部修改文件

- `README.md`：目标构建依赖和说明。
- `build.gradle`：目标构建依赖和说明。
- `docs/porting.md`：迁移原理、文件清单和实际验证记录。
- `gradle.properties`：目标构建依赖和说明。
- `scripts/review-server.ps1`：可重复运行的真实专服与配方/组件/协议回归。
- `src/client/java/nya/tuyw/addurdisc/AddurDiscClient.java`：目标客户端界面、声音与网络API。
- `src/client/java/nya/tuyw/addurdisc/disc/DiscClientSounds.java`：目标客户端界面、声音与网络API。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：目标客户端界面、声音与网络API。
- `src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`：目标客户端界面、声音与网络API。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/MusicPickerScreen.java`：目标客户端界面、声音与网络API。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：目标客户端界面、声音与网络API。
- `src/client/java/nya/tuyw/addurdisc/mixin/LevelEventHandlerMixin.java`：目标客户端界面、声音与网络API。
- `src/main/java/nya/tuyw/addurdisc/disc/CustomDiscItem.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/disc/SongBinding.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscNetwork.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPayloads.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPlayback.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/init/Generator.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/init/ModInitialize.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/mixin/AnvilMenuMixin.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxBlockEntityMixin.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxPlayableTooltipMixin.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxRemovalMixin.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongMixin.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongPlayerMixin.java`：目标组件、网络、唱片机和铁砧API及生命周期。
- `src/main/resources/addurdisc.mixins.json`：目标资源格式、配方目录、版本和Mixin配置。
- `src/main/resources/assets/addurdisc/items/custom_disc.json`：目标资源格式、配方目录、版本和Mixin配置。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：目标资源格式、配方目录、版本和Mixin配置。
- `src/main/resources/data/addurdisc/recipe/custom_disc.json`：目标资源格式、配方目录、版本和Mixin配置。
- `src/main/resources/fabric.mod.json`：目标资源格式、配方目录、版本和Mixin配置。
