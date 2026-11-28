package top.drawtwice.taikocraft.judge

import top.drawtwice.taikocraft.chart.BalloonSegment
import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.Note
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.chart.RollSegment
import kotlin.math.abs

/**
 * 判定引擎（纯逻辑）。
 *
 * 计分（SCOREMODE 2）：良 300 / 可 100 / 不可 50，大音符 ×2。
 * 同侧 20ms 冷却防连打误触。
 */
class JudgeEngine(
    private val chart: Chart,
    private val config: JudgeConfig = JudgeConfig(),
) {
    private val notes: List<Note> = chart.notes.sortedBy { it.timeMs }
    private val rolls: List<RollSegment> = chart.rolls.sortedBy { it.startMs }
    private val balloons: List<BalloonSegment> = chart.balloons.sortedBy { it.startMs }

    private val judged = BooleanArray(notes.size)
    private var advanceCursor = 0
    private val balloonHits = IntArray(balloons.size)
    private val pendingBig = HashMap<Int, PendingBig>()
    private val lastPressAt = HashMap<Side, Double>()

    private var combo = 0
    private var maxCombo = 0
    private var score = 0L

    val currentCombo: Int get() = combo
    val maxComboSeen: Int get() = maxCombo
    val currentScore: Long get() = score

    private data class PendingBig(val firstTimeMs: Double, val color: NoteColor, val judgment: Judgment)

    fun press(event: InputEvent): JudgeResult? {
        val last = lastPressAt[event.side]
        if (last != null && event.timeMs - last < 20.0) return null
        lastPressAt[event.side] = event.timeMs

        tryCompleteBig(event)?.let { return it }

        // 连打：加分但**不加连击**（太鼓原版：连打不计入 combo）
        rolls.firstOrNull { event.timeMs in it.startMs..it.endMs }?.let { roll ->
            score += if (roll.big) 200 else 100
            return JudgeResult(
                Judgment.PERFECT, null, event.color, roll.big, fromRollOrBalloon = true,
                side = event.side, player = roll.player,
            )
        }

        // 气球：加分但**不加连击**
        balloons.firstOrNull { event.timeMs in it.startMs..it.endMs }?.let { b ->
            val idx = balloons.indexOf(b)
            if (idx >= 0 && balloonHits[idx] < b.requiredHits) {
                balloonHits[idx]++
                score += 100
                return JudgeResult(
                    Judgment.PERFECT, null, NoteColor.DON, false, fromRollOrBalloon = true,
                    balloonHits = balloonHits[idx], balloonRequired = b.requiredHits,
                    side = event.side, player = b.player,
                )
            }
        }

        // 只匹配**同色**音符。
        // 打错颜色 = **完全无反应**（音符不动、不判、不断连击）——
        // 这是太鼓达人原版行为。OpenTaiko 有「严格模式」会把错色判不可，
        // 此项留待设置里将来加开关。
        val best = findBest(event.timeMs, event.color) ?: return null
        val (idx, delta) = best
        val note = notes[idx]
        val d = abs(delta)
        val judgment = when {
            d <= config.perfectMs -> Judgment.PERFECT
            d <= config.goodMs -> Judgment.GOOD
            d <= config.poorMs -> Judgment.POOR
            else -> return null
        }
        judged[idx] = true
        val result = JudgeResult(
            judgment, note.timeMs, note.color, note.big, bigCompleted = !note.big,
            side = event.side, player = note.player,
        )
        if (note.big) pendingBig[idx] = PendingBig(event.timeMs, note.color, judgment)
        register(result, note)
        return result
    }

    private fun tryCompleteBig(event: InputEvent): JudgeResult? {
        if (pendingBig.isEmpty()) return null
        for ((idx, p) in pendingBig.entries.toList()) {
            if (p.color != event.color) continue
            if (event.timeMs - p.firstTimeMs > config.bigPairWindowMs) continue
            pendingBig.remove(idx)
            val note = notes[idx]
            val r = JudgeResult(
                p.judgment, note.timeMs, note.color, true, bigCompleted = true,
                side = event.side, player = note.player,
            )
            register(r, note)
            return r
        }
        return null
    }

    /** 在判定窗口内找最近的**同色**未判定音符 */
    private fun findBest(timeMs: Double, color: NoteColor): Pair<Int, Double>? {
        var bestIdx = -1
        var bestDelta = Double.MAX_VALUE
        var i = lowerBound(timeMs - config.poorMs)
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs > timeMs + config.poorMs) break
            if (!judged[i] && n.color == color) {
                val d = timeMs - n.timeMs
                if (abs(d) < abs(bestDelta)) { bestDelta = d; bestIdx = i }
            }
            i++
        }
        return if (bestIdx < 0) null else bestIdx to bestDelta
    }

    /**
     * 找判定窗口内最近的**异色**未判定音符 —— 保留给将来的「严格模式」使用。
     * 默认不启用：打错色应当完全无反应。
     */
    @Suppress("unused")
    private fun findWrongColor(timeMs: Double, color: NoteColor): Int? {
        var bestIdx = -1
        var bestDelta = Double.MAX_VALUE
        var i = lowerBound(timeMs - config.poorMs)
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs > timeMs + config.poorMs) break
            if (!judged[i] && n.color != color) {
                val d = abs(timeMs - n.timeMs)
                if (d < bestDelta) { bestDelta = d; bestIdx = i }
            }
            i++
        }
        return if (bestIdx < 0) null else bestIdx
    }

    private fun lowerBound(target: Double): Int {
        var lo = 0; var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].timeMs < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun advanceTo(timeMs: Double) {
        while (advanceCursor < notes.size) {
            val i = advanceCursor
            if (notes[i].timeMs + config.poorMs >= timeMs) break
            advanceCursor++
            if (judged[i]) continue
            judged[i] = true
            pendingBig.remove(i)
            combo = 0
        }
    }

    private fun register(r: JudgeResult, note: Note) {
        when (r.judgment) {
            Judgment.PERFECT -> {
                combo++
                score += if (note.big && r.bigCompleted) 600 else 300
            }
            Judgment.GOOD -> { combo++; score += if (note.big) 200 else 100 }
            // 不可（含打错颜色）→ **断连击**，与 OpenTaiko 一致
            Judgment.POOR -> { combo = 0; score += if (note.big) 100 else 50 }
            Judgment.MISS -> combo = 0
        }
        maxCombo = maxOf(maxCombo, combo)
    }

    fun notesSnapshot(): List<Note> = notes
    fun isJudged(i: Int): Boolean = i in judged.indices && judged[i]
    fun balloonHitsOf(b: BalloonSegment): Int = balloons.indexOf(b).let { if (it >= 0) balloonHits[it] else 0 }
}
