package top.drawtwice.taikocraft.client

import top.drawtwice.taikocraft.audio.PlaybackClock
import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.TjaChartFile
import top.drawtwice.taikocraft.judge.InputEvent
import top.drawtwice.taikocraft.judge.JudgeConfig
import top.drawtwice.taikocraft.judge.JudgeEngine
import top.drawtwice.taikocraft.judge.JudgeResult
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import java.nio.file.Path

/**
 * 一局游玩的运行时状态。
 *
 * 时钟：以音频后端位置为主时钟（见 [PlaybackClock]）。
 */
class PlaySession(
    val chartFile: TjaChartFile,
    val chart: Chart,
    private val audioFile: Path?,
) {
    val clock = PlaybackClock(tjaOffsetMs = chart.offsetMs)
    val judge = JudgeEngine(chart, JudgeConfig())

    var lastResult: JudgeResult? = null
        private set
    var lastResultAtMs: Double = -1.0
        private set

    private var anchorNano = 0L
    private var anchorJudge = 0.0

    val renderTimeMs: Double get() = clock.chartTimeVisualMs
    val judgeTimeMs: Double get() = clock.chartTimeJudgeMs

    init {
        clock.visualOffsetMs = TaikoCraftClient.visualOffsetMs
        clock.audioOffsetMs = TaikoCraftClient.audioOffsetMs
    }

    fun start() {
        KeyInputBuffer.clear()
        clock.start()
        anchorNano = System.nanoTime()
        anchorJudge = clock.chartTimeJudgeMs
        @Suppress("UNUSED_VARIABLE") val unused = audioFile
    }

    fun stop() {
        clock.stop()
        KeyInputBuffer.clear()
    }

    fun tick() {
        anchorNano = System.nanoTime()
        anchorJudge = clock.chartTimeJudgeMs

        for (hit in KeyInputBuffer.drain()) {
            val delta = (hit.nanoTime - anchorNano) / 1_000_000.0
            val judgeTime = anchorJudge + delta
            judge.press(InputEvent(hit.color, hit.side, judgeTime))?.let {
                lastResult = it
                lastResultAtMs = judgeTime
            }
        }
        judge.advanceTo(clock.chartTimeJudgeMs)
    }
}
