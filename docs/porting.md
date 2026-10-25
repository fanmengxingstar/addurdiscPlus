# Minecraft 1.20.5 适配记录

## 输入与依赖

- 基线：26.3完整快照 `56dd853`；独立分支：`1.20.5`。
- Minecraft 1.20.5，Fabric API 0.97.8+1.20.5，Loader 0.19.5，Loom remap 1.17.21。
- 编译目标 Java21，Gradle 9.7.1此次由JDK25运行。

## 当前逻辑与改动依据

这个过渡版本已经提供数据组件和typed payload，却仍由RecordItem和旧唱片机负责播放，没有JukeboxSong注册表。因此不能直接套用26.3或旧NBT版本。

- `SongBinding`沿用标准Codec与StreamCodec，把歌曲名称、时长和标题保存到 `addurdisc:disc_song` 数据组件。统一构造器继续执行UTF-8字节截断、格式码清理和时长上限；fromStack/applyTo仅封装组件访问。
- 继续继承旧版RecordItem并注册占位声音，添加music_discs标签以支持漏斗；空白唱片仍拒绝手持和漏斗插入。占位声音的原版客户端播放被拦截，实际声音由模组同步通道播放。
- JukeboxBlockEntity从组件读取实际时长，使用原版持久化TickCount/RecordStartTick向中途入场玩家同步进度；停播和BlockEntity.setRemoved清理订阅。订阅、范围、维度隔离和服务端停止处理沿用原核心。
- 网络采用目标Fabric的PayloadTypeRegistry.playC2S/playS2C。这个版本的ServerPlayNetworking.Context没有server()，从当前player.server取得同一服务器；上传、容量、SHA1、物品身份、超时、限速和后台ffmpeg逻辑保持。
- 保留16KiB上传分片、128KiB下载分片、64首分页。目标原版ByteBufCodecs.stringUtf8按字符数限制、允许最多三倍编码字节；合法上传标题和落盘绑定仍由Utf8.truncate按128字节归一化。不能把这个目标API宣称为严格字节限制。
- 使用JOrbisAudioStream流式解码，按PCM帧跳读恢复播放位置并计入下载时间；保留取消与关闭同步。Minecraft.setLevel在这个版本增加ReceivingLevelScreen.Reason参数，清理Mixin按真实签名在换世界前立即清空声音。
- 文件选择改为游戏自带TinyFD异步系统对话框并保留AWT回退；拖拽和服务端转换功能保持。资源包采用PackLocationInfo/PackSelectionConfig与PathResourcesSupplier(Path)。
- 旧铁砧没有onlyRenaming保留路径，仅在空白加原片复制时跳过清空第二格；空白消耗、恰好1级经验、重命名及原版铁砧行为均保留。
- 恢复复数recipes/advancements目录和目标配方格式，客户端/公共Mixin分别使用各自refmap；发布jar经过remap。

## 验证

- `gradlew.bat build --no-daemon --max-workers=2`：完整构建成功，含公共和客户端编译、Mixin AP、remapJar/remapSourcesJar。证据：`build/build2.log`。
- `scripts/review-network.ps1 -JavaHome <JDK路径>`：真实生产类路径的typed payload回归通过。覆盖全部消息往返、空库、分页、字符串和分片/数量边界。证据：`build/network-test.log`。
- `scripts/review-legacy-server.ps1 -JavaHome <JDK路径> -AcceptedEula <既有eula=true文件>`：真实Fabric隔离服务端通过。仅绑定127.0.0.1随机端口，正常启动和关闭。
- 服务端实测：空白漏斗拒绝、已绑定与原版唱片接受、歌曲组件保存/读取往返、实际播放、237tick存档后恢复到238tick、动态时长终止、停播/移除清理、铁砧复制/重命名/恰好1级/原片保留和原版重命名。
- 三个公共Mixin均从真实日志核验应用：AnvilMenuMixin、JukeboxBlockEntityMixin、JukeboxRemovalMixin。客户端各注入签名另经目标jar的javap和AP核验。
- 证据：`build/server-test.log`、`build/review-check/server-test/console.log`；所有运行输出位于build内，不提交日志。
- `git diff --check`：通过。

