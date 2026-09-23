# AddurDisc

一个 Fabric 模组：合成一张空白唱片，右键导入你电脑里的任意音乐文件（或视频文件的音轨），
就能像原版唱片一样放进唱片机播放。曲目在服务器与所有玩家之间自动同步——谁都能听到。

Now you can add your discs! A Fabric mod that turns any local music file into a playable music disc:
craft a blank disc, right click to import, and the track syncs to the server and every player.

## 使用 / How to play

1. 用 8 个紫水晶碎片 + 1 个红石合成一张**空白唱片**（工作台配方，拾取紫水晶碎片后自动解锁）。
2. 手持空白唱片右键，选择音乐文件（或直接把文件拖进窗口）。
3. 模组用 ffmpeg 把它转成单声道 Ogg Vorbis 并上传到服务器。
4. 把绑定好的唱片放进唱片机播放。曲目会自动下载到每一位玩家的客户端。

Supported input: mp3 / wav / ogg / flac / m4a / aac / opus, and video files (mp4 / mkv / webm / avi / mov —
only the audio is kept).

## 前置要求 / Requirements

- Minecraft **26.2** · Fabric Loader ≥ 0.19.4 · Fabric API ≥ 0.161.0 · Java ≥ 25
- **客户端需要 ffmpeg**：放在 `PATH` 里（名字为 `ffmpeg`），或在
  `config/AddurDisc-common.json` 中把 `ffmpegPath` 设为可执行文件的完整路径。
  ffmpeg 不随模组分发，仅作为外部转换程序调用。没有 ffmpeg 的服务端/仅播放端不受影响。
- 客户端与服务器都需要安装本模组。

## 配置 / Configuration（`config/AddurDisc-common.json`）

| 字段 | 默认 | 说明 |
|---|---|---|
| `customPath` | `""` | 音乐目录的父路径（相对游戏目录）；留空即游戏目录本身 |
| `customPathName` | `.addurdisc` | 音乐文件夹的名字（必须是单级目录名） |
| `defaultSongLengthSeconds` | `180` | 无法从 Ogg 头读出时长时使用的回退长度（秒） |
| `ffmpegPath` | `""` | ffmpeg 可执行文件的完整路径；留空则从 `PATH` 查找 |
| `maxSongMegabytes` | `32` | 单首曲目大小上限（MB） |
| `maxStoreMegabytes` | `512` | 曲库总大小上限（MB） |
| `maxTracks` | `512` | 曲库曲目数量上限 |

## 工作原理（简要）

- 全模组只有一个物品 `addurdisc:custom_disc`，导入的曲目存放在
  `<游戏目录>/.addurdisc/assets/addurdisc/sounds/` 下的数据组件里，不新增注册项。
- 曲目通过自建分块协议在服务器与客户端之间传输（上传 16 KB/块，下载 128 KB/块，SHA-1 校验），
  服务端校验 Ogg Vorbis 内容后才入库。
- 播放广播不依赖原版唱片注册表（运行期构造的 `JukeboxSong` 没有 registry id），
  由模组自带的 `PlaySong` 通道完成。

## 构建 / Build

```bash
./gradlew build
```

产物在 `build/libs/AddurDisc-Fabric-26.2-<版本>.jar`（`build` 只生成 jar，无需 remap）。

## 许可证

MIT，见 [LICENSE](LICENSE)。
