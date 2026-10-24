# Minecraft 1.20.1 适配记录

## 输入与范围

- 输入快照：26.3 的 `56dd853`，功能保持一致；分支：`1.20.1`。
- Minecraft：1.20.1；Fabric API：0.92.12+1.20.1；Loader：0.19.5；Loom remap：1.17.21。
- 编译目标 Java 17；此次 Gradle 9.7.1 使用本机 JDK 25 运行。

## 适配原理

1. 旧版没有物品数据组件、JukeboxSong 和 typed payload。歌曲绑定改存于 `ItemStack` 的 `addurdisc_song` NBT，沿用同一归一化构造器、UTF-8 字节限制、时长限制；保存与物品同步由旧版原版 NBT 链路承担。
2. 唱片继承旧版 `RecordItem`，保留原版手持插入、红石与比较器、漏斗、粒子行为；补上 `minecraft:music_discs` 标签。空白唱片依然拒绝手持和漏斗插入。唱片机 Mixin 从物品 NBT 读取实际时长并使用原版持久化的 TickCount/RecordStartTick 追踪进度；订阅、停播、范围和中途入场逻辑沿用原实现。
3. 网络消息保留相同业务字段，以 Fabric 旧版通道和 `FriendlyByteBuf` 编解码。保留 16 KiB 上传分片、128 KiB 下载分片、64 首分页、名称/标题 UTF-8 字节上限，并拒绝超限数组、分页和尾随数据。I/O 线程解码后调度到游戏线程；上传权限、容量、SHA1、来源物品身份、超时、转换队列和限速逻辑保持。
4. 旧版使用 `OggAudioStream` 流式解码，以 PCM 帧跳读恢复中途入场位置，单次最多读取64 KiB；保留下载时间补偿、取消和关闭同步。换维度在 `Minecraft.setLevel` 开始时立即清理，断开仍由连接事件清理。
5. 文件选择替换为旧版游戏自带 TinyFD 的异步系统选择器，并保留 AWT 回退；拖拽与服务端 ffmpeg 转码链路保持。
6. 旧铁砧没有 `onlyRenaming` 保留路径；仅在空白加原片复制时跳过清空第二格，原版继续消费空白、收取1级、处理铁砧事件。名称处理保持。
7. 资源恢复旧版复数 recipes/advancements 与旧配方 JSON；动态资源包使用旧版 Pack API。公共与客户端 Mixin 配置分别引用对应 refmap。

## 验证

- `gradlew.bat build --no-daemon`：成功，包含公共/客户端编译、Mixin AP 目标校验、remapJar 和 remapSourcesJar。
- `scripts/review-legacy-server.ps1 -JavaHome <JDK目录> -AcceptedEula <既有eula=true文件>`：真实 Fabric 1.20.1 隔离服务端通过；仅绑定127.0.0.1随机端口，正常启动并关闭。验证空白漏斗拒绝、已绑定及原版唱片漏斗接受、NBT往返、实际播放、237tick保存后恢复到238tick、动态时长、停播/移除清理、铁砧复制/重命名/恰好1级/原片保留、原版铁砧不变。
- 所有公共 Mixin 已在实际加载日志出现：AnvilMenuMixin、JukeboxBlockEntityMixin、JukeboxRemovalMixin。
- 旧版网络回归见 `scripts/review-network.ps1`，使用真实生产类路径。
- 关键证据：`build/build-verified.log`、`build/server-test2.log`、`build/review-check/server-test/console.log`（构建目录不提交）。
- `git diff --check`：通过。

## 产物与实际限制

- `build/libs/AddurDisc-Fabric-1.20.1-1.4.jar` 是发布产物；带 sources 的 jar 只供源码查看。
- 未启动可见图形客户端；文件对话框、拖拽、声音播放和真人多人联机仍需游戏内确认。服务端实测和协议回归不能替代音频设备/操作系统/真人联机验证。
- 1.20.1 世界的旧 NBT 与26.3数据组件不是跨版本世界迁移格式；此次提供各版本独立适配，并未改变Minecraft自身的世界升级限制。

## 修改文件

- `build.gradle`：版本API、资源或构建适配。
- `gradle.properties`：版本API、资源或构建适配。
- `logs/latest.log`：新增适配资源。
- `scripts/LegacyNetworkRegression.java`：新增旧版验证或适配。
- `scripts/review-legacy-server.ps1`：新增旧版验证或适配。
- `scripts/review-network.ps1`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/AddurDiscClient.java`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/DiscClientSounds.java`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/net/LegacyClientNetworking.java`：新增旧版验证或适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/MusicPickerScreen.java`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/init/GeneratedPackSource.java`：版本API、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/mixin/ClientLevelChangeMixin.java`：新增旧版验证或适配。
- `src/client/java/nya/tuyw/addurdisc/mixin/LevelEventHandlerMixin.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/CustomDiscItem.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/SongBinding.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscNetwork.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPayloads.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPlayback.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/LegacyServerNetworking.java`：新增旧版验证或适配。
- `src/main/java/nya/tuyw/addurdisc/disc/store/SongInfo.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/init/Generator.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/init/ModInitialize.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/AnvilMenuMixin.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxBlockEntityMixin.java`：版本API、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxPlayableTooltipMixin.java`：删除不适用的现代API适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxRemovalMixin.java`：新增旧版验证或适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongMixin.java`：删除不适用的现代API适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongPlayerMixin.java`：删除不适用的现代API适配。
- `src/main/resources/addurdisc.client.mixins.json`：新增旧版验证或适配。
- `src/main/resources/addurdisc.mixins.json`：版本API、资源或构建适配。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：删除不适用的现代API适配。
- `src/main/resources/data/addurdisc/advancements/recipes/custom_disc.json`：新增适配资源。
- `src/main/resources/data/addurdisc/recipe/custom_disc.json`：删除不适用的现代API适配。
- `src/main/resources/data/addurdisc/recipes/custom_disc.json`：新增适配资源。
- `src/main/resources/data/minecraft/tags/items/music_discs.json`：新增适配资源。
- `src/main/resources/fabric.mod.json`：版本API、资源或构建适配。
- `docs/porting.md`：适配原理、全部修改、验证证据、产物与实际限制。

- README.md：同步1.20.1版本、Java17、Fabric API、remap构建及NBT说明。

## 主 agent 独立复核（2026-10-24）

主 agent 阅读了 NBT 绑定、旧版消息桥接、唱片机时长与播放进度、铁砧 onTake 保留逻辑，并独立重跑以下验证：

- `review-network.ps1`：`PASS legacy network: all messages, pages, UTF-8 byte limits, allocation bounds, trailing data`。
- `review-legacy-server.ps1`（项目此前已接受的测试 EULA）：`PASS real server: blank/hopper/bound/vanilla/play/tick/stop/removal/anvil; 3 common mixins applied; clean shutdown.`
- README 已同步 Java 17 运行目标与 Loom remap 构建说明；构建工具 Gradle 9.7.1 本身需要 JDK 17 以上，建议使用 JDK 25 构建。
