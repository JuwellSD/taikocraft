package top.drawtwice.taikocraft.client

import top.drawtwice.taikocraft.audio.OpenAlPlayback
import top.drawtwice.taikocraft.audio.PlaybackClock
import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.chart.TjaChartFile
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import top.drawtwice.taikocraft.judge.InputEvent
import top.drawtwice.taikocraft.judge.JudgeConfig
import top.drawtwice.taikocraft.judge.JudgeEngine
import top.drawtwice.taikocraft.judge.JudgeResult
import top.drawtwice.taikocraft.judge.Judgment
import top.drawtwice.taikocraft.judge.Side
import java.nio.file.Path

/**
 * 一局游玩的运行时状态：谱面 + 音频 + 时钟 + 判定。
 *
 * ## 双时钟
 * - [renderTimeMs]：音符**画在哪**。受目押偏移（visualOffsetMs）影响。
 * - [judgeTimeMs]：击打**算多准**。受音押偏移（audioOffsetMs）影响。
 *
 * ## 音频主时钟
 * 每帧把 [OpenAlPlayback.positionMs]（真实播放位置）喂给 [PlaybackClock]，
 * 画面完全跟着音频走 —— 这样既没有 500ms 的缓冲延迟，也不会随时间漂移。
 */
class PlaySession(
    val chartFile: TjaChartFile,
    val chart: Chart,
    private val audioFile: Path?,
    judgeConfig: JudgeConfig = JudgeConfig(),
) {
    val clock = PlaybackClock(tjaOffsetMs = chart.offsetMs)
    val judge = JudgeEngine(chart, judgeConfig)

    /** 播放器。音频文件缺失时为 null，此时退回系统时钟（无声音但界面可玩）。 */
    private var playback: OpenAlPlayback? = null
    private var playbackError: String? = null

    var lastResult: JudgeResult? = null
        private set
    var lastResultAtMs: Double = -1.0
        private set

    /** 命中特效（打中即爆开并消失） */
    data class HitFx(
        val startMs: Double,
        val color: NoteColor,
        val big: Boolean,
        val judgment: Judgment,
        val player: Int,
        val side: Side,
    )

    /**
     * 一次**实际按键**（与是否命中音符无关）。
     *
     * 左侧平面太鼓的高亮**只看这个** —— 玩家敲了什么就亮什么，
     * 没敲到音符也亮，敲错颜色也亮。这才是「键位示意」的正确语义。
     *
     * @param atNanos 系统时钟（`System.nanoTime()`），用于渐隐，
     *                不能用谱面时间（暂停时会跟着停）
     */
    data class PadPress(
        val color: NoteColor,
        val side: Side,
        val player: Int,
        val atNanos: Long,
    )

    private val _hitFx = mutableListOf<HitFx>()
    val hitFx: List<HitFx> get() = _hitFx

    /** 最近 700ms 内的实际按键，供平面太鼓渐隐高亮 */
    private val _padPresses = mutableListOf<PadPress>()
    val padPresses: List<PadPress> get() = _padPresses

    private var anchorNano = 0L
    private var anchorJudge = 0.0

    var volume: Float = 1.0f
    var autoPlay: Boolean = false
    private var autoCursor = 0

    val renderTimeMs: Double get() = clock.chartTimeVisualMs
    val judgeTimeMs: Double get() = clock.chartTimeJudgeMs

    /** 音频加载失败原因（界面可显示） */
    val audioStatus: String
        get() = playbackError ?: if (playback != null) "" else "无音频文件"

    init {
        clock.visualOffsetMs = TaikoCraftClient.visualOffsetMs
        clock.audioOffsetMs = TaikoCraftClient.audioOffsetMs
    }

    fun start() {
        KeyInputBuffer.clear()
        val file = audioFile
        if (file != null) {
            runCatching { OpenAlPlayback.create(file) }
                .onSuccess { playback = it }
                .onFailure {
                    playbackError = "音频加载失败：${it.message}"
                    top.drawtwice.taikocraft.TaikoCraft.LOGGER.error("音频加载失败", it)
                }
        }
        clock.start()
        playback?.play()
        playback?.setVolume(volume)
        anchorNano = System.nanoTime()
        anchorJudge = clock.chartTimeJudgeMs
    }

    fun stop() {
        clock.stop()
        playback?.stop()
        playback = null
        KeyInputBuffer.clear()
        _padPresses.clear()
        _hitFx.clear()
    }

    fun tick() {
        val player = playback
        if (player != null) {
            player.setVolume(volume)
            // 音频后端位置 = 主时钟来源
            clock.updateFromBackend(player.positionMs())
            if (player.tick() && clock.isPlaying) clock.pause()
        }

        anchorNano = System.nanoTime()
        anchorJudge = clock.chartTimeJudgeMs

        for (hit in KeyInputBuffer.drain()) {
            // ★ 先记录「玩家按了什么」——与判定结果无关
            _padPresses += PadPress(hit.color, hit.side, 1, hit.nanoTime)

            val delta = (hit.nanoTime - anchorNano) / 1_000_000.0
            val judgeTime = anchorJudge + delta
            judge.press(InputEvent(hit.color, hit.side, judgeTime))?.let {
                recordResult(it, judgeTime)
            }
        }

        if (autoPlay) {
            val now = clock.chartTimeJudgeMs
            val notes = judge.notesSnapshot()
            while (autoCursor < notes.size && notes[autoCursor].timeMs <= now) {
                val idx = autoCursor
                val note = notes[autoCursor]
                if (!judge.isJudged(idx)) {
                    judge.press(InputEvent(note.color, Side.LEFT, note.timeMs))?.let { recordResult(it, note.timeMs) }
                    if (note.big) {
                        judge.press(InputEvent(note.color, Side.RIGHT, note.timeMs))?.let { recordResult(it, note.timeMs) }
                    }
                }
                autoCursor++
            }
        }

        judge.advanceTo(clock.chartTimeJudgeMs)

        val cutoff = clock.chartTimeJudgeMs - 1000.0
        _hitFx.removeAll { it.startMs < cutoff }

        // 平面太鼓渐隐：清理时间必须 ≥ TaikoScreen.PAD_FADE_MS（550ms），
        // 否则记录提前消失会让渐隐在 73% 处突然中断，看起来像「瞬间消失」。
        val nowNano = System.nanoTime()
        _padPresses.removeAll { nowNano - it.atNanos > 700_000_000L }
    }

    private fun recordResult(r: JudgeResult, atMs: Double) {
        lastResult = r
        lastResultAtMs = atMs
        // 只对「良/可」生成爆开特效；不可不画（OpenTaiko 同行为）
        if (r.judgment == Judgment.PERFECT || r.judgment == Judgment.GOOD) {
            _hitFx += HitFx(atMs, r.color, r.big, r.judgment, r.player, r.side)
        }
    }
}
