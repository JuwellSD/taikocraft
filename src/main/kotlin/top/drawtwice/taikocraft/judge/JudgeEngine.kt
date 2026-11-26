package top.drawtwice.taikocraft.judge

import top.drawtwice.taikocraft.chart.BalloonSegment
import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.Note
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.chart.RollSegment
import kotlin.math.abs

/**
 * 判定引擎（纯逻辑，无 Minecraft 依赖，可单测）。
 *
 * 使用方式（渲染循环里）：
 *  1. `advanceTo(currentAudioTimeMs)` —— 把窗口外未击打的音符判 Miss
 *  2. `press(event)`                  —— 处理一次击打
 *
 * 计分：SCOREMODE 2（Phase 1 默认）——
 *  非 GOGO：良 300 / 可 100 / 不可 50；GOGO 时 ×1.2；大音符 ×2。
 */
class JudgeEngine(
    private val chart: Chart,
    private val config: JudgeConfig = JudgeConfig(),
) {
    private val notes: List<Note> = chart.notes.sortedBy { it.timeMs }
    private val rolls: List<RollSegment> = chart.rolls.sortedBy { it.startMs }
    private val balloons: List<BalloonSegment> = chart.balloons.sortedBy { it.startMs }

    private var nextNoteIndex = 0
    private val judged = BooleanArray(notes.size)

    private var nextBalloonIndex = 0
    private val balloonHits = IntArray(balloons.size)

    private var combo = 0
    private var maxCombo = 0
    private var score = 0L
    private var finished = false

    private val pendingBig = HashMap<Int, PendingBigHit>() // noteIndex -> 首次击打
    private val lastPressAt = HashMap<Side, Double>()

    val currentCombo: Int get() = combo
    val maxComboSeen: Int get() = maxCombo
    val currentScore: Long get() = score
    val isFinished: Boolean get() = finished

    private data class PendingBigHit(
        val firstTimeMs: Double,
        val color: NoteColor,
        val result: JudgeResult,
    )

    /** 处理一次击打；返回本次触发的判定（可能为空——例如落在空处且未惩罚） */
    fun press(event: InputEvent): JudgeResult? {
        // 同一只手的高频连打保护：40ms 内同侧不再判定
        val last = lastPressAt[event.side]
        if (last != null && event.timeMs - last < 40.0) return null
        lastPressAt[event.side] = event.timeMs

        // 大音符补第二手
        val bigResult = tryCompleteBig(event)
        if (bigResult != null) return bigResult

        // 连打区间内直接算命中
        rolls.firstOrNull { event.timeMs in it.startMs..it.endMs }?.let { roll ->
            rollHit(roll, event.timeMs)
            return JudgeResult(Judgment.PERFECT, null, event.color, roll.big, fromRollOrBalloon = true)
        }

        // 气球区间内累计
        balloons.firstOrNull { event.timeMs in it.startMs..it.endMs }?.let { balloon ->
            return balloonHit(balloon, event.timeMs)
        }

        // 普通音符：找最近的、未被判定的音符
        val best = findBestCandidate(event.timeMs)
        if (best == null) {
            // 空打不计分不计连击（Phase 1 宽容）
            return null
        }
        val (index, delta) = best
        val note = notes[index]
        val absDelta = abs(delta)

        if (note.color != event.color) {
            if (!config.penalizeWrongColor) return null
        }

        val judgment = when {
            absDelta <= config.perfectMs -> Judgment.PERFECT
            absDelta <= config.goodMs -> Judgment.GOOD
            absDelta <= config.poorMs -> Judgment.POOR
            else -> return null
        }

        val result = JudgeResult(
            judgment = judgment,
            noteTimeMs = note.timeMs,
            color = note.color,
            big = note.big,
            bigCompleted = !note.big,
        )

        if (note.big) {
            // 大音符：先挂起，允许 50ms 内另一只手补齐
            pendingBig[index] = PendingBigHit(event.timeMs, event.color, result)
            judged[index] = true
            registerResult(result, note)
            return result
        }

        judged[index] = true
        registerResult(result, note)
        return result
    }

    /** 提前第二手补全大音符 */
    private fun tryCompleteBig(event: InputEvent): JudgeResult? {
        if (pendingBig.isEmpty()) return null
        val now = event.timeMs
        var found = -1
        var foundKey: Int? = null
        for ((index, pending) in pendingBig) {
            // 同色、不同手（不同侧）、且间隔在窗口内
            if (pending.color != event.color) continue
            if (now - pending.firstTimeMs > config.bigPairWindowMs) continue
            val note = notes[index]
            if (abs(now - note.timeMs) > config.poorMs) continue
            found = index
            foundKey = index
            break
        }
        if (foundKey == null) {
            // 窗口过期：清理
            val expired = pendingBig.filterValues { now - it.firstTimeMs > config.bigPairWindowMs }
            expired.forEach { (k, _) -> pendingBig.remove(k) }
            return null
        }
        val pending = pendingBig.remove(foundKey)!!
        val note = notes[found]
        judged[found] = true
        val completed = JudgeResult(
            judgment = pending.result.judgment,
            noteTimeMs = note.timeMs,
            color = note.color,
            big = true,
            bigCompleted = true,
        )
        // 补全后追加一份得分并额外加连击
        registerResult(completed, note)
        return completed
    }

    private fun findBestCandidate(timeMs: Double): Pair<Int, Double>? {
        // 窗口内的音符（已判定过的跳过）
        // 用 nextNoteIndex 附近扫描：简单起见先线性扫（谱面长度一般 < 3000，判定频率低）
        var bestIndex = -1
        var bestDelta = Double.MAX_VALUE
        for ((i, note) in notes.withIndex()) {
            if (judged[i]) continue
            if (note.timeMs > timeMs + config.poorMs) break // 升序，后面的更远
            val delta = timeMs - note.timeMs
            if (abs(delta) < abs(bestDelta)) {
                bestDelta = delta
                bestIndex = i
            }
        }
        // 允许击打早于音符（timeMs < note.timeMs）的窗口
        if (bestIndex == -1) {
            for ((i, note) in notes.withIndex()) {
                if (judged[i]) continue
                if (note.timeMs < timeMs - config.poorMs) continue
                if (note.timeMs > timeMs + config.poorMs) break
                val delta = timeMs - note.timeMs
                if (abs(delta) < abs(bestDelta)) { bestDelta = delta; bestIndex = i }
            }
        }
        return if (bestIndex == -1) null else bestIndex to bestDelta
    }

    private fun rollHit(roll: RollSegment, timeMs: Double) {
        // 连打每一下都算 Perfect，GOGO 时后续再加成
        score += rollScoreFor(roll.big)
        combo += 1
        maxCombo = maxOf(maxCombo, combo)
    }

    private fun balloonHit(balloon: BalloonSegment, timeMs: Double): JudgeResult? {
        val index = balloons.indexOf(balloon)
        if (index < 0) return null
        if (balloonHits[index] >= balloon.requiredHits) return null
        balloonHits[index]++
        val hits = balloonHits[index]
        // 气球每一下 100（Phase 1 简化为固定值）
        score += 100
        combo += 1
        maxCombo = maxOf(maxCombo, combo)
        return JudgeResult(
            judgment = Judgment.PERFECT,
            noteTimeMs = null,
            color = NoteColor.DON,
            big = false,
            fromRollOrBalloon = true,
            balloonHits = hits,
            balloonRequired = balloon.requiredHits,
        )
    }

    private fun registerResult(result: JudgeResult, note: Note) {
        when (result.judgment) {
            Judgment.PERFECT -> {
                combo += 1
                score += baseScore(Judgment.PERFECT, result.bigCompleted && note.big, note.big)
            }
            Judgment.GOOD -> {
                combo += 1
                score += baseScore(Judgment.GOOD, result.bigCompleted && note.big, note.big)
            }
            Judgment.POOR -> {
                combo += 1
                score += baseScore(Judgment.POOR, result.bigCompleted && note.big, note.big)
            }
            Judgment.MISS -> combo = 0
        }
        maxCombo = maxOf(maxCombo, combo)
    }

    /** 把 timeMs 之前已超出窗口的未判定音符标为 Miss */
    fun advanceTo(audioTimeMs: Double) {
        for ((i, note) in notes.withIndex()) {
            if (judged[i]) continue
            if (note.timeMs + config.poorMs < audioTimeMs) {
                judged[i] = true
                // 挂起的大音符过期算 Miss（另一半没来）
                pendingBig.remove(i)
                combo = 0
            }
        }
        // 连打/气球没有 Miss 概念，仅清理过期计数
    }

    fun isComplete(audioTimeMs: Double): Boolean {
        if (audioTimeMs < chart.lastEventMs + config.poorMs) return false
        finished = notes.indices.all { judged[it] }
        return finished
    }

    /** 已判定的音符数 / 总数 */
    fun judgedCount(): Int = notes.indices.count { judged[it] }

    private fun baseScore(judgment: Judgment, bigCompleted: Boolean, big: Boolean): Int {
        val base = when (judgment) {
            Judgment.PERFECT -> 300
            Judgment.GOOD -> 100
            Judgment.POOR -> 50
            Judgment.MISS -> 0
        }
        return if (big) {
            if (bigCompleted) base * 2 else base
        } else base
    }

    private fun rollScoreFor(big: Boolean): Int = if (big) 200 else 100

    fun notesSnapshot(): List<Note> = notes
    fun isJudged(index: Int): Boolean = judged[index]
}
