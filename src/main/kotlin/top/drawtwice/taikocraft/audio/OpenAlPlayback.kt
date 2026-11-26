package top.drawtwice.taikocraft.audio

import org.lwjgl.openal.AL10
import org.lwjgl.openal.AL11
import org.lwjgl.stb.STBVorbis
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioSystem
import kotlin.math.max
import kotlin.math.min

/**
 * 单首歌曲的 OpenAL 流式播放器。
 *
 * 设计要点：
 * - 自建 OpenAL source（不占用 Minecraft 的音效通道），用 `alSourceQueueBuffers` 多缓冲流式喂数据
 * - 音频先完整解码进内存（16bit PCM，3 分钟立体声约 30MB，可接受）
 * - 播放位置用 `AL_SAMPLE_OFFSET`（当前缓冲内偏移）+ 已播完缓冲数累计，精度高于 `AL_SEC_OFFSET`
 * - 无音频设备时退化为 silent 模式（系统时钟推进），便于无头环境与单测
 *
 * 线程：所有方法必须在持有 OpenAL 上下文的线程（Minecraft sound 线程）调用。
 */
class OpenAlPlayback private constructor(
    private val pcm: ShortBuffer,
    private val sampleRate: Int,
    private val channels: Int,
    private val silent: Boolean,
) {
    private var source = 0
    private var buffers = IntArray(0)
    private var nextSampleIndex = 0
    private var scheduledBuffers = 0
    private var startedAtNanos = 0L
    private var paused = false
    private var pausedPositionMs = 0.0
    private var destroyed = false

    private val bytesPerSample = 2
    private val frameCount = if (channels > 0) pcm.limit() / channels else 0

    /** 当前播放位置（毫秒） */
    fun positionMs(): Double {
        if (silent) {
            return if (paused) pausedPositionMs
            else pausedPositionMs + (System.nanoTime() - startedAtNanos) / 1_000_000.0
        }
        if (destroyed || source == 0) return pausedPositionMs
        val offsetSamples = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET)
        val queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED)
        val consumedBuffers = scheduledBuffers - queued
        val samplesPlayed = consumedBuffers.toLong() * chunkSamples() + offsetSamples
        return samplesPlayed * 1000.0 / max(sampleRate, 1)
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
            scheduledBuffers--
            uploadChunk(freed)
        }
        val state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE)
        if (state != AL10.AL_PLAYING && !paused) {
            if (nextSampleIndex >= pcm.limit() && AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED) == 0) return true
            AL10.alSourcePlay(source)
        }
        return false
    }

    private fun chunkSamples(): Int = min(CHUNK_FRAMES, frameCount).toLong().coerceAtLeast(1).toInt()

    private fun uploadChunk(bufferId: Int) {
        if (nextSampleIndex >= pcm.limit()) return
        val count = min(chunkSamples() * channels, pcm.limit() - nextSampleIndex)
        val copy = ShortArray(count)
        pcm.position(nextSampleIndex)
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
        nextSampleIndex += count
        scheduledBuffers++
    }

    companion object {
        /** 每块缓冲的帧数，约 200ms @44.1kHz */
        private const val CHUNK_FRAMES = 8820

        /** 预排缓冲数 */
        private const val BUFFER_COUNT = 3

        data class DecodedPcm(val pcm: ShortBuffer, val sampleRate: Int, val channels: Int)

        /**
         * 从文件创建。Phase 1 支持 .ogg（STB Vorbis）与 .wav（javax.sound，16bit PCM）。
         * @param silent 无音频设备时用静默模式
         */
        fun create(path: Path, silent: Boolean = false): OpenAlPlayback {
            val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
            val decoded = when (ext) {
                "ogg" -> decodeOgg(Files.readAllBytes(path))
                "wav" -> decodeWav(path)
                else -> throw IllegalArgumentException("不支持的音频格式：.$ext（Phase 1 仅支持 ogg / wav）")
            }
            return createFrom(decoded, silent)
        }

        fun createFrom(decoded: DecodedPcm, silent: Boolean = false): OpenAlPlayback {
            val playback = OpenAlPlayback(decoded.pcm, decoded.sampleRate, decoded.channels, silent)
            if (!silent) {
                playback.source = AL10.alGenSources()
                val needed = min(BUFFER_COUNT, max(1, decoded.pcm.limit() / (CHUNK_FRAMES * decoded.channels)))
                playback.buffers = IntArray(needed)
                for (i in 0 until needed) playback.buffers[i] = AL10.alGenBuffers()
                for (i in 0 until needed) playback.uploadChunk(playback.buffers[i])
                AL10.alSourcei(playback.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE)
            }
            return playback
        }

        private fun decodeOgg(bytes: ByteArray): DecodedPcm {
            val direct = MemoryUtil.memAlloc(bytes.size).put(bytes).flip() as ByteBuffer
            MemoryStack.stackPush().use { stack ->
                val error = stack.mallocInt(1)
                val decoder = STBVorbis.stb_vorbis_open_memory(direct, error, null)
                if (decoder == 0L) {
                    MemoryUtil.memFree(direct)
                    throw IllegalStateException("OGG 解码失败（错误码 ${error.get(0)}）")
                }
                try {
                    val info = STBVorbis.stb_vorbis_get_info(decoder)
                    val channels = info.channels()
                    val rate = info.sample_rate()
                    val frames = STBVorbis.stb_vorbis_stream_length_in_samples(decoder)
                    val pcm = ShortBuffer.allocate(max(frames, 0) * channels)
                    // 缓冲区分配在栈外，循环内复用
                    val chunk = org.lwjgl.system.MemoryUtil.memAllocShort(CHUNK_FRAMES * channels)
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
                        org.lwjgl.system.MemoryUtil.memFree(chunk)
                    }
                    pcm.flip()
                    return DecodedPcm(pcm, rate, channels)
                } finally {
                    STBVorbis.stb_vorbis_close(decoder)
                    MemoryUtil.memFree(direct)
                }
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
