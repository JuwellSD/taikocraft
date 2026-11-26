package top.drawtwice.taikocraft.judge

import top.drawtwice.taikocraft.chart.NoteColor

/** 判定结果 */
enum class Judgment { PERFECT, GOOD, POOR, MISS }

/** 左右手/左右半区 */
enum class Side { LEFT, RIGHT }

/**
 * 一次输入事件。
 *
 * @param timeMs 事件在**音频时间轴**上的时刻（已扣除全局延迟设置）
 */
data class InputEvent(
    val color: NoteColor,
    val side: Side,
    val timeMs: Double,
)

/** 一次判定产出 */
data class JudgeResult(
    val judgment: Judgment,
    /** 命中的音符时刻；连打/气球为 null */
    val noteTimeMs: Double?,
    val color: NoteColor,
    val big: Boolean,
    /** 大音符是否完成双手（键盘双键）判定，得满分 */
    val bigCompleted: Boolean = false,
    /** 该次判定是否来自连打/气球 */
    val fromRollOrBalloon: Boolean = false,
    /** 气球计数（仅气球） */
    val balloonHits: Int? = null,
    val balloonRequired: Int? = null,
)

/**
 * 判定参数。默认值与 OpenTaiko / TJAPlayer3 默认一致。
 *
 * 窗口判定（|Δt|，含端点）：
 *   良 ≤ 25ms，可 26–75ms，不可 76–108ms，超出为 Miss。
 */
data class JudgeConfig(
    val perfectMs: Double = 25.0,
    val goodMs: Double = 75.0,
    val poorMs: Double = 108.0,
    /** 键盘双键大音符：两手击打的最大间隔 */
    val bigPairWindowMs: Double = 50.0,
    /** 气球头允许提前判定 */
    val balloonHeadEarlyMs: Double = 17.0,
    /** 是否把错色击打视为对最近音符的不可（Phase 1 默认关闭，宽容处理） */
    val penalizeWrongColor: Boolean = false,
)
