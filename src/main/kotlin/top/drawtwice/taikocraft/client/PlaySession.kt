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

    private val _hitFx = mutableListOf<HitFx>()
    val hitFx: List<HitFx> get() = _hitFx

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
        if (audioFile != null) {
            runCatching { OpenAlPlayback.create(audioFile) }
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
    }

    private fun recordResult(r: JudgeResult, atMs: Double) {
        lastResult = r
        lastResultAtMs = atMs
        if (r.judgment != Judgment.MISS) {
            _hitFx += HitFx(atMs, r.color, r.big, r.judgment, r.player, r.side)
        }
    }
}
