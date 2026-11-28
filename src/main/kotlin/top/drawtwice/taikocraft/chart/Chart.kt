package top.drawtwice.taikocraft.chart

/** 音符颜色：咚（鼓面）/ 咔（鼓边） */
enum class NoteColor { DON, KA }

/**
 * 谱面上的一个音符。
 *
 * @param timeMs 音频时间轴上的绝对时刻（毫秒）。**不含 OFFSET**（由 PlaybackClock 统一叠加）
 * @param bpmAtNote 该音符所在处的 BPM（变速渲染用）
 */
data class Note(
    val timeMs: Double,
    val color: NoteColor,
    val big: Boolean = false,
    val player: Int = 1,
    val bpmAtNote: Double = 120.0,
) {
    init {
        require(player == 1 || player == 2) { "player 只能是 1 或 2，收到 $player" }
    }
}

/** 连打（TJA 5/6 起、8 止；osu Slider） */
data class RollSegment(
    val startMs: Double,
    val endMs: Double,
    val big: Boolean = false,
    val player: Int = 1,
) {
    val durationMs: Double get() = endMs - startMs
}

/**
 * 气球（TJA 7/9 起、8 止；osu Spinner）。
 *
 * ## 行为（坠尔要求，符合太鼓原版）
 * 1. 气球音符到达判定点后**停住**不动
 * 2. 玩家点击 → 开始计数，气球逐渐鼓胀
 * 3. 点够 [requiredHits] → **爆开**
 * 4. 时间到还没点够 → **飞走**（点都没点也一样，时间到就消失）
 */
data class BalloonSegment(
    val startMs: Double,
    val endMs: Double,
    val requiredHits: Int,
    val player: Int = 1,
)

/** 小节线（渲染用），记录所在 BPM 以便变速段正确定位 */
data class BarLine(val timeMs: Double, val bpmAtBar: Double = 120.0)

/** 一个难度的谱面 */
data class Chart(
    val course: Int,
    val courseName: String,
    val level: Int,
    val bpm: Double,
    val offsetMs: Double,
    val notes: List<Note>,
    val rolls: List<RollSegment> = emptyList(),
    val balloons: List<BalloonSegment> = emptyList(),
    val barLines: List<BarLine> = emptyList(),
) {
    val lastEventMs: Double
        get() = maxOf(
            notes.lastOrNull()?.timeMs ?: 0.0,
            rolls.lastOrNull()?.endMs ?: 0.0,
            balloons.lastOrNull()?.endMs ?: 0.0,
        )

    val maxBpm: Double
        get() = notes.maxOfOrNull { it.bpmAtNote } ?: bpm
}

/** 一个 TJA 文件解析出的全部内容 */
data class TjaChartFile(
    val title: String,
    val subtitle: String = "",
    val audioFileName: String? = null,
    val demostartMs: Double = 0.0,
    val genre: String = "",
    val charts: List<Chart>,
) {
    val displayTitle: String
        get() = if (subtitle.isEmpty()) title else "$title $subtitle"
}
