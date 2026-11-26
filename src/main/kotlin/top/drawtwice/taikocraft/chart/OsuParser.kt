package top.drawtwice.taikocraft.chart

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * osu! 谱面（`.osu`）解析 + `.osz` 曲包导入。
 *
 * 关键映射（与太鼓官方一致）：
 * - 圆圈 + Normal/无 hitsound        → 小咚
 * - 圆圈 + Whistle 或 Clap           → 小咔
 * - 上述 + Finish                    → 大音符（颜色同上）
 * - Slider                           → 连打（RollSegment）
 * - **Spinner（拨浪鼓 / 转盘）        → 气球（BalloonSegment）** ← 坠尔明确要求
 *
 * osz 是 ZIP：内含音频 + 每难度一个 `.osu`。本解析器只做**纯数据**解析，
 * 不写磁盘（落盘与音频解码由上层 [top.drawtwice.taikocraft.chart.SongImporter] 负责）。
 */
object OsuParser {

    /** osu hitsound 位 */
    private const val HIT_NORMAL = 1
    private const val HIT_WHISTLE = 2
    private const val HIT_FINISH = 4
    private const val HIT_CLAP = 8

    /** HitObject type 位 */
    private const val TYPE_CIRCLE = 1
    private const val TYPE_SLIDER = 2
    private const val TYPE_SPINNER = 8

    data class OsuBeatmap(
        val title: String,
        val artist: String,
        val version: String,       // 难度名
        val creator: String,
        val audioFileName: String?,
        val mode: Int,
        val overallDifficulty: Double,
        val chart: Chart,
    )

    data class OszContents(
        val beatmaps: List<OsuBeatmap>,
        /** 包内相对路径 → 字节（仅白名单扩展名，防止解压炸弹/路径穿越） */
        val files: Map<String, ByteArray>,
    )

