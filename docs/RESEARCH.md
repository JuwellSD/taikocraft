# 技术调研备忘（Phase 1 依据）

> 本文件记录实现所依据的一手资料结论，便于后续 AI 或贡献者核对。
> 研究时间：2026-11-27。

## 1. 判定窗口

| 判定 | 窗口（\|Δt\|，含端点） | OpenTaiko 默认 | TJAPlayer3 默认 |
|---|---|---|---|
| 良 Perfect | ≤ 25 ms | ✅ | ✅ |
| 可 Good | 26–75 ms | ✅ | ✅ |
| 不可 Poor | 76–108 ms | ✅ | ✅ |
| Miss | > 108 ms | ✅ | ✅ |

- OpenTaiko 有 7 档难度梯度（Easy/Normal 用 Lv0–4，Hard/Extreme 用 Lv2–6），默认 `TimingZones = 2` 即上表。
- TJAPlayer3 默认 `nヒット範囲ms = 25 / 75 / 108`，可被 `#BOX` 覆盖；两者都按播放速度等比缩放。
- **大音符**：判定窗与普通音符相同，得分 ×2（真打/ShinUchi 不计）。
- **两键大音符**：同色两键在 **50 ms** 内命中才算双手完成，否则按单键（本项目默认 50 ms，见 `JudgeConfig.bigPairWindowMs`）。
- **气球头**：允许提前 **17 ms** 起打（OpenTaiko 与 TJAPlayer3 一致）。
- **连打/气球区间内**：有效输入一律判 Perfect，不判 Miss。

## 2. TJA 格式

### 2.1 头部字段

| 字段 | 语义 |
|---|---|
| `TITLE` / `SUBTITLE` | 曲名 / 副标题（`--` 隐藏、`++` 强制显示） |
| `BPM` | 初始 BPM，缺省 120，四分音符基准 |
| `WAVE` | 音频文件名（相对 TJA 同目录） |
| `OFFSET` | 偏移（秒，浮点）|
| `DEMOSTART` | 选曲预览起点（秒），与谱面时间轴无关 |
| `COURSE` | Easy/Normal/Hard/Oni/Edit（0–4） |
| `LEVEL` | 星级 |
| `BALLOON` | 气球所需击打数，按出现顺序逗号分隔 |
| `GENRE` | 曲风分类 |

### 2.2 OFFSET 语义（本项目采用）

```
音符音频位置(ms) = 小节推算时间(ms) + #DELAY(ms) − OFFSET×1000
```

- 正 OFFSET = 谱面相对音频**提前**
- 负 OFFSET = 谱面相对音频**延后**
- 与 OpenTaiko `CTja.cs` 的 `RawTjaTimeToTjaTimeMusic/Note` 等价
- ⚠️ OutFox 社区文档对正负描述含糊，本项目以上式为唯一标准（已写入单测）

### 2.3 音符字符

| 字符 | 含义 | 字符 | 含义 |
|---|---|---|---|
| `0` | 空 | `5` / `6` | 小/大连打起点 |
| `1` / `2` | 小咚 / 小咔 | `7` | 气球起点 |
| `3` / `4` | 大咚 / 大咔 | `8` | 连打/气球结束 |
| `9` | 久寿玉（Kusudama，按气球处理） | `A` / `B` | 2P 大咚 / 大咔 |
| `C` | 地雷 | `D` | 导火索连打 |
| `F` | ADLIB | `G` | 紫（咚咔同击） |

Phase 1 实现：`1 2 3 4 5 6 7 8 9 A B`；`C D F G` 暂不生成音符。

### 2.4 命令

| 命令 | 语义 | 影响时间轴 |
|---|---|---|
| `#BPMCHANGE x` | 从当前位置改 BPM | ✅ |
| `#MEASURE n/m` | 小节长改为 (n/m)×4 拍 | ✅ |
| `#DELAY x` | 后续整体后移 x 秒（负值前移） | ✅ |
| `#SCROLL x` | 仅视觉倍速 | ❌ |
| `#GOGOSTART` / `#GOGOEND` | GOGO 区（得分 ×1.2） | ❌ |
| `#BARLINEOFF` / `#BARLINEON` | 隐藏/显示小节线 | ❌ |
| `#BRANCHSTART c,a,b` | 分叉条件 | ❌ |
| `#SECTION` | 重置分叉统计窗口 | ❌ |
| `#N` / `#E` / `#M` | 普通/玄人/达人分支 | — |
| `#BRANCHEND` / `#LEVELHOLD` | 分叉结束 / 锁定分叉 | ❌ |

Phase 1：解析 `#BPMCHANGE` / `#MEASURE` / `#DELAY`；分叉只取 `#N`；其余忽略。
**已知近似**：小节内的内联命令从下一条小节生效，不做小节内切分。

