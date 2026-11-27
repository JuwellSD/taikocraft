package top.drawtwice.taikocraft.judge

import top.drawtwice.taikocraft.chart.NoteColor

/** 判定结果 */
enum class Judgment { PERFECT, GOOD, POOR, MISS }

/** 左右手 */
enum class Side { LEFT, RIGHT }

/** 一次输入 */
data class InputEvent(val color: NoteColor, val side: Side, val timeMs: Double)

/** 一次判定产出 */
data class JudgeResult(
    val judgment: Judgment,
    val noteTimeMs: Double?,
    val color: NoteColor,
    val big: Boolean,
    val bigCompleted: Boolean = false,
    val fromRollOrBalloon: Boolean = false,
    val balloonHits: Int? = null,
    val balloonRequired: Int? = null,
    /** 打击落在哪一侧（供平面太鼓高亮） */
    val side: Side = Side.LEFT,
    /** 第几玩家（供平面太鼓高亮） */
    val player: Int = 1,
)

/**
 * 判定参数。默认值与 OpenTaiko / TJAPlayer3 一致。
 * 良 ≤25ms，可 ≤75ms，不可 ≤108ms，超出 Miss。
 */
data class JudgeConfig(
    val perfectMs: Double = 25.0,
    val goodMs: Double = 75.0,
    val poorMs: Double = 108.0,
    val bigPairWindowMs: Double = 50.0,
)
