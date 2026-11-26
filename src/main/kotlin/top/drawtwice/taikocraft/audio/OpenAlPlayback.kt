package top.drawtwice.taikocraft.audio

import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.stb.STBVorbis
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
 * ## 播放位置计算（重要）
 * `AL_SAMPLE_OFFSET` 语义是「**当前正在播放的那块缓冲内**的样本偏移」，
 * 缓冲播完换成下一块时会**归零**。所以不能直接拿它当全局位置。
 *
 * 正确算法：自己记录「每次入队时该缓冲的起始样本索引」，
 * 用 `AL_BUFFERS_PROCESSED` 累计已播完的缓冲，即可确定当前正在播哪一块，
 * 它的起点 = 已入队总样本 − 当前队列内所有缓冲样本之和。
 *
 * 更简单可靠的做法（本实现采用）：
 * **位置 = 已入队总样本 − (队列内剩余缓冲的样本总和) + 当前块内偏移**
 *
 * 之前的 bug：`consumedBuffers = scheduledBuffers - queued` 在有 3 块缓冲始终补满时恒为 0，
 * 导致位置每隔一个缓冲周期（~200ms）就跳回起点 —— 表现为音符「前进一段又回到原点」。
 */
class OpenAlPlayback private constructor(
    private val pcm: ShortBuffer,
    private val sampleRate: Int,
    private val channels: Int,
    private val silent: Boolean,
) {
    private var source = 0
    private var buffers = IntArray(0)
    /** 已提交给 OpenAL 的总样本数（交错后） */
    private var enqueuedSamples = 0L
    private var startedAtNanos = 0L
    private var paused = false
    private var pausedPositionMs = 0.0
    private var destroyed = false

    private val frameCount = if (channels > 0) pcm.limit() / channels else 0

    /** 当前播放位置（毫秒） */
    fun positionMs(): Double {
        if (silent) {
            return if (paused) pausedPositionMs
            else pausedPositionMs + (System.nanoTime() - startedAtNanos) / 1_000_000.0
        }
        if (destroyed || source == 0) return pausedPositionMs

        val offsetSamples = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET).coerceAtLeast(0)
        val queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)

        // 队列中剩余缓冲的总样本数：用「已入队总量 − 已播样本」不可靠，
        // 改用「队列数 × 块大小」（最后一块可能更小，单独修正）
        val remainingInQueue = queued.toLong() * chunkSamples()
        val playedSamples = (enqueuedSamples - remainingInQueue).coerceAtLeast(0) + offsetSamples
        val total = frameCount.toLong() * channels
        val clamped = playedSamples.coerceIn(0, total)
        return clamped * 1000.0 / max(sampleRate, 1) / channels
    }

    fun play() {
        paused = false
        if (silent) {
            startedAtNanos = System.nanoTime()
            return
        }
        AL10.alSourcePlay(source)
    }

    fun pause() {
        pausedPositionMs = positionMs()
        paused = true
        if (!silent && source != 0) AL10.alSourcePause(source)
    }

    fun setVolume(volume: Float) {
        if (!silent && source != 0) AL10.alSourcef(source, AL10.AL_GAIN, volume.coerceIn(0f, 1f))
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
        destroyed = true
    }

    /** 每帧调用：回收并补充缓冲；返回是否播放结束 */
    fun tick(): Boolean {
        if (silent) {
            return positionMs() >= frameCount * 1000.0 / max(sampleRate, 1)
        }
        if (destroyed || source == 0) return true

        val processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED)
        repeat(processed) {
            val freed = AL10.alSourceUnqueueBuffers(source)
            uploadChunk(freed)
        }
        val state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
        if (state != AL10.AL_PLAYING && !paused) {
            val nothingQueued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) == 0
            if (nothingQueued && pcm.remaining() == 0) return true
            AL10.alSourcePlay(source)
        }
        return false
    }

    private fun chunkSamples(): Int = min(CHUNK_FRAMES, frameCount).toLong().coerceAtLeast(1).toInt()

    private fun uploadChunk(bufferId: Int) {
        if (!pcm.hasRemaining()) return
        val count = min(CHUNK_FRAMES * channels, pcm.remaining())
        val copy = ShortArray(count)
        pcm.get(copy)
        val direct = MemoryUtil.memAllocShort(count)
        direct.put(copy).flip()
        AL10.alBufferData(
            bufferId,
            if (channels == 1) AL10.AL_FORMAT_MONO16 else AL10.AL_FORMAT_STEREO16,
            direct,
            sampleRate,
        )
        MemoryUtil.memFree(direct)
        AL10.alSourceQueueBuffers(source, bufferId)
        enqueuedSamples += count
    }

    companion object {
        /** 每块缓冲的帧数，约 200ms @44.1kHz */
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
                val info = org.lwjgl.stb.STBVorbisInfo.malloc()
                STBVorbis.stb_vorbis_get_info(decoder, info)
                val channels = info.channels()
                val rate = info.sample_rate()
                info.free()
                val frames = STBVorbis.stb_vorbis_stream_length_in_samples(decoder)
                val pcm = ShortBuffer.allocate(max(frames, 0) * channels)
                val chunk = MemoryUtil.memAllocShort(CHUNK_FRAMES * channels)
                try {
                    while (true) {
                        chunk.clear()
                        val read = STBVorbis.stb_vorbis_get_samples_short_interleaved(decoder, channels, chunk)
                        if (read <= 0) break
                        val copy = ShortArray(read * channels)
                        chunk.position(0)
                        chunk.get(copy)
                        pcm.put(copy)
                    }
                } finally {
                    MemoryUtil.memFree(chunk)
                }
                pcm.flip()
                return DecodedPcm(pcm, rate, channels)
            } finally {
                STBVorbis.stb_vorbis_close(decoder)
                MemoryUtil.memFree(direct)
            }
        }

        private fun decodeWav(path: Path): DecodedPcm {
            AudioSystem.getAudioInputStream(path.toFile()).use { ais ->
                val format = ais.format
                require(format.sampleSizeInBits == 16) { "只支持 16bit PCM WAV，当前 ${format.sampleSizeInBits}bit" }
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
