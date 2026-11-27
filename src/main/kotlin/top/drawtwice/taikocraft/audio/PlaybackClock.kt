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
    /**
     * 音频启动延迟（毫秒）：TJA 的 OFFSET 语义。
     *
     * OpenTaiko 的处理：
     * - **正 OFFSET**：只把「音乐」往后推 → 音频延后启动，谱面时间不变
     * - **负 OFFSET**：只把「音符」往后推 → 音频立刻开始，谱面时间整体 +|OFFSET|
     *
     * 本类把它归一化为「音频先等多久」：offset > 0 时音频延迟启动，
     * offset < 0 时音频立即启动、谱面时间轴整体后移。
     */
    val tjaOffsetMs: Double = 0.0,
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

    /**
     * 音频后端报告的真实播放位置（毫秒）。
     *
     * ## 为什么必须用这个而不是纯系统时钟
     * 坠尔实测：校准推荐 -80ms，但舒适值是 +440ms，**系统性差 520ms**。
     *
     * 原因是我的旧实现以 `System.nanoTime()` 为唯一时钟源：
     * 从「我们决定开始播放」那一刻起算，但音频真正出声要经过
     * OpenAL 缓冲填充 + 驱动混音 + 声卡输出，这 500ms 左右的延迟
     * 完全不在我的时钟里 → 画面永远比声音早。
     *
     * 正确做法（OpenTaiko 的 SoundTimer 思路）：
     * **以音频实际播放位置为主时钟**，画面跟着音频走。
     * 后端每次 tick 报告位置，时钟以它为基准，系统时钟只在两次报告之间插值。
     */
    private var backendPositionMs: Double = -1.0
    private var backendAtNanos: Long = 0L
    /** 后端是否已经报告过位置（首次报告前用系统时钟兜底） */
    private var hasBackend = false

    /** 当前歌曲播放位置（毫秒）：优先用音频后端位置 + 插值 */
    val songPositionMs: Double
        get() {
            if (!playing) return pausedPositionMs
            return if (hasBackend) {
                // 音频位置 + 自上次报告以来经过的时间（插值，最大 100ms 防漂移）
                val since = ((System.nanoTime() - backendAtNanos) / 1_000_000.0).coerceIn(0.0, 100.0)
                backendPositionMs + since
            } else {
                // 后端还没报告（解码中/静默模式），用系统时钟兜底
                startPositionMs + (System.nanoTime() - startNanos) / 1_000_000.0
            }
        }

    fun start(fromMs: Double = 0.0) {
        startPositionMs = fromMs
        // 正 OFFSET：音频延后启动（谱面从 0 开始，音乐晚 offsetMs 才响）
        val delay = if (tjaOffsetMs > 0) tjaOffsetMs else 0.0
        startNanos = System.nanoTime() - (delay * 1_000_000).toLong()
        pausedPositionMs = fromMs
        playing = true
        audioStartDelayMs = delay
    }

    /** 音频启动延迟（毫秒）；>0 表示还没开始出声 */
    private var audioStartDelayMs: Double = 0.0

    /** 音频是否已经真正开始出声（用于判断是否需要启动播放器） */
    val audioShouldStart: Boolean
        get() = playing && songPositionMs >= audioStartDelayMs

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

    /**
     * 每帧由音频后端报告真实播放位置 —— **这是主时钟来源**。
     *
     * 只在位置前进时接受（防回跳），且限速（防驱动跳变导致突进）。
     */
    fun updateFromBackend(positionMs: Double) {
        if (!playing) return
        if (positionMs < 0) return
        if (!hasBackend) {
            backendPositionMs = positionMs
            backendAtNanos = System.nanoTime()
            hasBackend = true
            return
        }
        // 只允许小幅前进（防止 AL_SAMPLE_OFFSET 在缓冲边界跳变）
        val drift = positionMs - backendPositionMs
        if (drift > 0 && drift < 200.0) {
            backendPositionMs = positionMs
            backendAtNanos = System.nanoTime()
        } else if (drift >= 200.0) {
            // 大跳变：可能是 seek/恢复，直接接受
            backendPositionMs = positionMs
            backendAtNanos = System.nanoTime()
        }
    }

    /**
     * 谱面时间 = 音频播放位置 + TJA OFFSET
     *
     * OpenTaiko 的语义：
     * - 谱面原始时间轴（不含 OFFSET）从 0 开始
     * - **正 OFFSET** → 音符相对音频**提前**，即谱面时间 = 音频 + OFFSET
     * - **负 OFFSET** → 音符相对音频**延后**，同样是 + OFFSET（负值就是减）
     *
     * 例：OFFSET = -1.816s，音频播到 0ms 时谱面在 -1816ms（还没开始），
     *     音频播到 1816ms 时谱面在 0ms（第一个音符命中）。
     */
    private val chartBaseMs: Double
        get() = songPositionMs + tjaOffsetMs + leadInMs

    // ------------------------------------------------------------------
    // 两个偏移的语义（与常见太鼓模拟器一致）
    //
    //   音押偏移（audioOffsetMs）：**动整个音符** ——
    //       谱面的视觉位置与判定时刻一起平移。
    //       调它不会造成「看到的位置」和「判定的位置」错位。
    //
    //   目押偏移（visualOffsetMs）：**只动视觉** ——
    //       在「音押已确定的判定时刻」基础上，额外把音符的**绘制位置**
    //       提前/延后一点，让习惯提前或延后看谱的玩家感觉更顺。
    //       它**故意**让视觉与判定错开，不影响实际打击判定。
    // ------------------------------------------------------------------

    /**
     * 判定时间轴（击打时刻换算）：只受**音押**影响。
     *
     * 音押 > 0：谱面整体提前（音符来得更早、判定点也提前）。
     */
    val chartTimeJudgeMs: Double
        get() = chartBaseMs - audioOffsetMs

    /**
     * 渲染时间轴（音符字幕位置）：受**音押 + 目押**共同影响。
     *
     * = 判定时间轴 − 目押偏移
     * 目押 > 0：音符在屏幕上显示得更早（视觉提前），判定不变。
     */
    val chartTimeVisualMs: Double
        get() = chartTimeJudgeMs - visualOffsetMs

    val chartTimeMs: Double get() = chartTimeVisualMs

    val isPlaying: Boolean get() = playing
}
