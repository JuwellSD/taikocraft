package top.drawtwice.taikocraft.audio

/**
 * 歌曲播放时钟。
 *
 * ## 主时钟 = 音频后端位置（2026-11-28 关键修复）
 * 坠尔实测：校准推荐 -80ms，舒适值 +440ms，**系统性差 520ms**。
 * 根因是旧实现用 `System.nanoTime()` 从「决定播放」起算，
 * 不含 OpenAL 缓冲 + 声卡输出的 500ms。
 *
 * 现在：**以音频后端报告的真实位置为主时钟**，画面跟着音频走。
 * 后端两次报告之间用系统时钟插值（上限 100ms 防漂移）。
 *
 * ## 两个偏移
 * - 音押（audioOffsetMs）：视觉与判定**一起**平移
 * - 目押（visualOffsetMs）：只动视觉，在判定基础上额外偏移
 */
class PlaybackClock(private val tjaOffsetMs: Double = 0.0) {

    var visualOffsetMs: Double = 0.0
    var audioOffsetMs: Double = 0.0

    private var startNanos = 0L
    private var startPos = 0.0
    private var pausedPos = 0.0
    private var playing = false

    private var backendPos = -1.0
    private var backendAt = 0L
    private var hasBackend = false

    val songPositionMs: Double
        get() {
            if (!playing) return pausedPos
            return if (hasBackend) {
                val since = ((System.nanoTime() - backendAt) / 1_000_000.0).coerceIn(0.0, 100.0)
                backendPos + since
            } else {
                startPos + (System.nanoTime() - startNanos) / 1_000_000.0
            }
        }

    fun start(fromMs: Double = 0.0) {
        startPos = fromMs; pausedPos = fromMs
        startNanos = System.nanoTime(); playing = true
        hasBackend = false; backendPos = -1.0
    }

    fun pause() { pausedPos = songPositionMs; playing = false }
    fun resume() { startPos = pausedPos; startNanos = System.nanoTime(); playing = true }
    fun stop() { playing = false; startPos = 0.0; pausedPos = 0.0; hasBackend = false; backendPos = -1.0 }

    /** 每帧由音频后端上报真实位置 —— 主时钟来源 */
    fun updateFromBackend(positionMs: Double) {
        if (!playing || positionMs < 0) return
        if (!hasBackend) { backendPos = positionMs; backendAt = System.nanoTime(); hasBackend = true; return }
        val drift = positionMs - backendPos
        // 接受小幅前进与大跳变（seek），拒绝小幅回退
        if (drift > -50.0) {
            backendPos = positionMs; backendAt = System.nanoTime()
        }
    }

    /** 判定时间轴：只受音押影响 */
    val chartTimeJudgeMs: Double
        get() = songPositionMs + tjaOffsetMs - audioOffsetMs

    /** 渲染时间轴：音押 + 目押 */
    val chartTimeVisualMs: Double
        get() = chartTimeJudgeMs - visualOffsetMs

    val isPlaying: Boolean get() = playing
    val offsetMs: Double get() = tjaOffsetMs
}
