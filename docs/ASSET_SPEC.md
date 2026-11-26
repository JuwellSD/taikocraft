# TaikoCraft 资产规格（供模型/美术生成 AI 使用）

> 本文件描述本 Mod 需要的**全部可由外部工具生成的资产**：3D 模型、贴图、音效。
> 请严格按此规格输出，避免尺寸/坐标/命名不一致导致无法集入。
>
> **版权红线**：不得使用或模仿万代南梦宫官方太鼓达人素材（角色、鼓面图案、字体、音效）。
> 允许参考真实街机机台与 HORI 控制器的**物理结构与比例**，但外观需原创。

---

## 1. 坐标系约定（Minecraft）

Minecraft 方块坐标：**1 格 = 1 米**，Y 轴向上，右手系（X 东、Z 南）。

机台方块（`taikocraft:taiko_machine`）：

| 参数 | 值 | 说明 |
|---|---|---|
| 占位 | 1×1×1 格（可扩展至 2×1×1） | BlockBench 模型建议 32×32×32 像素（1 像素 = 1/16 格） |
| 朝向 | 玩家面朝 −Z 时正面朝向 +Z | 模型 y=0 为方块底面 |
| 鼓面中心（世界坐标偏移） | `(0, 0.55, 0.35)` 格 | 相对方块原点 |
| 鼓面半径 `R_don` | **0.8 格** | 内部实心圆柱（咚） |
| 鼓边半径 `R_ka` | **1.3 格** | 外部空心圆柱环（咔） |
| 敲击高度窗口 | 咚 `[Cy, Cy+0.15]`；咔 `[Cy, Cy+0.3]` | 世界 Y 坐标 |
| 左右分区 | 以鼓面 X 中线为界，死区 ±0.05 格 | 左 = −X，右 = +X |

> 以上与 `taikomd.md` §2.2 / §2.3 完全一致，**VR 判定直接读取这些常量**。

---

## 2. 需要的 3D 模型清单

| 资源 ID | 描述 | 面数建议 | 备注 |
|---|---|---|---|
| `taikocraft:taiko_machine` | 街机机台（含屏幕、鼓面、鼓边、按钮） | ≤ 3000 | 参照 cankao.jpeg：橙色边框、黑色屏幕、白色鼓面 |
| `taikocraft:taiko_drum` | 独立鼓（Phase 3 投币机台用） | ≤ 1500 | 直径 1.3 格 |
| `taikocraft:taiko_stick` | 鼓棒（手持物品模型） | ≤ 200 | 长 0.6 格、粗 0.04 格 |
| `taikocraft:taiko_coin` | 游戏币（物品） | ≤ 100 | 直径约 0.12 格 |

### 机台必须可拆分的部件
模型需按以下部件分组导出（便于程序替换贴图与做发光/动画）：

1. `screen` —— 屏幕面（贴图由 Mod 动态渲染，**导出为纯黑材质占位**）
2. `drum_face` —— 鼓面（咚区）
3. `drum_rim` —— 鼓边（咔区）
4. `body` —— 机身
5. `buttons` —— 底部按钮条

---

## 3. 需要的贴图

| 资源 ID | 尺寸 | 内容 |
|---|---|---|
| `taikocraft:block/taiko_machine` | 16×16 或 32×32 | 机台通用贴图 |
| `taikocraft:item/taiko_stick` | 16×16 | 鼓棒 |
| `taikocraft:item/taiko_coin` | 16×16 | 游戏币 |
| `taikocraft:gui/note_don` | 64×64 | 咚音符（红色实心圆 + 白边） |
| `taikocraft:gui/note_ka` | 64×64 | 咔音符（蓝色实心圆 + 白边） |
| `taikocraft:gui/note_don_big` | 96×96 | 大咚（带中心白点） |
| `taikocraft:gui/note_ka_big` | 96×96 | 大咔 |
| `taikocraft:gui/judge_line` | 8×256 | 判定线（白色竖条） |

### 配色规范（与代码常量一致）

| 用途 | 颜色 | Hex |
|---|---|---|
| 咚（鼓面） | 红 | `#E8453C` |
| 咔（鼓边） | 蓝 | `#2F9BE8` |
| 判定·良 | 金黄 | `#FFD24A` |
| 判定·可 | 白 | `#FFFFFF` |
| 判定·不可 | 灰 | `#AAAAAA` |
| 连击数 | 绿 | `#7FE87F` |

---

## 4. 需要的音效（必须原创或 CC0/CC-BY 许可）

> 禁止使用官方太鼓达人音效，也不要使用网上下载的"太鼓音源"（多为拆包）。

| 资源 ID | 描述 | 时长 | 格式 |
|---|---|---|---|
| `taikocraft:don` | 鼓面小击（咚） | 80–150 ms | OGG，44.1kHz，单声道 |
| `taikocraft:ka` | 鼓边小击（咔） | 80–150 ms | OGG |
| `taikocraft:don_big` | 鼓面重击 | 150–250 ms | OGG |
| `taikocraft:ka_big` | 鼓边重击 | 150–250 ms | OGG |
| `taikocraft:balloon_pop` | 气球完成 | 200–400 ms | OGG |
| `taikocraft:count_in` | 开始倒数 | 3× 400 ms | OGG |

### 音效设计要点
- **咚**：低沉、短促的鼓膜撞击（基频 80–200 Hz 有明显能量）
- **咔**：明亮、清脆的鼓边敲击（2–5 kHz 有明显能量），与咚**听感差异明显**
- **不能有混响尾巴**（>300 ms 会糊掉高速连打）
- 音量归一化到 −6 dBFS 峰值

---

## 5. 动画/状态反馈（Phase 2 VR 需要）

| 状态 | 表现 |
|---|---|
| 鼓面被击打 | `drum_face` 部件短暂变亮为 `#E8453C`（100 ms 内淡出） |
| 鼓边被击打 | `drum_rim` 部件短暂变亮为 `#2F9BE8`（100 ms 内淡出） |
| 高亮溢出限制 | 高亮**不得**超出各自的判定圆柱范围（咚 ≤ R_don，咔在 R_don–R_ka 环内） |

> 若以发光贴图（emissive）实现，请为上述两个部件提供独立的 `_emissive` 贴图。

---

## 6. 交付格式

- 3D 模型：**BlockBench `.bbmodel`**（保留可编辑分组），或导出 `.json` + `.png`（Java 版模型格式）
- 贴图：PNG，透明通道，无压缩伪影
- 音效：OGG Vorbis，44.1kHz
- 命名严格遵循上表资源 ID
- 附一份 `manifest.json` 说明每个文件的用途

---

## 7. 当前进度（生成资产前请先确认）

Phase 1 **尚未注册任何方块/物品/音效**，上表是**规划中的规格**。
如果你在生成资产用于提交，请先在这些位置创建对应注册代码：

- 方块：`src/main/kotlin/top/drawtwice/taikocraft/common/TaikoBlocks.kt`（待创建）
- 物品：`src/main/kotlin/top/drawtwice/taikocraft/common/TaikoItems.kt`（待创建）
- 音效：`src/main/kotlin/top/drawtwice/taikocraft/common/TaikoSounds.kt`（待创建）
- 资源：`src/main/resources/assets/taikocraft/`
