package top.drawtwice.taikocraft.chart

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `.osz` 曲包**元数据编辑器**（不是谱面编辑器）。
 *
 * 坠尔的需求（2026-11-27）：osz 里的难度常常很乱，需要能：
 * - 改歌曲名 / 歌手名
 * - 改难度名
 * - 删除特定难度
 *
 * 设计原则：
 * - **不重新编码**：未修改的文件按原始字节写回，只改 `.osu` 里的 Metadata 行
 * - **保留换行风格**：CRLF / LF 原样保留
 * - **保留其余字段**：只动被明确设置的键，其它行不碰
 * - **不写磁盘**：只处理内存字节，落盘由调用方负责
 */
class OszEditor private constructor(
    private val entries: List<Entry>,
) {

    class Entry(
        val name: String,
        val data: ByteArray,
        val isDirectory: Boolean = false,
    )

    /** 一个可编辑的难度 */
    inner class Difficulty internal constructor(
        private val entry: Entry,
    ) {
        val fileName: String get() = entry.name

        private var deleted = false
        private val edits = mutableMapOf<String, String>()
        private val debug = mutableMapOf<String, String>()

        /** 原始元数据（展示用） */
        val parsed: OsuParser.OsuBeatmap by lazy {
            OsuParser.parseOsu(TjaParser.decodeAny(entry.data))
        }

        val originalTitle: String get() = parsed.title
        val originalArtist: String get() = parsed.artist
        val originalVersion: String get() = parsed.version

        val title: String get() = edits["TitleUnicode"] ?: edits["Title"] ?: originalTitle
        val artist: String get() = edits["ArtistUnicode"] ?: edits["Artist"] ?: originalArtist
        val version: String get() = edits["Version"] ?: originalVersion

        val isDeleted: Boolean get() = deleted
        val isModified: Boolean get() = edits.isNotEmpty() || deleted
        val hasEdits: Boolean get() = edits.isNotEmpty()

        fun setTitle(value: String) = apply {
            edits["Title"] = value
            edits["TitleUnicode"] = value
            debug.put("title", value)
        }

        fun setArtist(value: String) = apply {
            edits["Artist"] = value
            edits["ArtistUnicode"] = value
            debug.put("artist", value)
        }

        fun setVersion(value: String) = apply {
            edits["Version"] = value
            debug.put("version", value)
        }

        fun delete() = apply { deleted = true }
        fun restore() = apply { deleted = false }

        internal fun editedBytes(): ByteArray {
            if (edits.isEmpty()) return entry.data
            val text = TjaParser.decodeAny(entry.data)
            return applyEdits(text, edits).toByteArray(Charsets.UTF_8)
        }
    }

    val difficulties: List<Difficulty> by lazy {
        entries.filter { !it.isDirectory && it.name.endsWith(".osu", ignoreCase = true) }
            .map { Difficulty(it) }
    }

    val audioFileName: String? by lazy {
        val exts = setOf("ogg", "mp3", "wav")
        entries.firstOrNull { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in exts }?.name
    }

    /** 导出为新的 osz 字节；被删除的难度不写入 */
    fun export(): ByteArray {
        val byName = difficulties.associateBy { it.fileName }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (entry in entries) {
                if (entry.isDirectory) continue // 目录项由解包方自动创建
                val difficulty = byName[entry.name]
                if (difficulty != null && difficulty.isDeleted) continue
                val payload = if (difficulty != null && difficulty.hasEdits) difficulty.editedBytes() else entry.data
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    companion object {
        fun load(bytes: ByteArray): OszEditor {
            val entries = mutableListOf<Entry>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name.replace('\\', '/')
                    if (isSafePath(name)) {
                        if (e.isDirectory) {
                            entries += Entry(name, ByteArray(0), isDirectory = true)
                        } else {
                            entries += Entry(name, zip.readBytes())
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
            return OszEditor(entries)
        }

        fun load(path: Path): OszEditor = load(Files.readAllBytes(path))

        /**
         * 只改 Metadata 段里指定键的值，其余内容逐字保留（含换行风格）。
         * 键不存在时插入到 [Metadata] 段末尾。
         */
        internal fun applyEdits(text: String, edits: Map<String, String>): String {
            if (edits.isEmpty()) return text
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            val lines = text.split("\n").toMutableList()
            val remaining = LinkedHashMap(edits)

            var inMetadata = false
            var metadataEndIndex = -1

            for (i in lines.indices) {
                val raw = lines[i]
                val line = raw.trimEnd('\r')

                if (line.startsWith("[") && line.endsWith("]")) {
                    if (inMetadata && metadataEndIndex == -1) metadataEndIndex = i
                    inMetadata = line == "[Metadata]"
                    continue
                }
                if (!inMetadata) continue
                if (!line.contains(':')) continue

                val key = line.substringBefore(':').trim()
                val newValue = remaining.remove(key) ?: continue
                lines[i] = "$key:$newValue"
            }

            if (remaining.isNotEmpty()) {
                val insertAt = if (metadataEndIndex >= 0) metadataEndIndex else lines.size
                lines.addAll(insertAt, remaining.map { (k, v) -> "$k:$v" })
            }

            return lines.joinToString(newline)
        }

        private fun isSafePath(name: String): Boolean {
            if (name.startsWith("/") || name.contains("..")) return false
            if (Regex("^[A-Za-z]:").containsMatchIn(name)) return false
            return true
        }
    }
}
