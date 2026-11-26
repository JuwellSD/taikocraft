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
 * 生命周期由 [top.drawtwice.taikocraft.client.TaikoScreen] 管理，
 * 判定与渲染在客户端渲染线程推进（文档 3.1 的本地预测思想；
 * 联机校验留到 Phase 4）。
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

    /** 输入时间戳与谱面时间的基准差：used to map nanoTime → chartTime */
    private var anchorNanoTime: Long = 0L
    private var anchorChartTimeMs: Double = 0.0

    var volume: Float = 1.0f

    fun start() {
        clock.start()
        playback?.play()
        playback?.setVolume(volume)
        anchorNanoTime = System.nanoTime()
        anchorChartTimeMs = clock.chartTimeMs
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

    /** 每帧推进 */
    fun tick() {
        playback?.let { player ->
            clock.updateFromBackend(player.positionMs())
            player.setVolume(volume)
            if (player.tick() && clock.isPlaying) {
                // 音频播完，让时钟自然走完尾音
                clock.pause()
            }
        }
        anchorNanoTime = System.nanoTime()
        anchorChartTimeMs = clock.chartTimeMs

        // 消费按键事件，把 nanoTime 换算为谱面时间（考虑全局延迟）
        for (hit in KeyInputBuffer.drain()) {
            val deltaMs = (hit.nanoTime - anchorNanoTime) / 1_000_000.0
            val chartTime = anchorChartTimeMs + deltaMs
            judge.press(InputEvent(hit.color, hit.side, chartTime))?.let { result ->
                lastResult = result
                lastResultAtMs = chartTime
            }
        }
        judge.advanceTo(clock.chartTimeMs)
    }

    /** 判定进度 0..1 */
    fun progress(): Float {
        val total = chart.lastEventMs
        if (total <= 0) return 0f
        return (clock.chartTimeMs / total).coerceIn(0.0, 1.0).toFloat()
    }
}
