package top.drawtwice.taikocraft.audio

import org.lwjgl.openal.AL10
import org.lwjgl.system.MemoryUtil
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 校准用的节拍音发生器。
 *
 * 用 OpenAL 直接播放一个**程序合成**的短促点击音（正弦 + 指数衰减包络）。
 * 不依赖任何音频文件，也不涉及版权问题。
 *
 * 音色：
 * - 普通拍：880 Hz，60ms
 * - 重拍（每 4 拍）：1320 Hz，80ms，音量稍大
 *
 * 线程：必须在持有 OpenAL 上下文的线程调用（Minecraft sound 线程）。
 */
object MetronomeSound {

    private const val SAMPLE_RATE = 44100
    private const val BUFFER_MILLIS = 90

    private var source = 0
    private var bufferId = 0
    private var initialized = false
    private var available = false

    /** 初始化（失败时静默降级，不抛异常） */
    fun init() {
        if (initialized) return
        initialized = true
        try {
            source = AL10.alGenSources()
            bufferId = AL10.alGenBuffers()
            if (source == 0 || bufferId == 0) {
                available = false
                return
            }
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
            uploadClick(880, 1.0f)
            available = true
        } catch (t: Throwable) {
            available = false
        }
    }

    /** 播放一拍 */
    fun play(accent: Boolean = false) {
        if (!available) return
        try {
            uploadClick(if (accent) 1320 else 880, if (accent) 1.0f else 0.75f)
            AL10.alSourceStop(source)
            AL10.alSourcei(source, AL10.AL_BUFFER, bufferId)
            AL10.alSourcePlay(source)
        } catch (_: Throwable) {
            // 播放失败不影响校准主流程
        }
    }

    fun setVolume(volume: Float) {
        if (!available) return
        try {
            AL10.alSourcef(source, AL10.AL_GAIN, volume.coerceIn(0f, 1f))
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        if (!available) return
        try {
            AL10.alSourceStop(source)
            AL10.alDeleteSources(intArrayOf(source))
            AL10.alDeleteBuffers(intArrayOf(bufferId))
        } catch (_: Throwable) {
        }
        available = false
        source = 0
        bufferId = 0
        initialized = false
    }

    /** 合成并上传一个点击音 */
    private fun uploadClick(freqHz: Int, gain: Float) {
        val frames = SAMPLE_RATE * BUFFER_MILLIS / 1000
        val buf = MemoryUtil.memAllocShort(frames)
        try {
            for (i in 0 until frames) {
                val t = i.toDouble() / SAMPLE_RATE
                // 指数衰减包络（~25ms 半衰）
                val env = exp(-t / 0.025)
                val sample = sin(2.0 * PI * freqHz * t) * env * gain
                buf.put(i, (sample * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort())
            }
            buf.rewind()
            AL10.alBufferData(bufferId, AL10.AL_FORMAT_MONO16, buf, SAMPLE_RATE)
        } finally {
            MemoryUtil.memFree(buf)
        }
    }
}
