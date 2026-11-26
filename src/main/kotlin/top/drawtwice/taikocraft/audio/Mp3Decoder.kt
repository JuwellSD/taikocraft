package top.drawtwice.taikocraft.audio

import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.max

/**
 * MP3 解码（隔离层）。
 *
 * ⚠️ **许可证提示**：mp3spi / JLayer 均为 **LGPL-2.1**。
 * 本文件是唯一接触它们的代码；如需替换解码实现（例如改为 STB 或原生库），
 * 只需替换本文件，其他代码不受影响。
 *
 * 依赖（见 build.gradle）：
 * ```
 * implementation "com.googlecode.soundlibs:mp3spi:1.9.5.4"
 * ```
 */
object Mp3Decoder {

    /**
     * 解码为 16bit PCM。
     * 输出统一转换为 16bit 小端、原始声道数。
     */
    fun decode(bytes: ByteArray): OpenAlPlayback.DecodedPcm {
        val reader = MpegAudioFileReader()
        AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { /* 验证可识别 */ }

        val raw = reader.getAudioInputStream(ByteArrayInputStream(bytes))
        val format = raw.format
        val pcmFormat = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            format.sampleRate,
            16,
            format.channels,
            format.channels * 2,
            format.sampleRate,
            false,
        )
        AudioSystem.getAudioInputStream(pcmFormat, raw).use { pcmStream ->
            val data = pcmStream.readAllBytes()
            val shorts = ShortArray(data.size / 2)
            var i = 0
            var j = 0
            while (i + 1 < data.size) {
                shorts[j++] = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
                i += 2
            }
            return OpenAlPlayback.DecodedPcm(
                ShortBuffer.wrap(shorts, 0, j),
                format.sampleRate.toInt(),
                format.channels,
            )
        }
    }

    fun decode(path: Path): OpenAlPlayback.DecodedPcm =
        decode(java.nio.file.Files.readAllBytes(path))

    @Suppress("unused")
    private fun unused(byteBuffer: ByteBuffer) = max(byteBuffer.capacity(), 0)
}
