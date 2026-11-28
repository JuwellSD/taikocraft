package top.drawtwice.taikocraft.audio

import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 校准用的节拍音轨（**预合成整段 PCM**）。
 *
 * ## 为什么不能再每次现敲
 * 旧实现用 `MetronomeSound`：每拍调 `alBufferData` 重传 buffer 再 `alSourcePlay`。
 * 那条路径与视觉用的 `System.nanoTime()` **没有任何同步**，
 * 坠尔实测声音比画面晚约 230ms（跟音乐点得 -130，跟画面点却是对的）。
 *
 * 现在把整段节拍**一次性合成成一条 PCM 音轨**，交给 [OpenAlPlayback] 流式播放，
 * 校准界面直接用 `playback.positionMs()` 当**唯一时钟**驱动画面 ——
 * 声音和画面共用同一个时间源，物理上不可能再错位。
 *
 * ## 音色
 * - 普通拍：880 Hz，90ms 指数衰减
 * - 重拍（每 4 拍）：1320 Hz，略响
 * - 拍间隔 500ms，与 OpenTaiko `CCounter(0, 500, 1)` 一致
 */
object ClickTrack {

    const val SAMPLE_RATE = 44100

    /** 拍间隔（毫秒），与 OpenTaiko 校准周期一致 */
    const val BEAT_MS = 500

    /** 总拍数：120 拍 = 60 秒 */
    private const val BEATS = 120

    /** 每段点击音长度（毫秒） */
    private const val CLICK_MS = 90

    /** 总时长（毫秒） */
    val durationMs: Int get() = BEAT_MS * BEATS

    fun build(): OpenAlPlayback.Companion.DecodedPcm {
        val totalFrames = SAMPLE_RATE * BEAT_MS * BEATS / 1000
        val pcm = ShortBuffer.allocate(totalFrames)
        val clickFrames = SAMPLE_RATE * CLICK_MS / 1000
        val beatFrames = SAMPLE_RATE * BEAT_MS / 1000

        for (b in 0 until BEATS) {
            val accent = b % 4 == 0
            val freq = if (accent) 1320.0 else 880.0
            val gain = if (accent) 0.90 else 0.65
            val start = b * beatFrames
            for (i in 0 until clickFrames) {
                val idx = start + i
                if (idx >= totalFrames) break
                val t = i.toDouble() / SAMPLE_RATE
                val env = exp(-t / 0.022)
                val s = sin(2.0 * PI * freq * t) * env * gain
                pcm.put(idx, (s * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        pcm.rewind()
        return OpenAlPlayback.Companion.DecodedPcm(pcm, SAMPLE_RATE, 1)
    }
}