### 2.5 时间轴公式

```
小节时长(ms) = 240000 / BPM × (n/m)     // 4/4 → 240000/BPM
音符时刻     = 小节起点 + 小节时长 × i/N
```

## 3. osu! 映射（osz 导入）

`.osz` 是 ZIP：音频 + 每难度一个 `.osu`（`Mode:1` 为 taiko）+ 背景/视频 + hitsound。

| osu 对象 | 太鼓映射 |
|---|---|
| 圆圈 + Normal/无 hitsound | 小咚 |
| 圆圈 + Whistle 或 Clap | 小咔 |
| 上述 + Finish | 大音符（颜色同上） |
| Slider | 连打 |
| **Spinner（拨浪鼓/转盘）** | **气球**（坠尔明确要求） |

- 气球所需击打数：`时长(秒) × DifficultyRange(OD, 3, 5, 7.5) × 1.65`
- 难度名映射：Easy/Kantan → 0，Normal/Futsuu → 1，Hard/Muzukashii → 2，Oni/Insane/Extreme → 3
- `AudioFilename` 相对 `.osu` 所在目录，导入时需解析为 zip 内完整路径

## 4. 技术栈版本

| 项 | 版本 | 备注 |
|---|---|---|
| NeoForge | 21.1.268 | 1.21.1 |
| ModDevGradle | 2.0.148 | |
| Gradle | 9.2.1 | wrapper |
| Java | 21 | |
| Kotlin | 2.4.21 | 必须与 KFF 内置一致 |
| KotlinForForge | `thedarkcolour:kotlinforforge-neoforge:5.13.0` | 提供 `modLoader="kotlinforforge"` |
| Parchment | 1.21.1:2024.11.17 | |

### KFF 约束（重要）

1. `neoforge.mods.toml` 的 `modLoader="kotlinforforge"`，并添加 `kotlinforforge` 依赖。
2. mod class **恰好 1 个公开构造器**，参数只能是 `IEventBus` / `ModContainer` / `Dist`。
3. `object` 入口不能有构造器参数 → 用 `class` + `companion object`。
4. `@Mod(dist = [Dist.CLIENT])` 的 `dist` 是**数组**。

## 5. 音频

- Minecraft 1.21.1 自带 `lwjgl-openal 3.3.3`、`lwjgl-stb 3.3.3`（含 natives）
- 原版 OGG 解码用 `jcraft:jorbis` + `OggAudioStream`，**不是** STB
- 本项目自建 OpenAL source + `alSourceQueueBuffers` 三缓冲流式播放
- **播放时钟**：`AL_SAMPLE_OFFSET`（当前 buffer 内偏移）+ 已播完 buffer 数 × 块样本数
  - `AL_SEC_OFFSET` 精度低，仅作后备
  - ⚠️ 各 OpenAL 实现对 `AL_SAMPLE_OFFSET` 行为可能不一致，已列为本项目风险项，需实机验证
- OGG 解码：`STBVorbis.stb_vorbis_open_memory(ByteBuffer, int[], STBVorbisAlloc?)`
  - 错误码出参是 `int[]`，不是 `IntBuffer`
  - `stb_vorbis_get_info(decoder, STBVorbisInfo)` 需要传入实例填充
- MP3：JLayer / mp3spi 均为 LGPL，Phase 1 不支持（仅 ogg/wav）

## 6. 输入精度

- Mixin `net.minecraft.client.KeyboardHandler#keyPress(long, int, int, int, int)` HEAD 注入
- 时间戳用 `System.nanoTime()`
- **精度上限约 1 帧（16.7 ms）**：GLFW 回调在 `glfwPollEvents` 时批量派发
- 不要用 `KeyBinding.wasPressed()`（tick 粒度，丢失精度）

## 7. Vivecraft（Phase 2 预研）

- 版本 `1.21.1-1.3.15`，同一版本号提供 fabric / forge / **neoforge** 三个 jar（Modrinth `wGoQDPN5`）
- API：`org.vivecraft.api`（1.3.0+ 正式版）
  - `VRAPI.instance()` → `isVRPlayer()`、`getVRPose()`、`getHistoricalVRPoses()`
  - `VRClientAPI.instance()` → `getWorldRenderPose()`（每帧最新）、`triggerHapticPulse()`
  - `VRPose.getHand(InteractionHand)` → `getPos()`(Vec3)、`getDir()`、`getRotation()`
- **限制**：
  - 姿态历史以 **tick** 为单位，**不暴露带时间戳的原始按键事件**
  - → VR 击打判定必须基于**每帧手部位置/速度**（正好符合设计文档的几何碰撞方案）
  - 判定在客户端完成，服务端只做校验
- Maven 无独立 API 包，用整包 `maven.modrinth:vivecraft:1.21.1-1.3.15-neoforge`
