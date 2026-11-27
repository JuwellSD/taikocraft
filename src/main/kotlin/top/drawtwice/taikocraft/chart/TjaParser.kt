package top.drawtwice.taikocraft.chart

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TJA 谱面解析器。
 *
 * ## 时间轴模型（对齐 OpenTaiko CTja.cs）
 * ```
 * 预处理：
 *   不含逗号的行 → divPerMeasure += 本行字母数字数（跨行累加）
 *   含逗号的行   → divsPerMeasure.Add(acc + 本行字母数字数); acc = 0
 *
 * 每格时长 = (15000/BPM × s/m) × 16 / 本小节div数
 * ```
 *
 * ## 关键修正记录
 * 1. **行尾注释必须剥离**：`22222222,//55` 里的 `55` 会被当成两个连打起
 * 2. **逗号不推进时间**：小节时长已由字符推进累计完成
 * 3. **OFFSET 不在这里处理**：由 PlaybackClock 统一叠加，避免双重计算
 */
object TjaParser {

    private val HEADER_KEYS = setOf(
        "TITLE", "SUBTITLE", "BPM", "WAVE", "OFFSET", "DEMOSTART", "COURSE", "LEVEL",
        "GENRE", "BALLOON", "BALLOONNOR", "BALLOONEXP", "BALLOONMAS",
        "SCOREMODE", "SCOREINIT", "SCOREDIFF", "STYLE", "SONGVOL", "SEVOL",
        "MOVIEOFFSET", "PREIMAGE", "MAKER", "LYRICS", "SIDE", "LIFE",
    )

    fun parse(text: String, defaultAudioName: String? = null): TjaChartFile {
        val blocks = splitByCourse(text)
        val header = parseHeader(blocks.firstOrNull()?.headerPart ?: "")

        val charts = blocks.map { parseChartBlock(header + parseHeader(it.headerPart), it.body, it.courseName) }
        require(charts.isNotEmpty()) { "TJA 里没有任何谱面块" }

        return TjaChartFile(
            title = header["TITLE"] ?: "",
            subtitle = header["SUBTITLE"] ?: "",
            audioFileName = header["WAVE"] ?: defaultAudioName,
            demostartMs = (header["DEMOSTART"]?.toDoubleOrNull() ?: 0.0) * 1000.0,
            genre = header["GENRE"] ?: "",
            charts = charts,
        )
    }

    fun parse(bytes: ByteArray, defaultAudioName: String? = null): TjaChartFile =
        parse(decodeAny(bytes), defaultAudioName)

    fun decodeAny(bytes: ByteArray): String {
        val stripped = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) bytes.copyOfRange(3, bytes.size) else bytes

        for (cs in listOf(Charsets.UTF_8, Charset.forName("MS932"), Charset.forName("GBK"))) {
            try {
                val decoder = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return decoder.decode(ByteBuffer.wrap(stripped)).toString()
            } catch (_: CharacterCodingException) {
                // 换下一个
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------

    private class Block(val courseName: String, val headerPart: String, val body: String)

    private fun splitByCourse(text: String): List<Block> {
        val preamble = StringBuilder()
        val blocks = mutableListOf<Block>()
        var curCourse: String? = null
        val curHeader = StringBuilder()
        val curBody = StringBuilder()
        var inBody = false
        var sawCourse = false

        fun finish() {
            if (curCourse != null) blocks += Block(curCourse!!, curHeader.toString(), curBody.toString())
            curCourse = null
            curHeader.setLength(0); curBody.setLength(0); inBody = false
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            val up = line.uppercase()
            if (up.startsWith("COURSE:")) {
                finish(); sawCourse = true
                curCourse = line.substringAfter(':').trim(); continue
            }
            if (up.startsWith("#START")) { inBody = true; continue }
            if (up == "#END" || up.startsWith("#END ")) { inBody = false; continue }
            if (curCourse == null) { preamble.appendLine(raw); continue }

            val isHeader = line.isNotEmpty() && line.contains(':') &&
                HEADER_KEYS.contains(line.substringBefore(':').trim().uppercase())
            if (inBody || !isHeader) {
                if (!isHeader && line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("//")) {
                    inBody = true
                }
                curBody.appendLine(raw)
            } else {
                curHeader.appendLine(raw)
            }
        }
        finish()

        if (!sawCourse) return listOf(Block("Oni", "", preamble.toString()))
        if (blocks.isNotEmpty()) {
            val f = blocks.first()
            blocks[0] = Block(f.courseName, preamble.toString() + f.headerPart, f.body)
        }
        return blocks
    }

    private fun parseHeader(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!line.contains(':')) continue
            val key = line.substringBefore(':').trim().uppercase()
            if (key !in HEADER_KEYS) continue
            map[key] = line.substringAfter(':').trim()
        }
        return map
    }

    // ------------------------------------------------------------------

    private fun parseChartBlock(header: Map<String, String>, body: String, courseName: String): Chart {
        val initialBpm = header["BPM"]?.toDoubleOrNull() ?: 120.0
        val offsetMs = (header["OFFSET"]?.toDoubleOrNull() ?: 0.0) * 1000.0
        val level = header["LEVEL"]?.toDoubleOrNull()?.toInt() ?: 0
        val balloonField = (header["BALLOON"] ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }

        val notes = mutableListOf<Note>()
        val rolls = mutableListOf<RollSegment>()
        val balloons = mutableListOf<BalloonSegment>()
        val barLines = mutableListOf<BarLine>()

        var currentBpm = initialBpm
        var measureS = 4.0
        var measureM = 4.0
        var currentTimeMs = 0.0
        var delayMs = 0.0
        var balloonIndex = 0
        var openRollStart = Double.NaN
        var openRollBig = false
        var openBalloonStart = Double.NaN
        var branchDepth = 0
        var activeBranch = 'N'

        // ---- 预处理：统计每小节 div 数（OpenTaiko CTja.cs:1670-1700）----
        val divsPerMeasure = mutableListOf<Int>()
        run {
            var acc = 0
            for (raw in body.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
                val data = line.substringBefore("//").substringBefore('#')
                val cnt = data.count { it.isLetterOrDigit() }
                if (',' !in data) acc += cnt
                else { divsPerMeasure += acc + cnt; acc = 0 }
            }
            divsPerMeasure += acc
        }

        var divIdx = 0
        var measureDivs = (divsPerMeasure.getOrNull(0) ?: 0).coerceAtLeast(1)
        var measureChars = 0

        fun toAudio(pos: Double): Double = pos + delayMs

        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) continue

