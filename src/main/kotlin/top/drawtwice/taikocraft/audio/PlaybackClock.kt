package top.drawtwice.taikocraft.audio

/**
 * 歌曲播放时间轴（纯逻辑，可单测）。
 *
 * ## 两个独立的延迟（坠尔的设计要求）
 * 「视觉校准」和「音频校准」必须**分开**，否则不是音游：
 *
 * | 偏移 | 影响 | 校准方式 |
 * |---|---|---|
 * | **目押偏移**（visualOffsetMs） | **只影响音符的屏幕位置** | 玩家看着节拍图形按，测「眼睛→手」延迟 |
 * | **音押偏移**（audioOffsetMs） | **只影响判定时刻** | 玩家闭眼听声音按，测「耳朵→手」延迟 |
 *
 * 两者都叠加在同一个底层时钟上，但作用在不同的消费点：
 * ```
 * 渲染位置用 chartTimeVisualMs = songPosition + leadIn − visualOffset
 * 判定时刻用 chartTimeJudgeMs  = songPosition + leadIn − audioOffset
 * ```
 *
 * 这样：
 * - 显示器延迟大 → 调 visualOffset，音符显示位置整体提前/推后，**判定不变**
 * - 声卡延迟大   → 调 audioOffset，判定窗口整体平移，**画面不变**
 */
class PlaybackClock(
    /** 音频文件自身的起始静音（秒），来自 TJA 前导或 osu 的 [General] AudioLeadIn */
    private val leadInMs: Double = 0.0,
) {
    /** 目押偏移（毫秒）：只影响**渲染位置** */
    var visualOffsetMs: Double = 0.0

    /** 音押偏移（毫秒）：只影响**判定时刻** */
    var audioOffsetMs: Double = 0.0

    /** 兼容旧代码：设置两者（一般不要用，除非玩家只想调一个） */
    var universalOffsetMs: Double
        get() = audioOffsetMs
        set(value) {
            visualOffsetMs = value
            audioOffsetMs = value
        }

    /** 当前音频内播放位置（毫秒），由播放器每帧写入 */
    var songPositionMs: Double = 0.0
        private set

    private var playing = false
    private var pausedAtMs = 0.0

    fun start(fromMs: Double = 0.0) {
        songPositionMs = fromMs
        playing = true
    }

    fun pause() {
        pausedAtMs = songPositionMs
        playing = false
    }

    fun resume() {
        songPositionMs = pausedAtMs
        playing = true
    }

    fun stop() {
        playing = false
        songPositionMs = 0.0
    }

    /** 由音频后端每帧调用：报告真实播放位置 */
    fun updateFromBackend(positionMs: Double) {
        if (!playing) return
        songPositionMs = positionMs
    }

    /** 渲染用时间轴（音符位置）：受目押偏移影响 */
    val chartTimeVisualMs: Double
        get() = songPositionMs + leadInMs - visualOffsetMs

    /** 判定用时间轴（击打时刻换算）：受音押偏移影响 */
    val chartTimeJudgeMs: Double
        get() = songPositionMs + leadInMs - audioOffsetMs

    /** 兼容旧代码 */
    val chartTimeMs: Double get() = chartTimeVisualMs

    val isPlaying: Boolean get() = playing
}
