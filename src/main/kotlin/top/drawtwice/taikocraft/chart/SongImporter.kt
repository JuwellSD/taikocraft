package top.drawtwice.taikocraft.chart

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * 曲目导入器：把玩家提供的文件变成曲库目录。
 *
 * 坠尔确认的三条导入规则（2026-11-27）：
 *
 * 1. **`.osz`** —— 本身就是 ZIP，可直接导入
 * 2. **`.tja`** —— 必须**附带音频**（同目录的 `.ogg` / `.mp3` / `.wav`）
 * 3. **压缩包（zip）** —— 内含 `.tja` + 音频（或 `.osz` 也能塞进去）一起导入
 *
 * 导入结果统一落到 `config/taikocraft/songs/<曲名>/`：
 * ```
 * songs/<曲名>/
 *   ├─ chart.tja 或 <原 osu 文件名>
 *   ├─ song.ogg / song.mp3 / song.wav
 *   └─ （可选）其它 .osu
 * ```
 */
object SongImporter {

    private val AUDIO_EXTS = setOf("ogg", "mp3", "wav")
    private val MAX_FILE = 256L * 1024 * 1024

    sealed class Result {
        data class Ok(val songDir: Path, val title: String, val difficulties: Int) : Result()
        data class Fail(val reason: String) : Result()
    }

    /**
     * 导入一个文件（自动识别 .osz / .tja / .zip）。
     *
     * @param source 玩家提供的文件
     * @param audioCompanion 若 source 是单个 .tja，这里传同目录下选中的音频文件
     * @param songsRoot 曲库根目录
     */
    fun import(source: Path, songsRoot: Path, audioCompanion: Path? = null): Result {
        return when (source.extension.lowercase()) {
            "osz" -> importOsz(Files.readAllBytes(source), songsRoot, source.name.removeSuffix(".osz"))
            "tja" -> importSingleTja(source, audioCompanion, songsRoot)
            "zip" -> importZip(Files.readAllBytes(source), songsRoot, source.name.removeSuffix(".zip"))
            else -> Result.Fail("不支持的文件类型：.${source.extension}（支持 .osz / .tja / .zip）")
        }
    }

    // ------------------------------------------------------------------
    // .osz：直接是压缩包，包内是 osu 谱面
    // ------------------------------------------------------------------

    private fun importOsz(bytes: ByteArray, songsRoot: Path, fallbackName: String): Result {
        val contents = runCatching { OsuParser.parseOsz(bytes) }
            .getOrElse { return Result.Fail("osz 解析失败：${it.message}") }

        if (contents.beatmaps.isEmpty()) {
            return Result.Fail("osz 里没有找到 .osu 谱面")
        }
        val audioName = contents.beatmaps.firstNotNullOfOrNull { it.audioFileName }
        val audioBytes = audioName?.let { contents.files[it] }
        if (audioBytes == null) {
            return Result.Fail("osz 里没有找到音频文件（ogg/mp3/wav）")
        }

        val title = contents.beatmaps.first().title.ifBlank { fallbackName }
        val dir = allocateSongDir(songsRoot, title)
        return runCatching {
            Files.createDirectories(dir)
            // 音频：统一命名 song.<ext>（谱面内的 AudioFilename 引用不影响，因为导入时已解析）
            val audioExt = audioName.substringAfterLast('.').lowercase()
            Files.write(dir.resolve("song.$audioExt"), audioBytes)
            // 每个难度一个 .osu
            var count = 0
            for ((i, bm) in contents.beatmaps.withIndex()) {
                val fileName = "${i}_${sanitize(bm.version.ifBlank { "diff$i" })}.osu"
                val original = contents.files.entries.firstOrNull { it.value === audioBytes }
                @Suppress("UNUSED_EXPRESSION") original
                // 找该难度对应的原始 .osu 字节
                val osuBytes = findOsuBytesFor(contents, bm)
                Files.write(dir.resolve(fileName), osuBytes ?: continue)
                count++
            }
            Result.Ok(dir, title, count)
        }.getOrElse { Result.Fail("写入失败：${it.message}") }
    }

    /** 按 Metadata 匹配回原始 .osu 字节 */
    private fun findOsuBytesFor(contents: OsuParser.OszContents, bm: OsuParser.OsuBeatmap): ByteArray? {
        return contents.files.entries
            .filter { it.key.endsWith(".osu", ignoreCase = true) }
            .firstOrNull { (_, data) ->
                val parsed = runCatching { OsuParser.parseOsu(TjaParser.decodeAny(data)) }.getOrNull()
                parsed != null &&
                    parsed.version == bm.version &&
                    parsed.title == bm.title &&
                    parsed.creator == bm.creator
            }?.value
    }

    // ------------------------------------------------------------------
    // .tja：必须附带音频
    // ------------------------------------------------------------------

