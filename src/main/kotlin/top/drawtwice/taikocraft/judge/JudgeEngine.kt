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

        rolls.firstOrNull { event.timeMs in it.startMs..it.endMs }?.let { roll ->
            score += if (roll.big) 200 else 100
            combo++; maxCombo = maxOf(maxCombo, combo)
            return JudgeResult(Judgment.PERFECT, null, event.color, roll.big, fromRollOrBalloon = true)
        }

        balloons.firstOrNull { event.timeMs in it.startMs..it.endMs }?.let { b ->
            val idx = balloons.indexOf(b)
            if (idx >= 0 && balloonHits[idx] < b.requiredHits) {
                balloonHits[idx]++
                score += 100; combo++; maxCombo = maxOf(maxCombo, combo)
                return JudgeResult(
                    Judgment.PERFECT, null, NoteColor.DON, false, fromRollOrBalloon = true,
                    balloonHits = balloonHits[idx], balloonRequired = b.requiredHits,
                )
            }
        }

        val best = findBest(event.timeMs) ?: return null
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
        val result = JudgeResult(judgment, note.timeMs, note.color, note.big, bigCompleted = !note.big)
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
            val r = JudgeResult(p.judgment, note.timeMs, note.color, true, bigCompleted = true)
            register(r, note)
            return r
        }
        return null
    }

    private fun findBest(timeMs: Double): Pair<Int, Double>? {
        var bestIdx = -1
        var bestDelta = Double.MAX_VALUE
        var i = lowerBound(timeMs - config.poorMs)
        while (i < notes.size) {
            val n = notes[i]
            if (n.timeMs > timeMs + config.poorMs) break
            if (!judged[i]) {
                val d = timeMs - n.timeMs
                if (abs(d) < abs(bestDelta)) { bestDelta = d; bestIdx = i }
            }
            i++
        }
        return if (bestIdx < 0) null else bestIdx to bestDelta
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
            Judgment.PERFECT -> { combo++; score += if (note.big && r.bigCompleted) 600 else if (note.big) 300 else 300 }
            Judgment.GOOD -> { combo++; score += if (note.big) 200 else 100 }
            Judgment.POOR -> { combo++; score += if (note.big) 100 else 50 }
            Judgment.MISS -> combo = 0
        }
        maxCombo = maxOf(maxCombo, combo)
    }

    fun notesSnapshot(): List<Note> = notes
    fun isJudged(i: Int): Boolean = i in judged.indices && judged[i]
    fun balloonHitsOf(b: BalloonSegment): Int = balloons.indexOf(b).let { if (it >= 0) balloonHits[it] else 0 }
}
