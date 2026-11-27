package top.drawtwice.taikocraft.chart

import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * 曲库：扫描 `.minecraft/taikocraft/` 目录。
 *
 * ## 目录结构（坠尔要求放在 .minecraft 根目录，不放 config）
 * ```
 * .minecraft/taikocraft/
 *   ├─ songs/                 <- 曲目目录
 *   │   └─ <曲名>/
 *   │       ├─ chart.tja
 *   │       ├─ song.ogg/mp3/wav
 *   │       └─ xxx.osu
 *   └─ settings.properties    <- 延迟/键位/材质设置
 * ```
 */
object SongLibrary {

    /** 曲库根：.minecraft/taikocraft/ */
    fun root(): Path {
        val gameDir = Minecraft.getInstance().gameDirectory.toPath()
        return gameDir.resolve("taikocraft")
    }

    fun songsDir(): Path = root().resolve("songs")

    fun settingsFile(): Path = root().resolve("settings.properties")

    fun ensureDirs() {
        Files.createDirectories(songsDir())
    }

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
        val index: Int,
    )

    fun listSongs(): List<SongEntry> {
        val dir = songsDir()
        if (!Files.isDirectory(dir)) return emptyList()
        val dirs = Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.toList() }
        return dirs.mapNotNull { scanSong(it) }.sortedBy { it.title }
    }

    private fun scanSong(dir: Path): SongEntry? {
        val files = Files.list(dir).use { it.toList() }
        val audio = files.firstOrNull { it.extension.lowercase() in setOf("ogg", "mp3", "wav") }
        val diffs = mutableListOf<DifficultyEntry>()

        for (f in files) {
            when (f.extension.lowercase()) {
                "tja" -> runCatching {
                    val parsed = TjaParser.parse(Files.readAllBytes(f), audio?.name)
                    parsed.charts.forEachIndexed { i, c ->
                        diffs += DifficultyEntry(c.courseName, c.level, c.course, f, i)
                    }
                }
                "osu" -> runCatching {
                    val bm = OsuParser.parseOsu(TjaParser.decodeAny(Files.readAllBytes(f)), audio?.name)
                    diffs += DifficultyEntry(bm.version.ifBlank { "Oni" }, 0, bm.chart.course, f, 0)
                }
            }
        }
        if (diffs.isEmpty() && audio == null) return null

        val title = diffs.firstOrNull()?.let { d ->
            runCatching {
                when (d.source.extension.lowercase()) {
                    "tja" -> TjaParser.parse(Files.readAllBytes(d.source)).title
                    "osu" -> OsuParser.parseOsu(TjaParser.decodeAny(Files.readAllBytes(d.source))).title
                    else -> dir.name
                }
            }.getOrDefault(dir.name)
        } ?: dir.name

        return SongEntry(dir, title.ifBlank { dir.name }, "", diffs, audio)
    }
}
