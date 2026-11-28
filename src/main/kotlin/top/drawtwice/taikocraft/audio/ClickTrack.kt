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
 * ## ⚠️ 整数溢出的坑（2026-11-28 校准界面冻死的根因）
 * 最初写成 `SAMPLE_RATE * BEAT_MS * BEATS / 1000`：
 * ```
 * 44100 × 500 × 120 = 2,646,000,000   >   Int.MAX_VALUE (2,147,483,647)
 * ```
 * Int 溢出成 **−1,648,967,296**，再 `/1000` 得负数，
 * `ShortBuffer.allocate(负数)` 抛异常 → `playback` 为 null →
 * `nowMs()` 恒返回 0 → 画面与声音全冻住。
 *
 * 现在全程用 `Long` 计算，并把音轨缩到 32 拍（16 秒）循环，
 * 既避开溢出也省内存（32 拍只需 1.4MB）。
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

    /** 总拍数：32 拍 = 16 秒，播完循环。 */
    private const val BEATS = 32

    /** 每段点击音长度（毫秒） */
    private const val CLICK_MS = 90

    /** 总时长（毫秒）—— 全程 Long 运算，不溢出 */
    val durationMs: Int get() = (BEAT_MS.toLong() * BEATS).toInt()

    fun build(): OpenAlPlayback.Companion.DecodedPcm {
        // ★ 必须用 Long：44100 × 500 × 32 = 705,600,000（安全，但仍用 Long 防未来改大）
        val totalFrames = (SAMPLE_RATE.toLong() * BEAT_MS.toLong() * BEATS / 1000L).toInt()
        require(totalFrames > 0) { "ClickTrack 帧数计算错误：$totalFrames" }

        val pcm = ShortBuffer.allocate(totalFrames)
        val clickFrames = (SAMPLE_RATE.toLong() * CLICK_MS / 1000L).toInt()
        val beatFrames = (SAMPLE_RATE.toLong() * BEAT_MS / 1000L).toInt()

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
