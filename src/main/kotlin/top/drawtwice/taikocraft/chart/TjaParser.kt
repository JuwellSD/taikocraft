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
        /** 拍号分子/分母（OpenTaiko 的 fNow_Measure_s / fNow_Measure_m），默认 4/4 */
        var measureS = 4.0
        var measureM = 4.0
        /** 当前时间轴位置（不含 DELAY/OFFSET 的原始毫秒） */
        var currentTimeMs = 0.0
        var delayMs = 0.0
        var balloonIndex = 0

        var openRollStart = Double.NaN
        var openRollBig = false
        var openBalloonStart = Double.NaN

        var branchDepth = 0
        var activeBranch = 'N'

        // ---------------------------------------------------------------
        // 预处理：统计每小节的 div 数（OpenTaiko CTja.cs:1670-1700）
        //   不含逗号的行 → divPerMeasure += 本行字母数字字符数（跨行累加）
        //   含逗号的行   → divsPerMeasure.Add(acc + 本行字母数字数); 归零
        // ---------------------------------------------------------------
        val divsPerMeasure = mutableListOf<Int>()
        run {
            var acc = 0
            for (rawLine in body.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) continue
                val data = line.substringBefore("//").substringBefore('#')
                val cnt = data.count { it.isLetterOrDigit() }
                if (',' !in data) acc += cnt else { divsPerMeasure += acc + cnt; acc = 0 }
            }
            divsPerMeasure += acc
        }

        var divIdx = 0
        var measureDivs = (divsPerMeasure.getOrNull(0) ?: 0).coerceAtLeast(1)
        /** 本小节已处理的音符字符数（用于空小节判定） */
        var measureChars = 0

        fun toAudioTime(pos: Double): Double = pos + delayMs - offsetMs

        // ---------------------------------------------------------------
        // 单遍处理：命令与数据行按顺序处理，保证 BPM 变化的相对位置正确
        // ---------------------------------------------------------------
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
                            val sv = parts[0].toDoubleOrNull()
                            val mv = parts[1].toDoubleOrNull()
                            if (sv != null && mv != null && mv != 0.0 && sv > 0) {
                                measureS = sv
                                measureM = mv
                            }
                        }
                    }
                    upper.startsWith("#DELAY") ->
                        delayMs += (line.substringAfter("#DELAY", "").trim().toDoubleOrNull() ?: 0.0) * 1000.0
                    upper.startsWith("#BRANCHSTART") -> { branchDepth++; activeBranch = 'N' }
                    upper == "#N" || upper == "#E" || upper == "#M" -> activeBranch = upper[1]
                    upper.startsWith("#BRANCHEND") -> { branchDepth--; activeBranch = 'N' }
                    else -> Unit
                }
                continue
            }

            if (branchDepth > 0 && activeBranch != 'N') continue

            // ★ 先剥离行尾注释（// 之后全部丢弃），再做内联命令处理
            val noComment = line.substringBefore("//")
            if (noComment.isEmpty()) continue

            // 内联命令（行中出现 #）：从该点起生效，后面不参与本行
            val inlineIdx = noComment.indexOf('#')
            val dataPart = if (inlineIdx >= 0) {
                applyInlineCommand(
                    noComment.substring(inlineIdx),
                    onBpm = { currentBpm = it },
                    onMeasure = { s2, m2 -> measureS = s2; measureM = m2 },
                )
                noComment.substring(0, inlineIdx)
            } else noComment

            val divMs = 15000.0 / currentBpm * (measureS / measureM)

            for (ch in dataPart) {
                if (ch == ',') {
                    // 逗号只标记小节结束，**不推进时间**——
                    // 小节时长已由前面各字符的推进累计完成（对齐 OpenTaiko：逗号处 return）。
                    // 例外：空小节（本小节 0 个音符字符）需要补推一整小节。
                    if (measureDivs <= 1 && measureChars == 0) {
                        currentTimeMs += divMs * 16.0
                    }
                    barLines += BarLine(toAudioTime(currentTimeMs))
                    divIdx++
                    measureDivs = (divsPerMeasure.getOrNull(divIdx) ?: 0).coerceAtLeast(1)
                    measureChars = 0
                    continue
                }
                if (!ch.isLetterOrDigit()) continue

                val time = toAudioTime(currentTimeMs)
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
                }
                currentTimeMs += divMs * 16.0 / measureDivs
                measureChars++
            }
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
     * 应用行内命令（行中出现的 `#BPMCHANGE` / `#MEASURE`）。
     * 只处理第一个命令，其余忽略（与 OpenTaiko 的 `isBpmChangeInsertedBeforeDiv` 简化等价）。
     */
    private fun applyInlineCommand(
        rest: String,
        onBpm: (Double) -> Unit,
        onMeasure: (Double, Double) -> Unit,
    ) {
        val upper = rest.uppercase()
        when {
            upper.startsWith("#BPMCHANGE") ->
                rest.substringAfter("#BPMCHANGE").trim().toDoubleOrNull()?.let(onBpm)
            upper.startsWith("#MEASURE") -> {
                val parts = rest.substringAfter("#MEASURE").trim().split('/')
                if (parts.size == 2) {
                    val s = parts[0].toDoubleOrNull()
                    val m = parts[1].toDoubleOrNull()
                    if (s != null && m != null && m != 0.0 && s > 0) onMeasure(s, m)
                }
            }
        }
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
