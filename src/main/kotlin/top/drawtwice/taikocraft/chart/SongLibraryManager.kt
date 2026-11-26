package top.drawtwice.taikocraft.chart

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * 曲库管理：删除曲目、删除单个难度、重命名显示标题。
 *
 * 曲库结构（[SongLibrary] 扫描的）：
 * ```
 * songs/
 *   <曲目目录>/
 *     ├─ chart.tja 或 xxx.tja    ← 可能含多个 COURSE
 *     ├─ xxx.osu                 ← osu 导入时会拆成多个
 *     ├─ song.ogg/mp3/wav
 *     └─ meta.json               ← 本类写：显示标题等元信息
 * ```
 *
 * 删除策略：
 * - **删整首歌**：整个目录移入 `songs/.trash/<名称>_<时间戳>/`（可手动恢复）
 * - **删单个难度**：从 TJA/OSU 中移除对应 COURSE/难度并重写文件
 *   （TJA 多难度共享音频，删一个难度不能删目录）
 */
object SongLibraryManager {

    /** 回收站：不真删，移到 songs/.trash/，避免误操作 */
    fun trashDir(songsRoot: Path): Path = songsRoot.resolve(".trash")

    /**
     * 删除整首曲目（移入回收站，可恢复）。
     * @return 移动后的路径
     */
    fun deleteSong(songsRoot: Path, songDir: Path): Path {
        require(songDir.startsWith(songsRoot)) { "拒绝删除曲库外的目录：$songDir" }
        val trash = trashDir(songsRoot)
        Files.createDirectories(trash)
        val stamp = System.currentTimeMillis()
        var target = trash.resolve("${songDir.name}_$stamp")
        var i = 2
        while (target.exists()) {
            target = trash.resolve("${songDir.name}_${stamp}_$i")
            i++
        }
        Files.move(songDir, target)
        return target
    }

    /** 恢复回收站中的一项 */
    fun restore(songsRoot: Path, trashedDir: Path): Path {
        val target = songsRoot.resolve(trashedDir.name.substringBeforeLast('_'))
        Files.move(trashedDir, target)
        return target
    }

    /** 列出回收站内容 */
    fun listTrash(songsRoot: Path): List<Path> {
        val trash = trashDir(songsRoot)
        if (!Files.isDirectory(trash)) return emptyList()
        return Files.list(trash).use { it.toList() }.filter { Files.isDirectory(it) }
    }

    /**
     * 从 TJA 文件中删除指定 COURSE 块，保留其余。
     *
     * @param courseName 要删除的难度名（如 "Hard"），大小写不敏感
     * @return 删除后剩余的难度名列表
     */
    fun removeTjaCourse(tjaFile: Path, courseName: String): List<String> {
        val bytes = Files.readAllBytes(tjaFile)
        val text = TjaParser.decodeAny(bytes)
        val newText = removeTjaCourseFromText(text, courseName)
        Files.write(tjaFile, newText.toByteArray(Charsets.UTF_8))
        return TjaParser.parse(Files.readAllBytes(tjaFile)).charts.map { it.courseName }
    }

    /**
     * 纯文本操作：删除匹配的 `COURSE:xxx` 块（到下一个 COURSE 或文件末尾）。
     * 可单测。
     */
    fun removeTjaCourseFromText(text: String, courseName: String): String {
        val lines = text.split("\n")
        val out = StringBuilder()
        var skipping = false
        var skippedAny = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.uppercase().startsWith("COURSE:")) {
                val name = trimmed.substringAfter(':').trim()
                skipping = name.equals(courseName, ignoreCase = true)
                if (skipping) skippedAny = true
            }
            if (!skipping) {
                out.append(line).append("\n")
            }
        }
        require(skippedAny) { "没有找到 COURSE: $courseName" }
        return out.toString().trimEnd() + "\n"
    }

    /** 删除单个 .osu 文件（osz 导入时每个难度一个独立文件） */
    fun deleteOsuFile(osuFile: Path) {
        Files.delete(osuFile)
        // 若目录里已经没有谱面了，整个目录也删掉
        val dir = osuFile.parent
        val remaining = Files.list(dir).use { it.toList() }
        val hasChart = remaining.any {
            val ext = it.fileName.toString().substringAfterLast('.', "").lowercase()
            ext == "tja" || ext == "osu"
        }
        if (!hasChart) {
            deleteSongDir(dir)
        }
    }

    private fun deleteSongDir(dir: Path) {
        // 优先移入回收站
        val songsRoot = dir.parent
        if (songsRoot != null && songsRoot.fileName?.toString() == "songs") {
            deleteSong(songsRoot, dir)
        } else {
            dir.toFile().deleteRecursively()
        }
    }

    /** 设置显示标题（写入 meta.json） */
    fun setDisplayTitle(songDir: Path, title: String) {
        val meta = songDir.resolve("meta.json")
        val json = """{"displayTitle":"${title.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        Files.writeString(meta, json)
    }

    /** 读取显示标题（meta.json 优先，否则用 TJA/OSU 的 TITLE） */
    fun readDisplayTitle(songDir: Path): String? {
        val meta = songDir.resolve("meta.json")
        if (!meta.exists()) return null
        return runCatching {
            val text = Files.readString(meta)
            Regex("\"displayTitle\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1)
                ?.replace("\\\\", "\\")?.replace("\\\"", "\"")
        }.getOrNull()
    }
}
