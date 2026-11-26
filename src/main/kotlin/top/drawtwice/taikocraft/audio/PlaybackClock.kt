package top.drawtwice.taikocraft.audio

/**
 * 歌曲播放时间轴（纯逻辑，可单测）。
 *
 * 音频位置 = 歌曲起始时刻 + 音频内播放位置 − 全局延迟偏移
 * 其中「音频内播放位置」由 OpenAL 的 AL_SAMPLE_OFFSET 累计得到（[OpenAlPlayback] 负责）。
 *
 * 这里的延迟偏移来自玩家校准（CalibrationScreen），>0 表示玩家设备偏慢，
 * 判定时应把击打时刻往后挪（等价于音符时间向前挪）。
 */
class PlaybackClock(
    /** 音频文件自身的起始静音（秒），来自 TJA 前导或 osu 的 [General] AudioLeadIn */
    private val leadInMs: Double = 0.0,
) {
    /** 校准得到的全局偏移（毫秒）。正值 = 视频/音频输出偏慢，判定窗口整体后移 */
    var universalOffsetMs: Double = 0.0
        set(value) {
            field = value
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

    /**
     * 当前"谱面时间"：判定与渲染都用它。
     * = 音频位置 + 前导 − 全局延迟
     */
    val chartTimeMs: Double
        get() = songPositionMs + leadInMs - universalOffsetMs

    val isPlaying: Boolean get() = playing
}
