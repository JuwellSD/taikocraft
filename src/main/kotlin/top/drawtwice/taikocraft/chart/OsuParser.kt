package top.drawtwice.taikocraft.chart

/**
 * osu! `.osu` / `.osz` 解析。
 *
 * 映射规则（太鼓官方）：
 * - 圆圈 + Normal → 小咚
 * - 圆圈 + Whistle/Clap → 小咔
 * - 加 Finish → 大音符
 * - Slider → 连打
 * - **Spinner（拨浪鼓）→ 气球**
 */
object OsuParser {

    private const val HIT_WHISTLE = 2
    private const val HIT_FINISH = 4
    private const val HIT_CLAP = 8
    private const val TYPE_CIRCLE = 1
    private const val TYPE_SLIDER = 2
    private const val TYPE_SPINNER = 8

    data class OsuBeatmap(
        val title: String,
        val artist: String,
        val version: String,
        val creator: String,
        val audioFileName: String?,
        val mode: Int,
        val chart: Chart,
    )

    fun parseOsu(text: String, defaultAudio: String? = null): OsuBeatmap {
        val general = mutableMapOf<String, String>()
        val meta = mutableMapOf<String, String>()
        val diff = mutableMapOf<String, String>()
        var section = ""
        val timing = mutableListOf<String>()
        val objects = mutableListOf<String>()

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) continue
            if (line.startsWith("[") && line.endsWith("]")) { section = line.substring(1, line.length - 1); continue }
            when (section) {
                "General" -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let { general[it[0].trim()] = it[1].trim() }
                "Metadata" -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let { meta[it[0].trim()] = it[1].trim() }
                "Difficulty" -> line.split(':', limit = 2).takeIf { it.size == 2 }?.let { diff[it[0].trim()] = it[1].trim() }
                "TimingPoints" -> timing += line
                "HitObjects" -> objects += line
            }
        }

        val od = diff["OverallDifficulty"]?.toDoubleOrNull() ?: 5.0
        val firstTiming = timing.firstOrNull { tp ->
            val p = tp.split(','); p.size >= 2 && (p[1].toDoubleOrNull() ?: 0.0) > 0.0
        }
        val beatLength = firstTiming?.split(',')?.getOrNull(1)?.toDoubleOrNull() ?: 500.0
        val bpm = if (beatLength > 0) 60000.0 / beatLength else 120.0

        val notes = mutableListOf<Note>()
        val rolls = mutableListOf<RollSegment>()
        val balloons = mutableListOf<BalloonSegment>()

        for (obj in objects) {
            val p = obj.split(',')
            if (p.size < 4) continue
            val time = p[2].toDoubleOrNull() ?: continue
            val type = p[3].toIntOrNull() ?: continue
            val sound = p.getOrNull(4)?.toIntOrNull() ?: 0
            val color = if (sound and (HIT_WHISTLE or HIT_CLAP) != 0) NoteColor.KA else NoteColor.DON
            val big = sound and HIT_FINISH != 0

            when {
                type and TYPE_CIRCLE != 0 -> notes += Note(time, color, big = big, bpmAtNote = bpm)
                type and TYPE_SLIDER != 0 -> {
                    val repeats = p.getOrNull(6)?.toIntOrNull() ?: 1
                    val length = p.getOrNull(7)?.toDoubleOrNull() ?: 100.0
                    val beatMs = 60000.0 / bpm
                    rolls += RollSegment(time, time + maxOf(beatMs * repeats, beatMs * length / 100.0))
                }
                type and TYPE_SPINNER != 0 -> {
                    val end = p.getOrNull(5)?.toDoubleOrNull() ?: (time + 1000.0)
                    val dur = (end - time).coerceAtLeast(1.0)
                    val hps = odRange(od, 3.0, 5.0, 7.5)
                    balloons += BalloonSegment(time, end, (dur / 1000.0 * hps * 1.65).toInt().coerceAtLeast(1))
                }
            }
        }

        return OsuBeatmap(
            title = meta["TitleUnicode"] ?: meta["Title"] ?: "",
            artist = meta["ArtistUnicode"] ?: meta["Artist"] ?: "",
            version = meta["Version"] ?: "",
            creator = meta["Creator"] ?: "",
            audioFileName = general["AudioFilename"] ?: defaultAudio,
            mode = general["Mode"]?.toIntOrNull() ?: 0,
            chart = Chart(
                course = courseFrom(meta["Version"] ?: ""), courseName = meta["Version"] ?: "Oni",
                level = 0, bpm = bpm, offsetMs = 0.0,
                notes = notes.sortedBy { it.timeMs },
                rolls = rolls.sortedBy { it.startMs },
                balloons = balloons.sortedBy { it.startMs },
            ),
        )
    }

    private fun odRange(od: Double, min: Double, mid: Double, max: Double): Double = when {
        od > 5.0 -> mid + (max - mid) * (od - 5.0) / 5.0
        od < 5.0 -> mid + (min - mid) * (5.0 - od) / 5.0
        else -> mid
    }

    private fun courseFrom(v: String): Int {
        val s = v.lowercase()
        return when {
            "easy" in s || "kantan" in s -> 0
            "normal" in s || "futsuu" in s -> 1
            "hard" in s || "muzukashii" in s -> 2
            else -> 3
        }
    }
}
