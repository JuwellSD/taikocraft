package top.drawtwice.taikocraft.chart

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * 本地曲库：扫描 `config/taikocraft/songs/` 下的曲目目录。
 *
 * 每个曲目目录：
 *   songs/<曲目名>/
 *     ├─ chart.tja                    （或任意 .tja）
 *     ├─ song.ogg / song.wav
 *     └─ meta.json                    （可选：显示名、作者、难度改名）
 *
 * Phase 1 只做读取；曲包编辑器（改名/删难度/重打包）在后续阶段。
 */
class SongLibrary(private val root: Path) {

    /** 一首歌的元信息 + 可用难度 */
    data class SongEntry(
        val directory: Path,
        val title: String,
        val subtitle: String,
        val difficulties: List<DifficultyEntry>,
        val audioFile: Path?,
    ) {
        val displayTitle: String get() = if (subtitle.isBlank()) title else "$title $subtitle"
    }

    data class DifficultyEntry(
        val name: String,
        val level: Int,
        val course: Int,
        val source: Path,
        /** 该难度在源文件中的索引（多难度 TJA / 多 .osu） */
        val index: Int,
    )

    fun listSongs(): List<SongEntry> {
        if (!Files.isDirectory(root)) return emptyList()
        val dirs = Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }.toList()
        }
        return dirs.mapNotNull { scanSong(it) }.sortedBy { it.title }
    }

    private fun scanSong(dir: Path): SongEntry? {
        val files = Files.list(dir).use { it.toList() }
        val audio = files.firstOrNull { it.extension.lowercase() in setOf("ogg", "wav") }
        val difficulties = mutableListOf<DifficultyEntry>()

        for (file in files) {
            when (file.extension.lowercase()) {
                "tja" -> {
                    runCatching {
                        val parsed = TjaParser.parse(Files.readAllBytes(file), audio?.name)
                        parsed.charts.forEachIndexed { i, chart ->
                            difficulties += DifficultyEntry(
                                name = chart.courseName,
                                level = chart.level,
                                course = chart.course,
                                source = file,
                                index = i,
                            )
                        }
                    }
                }
                "osu" -> {
                    runCatching {
                        val bm = OsuParser.parseOsu(TjaParser.decodeAny(Files.readAllBytes(file)))
                        difficulties += DifficultyEntry(
                            name = bm.version.ifBlank { "Oni" },
                            level = 0,
                            course = bm.chart.course,
                            source = file,
                            index = 0,
                        )
                    }
                }
            }
        }

        if (difficulties.isEmpty() && audio == null) return null
        val title = difficulties.firstOrNull()?.let { diff ->
            runCatching {
                when (diff.source.extension.lowercase()) {
                    "tja" -> TjaParser.parse(Files.readAllBytes(diff.source)).title
                    "osu" -> OsuParser.parseOsu(TjaParser.decodeAny(Files.readAllBytes(diff.source))).title
                    else -> dir.name
                }
            }.getOrDefault(dir.name)
        } ?: dir.name

        return SongEntry(
            directory = dir,
            title = title.ifBlank { dir.name },
            subtitle = "",
            difficulties = difficulties,
            audioFile = audio,
        )
    }

    /** 默认曲库根目录 */
    companion object {
        fun defaultRoot(): Path = Path.of("config", "taikocraft", "songs")
    }
}