    private fun importSingleTja(tja: Path, audio: Path?, songsRoot: Path): Result {
        if (audio == null || !Files.isRegularFile(audio)) {
            return Result.Fail(
                "TJA 需要附带音频文件（.ogg / .mp3 / .wav）。\n" +
                    "请把音频和 .tja 放在同一目录，或把两者打进一个 zip 一起导入。",
            )
        }
        if (audio.extension.lowercase() !in AUDIO_EXTS) {
            return Result.Fail("音频格式不支持：.${audio.extension}（支持 ogg / mp3 / wav）")
        }
        val parsed = runCatching { TjaParser.parse(Files.readAllBytes(tja), audio.name) }
            .getOrElse { return Result.Fail("TJA 解析失败：${it.message}") }
        if (parsed.charts.isEmpty()) return Result.Fail("TJA 里没有谱面块")

        val title = parsed.title.ifBlank { tja.name.removeSuffix(".tja") }
        val dir = allocateSongDir(songsRoot, title)
        return runCatching {
            Files.createDirectories(dir)
            Files.copy(tja, dir.resolve("chart.tja"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val ext = audio.extension.lowercase()
            Files.copy(audio, dir.resolve("song.$ext"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Result.Ok(dir, title, parsed.charts.size)
        }.getOrElse { Result.Fail("写入失败：${it.message}") }
    }

    // ------------------------------------------------------------------
    // .zip：内含 tja + 音频，或内含 osz
    // ------------------------------------------------------------------

    private fun importZip(bytes: ByteArray, songsRoot: Path, fallbackName: String): Result {
        val tjas = mutableMapOf<String, ByteArray>()
        val audios = mutableMapOf<String, ByteArray>()
        val oszInside = mutableMapOf<String, ByteArray>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            var total = 0L
            while (e != null) {
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory && isSafe(name)) {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_FILE * 2) return Result.Fail("压缩包过大")
                    when (ext) {
                        "tja" -> tjas[name] = data
                        in AUDIO_EXTS -> audios[name] = data
                        "osz" -> oszInside[name] = data
                    }
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }

        // 内含 osz → 优先按 osz 处理
        oszInside.entries.firstOrNull()?.let { (name, data) ->
            return importOsz(data, songsRoot, name.substringAfterLast('/').removeSuffix(".osz"))
        }

        if (tjas.isEmpty()) {
            return Result.Fail("压缩包里没有 .tja 谱面（也没有 .osz）")
        }
        if (audios.isEmpty()) {
            return Result.Fail("压缩包里没有音频文件（ogg / mp3 / wav）")
        }

        // 优先取与 tja 同目录的音频
        val (tjaName, tjaBytes) = tjas.entries.first()
        val tjaDir = tjaName.substringBeforeLast('/', "")
        val audioEntry = audios.entries.firstOrNull { (n, _) -> n.substringBeforeLast('/', "") == tjaDir }
            ?: audios.entries.first()

        val parsed = runCatching { TjaParser.parse(tjaBytes, audioEntry.key.substringAfterLast('/')) }
            .getOrElse { return Result.Fail("TJA 解析失败：${it.message}") }
        if (parsed.charts.isEmpty()) return Result.Fail("TJA 里没有谱面块")

        val title = parsed.title.ifBlank { fallbackName }
        val dir = allocateSongDir(songsRoot, title)
        return runCatching {
            Files.createDirectories(dir)
            Files.write(dir.resolve("chart.tja"), tjaBytes)
            // 多余难度文件也一并写入（同一压缩包内的其它 .tja）
            for ((i, entry) in tjas.entries.withIndex()) {
                if (i == 0) continue
                Files.write(dir.resolve("extra_$i.tja"), entry.value)
            }
            val audioExt = audioEntry.key.substringAfterLast('.', "").lowercase()
            Files.write(dir.resolve("song.$audioExt"), audioEntry.value)
            Result.Ok(dir, title, tjas.size)
        }.getOrElse { Result.Fail("写入失败：${it.message}") }
    }

    // ------------------------------------------------------------------

    /** 分配一个不冲突的曲目目录：<songsRoot>/<曲名>，已存在则加 _2、_3 */
    private fun allocateSongDir(songsRoot: Path, title: String): Path {
        val base = sanitize(title.ifBlank { "未命名曲目" })
        var candidate = songsRoot.resolve(base)
        var i = 2
        while (Files.exists(candidate)) {
            candidate = songsRoot.resolve("${base}_$i")
            i++
        }
        return candidate
    }

    /** 去掉文件系统不允许的字符 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().take(80).ifBlank { "未命名" }

    private fun isSafe(name: String): Boolean {
        if (name.startsWith("/") || name.contains("..")) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return false
        return true
    }
}
