package top.drawtwice.taikocraft.audio

import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.stb.STBVorbis
import org.lwjgl.stb.STBVorbisInfo
import org.lwjgl.system.MemoryUtil
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ShortBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import kotlin.math.max
import kotlin.math.min

/**
 * 单首歌曲的 OpenAL 流式播放器。
 *
 * ## 播放位置（精确算法）
 * `AL_SAMPLE_OFFSET` 只是**当前缓冲内**的偏移，换块就归零，不能当全局位置。
 *
 * 本实现维护两个量：
 * - `enqueuedSamples`：累计提交的交错样本数
 * - `queuedSizes`：队列中每块缓冲的交错样本数（**精确值**，不是估算）
 *
 * ```
 * 已播帧 = (enqueuedSamples − queuedSizes.sum()) / channels + AL_SAMPLE_OFFSET
 * ```
 *
 * 换块瞬间，`AL_SAMPLE_OFFSET` 归零与 `queuedSizes` 减少的量正好抵消，位置连续。
 * （旧实现用 `queued × 固定块大小` 估算，最后一块偏小会导致周期性回跳。）
 */
class OpenAlPlayback private constructor(
    private val pcm: ShortBuffer,
    private val sampleRate: Int,
    private val channels: Int,
    private val silent: Boolean,
) {
    private var source = 0
    private var buffers = IntArray(0)
    private var enqueuedSamples = 0L
    private val queuedSizes = ArrayDeque<Int>()
    private var startedAtNanos = 0L
    private var paused = false
    private var pausedPositionMs = 0.0
    private var destroyed = false
    private var volume: Float = 1.0f

    private val frameCount = if (channels > 0) pcm.limit() / channels else 0

    /** 总时长（毫秒） */
    val durationMs: Double
        get() = if (sampleRate <= 0) 0.0 else frameCount * 1000.0 / sampleRate

    /** 当前播放位置（毫秒），单调递增 */
    fun positionMs(): Double {
        if (silent) {
            return if (paused) pausedPositionMs
            else pausedPositionMs + (System.nanoTime() - startedAtNanos) / 1_000_000.0
        }
        if (destroyed || source == 0) return pausedPositionMs

        val offsetFrames = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET).coerceAtLeast(0)
        val queuedInterleaved = queuedSizes.sumOf { it.toLong() }
        val playedInterleaved = (enqueuedSamples - queuedInterleaved).coerceAtLeast(0L)
        val playedFrames = playedInterleaved / channels + offsetFrames
        val totalFrames = frameCount.toLong()
        return playedFrames.coerceIn(0L, totalFrames) * 1000.0 / max(sampleRate, 1)
    }

    fun play() {
        paused = false
        if (silent) {
            startedAtNanos = System.nanoTime()
            return
        }
        if (!destroyed && source != 0) AL10.alSourcePlay(source)
    }

    fun pause() {
        pausedPositionMs = positionMs()
        paused = true
        if (!silent && !destroyed && source != 0) AL10.alSourcePause(source)
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (!silent && !destroyed && source != 0) AL10.alSourcef(source, AL10.AL_GAIN, volume)
    }

    fun stop() {
        if (destroyed) return
        if (!silent && source != 0) {
            AL10.alSourceStop(source)
            while (AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) > 0) {
                AL10.alSourceUnqueueBuffers(source)
            }
            AL10.alDeleteBuffers(buffers)
            AL10.alDeleteSources(intArrayOf(source))
            source = 0
        }
        queuedSizes.clear()
        destroyed = true
    }

    /** 每帧调用：回收并补充缓冲；返回 true 表示播放结束 */
    fun tick(): Boolean {
        if (silent) return positionMs() >= durationMs
        if (destroyed || source == 0) return true

        val processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        repeat(processed) {
            val freed = AL10.alSourceUnqueueBuffers(source)
            if (queuedSizes.isNotEmpty()) queuedSizes.removeFirst()
            uploadChunk(freed)
        }
        val state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
        if (state != AL10.AL_PLAYING && !paused) {
            val nothingQueued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) == 0
            if (nothingQueued && !pcm.hasRemaining()) return true
            AL10.alSourcePlay(source)
        }
        return false
    }

    private fun uploadChunk(bufferId: Int) {
        if (!pcm.hasRemaining()) return
        val count = min(CHUNK_FRAMES * channels, pcm.remaining())
        val copy = ShortArray(count)
        pcm.get(copy)
        val direct = MemoryUtil.memAllocShort(count)
        try {
            direct.put(copy).flip()
            AL10.alBufferData(
                bufferId,
                if (channels == 1) AL10.AL_FORMAT_MONO16 else AL10.AL_FORMAT_STEREO16,
                direct,
                sampleRate,
            )
        } finally {
            MemoryUtil.memFree(direct)
        }
        AL10.alSourceQueueBuffers(source, bufferId)
        queuedSizes.addLast(count)
        enqueuedSamples += count
    }

    companion object {
        /** 每块缓冲帧数，约 200ms @44.1kHz */
        private const val CHUNK_FRAMES = 8820

        /** 预排缓冲数 */
        private const val BUFFER_COUNT = 4

        data class DecodedPcm(val pcm: ShortBuffer, val sampleRate: Int, val channels: Int)

        fun create(path: Path, silent: Boolean = false): OpenAlPlayback {
            val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
            val decoded = when (ext) {
                "ogg" -> decodeOgg(Files.readAllBytes(path))
                "wav" -> decodeWav(path)
                "mp3" -> Mp3Decoder.decode(path)
                else -> throw IllegalArgumentException("不支持的音频格式：.$ext（支持 ogg / wav / mp3）")
            }
            return createFrom(decoded, silent)
        }

        fun createFrom(decoded: DecodedPcm, silent: Boolean = false): OpenAlPlayback {
            val playback = OpenAlPlayback(decoded.pcm, decoded.sampleRate, decoded.channels, silent)
            if (!silent) {
                playback.source = AL10.alGenSources()
                playback.buffers = IntArray(BUFFER_COUNT) { AL10.alGenBuffers() }
                for (i in 0 until BUFFER_COUNT) playback.uploadChunk(playback.buffers[i])
                AL10.alSourcei(playback.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
            }
            return playback
        }

        private fun decodeOgg(bytes: ByteArray): DecodedPcm {
            val direct = MemoryUtil.memAlloc(bytes.size)
            direct.put(bytes).flip()
            val error = IntArray(1)
            val decoder = STBVorbis.stb_vorbis_open_memory(direct, error, null)
            if (decoder == 0L) {
                MemoryUtil.memFree(direct)
                throw IllegalStateException("OGG 解码失败（错误码 ${error[0]}）")
            }
            try {
                val info = STBVorbisInfo.malloc()
                STBVorbis.stb_vorbis_get_info(decoder, info)
                val ch = info.channels()
                val rate = info.sample_rate()
                info.free()
                val frames = STBVorbis.stb_vorbis_stream_length_in_samples(decoder)
                val pcm = ShortBuffer.allocate(max(frames, 0) * ch)
                val chunk = MemoryUtil.memAllocShort(CHUNK_FRAMES * ch)
                try {
                    while (true) {
                        chunk.clear()
                        val read = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, ch, chunk)
                        if (read <= 0) break
                        val copy = ShortArray(read * ch)
                        chunk.position(0)
                        chunk.get(copy)
                        pcm.put(copy)
                    }
                } finally {
                    MemoryUtil.memFree(chunk)
                }
                pcm.flip()
                return DecodedPcm(pcm, rate, ch)
            } finally {
                STBVorbis.stb_vorbis_close(decoder)
                MemoryUtil.memFree(direct)
            }
        }

        private fun decodeWav(path: Path): DecodedPcm {
            AudioSystem.getAudioInputStream(path.toFile()).use { ais ->
                val format = ais.format
                require(format.sampleSizeInBits == 16) {
                    "只支持 16bit PCM WAV，当前 ${format.sampleSizeInBits}bit"
                }
                val raw = readAll(ais)
                val shorts = ShortArray(raw.size / 2)
                var j = 0
                var i = 0
                while (i + 1 < raw.size) {
                    shorts[j++] = ((raw[i + 1].toInt() shl 8) or (raw[i].toInt() and 0xFF)).toShort()
                    i += 2
                }
                return DecodedPcm(ShortBuffer.wrap(shorts, 0, j), format.sampleRate.toInt(), format.channels)
            }
        }

        private fun readAll(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
