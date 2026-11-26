package top.drawtwice.taikocraft.audio

/**
 * 歌曲播放时间轴（纯逻辑，可单测）。
 *
 * ## 时钟源策略（重新设计）
 * 早期版本直接使用 OpenAL 的 `AL_SAMPLE_OFFSET` 作为播放位置，
 * 但该值在**每块缓冲边界会跳变**（某些驱动的软实现还会整体回绕），
 * 表现为音符「周期性地往前突进一段」。
 *
 * 现在的方案：**以单调系统时钟为主时钟**。
 * - `start()` 记录起点 `System.nanoTime()`
 * - 位置 = (now − 起点) / 1e6 + 起始偏移
 * - 音频后端只在**开始播放**时对齐一次（[syncToBackend]），之后不再干预
 *
 * 这样做的好处：
 * - 位置严格单调递增，不可能回跳或突进
 * - 与音频的实际漂移在几分钟内通常 < 10ms（OpenAL 软件混音本就基于系统时钟）
 * - 不受驱动实现差异影响
 *
 * ## 两个独立的延迟（坠尔的设计要求）
 * | 偏移 | 影响 | 校准方式 |
 * |---|---|---|
 * | **目押偏移**（visualOffsetMs） | **只影响音符的屏幕位置** | 看着节拍图形按 |
 * | **音押偏移**（audioOffsetMs） | **只影响判定时刻** | 听着节拍声音按 |
 */
class PlaybackClock(
    /** 音频文件自身的起始静音（毫秒） */
    private val leadInMs: Double = 0.0,
) {
    /** 目押偏移（毫秒）：只影响**渲染位置** */
    var visualOffsetMs: Double = 0.0

    /** 音押偏移（毫秒）：只影响**判定时刻** */
    var audioOffsetMs: Double = 0.0

    /** 兼容旧代码 */
    var universalOffsetMs: Double
        get() = audioOffsetMs
        set(value) {
            visualOffsetMs = value
            audioOffsetMs = value
        }

    /** 起点基准（System.nanoTime） */
    private var startNanos: Long = 0L
    /** 起点对应的歌曲位置（毫秒） */
    private var startPositionMs: Double = 0.0
    /** 暂停时刻的位置 */
    private var pausedPositionMs: Double = 0.0

    private var playing = false

    /** 当前歌曲播放位置（毫秒），单调递增 */
    val songPositionMs: Double
        get() = if (!playing) pausedPositionMs
        else startPositionMs + (System.nanoTime() - startNanos) / 1_000_000.0

    fun start(fromMs: Double = 0.0) {
        startPositionMs = fromMs
        startNanos = System.nanoTime()
        pausedPositionMs = fromMs
        playing = true
    }

    fun pause() {
        pausedPositionMs = songPositionMs
        playing = false
    }

    fun resume() {
        startNanos = System.nanoTime()
        startPositionMs = pausedPositionMs
        playing = true
    }

    fun stop() {
        playing = false
        startPositionMs = 0.0
        pausedPositionMs = 0.0
    }

    /**
     * 与音频后端对齐一次（可选）。
     *
     * **只在开始时调用一次**，不要每帧调用 —— 后端值会跳变，
     * 每帧对齐就是「音符突进」的根源。
     *
     * @param backendPositionMs 后端报告的播放位置
     * @param toleranceMs 允许误差；超过才真正纠正（默认 80ms）
     */
    fun syncToBackend(backendPositionMs: Double, toleranceMs: Double = 80.0) {
        if (!playing) return
        val drift = backendPositionMs - songPositionMs
        if (kotlin.math.abs(drift) > toleranceMs) {
            // 只有严重偏离（例如暂停恢复后后端位置完全不同）才纠正
            startPositionMs = backendPositionMs
            startNanos = System.nanoTime()
        }
    }

    /** 兼容旧接口：忽略（主时钟已经是系统时钟） */
    fun updateFromBackend(positionMs: Double) {
        // 故意不做任何事：每帧对齐会导致回跳/突进
    }

    /** 渲染用时间轴（音符位置）：受目押偏移影响 */
    val chartTimeVisualMs: Double
        get() = songPositionMs + leadInMs - visualOffsetMs

    /** 判定用时间轴（击打时刻换算）：受音押偏移影响 */
    val chartTimeJudgeMs: Double
        get() = songPositionMs + leadInMs - audioOffsetMs

    val chartTimeMs: Double get() = chartTimeVisualMs

    val isPlaying: Boolean get() = playing
}