    /**
     * 解析 `.osu` 文本。
     *
     * @param defaultAudioName 若谱面未写 `AudioFilename`，用这个（通常取自 osz 包内音频）
     */
    fun parseOsu(text: String, defaultAudioName: String? = null): OsuBeatmap {
        val general = mutableMapOf<String, String>()
        val metadata = mutableMapOf<String, String>()
        val difficulty = mutableMapOf<String, String>()

        var section = ""
        val timingPoints = mutableListOf<String>()
        val hitObjects = mutableListOf<String>()

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1)
                continue
            }
            when (section) {
                "General" -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let {
                    general[it[0].trim()] = it[1].trim()
                }
                "Metadata" -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let {
                    metadata[it[0].trim()] = it[1].trim()
                }
                "Difficulty" -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let {
                    difficulty[it[0].trim()] = it[1].trim()
                }
                "TimingPoints" -> timingPoints += line
                "HitObjects" -> hitObjects += line
            }
        }

        val mode = general["Mode"]?.toIntOrNull() ?: 0
        val od = difficulty["OverallDifficulty"]?.toDoubleOrNull() ?: 5.0

        // 时间轴：取第一条 uninherited（红线）TimingPoint 的 BPM 作为初始 BPM
        val firstTiming = timingPoints.firstOrNull { tp ->
            val parts = tp.split(',')
            parts.size >= 2 && (parts[1].toDoubleOrNull() ?: 0.0) > 0.0
        }
        val beatLength = firstTiming?.split(',')?.getOrNull(1)?.toDoubleOrNull() ?: 500.0
        val bpm = if (beatLength > 0) 60000.0 / beatLength else 120.0

        val notes = mutableListOf<Note>()
        val rolls = mutableListOf<RollSegment>()
        val balloons = mutableListOf<BalloonSegment>()

        for (obj in hitObjects) {
            val parts = obj.split(',')
            if (parts.size < 4) continue
            val time = parts[2].toDoubleOrNull() ?: continue
            val type = parts[3].toIntOrNull() ?: continue
            val hitSound = parts.getOrNull(4)?.toIntOrNull() ?: 0

            // 太鼓里颜色由 hitsound 决定：Whistle/Clap = 咔，否则 = 咚
            val color = if (hitSound and (HIT_WHISTLE or HIT_CLAP) != 0) NoteColor.KA else NoteColor.DON
            val big = hitSound and HIT_FINISH != 0

            when {
                type and TYPE_CIRCLE != 0 -> {
                    notes += Note(time, color, big = big)
                }
                type and TYPE_SLIDER != 0 -> {
                    // Slider → 连打。滑块长度字段（第 8 项）为重复次数；
                    // 时长用后续 timingPoints 推算较复杂，Phase 1 用近似 1 拍/重复。
                    val repeats = parts.getOrNull(6)?.toIntOrNull() ?: 1
                    val lengthBeats = parts.getOrNull(7)?.toDoubleOrNull() ?: 100.0
                    val beatMs = 60000.0 / bpm
                    val duration = maxOf(beatMs * repeats, beatMs * (lengthBeats / 100.0))
                    rolls += RollSegment(time, time + duration, big = false)
                }
                type and TYPE_SPINNER != 0 -> {
                    // ★ 拨浪鼓 / 转盘 → 气球
                    // Spinner 行格式：x,y,time,type,hitSound,endTime,...
                    val endTime = parts.getOrNull(5)?.toDoubleOrNull() ?: (time + 1000.0)
                    val duration = (endTime - time).coerceAtLeast(1.0)
                    // 需要击打数：osu!taiko 官方公式 ≈ 时长 × DifficultyRange(OD, 3, 5, 7.5) × 1.65
                    val hitsPerSecond = odRange(od, 3.0, 5.0, 7.5)
                    val required = (duration / 1000.0 * hitsPerSecond * 1.65).toInt().coerceAtLeast(1)
                    balloons += BalloonSegment(time, endTime, required)
                }
            }
        }

        val chart = Chart(
            course = courseFromVersion(metadata["Version"] ?: ""),
            courseName = metadata["Version"] ?: "Oni",
            level = 0,
            bpm = bpm,
            offsetMs = 0.0, // osu 时间轴本身就是音频时间
            notes = notes.sortedBy { it.timeMs },
            rolls = rolls.sortedBy { it.startMs },
            balloons = balloons.sortedBy { it.startMs },
            barLines = emptyList(),
        )

        return OsuBeatmap(
            title = metadata["TitleUnicode"] ?: metadata["Title"] ?: "",
            artist = metadata["ArtistUnicode"] ?: metadata["Artist"] ?: "",
            version = metadata["Version"] ?: "",
            creator = metadata["Creator"] ?: "",
            audioFileName = general["AudioFilename"] ?: defaultAudioName,
            mode = mode,
            overallDifficulty = od,
            chart = chart,
        )
    }

    /**
     * 解析 `.osz`（ZIP）字节。
     * 安全约束：跳过目录项、拒绝绝对路径与 `..`、只接收白名单扩展名，
     * 并限制单文件大小，防止解压炸弹。
     */
    fun parseOsz(bytes: ByteArray): OszContents {
        val allowed = setOf("osu", "ogg", "mp3", "wav")
        val maxFileSize = 128L * 1024 * 1024 // 128MB
        val files = mutableMapOf<String, ByteArray>()
        val parsed = mutableListOf<Pair<String, OsuBeatmap>>() // .osu 路径 → 谱面

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var total = 0L
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (!entry.isDirectory) {
                    val safe = isSafePath(name)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (safe && ext in allowed) {
                        val data = zip.readNBytes(maxFileSize.toInt() + 1)
                        if (data.size <= maxFileSize) {
                            files[name] = data
                            total += data.size
                            if (total > maxFileSize * 4) break // 包总体上限
                            if (ext == "osu") {
                                parsed += name to parseOsu(TjaParser.decodeAny(data))
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // AudioFilename 是相对 .osu 所在目录的 → 解析成 zip 内完整路径
        val audioExts = setOf("ogg", "mp3", "wav")
        val firstAudio = files.keys.firstOrNull { it.substringAfterLast('.', "").lowercase() in audioExts }
        val beatmaps = parsed.map { (osuPath, bm) ->
            val dir = osuPath.substringBeforeLast('/', "")
            val declared = bm.audioFileName
            val resolved = when {
                declared.isNullOrBlank() -> null
                declared in files -> declared
                dir.isNotEmpty() && "$dir/$declared" in files -> "$dir/$declared"
                else -> declared
            } ?: firstAudio
            bm.copy(audioFileName = resolved)
        }
        return OszContents(beatmaps, files)
    }

    private fun isSafePath(name: String): Boolean {
        if (name.startsWith("/") || name.contains("..")) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return false // Windows 盘符
        return true
    }

    /** osu! 的 DifficultyRange 线性插值 */
    private fun odRange(od: Double, min: Double, mid: Double, max: Double): Double = when {
        od > 5.0 -> mid + (max - mid) * (od - 5.0) / 5.0
        od < 5.0 -> mid + (min - mid) * (5.0 - od) / 5.0
        else -> mid
    }

    private fun courseFromVersion(version: String): Int {
        val v = version.lowercase()
        return when {
            "easy" in v || "kantan" in v -> 0
            "normal" in v || "futsuu" in v -> 1
            "hard" in v || "muzukashii" in v -> 2
            "oni" in v || "insane" in v || "extreme" in v -> 3
            else -> 3
        }
    }
}