## 产物与限制

- 发布产物：`build/libs/AddurDisc-Fabric-1.20.5-1.4.jar`；sources jar仅供源码参考。
- 未启动可见图形客户端。系统对话框、拖拽、实际音频设备和真人多人联机仍需游戏内确认；真实服务端和协议回归不等于这些手动测试已完成。
- 各版本独立适配，不提供跨Minecraft世界格式迁移承诺。

## 修改文件

- `README.md`：目标API、组件、资源或构建适配。
- `build.gradle`：目标API、组件、资源或构建适配。
- `gradle.properties`：目标API、组件、资源或构建适配。
- `scripts/PayloadNetworkRegression.java`：新增过渡版本验证或适配。
- `scripts/review-legacy-server.ps1`：新增过渡版本验证或适配。
- `scripts/review-network.ps1`：目标API、组件、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/AddurDiscClient.java`：目标API、组件、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/DiscClientSounds.java`：目标API、组件、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ImportedDiscSoundInstance.java`：目标API、组件、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/net/DiscClientNetwork.java`：目标API、组件、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/MusicPickerScreen.java`：目标API、组件、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/disc/ui/NativeFileDialog.java`：目标API、组件、资源或构建适配。
- `src/client/java/nya/tuyw/addurdisc/mixin/ClientLevelChangeMixin.java`：新增过渡版本验证或适配。
- `src/client/java/nya/tuyw/addurdisc/mixin/LevelEventHandlerMixin.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/CustomDiscItem.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/SongBinding.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscNetwork.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPayloads.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/disc/net/DiscPlayback.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/init/Generator.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/init/ModInitialize.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/AnvilMenuMixin.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxBlockEntityMixin.java`：目标API、组件、资源或构建适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxPlayableTooltipMixin.java`：删除现代唱片机专用实现。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxRemovalMixin.java`：新增过渡版本验证或适配。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongMixin.java`：删除现代唱片机专用实现。
- `src/main/java/nya/tuyw/addurdisc/mixin/JukeboxSongPlayerMixin.java`：删除现代唱片机专用实现。
- `src/main/resources/addurdisc.client.mixins.json`：新增过渡版本验证或适配。
- `src/main/resources/addurdisc.mixins.json`：目标API、组件、资源或构建适配。
- `src/main/resources/data/addurdisc/advancement/recipes/custom_disc.json`：删除现代唱片机专用实现。
- `src/main/resources/data/addurdisc/advancements/recipes/custom_disc.json`：新增版本资源或验证。
- `src/main/resources/data/addurdisc/recipe/custom_disc.json`：删除现代唱片机专用实现。
- `src/main/resources/data/addurdisc/recipes/custom_disc.json`：新增版本资源或验证。
- `src/main/resources/data/minecraft/tags/items/music_discs.json`：新增版本资源或验证。
- `src/main/resources/fabric.mod.json`：目标API、组件、资源或构建适配。
- `docs/porting.md`：适配依据、全部修改、验证与产物记录。

## 主 agent 独立复核（2026-10-24）

主 agent 已读组件绑定访问和注册代码，并在每个目标独立运行typed payload真实消息/分页/字符串/分片分配边界回归，以及Fabric隔离服务器回归。全部通过：blank/hopper/bound/vanilla/play/tick/stop/removal/anvil，含组件持久化往返、237tick进度恢复、动态时长、复制后保留原片及一级经验。三个公共Mixin实际加载并正常关闭。

## 2026-10-25 手部枚举边界复核

新增真实payload回归先证实非法手值2、255会被误认为副手；解码改为仅接受0/1，其余抛DecoderException。合法主手/副手的编码字节保持不变。两版完整构建与真实网络回归均通过，主agent独立复跑也通过。修改仅DiscPayloads.java、PayloadNetworkRegression.java和本记录；保留此前实际服务器行为验证。
