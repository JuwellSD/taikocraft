package top.drawtwice.taikocraft.chart

/** 音符颜色：咚（鼓面）/ 咔（鼓边） */
enum class NoteColor { DON, KA }

/**
 * 谱面上的一个音符。
 *
 * @param timeMs 该音符在**音频时间轴**上的绝对时刻（毫秒），已应用 OFFSET 与 #DELAY。
 * @param color 咚 / 咔
 * @param big 大音符
 * @param player 1 = 1P，2 = 2P
 * @param bpmAtNote 音符所在处的 BPM。渲染时用来实现「变速视觉」：
 *   `距离(4分音符) = Δms × bpmAtNote / 240000`（OpenTaiko Normal 模式）
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

/** 连打（TJA 的 5/6 起、8 止；osu 的 Slider） */
data class RollSegment(
    val startMs: Double,
    val endMs: Double,
    val big: Boolean = false,
    val player: Int = 1,
) {
    /** 连打区间内每一下都按节拍点计分时的标准间隔（太鼓达人按 1/N 拍，这里由渲染层再算） */
    val durationMs: Double get() = endMs - startMs
}

/**
 * 气球（TJA 的 7 起、8 止；osu 的 Spinner/拨浪鼓）。
 *
 * @param requiredHits 需要击打的次数。TJA 由 BALLOON 头字段按出现顺序给出；
 *   osu 由 Spinner 时长换算。
 */
data class BalloonSegment(
    val startMs: Double,
    val endMs: Double,
    val requiredHits: Int,
    val player: Int = 1,
)

/**
 * 小节线（用于渲染）。
 * @param timeMs 小节线时刻
 * @param bpmAtBar 该小节处的 BPM（变速段渲染需要）
 */
data class BarLine(val timeMs: Double, val bpmAtBar: Double = 120.0)

/**
 * 一个难度的谱面。
 *
 * @param course 0=Easy 1=Normal 2=Hard 3=Oni 4=Edit；TJA 的 COURSE 名称会映射到这里
 * @param notes 只含普通音符（咚/咔），按时间升序
 */
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
    /** 谱面最后一个事件的时间（含连打/气球尾部），用于判断歌曲结束 */
    val lastEventMs: Double
        get() = maxOf(
            notes.lastOrNull()?.timeMs ?: 0.0,
            rolls.lastOrNull()?.endMs ?: 0.0,
            balloons.lastOrNull()?.endMs ?: 0.0,
        )
}

/**
 * 一个 TJA 文件解析出来的全部内容。
 *
 * @param audioFileName TJA 的 WAVE 字段（相对同目录）
 * @param demostartMs 选曲预览起点
 * @param charts 单文件多难度，按 COURSE 出现顺序
 */
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
