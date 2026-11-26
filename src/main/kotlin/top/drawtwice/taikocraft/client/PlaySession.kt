package top.drawtwice.taikocraft.client

import top.drawtwice.taikocraft.audio.OpenAlPlayback
import top.drawtwice.taikocraft.audio.PlaybackClock
import top.drawtwice.taikocraft.chart.Chart
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
    val clock = PlaybackClock()
    val judge = JudgeEngine(chart, judgeConfig)

    /** 最近一次判定结果，供界面做反馈动画 */
    var lastResult: JudgeResult? = null
        private set
    var lastResultAtMs: Double = -1.0
        private set

    /** 输入时间戳换算用的锚点 */
    private var anchorNanoTime: Long = 0L
    private var anchorJudgeTimeMs: Double = 0.0

    var volume: Float = 1.0f

    /** 渲染用时间（受目押偏移影响） */
    val renderTimeMs: Double get() = clock.chartTimeVisualMs

    /** 判定用时间（受音押偏移影响） */
    val judgeTimeMs: Double get() = clock.chartTimeJudgeMs

    fun start() {
        clock.start()
        playback?.play()
        playback?.setVolume(volume)
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
            clock.updateFromBackend(player.positionMs())
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
            }
        }
        // Miss 判定也走判定时钟
        judge.advanceTo(clock.chartTimeJudgeMs)
    }

    /** 判定进度 0..1 */
    fun progress(): Float {
        val total = chart.lastEventMs
        if (total <= 0) return 0f
        return (clock.chartTimeVisualMs / total).coerceIn(0.0, 1.0).toFloat()
    }
}
