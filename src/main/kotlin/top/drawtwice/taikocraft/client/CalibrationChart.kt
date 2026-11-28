package top.drawtwice.taikocraft.client

import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.Note
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.chart.TjaChartFile

/**
 * 校准用定拍谱面。
 *
 * 坠尔的要求：不要自己发明一套测量流程，**直接塞一个简单关卡让玩家玩**，
 * 从实际击打的偏差里统计推荐值。
 *
 * ## 谱面设计
 * - 120 BPM，4/4 拍
 * - 每拍一个咚，共 32 拍（16 秒）
 * - 前 4 拍是空拍（让玩家听清节奏再开始）
 * - 之后每 4 拍换一次颜色（咚/咔交替），避免手部疲劳并检验左右手
 *
 * 这样玩家能自然地跟着音乐敲，每次敲击相对音符的偏差就是真实的输入延迟。
 */
object CalibrationChart {

    const val BPM = 120.0

    /** 每拍毫秒数 */
    const val BEAT_MS = 60000.0 / BPM   // 500ms

    /** 起始空拍数（4 拍 = 2 秒，让玩家进状态） */
    const val LEAD_IN_BEATS = 4

    /** 总拍数 */
    const val TOTAL_BEATS = 32

    /** 总时长（毫秒） */
    val durationMs: Double get() = TOTAL_BEATS * BEAT_MS

    fun build(): TjaChartFile {
        val notes = mutableListOf<Note>()
        for (beat in LEAD_IN_BEATS until TOTAL_BEATS) {
            val t = beat * BEAT_MS
            // 每 4 拍换色：咚咚咔咔循环，让左右手都用上
            val color = if ((beat / 4) % 2 == 0) NoteColor.DON else NoteColor.KA
            notes += Note(
                timeMs = t,
                color = color,
                big = false,
                player = 1,
                bpmAtNote = BPM,
            )
        }
        val chart = Chart(
            course = 0,
            courseName = "校准",
            level = 1,
            bpm = BPM,
            offsetMs = 0.0,
            notes = notes,
            rolls = emptyList(),
            balloons = emptyList(),
            barLines = (0..TOTAL_BEATS step 4).map {
                top.drawtwice.taikocraft.chart.BarLine(it * BEAT_MS, BPM)
            },
        )
        return TjaChartFile(
            title = "延迟校准",
            subtitle = "",
            audioFileName = null,
            demostartMs = 0.0,
            genre = "校准",
            charts = listOf(chart),
        )
    }
}
