# TaikoCraft 开发路线图

> 设计总纲见 [taikomd.md](taikomd.md)。本文件只记**排期与新增需求**。

## Phase 1（进行中）

- [x] NeoForge 1.21.1 + Kotlin 工程脚手架、GitHub Actions CI
- [x] TJA 解析器（多难度、BPMCHANGE/MEASURE/DELAY、连打/气球、分支取 #N）
- [x] 判定引擎（良 25ms / 可 75ms / 不可 108ms、双键大音符、连打与气球）
- [x] OSZ 导入（Spinner/拨浪鼓 → 气球、Slider → 连打、ZIP 安全读取）
- [ ] 音频播放（OpenAL 双缓冲流式）+ 播放时钟（AL_SAMPLE_OFFSET）
- [ ] 键盘时间戳输入（Mixin KeyboardHandler#keyPress）+ 下落式谱面界面
- [ ] 延迟校准 GUI
- [ ] 文档与仓库交付

## 后续新增需求（坠尔提出）

### 曲包编辑器（Phase 1 之后）
> 2026-11-27 提出

编辑的不是谱面，而是**元数据管理**：

- 修改歌曲名、歌手名、难度名
- 删除特定难度
- 原因：osz 里的难度常常很乱（重复、命名混乱、有废谱）

实现方向：读 osz → 展示难度列表（文件名、Metadata.Version、Title/Artist）→ 支持改名 / 勾选删除 / 重新打包导出 osz。

## Phase 2 — VR（Vivecraft）

- 依赖 `1.21.1-1.3.15` neoforge 变体，`org.vivecraft.api`
- 每帧手部位置/速度做几何碰撞判定（非按键事件，API 不暴露原始按键时间戳）
- 立体同心圆柱判定区 + 中线左右分区 + 线段采样滚奏 + 40–60ms 冷却锁

## Phase 3 — 实体外设与机台

- 手柄/MIDI 鼓桥接、力度判定
- 游戏币物品、投币、机台方块

## Phase 4 — 联机与观战

- C2SPlayHitPacket / S2CPlayHitBroadcastPacket
- 大屏幕渲染、观战延迟补偿
- 双人音效隔离、旁观者正解音
- 曲包上传审核 + OP 指令
