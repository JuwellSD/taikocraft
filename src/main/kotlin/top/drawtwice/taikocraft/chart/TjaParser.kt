package top.drawtwice.taikocraft.chart

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TJA 谱面解析器。
 *
 * 支持范围（Phase 1）：
 * - 头部字段：TITLE / SUBTITLE / BPM / WAVE / OFFSET / DEMOSTART / COURSE / LEVEL / GENRE / BALLOON
 * - 多难度：一个文件多个 `COURSE:` 块
 * - 小节与音符：1 小咚 / 2 小咔 / 3 大咚 / 4 大咔 / 5·6 连打起 / 7 气球起 / 8 结束 / 9 久寿玉（按气球处理）
 * - 时间轴命令：#BPMCHANGE / #MEASURE / #DELAY / #SCROLL（仅记录不影响时间）
 * - 分支：#BRANCHSTART … #BRANCHEND 只取 `#N`（普通谱面）
 *
 * 时间轴约定（与 OpenTaiko 一致）：
 *   设 T = 仅由小节与 BPM 推算的原始时间（第 0 小节起点 T=0）
 *   音符音频位置 = T + #DELAY − OFFSET×1000
 *   即：正 OFFSET = 谱面相对音频提前；#DELAY 正数 = 后续整体后移。
 *
 * 已知近似（Phase 1 可接受）：小节内部的内联 `#BPMCHANGE/#MEASURE` 从下一条小节起生效，
 * 不做小节内切分。
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

        val charts = mutableListOf<Chart>()
        for (block in blocks) {
            val merged = HashMap(header)
            merged.putAll(parseHeader(block.headerPart))
            charts.add(parseChartBlock(merged, block.body, block.courseName))
        }
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

    /** 从字节解析：先试 UTF-8，失败回退 MS932（日文谱面常见），再不行用 GBK。 */
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
                // 换下一个编码
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    // ---------------------------------------------------------------------
    // 分块：文件头 + 每个 COURSE 块（header 行 / #START…#END 正文）
    // ---------------------------------------------------------------------

    private class Block(
        val courseName: String,
        val headerPart: String,
        val body: String,
    )

    private fun splitByCourse(text: String): List<Block> {
        val preamble = StringBuilder()
        val blocks = mutableListOf<Block>()

        var currentCourse: String? = null
        val currentHeader = StringBuilder()
        val currentBody = StringBuilder()
        var inBody = false
        var sawCourse = false

        fun finish() {
            if (currentCourse != null) {
                blocks += Block(currentCourse!!, currentHeader.toString(), currentBody.toString())
            }
            currentCourse = null
            currentHeader.setLength(0)
            currentBody.setLength(0)
            inBody = false
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            val upper = line.uppercase()

            if (upper.startsWith("COURSE:")) {
                finish()
                sawCourse = true
                currentCourse = line.substringAfter(':').trim()
                continue
            }
            if (upper.startsWith("#START")) { inBody = true; continue }
            if (upper == "#END" || upper.startsWith("#END ")) { inBody = false; continue }

            if (currentCourse == null) {
                preamble.appendLine(raw)
                continue
            }

            // COURSE 之后：判定是头部字段行还是谱面内容
            val isHeaderLine = isHeaderLine(line)
            if (inBody || !isHeaderLine) {
                // 谱面内容（含 # 命令与数据行）；某些 TJA 省略 #START/#END，直接跟内容
                if (!isHeaderLine && line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("//")) {
                    inBody = true
                }
                currentBody.appendLine(raw)
            } else {
                currentHeader.appendLine(raw)
            }
        }
        finish()

        if (!sawCourse) {
            // 完全没有 COURSE：整个文件当一份缺省 Oni 谱面
            return listOf(Block("Oni", "", preamble.toString()))
        }
        // 文件头字段对所有 COURSE 块生效
        if (blocks.isNotEmpty()) {
            val first = blocks.first()
            blocks[0] = Block(first.courseName, preamble.toString() + first.headerPart, first.body)
        }
        return blocks
    }

    private fun isHeaderLine(line: String): Boolean {
        if (!line.contains(':')) return false
        val key = line.substringBefore(':').trim().uppercase()
        return key in HEADER_KEYS
    }

    private fun parseHeader(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (!isHeaderLine(line)) continue
            map[line.substringBefore(':').trim().uppercase()] = line.substringAfter(':').trim()
        }
        return map
    }

    // ---------------------------------------------------------------------
    // 谱面正文
    // ---------------------------------------------------------------------

    private fun parseChartBlock(header: Map<String, String>, body: String, courseName: String): Chart {
        val initialBpm = header["BPM"]?.toDoubleOrNull() ?: 120.0
        val offsetMs = (header["OFFSET"]?.toDoubleOrNull() ?: 0.0) * 1000.0
        val level = header["LEVEL"]?.toDoubleOrNull()?.toInt() ?: 0
        val balloonField = parseBalloonField(header["BALLOON"])

        val notes = mutableListOf<Note>()
        val rolls = mutableListOf<RollSegment>()
        val balloonSegments = mutableListOf<BalloonSegment>()
        val barLines = mutableListOf<BarLine>()

        var currentBpm = initialBpm
        var measureLength = 4.0 / 4.0
        var measureStart = 0.0
        var delayMs = 0.0
        var balloonIndex = 0

        var openRollStart = Double.NaN
        var openRollBig = false
        var openBalloonStart = Double.NaN

        var branchDepth = 0
        var activeBranch = 'N'

        fun toAudioTime(pos: Double): Double = pos + delayMs - offsetMs

        for (rawLine in body.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("//")) continue

            if (line.startsWith("#")) {
                val upper = line.uppercase()
                when {
                    upper.startsWith("#BPMCHANGE") ->
                        currentBpm = line.substringAfter("#BPMCHANGE", "").trim().toDoubleOrNull() ?: currentBpm
                    upper.startsWith("#MEASURE") -> {
                        val parts = line.substringAfter("#MEASURE", "").trim().split('/')
                        if (parts.size == 2) {
                            val n = parts[0].toDoubleOrNull()
                            val m = parts[1].toDoubleOrNull()
                            if (n != null && m != null && m != 0.0) measureLength = n / m
                        }
                    }
                    upper.startsWith("#DELAY") ->
                        delayMs += (line.substringAfter("#DELAY", "").trim().toDoubleOrNull() ?: 0.0) * 1000.0
                    upper.startsWith("#BRANCHSTART") -> { branchDepth++; activeBranch = 'N' }
                    upper == "#N" || upper == "#E" || upper == "#M" -> activeBranch = upper[1]
                    upper.startsWith("#BRANCHEND") -> { branchDepth--; activeBranch = 'N' }
                    else -> Unit // #SCROLL/#GOGO/#BARLINE 等：Phase 1 忽略
                }
                continue
            }

            if (branchDepth > 0 && activeBranch != 'N') continue

            // 逐“小节片段”推进时间轴。
            // 注意：小节时长必须在处理片段之前用「当前」BPM/拍号算出来，
            // 内联 `#BPMCHANGE` 从本片段之后（即下一条片段）才生效。
            val measures = line.split(',')
            var cursor = measureStart
            for (measure in measures) {
                val measureDuration = 240000.0 / currentBpm * measureLength
                val mStart = cursor
                cursor += measureDuration

                val cleaned = stripInlineCommands(
                    measure.trim(),
                    onBpm = { currentBpm = it },
                    onMeasureLength = { measureLength = it },
                )
                if (cleaned.isEmpty()) continue

                val n = cleaned.length
                for ((i, ch) in cleaned.withIndex()) {
                    val time = toAudioTime(mStart + measureDuration * i / n)
                    when (ch) {
                        '1' -> notes += Note(time, NoteColor.DON)
                        '2' -> notes += Note(time, NoteColor.KA)
                        '3' -> notes += Note(time, NoteColor.DON, big = true)
                        '4' -> notes += Note(time, NoteColor.KA, big = true)
                        'A' -> notes += Note(time, NoteColor.DON, big = true, player = 2)
                        'B' -> notes += Note(time, NoteColor.KA, big = true, player = 2)
                        '5' -> { openRollStart = time; openRollBig = false }
                        '6' -> { openRollStart = time; openRollBig = true }
                        '7', '9' -> openBalloonStart = time
                        '8' -> {
                            if (!openRollStart.isNaN()) {
                                rolls += RollSegment(openRollStart, time, openRollBig)
                                openRollStart = Double.NaN
                            } else if (!openBalloonStart.isNaN()) {
                                val required = balloonField.getOrNull(balloonIndex) ?: 5
                                balloonIndex++
                                balloonSegments += BalloonSegment(openBalloonStart, time, required)
                                openBalloonStart = Double.NaN
                            }
                        }
                        // '0' 空 / 'F' ADLIB / 'C' 地雷 / 'D' 导火索 / 'G' 紫：Phase 1 不生成音符
                    }
                }

                // 每条小节末尾都记一条小节线（与音频时间轴对齐）
                barLines += BarLine(toAudioTime(cursor))
            }
            measureStart = cursor
        }

        return Chart(
            course = courseNumber(courseName),
            courseName = courseName,
            level = level,
            bpm = initialBpm,
            offsetMs = offsetMs,
            notes = notes.sortedBy { it.timeMs },
            rolls = rolls.sortedBy { it.startMs },
            balloons = balloonSegments.sortedBy { it.startMs },
            barLines = barLines,
        )
    }

    /**
     * 去掉小节片段里的内联命令（`#BPMCHANGE` / `#MEASURE`），保留音符字符。
     * 命令到本片段末尾为止的内容都丢弃（含其后的文字参数）。
     */
    private fun stripInlineCommands(
        measure: String,
        onBpm: (Double) -> Unit,
        onMeasureLength: (Double) -> Unit,
    ): String {
        if (!measure.contains('#')) return measure
        val sb = StringBuilder()
        var i = 0
        while (i < measure.length) {
            if (measure[i] == '#') {
                val rest = measure.substring(i)
                val upper = rest.uppercase()
                when {
                    upper.startsWith("#BPMCHANGE") ->
                        rest.substringAfter("#BPMCHANGE").trim().toDoubleOrNull()?.let(onBpm)
                    upper.startsWith("#MEASURE") -> {
                        val parts = rest.substringAfter("#MEASURE").trim().split('/')
                        if (parts.size == 2) {
                            val n = parts[0].toDoubleOrNull()
                            val m = parts[1].toDoubleOrNull()
                            if (n != null && m != null && m != 0.0) onMeasureLength(n / m)
                        }
                    }
                }
                break // 命令之后视为行尾
            }
            sb.append(measure[i])
            i++
        }
        return sb.toString()
    }

    private fun parseBalloonField(value: String?): List<Int> =
        value?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

    private fun courseNumber(name: String): Int = when (name.trim().lowercase()) {
        "easy", "0" -> 0
        "normal", "1" -> 1
        "hard", "2" -> 2
        "oni", "3", "ura", "extreme" -> 3
        "edit", "4", "tower" -> 4
        else -> 3
    }
}
