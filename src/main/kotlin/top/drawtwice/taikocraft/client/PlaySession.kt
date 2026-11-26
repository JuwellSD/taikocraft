package top.drawtwice.taikocraft.client

import top.drawtwice.taikocraft.audio.OpenAlPlayback
import top.drawtwice.taikocraft.audio.PlaybackClock
import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Side
import top.drawtwice.taikocraft.chart.TjaChartFile
import top.drawtwice.taikocraft.judge.InputEvent
import top.drawtwice.taikocraft.judge.JudgeConfig
import top.drawtwice.taikocraft.judge.JudgeEngine
import top.drawtwice.taikocraft.judge.JudgeResult
import top.drawtwice.taikocraft.client.input.KeyInputBuffer

/**
 * 一局游玩的运行时状态：谱面 + 音频 + 时钟 + 判定。
 *
 * ## 双时钟
 * - [renderTimeMs]：音符**画在哪**。受目押偏移（visualOffsetMs）影响。
 * - [judgeTimeMs]：击打**算多准**。受音押偏移（audioOffsetMs）影响。
 *
 * 两者都来自同一个音频播放位置，只是各自减掉不同的延迟。
 */
class PlaySession(
    val chartFile: TjaChartFile,
    val chart: Chart,
    private val playback: OpenAlPlayback?,
    judgeConfig: JudgeConfig = JudgeConfig(),
) {
    /** TJA OFFSET（毫秒），从谱面读取 */
    private val tjaOffsetMs: Double = chart.offsetMs

    val clock = PlaybackClock(tjaOffsetMs = tjaOffsetMs)
    val judge = JudgeEngine(chart, judgeConfig)

    /** 最近一次判定结果，供界面做反馈动画 */
    var lastResult: JudgeResult? = null
        private set
    var lastResultAtMs: Double = -1.0
        private set

    /**
     * 命中特效（打中即爆开并消失）。
     * 每次命中追加一条，界面按时间淡出，过期自动清理。
     */
    data class HitFx(
        val startMs: Double,
        val color: top.drawtwice.taikocraft.chart.NoteColor,
        val big: Boolean,
        val judgment: top.drawtwice.taikocraft.judge.Judgment,
    )

    private val _hitFx = mutableListOf<HitFx>()
    val hitFx: List<HitFx> get() = _hitFx

    /** 输入时间戳换算用的锚点 */
    private var anchorNanoTime: Long = 0L
    private var anchorJudgeTimeMs: Double = 0.0

    var volume: Float = 1.0f

    /**
     * 自动演奏（仅 OP 可开）。
     * 开启后模拟输入：每个音符到点时自动调用 judge.press()，判定为 Perfect。
     */
    var autoPlay: Boolean = false
        set(value) {
            field = value
            if (value) autoCursor = 0
        }

    private var autoCursor = 0

    /** 渲染用时间（受目押偏移影响） */
    val renderTimeMs: Double get() = clock.chartTimeVisualMs

    /** 判定用时间（受音押偏移影响） */
    val judgeTimeMs: Double get() = clock.chartTimeJudgeMs

    fun start() {
        // ★ 清空进入游戏前积压的按键，否则第一帧会一次性处理多个（"一下打三个"）
        KeyInputBuffer.clear()
        clock.start()
        playback?.play()
        playback?.setVolume(volume)
        // 开始时对齐一次后端位置（之后完全用系统时钟外推）
        playback?.let { clock.syncToBackend(it.positionMs(), toleranceMs = 200.0) }
        anchorNanoTime = System.nanoTime()
        anchorJudgeTimeMs = clock.chartTimeJudgeMs
    }

    fun pause() {
        clock.pause()
        playback?.pause()
    }

    fun stop() {
        clock.stop()
        playback?.stop()
        KeyInputBuffer.clear()
    }

    fun tick() {
        playback?.let { player ->
            // ★ 不再每帧用后端位置覆盖时钟（那是突进的根源）
            // 只在开始时对齐一次；运行中也仅在严重偏离时才纠正
            player.setVolume(volume)
            if (player.tick() && clock.isPlaying) {
                clock.pause()
            }
        }
        anchorNanoTime = System.nanoTime()
        anchorJudgeTimeMs = clock.chartTimeJudgeMs

        // 输入事件 → 判定时间轴
        for (hit in KeyInputBuffer.drain()) {
            val deltaMs = (hit.nanoTime - anchorNanoTime) / 1_000_000.0
            val judgeTime = anchorJudgeTimeMs + deltaMs
            judge.press(InputEvent(hit.color, hit.side, judgeTime))?.let { result ->
                lastResult = result
                lastResultAtMs = judgeTime
                // 记录特效（连打/气球也爆，但用音符时间更自然）
                _hitFx += HitFx(judgeTime, result.color, result.big, result.judgment)
            }
        }
        // ---- Autoplay：自动敲击 ----
        if (autoPlay) {
            val now = clock.chartTimeJudgeMs
            val notes = judge.notesSnapshot()
            while (autoCursor < notes.size && notes[autoCursor].timeMs <= now) {
                val note = notes[autoCursor]
                if (!judge.isJudged(autoCursor)) {
                    val side = Side.LEFT
                    judge.press(InputEvent(note.color, side, note.timeMs))?.let { result ->
                        lastResult = result
                        lastResultAtMs = note.timeMs
                        _hitFx += HitFx(note.timeMs, note.color, note.big, result.judgment)
                    }
                    // 大音符补第二只手
                    if (note.big) {
                        judge.press(InputEvent(note.color, Side.RIGHT, note.timeMs))?.let { result ->
                            lastResult = result
                            lastResultAtMs = note.timeMs
                            _hitFx += HitFx(note.timeMs, note.color, note.big, result.judgment)
                        }
                    }
                }
                autoCursor++
            }
        }

        // Miss 判定也走判定时钟
        judge.advanceTo(clock.chartTimeJudgeMs)

        // 清理 1 秒前的特效
        val cutoff = clock.chartTimeJudgeMs - 1000.0
        _hitFx.removeAll { it.startMs < cutoff }
    }

    /** 判定进度 0..1 */
    fun progress(): Float {
        val total = chart.lastEventMs
        if (total <= 0) return 0f
        return (clock.chartTimeVisualMs / total).coerceIn(0.0, 1.0).toFloat()
    }
}
