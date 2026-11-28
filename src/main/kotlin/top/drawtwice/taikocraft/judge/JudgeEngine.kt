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

    /**
     * 气球状态（坠尔要求的行为）：
     * - [BalloonState.IDLE] 还没到判定点，正在飞过来
     * - [BalloonState.HELD] 到判定点停住，等玩家点击（或时间到飞走）
     * - [BalloonState.POPPING] 玩家点够了，正在爆开
     * - [BalloonState.FLED] 时间到没点够，飞走了
     */
    enum class BalloonState { IDLE, HELD, POPPING, FLED }

    private val balloonStates = Array(balloons.size) { BalloonState.IDLE }
    private val balloonPopAtMs = DoubleArray(balloons.size) { -1.0 }

    fun balloonStateOf(i: Int): BalloonState =
        if (i in balloonStates.indices) balloonStates[i] else BalloonState.IDLE

    fun balloonPopAtOf(i: Int): Double =
        if (i in balloonPopAtMs.indices) balloonPopAtMs[i] else -1.0

    fun balloonIndexOf(b: BalloonSegment): Int = balloons.indexOf(b)

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

        // 气球：只在 HELD 状态（到达判定点后停住）才接受点击
        run {
            val bi = balloons.indexOfFirst { event.timeMs in it.startMs..it.endMs }
            if (bi >= 0) {
                val b = balloons[bi]
                if (balloonStates[bi] == BalloonState.HELD) {
                    if (balloonHits[bi] < b.requiredHits) {
                        balloonHits[bi]++
                        score += 100
                        // 点够了 → 进入爆开状态
                        if (balloonHits[bi] >= b.requiredHits) {
                            balloonStates[bi] = BalloonState.POPPING
                            balloonPopAtMs[bi] = event.timeMs
                        }
                        return JudgeResult(
                            Judgment.PERFECT, null, NoteColor.DON, false, fromRollOrBalloon = true,
                            balloonHits = balloonHits[bi], balloonRequired = b.requiredHits,
                            side = event.side, player = b.player,
                        )
                    }
                }
                // IDLE / POPPING / FLED 状态下点击气球 -> 无反应（不消耗，不影响别的判定）
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

        // 气球状态迁移
        for (i in balloons.indices) {
            val b = balloons[i]
            when (balloonStates[i]) {
                BalloonState.IDLE ->
                    // 到达判定点 → 停住等点击
                    if (timeMs >= b.startMs) balloonStates[i] = BalloonState.HELD
                BalloonState.HELD ->
                    // 时间到还没点够 → 飞走（一下没点也一样）
                    if (timeMs > b.endMs) balloonStates[i] = BalloonState.FLED
                BalloonState.POPPING -> {
                    // 爆开动画 500ms 后结束
                    if (timeMs > balloonPopAtMs[i] + 500.0) balloonStates[i] = BalloonState.FLED
                }
                BalloonState.FLED -> { /* 终态 */ }
            }
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
