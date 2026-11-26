# TaikoCraft · 太鼓工坊

在 Minecraft 里开一家太鼓机厅。

[中文](#中文) · [English](#english)

---

## 中文

### 这是什么

一个还原街机太鼓体验的 Minecraft Mod：支持 PC 键鼠、VR 体感挥棒、实体太鼓外设，
多人联机、街机式大屏观战、投币游玩、严格音画同步。

- **加载器**：NeoForge 1.21.1
- **语言**：Kotlin（KotlinForForge 5.13.0）
- **当前进度**：Phase 1 开发中

### 版权声明

本 Mod 采用**空心化架构**，**不附带任何**官方音频、图片、字体或官谱。
所有谱面与音频完全由玩家自行导入。禁止将万代南梦宫的官方资产打包进本仓库。

### Phase 1 已实现

| 模块 | 状态 |
|---|---|
| TJA 谱面解析（多难度 / BPMCHANGE / MEASURE / DELAY / 连打 / 气球 / 分支取 #N） | ✅ |
| osu! `.osz` 导入（**Spinner 拨浪鼓 → 气球**、Slider → 连打、ZIP 安全读取） | ✅ |
| 判定引擎（良 25ms / 可 75ms / 不可 108ms、双键大音符 50ms、连打气球） | ✅ |
| 音频播放（OpenAL 三缓冲流式，OGG/WAV）+ 播放时钟 | ✅ |
| 键盘时间戳输入（Mixin，GLFW 回调级） | ✅ |
| 下落式谱面界面 | ✅ |
| 延迟校准 GUI | ✅ |
| 选曲界面 + F8 入口 | ✅ |

### 待验证（需实机）

- `AL_SAMPLE_OFFSET` 播放时钟精度（不同 OpenAL 实现行为可能不同）
- TJA `OFFSET` 正负语义（已按 OpenTaiko 实现，需真实谱面回归）
- 音频设备回退路径

### 使用方式（开发版）

1. 构建：`./gradlew build`（产物在 `build/libs/`）
2. 安装：把 jar 放进 `mods/`，同时安装 **KotlinForForge 5.13.0**
3. 曲库目录：`config/taikocraft/songs/<曲目名>/`
   - 放 `.tja` + 音频（`.ogg` / `.wav`），或 `.osu` + 音频
4. 游戏内按 **F8** 打开选曲

### 键位

| 方案 | 左咚 | 左咔 | 右咚 | 右咔 |
|---|---|---|---|---|
| 新手 | D | J | F | K |
| 老手 | D | F | K | J |

大音符需要**两侧同时按**（50ms 内）才能得满分。

### 文档

- [设计总纲](docs/taikomd.md)
- [技术调研备忘](docs/RESEARCH.md)
- [资产规格（供模型生成 AI）](docs/ASSET_SPEC.md)
- [开发路线图](docs/ROADMAP.md)

---

## English

### What is this

A Minecraft mod recreating arcade taiko drum gameplay: keyboard, VR motion controllers,
and physical drum peripherals; multiplayer, arcade screen spectating, coin-op, tight audio sync.

- **Loader**: NeoForge 1.21.1
- **Language**: Kotlin (KotlinForForge 5.13.0)
- **Status**: Phase 1 in development

### Copyright

This mod ships **zero** official assets (audio, images, fonts, charts) — everything is
player-supplied. Do not commit Bandai Namco assets to this repository.

### Phase 1 implemented

- TJA chart parser (multi-difficulty, `#BPMCHANGE` / `#MEASURE` / `#DELAY`, rolls, balloons, `#N` branch)
- `.osz` import (**Spinner → balloon**, Slider → roll, safe ZIP reading)
- Judge engine (Perfect 25ms / Good 75ms / Poor 108ms, two-key big notes, rolls & balloons)
- OpenAL streaming playback (OGG/WAV) + playback clock
- GLFW-callback-level key timestamps via Mixin
- Falling-note gameplay screen
- Calibration GUI, song select (F8)

### Docs

- [Design](docs/taikomd.md) · [Research](docs/RESEARCH.md) · [Asset spec](docs/ASSET_SPEC.md) · [Roadmap](docs/ROADMAP.md)

### Build

```bash
./gradlew build    # output in build/libs/
```

Requires Java 21. CI runs on GitHub Actions.