            if (line.startsWith("#")) {
                val up = line.uppercase()
                when {
                    up.startsWith("#BPMCHANGE") ->
                        currentBpm = line.substringAfter("#BPMCHANGE", "").trim().toDoubleOrNull() ?: currentBpm
                    up.startsWith("#MEASURE") -> {
                        val p = line.substringAfter("#MEASURE", "").trim().split('/')
                        if (p.size == 2) {
                            val s = p[0].toDoubleOrNull(); val m = p[1].toDoubleOrNull()
                            if (s != null && m != null && m != 0.0 && s > 0) { measureS = s; measureM = m }
                        }
                    }
                    up.startsWith("#DELAY") ->
                        delayMs += (line.substringAfter("#DELAY", "").trim().toDoubleOrNull() ?: 0.0) * 1000.0
                    up.startsWith("#BRANCHSTART") -> { branchDepth++; activeBranch = 'N' }
                    up == "#N" || up == "#E" || up == "#M" -> activeBranch = up[1]
                    up.startsWith("#BRANCHEND") -> { branchDepth--; activeBranch = 'N' }
                }
                continue
            }
            if (branchDepth > 0 && activeBranch != 'N') continue

            val noComment = line.substringBefore("//")
            if (noComment.isEmpty()) continue
            val inlineIdx = noComment.indexOf('#')
            val dataPart = if (inlineIdx >= 0) {
                applyInline(noComment.substring(inlineIdx), { currentBpm = it }, { s, m -> measureS = s; measureM = m })
                noComment.substring(0, inlineIdx)
            } else noComment

            val divMs = 15000.0 / currentBpm * (measureS / measureM)
            for (ch in dataPart) {
                if (ch == ',') {
                    if (measureDivs <= 1 && measureChars == 0) currentTimeMs += divMs * 16.0
                    barLines += BarLine(toAudio(currentTimeMs), currentBpm)
                    divIdx++
                    measureDivs = (divsPerMeasure.getOrNull(divIdx) ?: 0).coerceAtLeast(1)
                    measureChars = 0
                    continue
                }
                if (!ch.isLetterOrDigit()) continue

                val t = toAudio(currentTimeMs)
                when (ch) {
                    '1' -> notes += Note(t, NoteColor.DON, bpmAtNote = currentBpm)
                    '2' -> notes += Note(t, NoteColor.KA, bpmAtNote = currentBpm)
                    '3' -> notes += Note(t, NoteColor.DON, big = true, bpmAtNote = currentBpm)
                    '4' -> notes += Note(t, NoteColor.KA, big = true, bpmAtNote = currentBpm)
                    'A' -> notes += Note(t, NoteColor.DON, big = true, player = 2, bpmAtNote = currentBpm)
                    'B' -> notes += Note(t, NoteColor.KA, big = true, player = 2, bpmAtNote = currentBpm)
                    '5' -> { openRollStart = t; openRollBig = false }
                    '6' -> { openRollStart = t; openRollBig = true }
                    '7', '9' -> openBalloonStart = t
                    '8' -> {
                        if (!openRollStart.isNaN()) {
                            rolls += RollSegment(openRollStart, t, openRollBig); openRollStart = Double.NaN
                        } else if (!openBalloonStart.isNaN()) {
                            balloons += BalloonSegment(openBalloonStart, t, balloonField.getOrNull(balloonIndex) ?: 5)
                            balloonIndex++; openBalloonStart = Double.NaN
                        }
                    }
                }
                currentTimeMs += divMs * 16.0 / measureDivs
                measureChars++
            }
        }

        return Chart(
            course = courseNumber(courseName), courseName = courseName, level = level,
            bpm = initialBpm, offsetMs = offsetMs,
            notes = notes.sortedBy { it.timeMs },
            rolls = rolls.sortedBy { it.startMs },
            balloons = balloons.sortedBy { it.startMs },
            barLines = barLines,
        )
    }

    private fun applyInline(
        rest: String, onBpm: (Double) -> Unit, onMeasure: (Double, Double) -> Unit,
    ) {
        val up = rest.uppercase()
        when {
            up.startsWith("#BPMCHANGE") ->
                rest.substringAfter("#BPMCHANGE").trim().toDoubleOrNull()?.let(onBpm)
            up.startsWith("#MEASURE") -> {
                val p = rest.substringAfter("#MEASURE").trim().split('/')
                if (p.size == 2) {
                    val s = p[0].toDoubleOrNull(); val m = p[1].toDoubleOrNull()
                    if (s != null && m != null && m != 0.0 && s > 0) onMeasure(s, m)
                }
            }
        }
    }

    private fun courseNumber(name: String): Int = when (name.trim().lowercase()) {
        "easy", "0" -> 0
        "normal", "1" -> 1
        "hard", "2" -> 2
        "oni", "3", "ura", "extreme" -> 3
        "edit", "4", "tower" -> 4
        else -> 3
    }
}
