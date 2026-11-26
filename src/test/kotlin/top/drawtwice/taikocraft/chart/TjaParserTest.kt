package top.drawtwice.taikocraft.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TjaParserTest {

    private fun tja(
        bpm: String = "120",
        offset: String = "0",
        course: String = "Oni",
        level: String = "8",
        body: String,
        balloon: String? = null,
        wave: String? = null,
    ): String = buildString {
        appendLine("TITLE:测试曲")
        appendLine("BPM:$bpm")
        if (wave != null) appendLine("WAVE:$wave")
        appendLine("OFFSET:$offset")
        if (balloon != null) appendLine("BALLOON:$balloon")
        appendLine("COURSE:$course")
        appendLine("LEVEL:$level")
        appendLine("#START")
        appendLine(body)
        appendLine("#END")
    }

    @Test
    fun `基本解析与标题`() {
        val file = TjaParser.parse(tja(body = "1111"))
        assertEquals("测试曲", file.title)
        assertEquals(1, file.charts.size)
        assertEquals(3, file.charts[0].course)
    }

    @Test
    fun `小节时长按 BPM 计算 - 120bpm 一拍 500ms 四拍 2000ms`() {
        // 逐字符模型："1," = 2 字符，每字符 2000/2 = 1000ms
        // 1@0，逗号推进完整小节 2000ms
        val file = TjaParser.parse(tja(body = "1,"))
        val chart = file.charts[0]
        assertEquals(2000.0, chart.barLines.first().timeMs, 0.001)
        assertEquals(0.0, chart.notes[0].timeMs, 0.001)
    }

    @Test
    fun `空小节推进时间轴 - 每个逗号推进一小节`() {
        // 逐字符模型：逗号 = 小节结束，推进一整小节（16 div）
        val file = TjaParser.parse(tja(body = ",,"))
        val chart = file.charts[0]
        assertEquals(2, chart.barLines.size)
        assertEquals(4000.0, chart.barLines.last().timeMs, 0.001)
        assertTrue(chart.notes.isEmpty())

        // "1,1"：第 1 个字在 0ms；逗号后第 3 个字符（索引 2）在 1/3 行 + 1 小节
        // 行共 3 字符 → 每字符 = 2000/3 ms；位置 = 2000（逗号推进）+ 2000/3
        val two = TjaParser.parse(tja(body = "1,1")).charts[0]
        assertEquals(2, two.notes.size)
        assertEquals(0.0, two.notes[0].timeMs, 0.001)
        assertEquals(2000.0 + 2000.0 / 3.0, two.notes[1].timeMs, 0.5)
    }

    @Test
    fun `音符在大节内的位置插值`() {
        // "1111" → 4 个字符，每字符 500ms
        val file = TjaParser.parse(tja(body = "1111"))
        val times = file.charts[0].notes.map { it.timeMs }
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), times)
    }

    @Test
    fun `OFFSET 为正时谱面提前 音符时刻变小`() {
        // OFFSET:1 秒 → 音频位置 = T - 1000
        val file = TjaParser.parse(tja(offset = "1", body = "1111"))
        val times = file.charts[0].notes.map { it.timeMs }
        assertEquals(listOf(-1000.0, -500.0, 0.0, 500.0), times)
    }

    @Test
    fun `BPMCHANGE 后小节时长变化`() {
        val body = "1" + "\n" + "#BPMCHANGE 240" + "\n" + "1"
        val file = TjaParser.parse(tja(body = body))
        val chart = file.charts[0]
        // 第一行 "1"（1 字符 @120）：音符@0，行末 2000
        // 第二行 "1"（1 字符 @240）：音符@2000
        assertEquals(2, chart.notes.size)
        assertEquals(0.0, chart.notes[0].timeMs, 0.001)
        assertEquals(2000.0, chart.notes[1].timeMs, 0.001)
    }

    @Test
    fun `MEASURE 改变小节长度 - 3 4 拍`() {
        val body = "#MEASURE 3/4" + "\n" + "1,"
        val file = TjaParser.parse(tja(body = body))
        // 120bpm + 3/4：divMs = 15000/120*(3/4) = 93.75ms
        // 行 "1," 字符数 2，每字符 93.75*16/2 = 750ms；逗号推进一整小节 1500ms
        assertEquals(1, file.charts[0].barLines.size)
        assertEquals(1500.0, file.charts[0].barLines.first().timeMs, 0.001)
    }

    @Test
    fun `DELAY 使后续整体后移`() {
        val body = """
            1
            #DELAY 1
            1
        """.trimIndent()
        val file = TjaParser.parse(tja(body = body))
        val notes = file.charts[0].notes
        assertEquals(0.0, notes[0].timeMs, 0.001)
        assertEquals(3000.0, notes[1].timeMs, 0.001) // 2000 + 1000
    }

    @Test
    fun `多难度分块 - 每个 COURSE 一个谱面`() {
        val text = """
            TITLE:多难度
            BPM:120
            WAVE:song.ogg
            OFFSET:0

            COURSE:Easy
            LEVEL:3
            #START
            1
            #END

            COURSE:Oni
            LEVEL:10
            #START
            1111
            #END
        """.trimIndent()
        val file = TjaParser.parse(text)
        assertEquals("多难度", file.title)
        assertEquals("song.ogg", file.audioFileName)
        assertEquals(2, file.charts.size)
        assertEquals(0, file.charts[0].course)
        assertEquals(3, file.charts[0].level)
        assertEquals(3, file.charts[1].course)
        assertEquals(10, file.charts[1].level)
        assertEquals(1, file.charts[0].notes.size)
        assertEquals(4, file.charts[1].notes.size)
    }

    @Test
    fun `大音符与颜色的映射`() {
        val file = TjaParser.parse(tja(body = "1234"))
        val notes = file.charts[0].notes
        assertEquals(NoteColor.DON, notes[0].color)
        assertEquals(false, notes[0].big)
        assertEquals(NoteColor.KA, notes[1].color)
        assertEquals(true, notes[2].big)
        assertEquals(NoteColor.KA, notes[3].color)
        assertEquals(true, notes[3].big)
    }

    @Test
    fun `连打起止 - 5 起 8 止`() {
        val file = TjaParser.parse(tja(body = "1,5,8,2"))
        val chart = file.charts[0]
        assertEquals(1, chart.rolls.size)
        val roll = chart.rolls[0]
        assertEquals(2000.0, roll.startMs, 0.001)
        assertEquals(4000.0, roll.endMs, 0.001)
        assertEquals(false, roll.big)
        assertEquals(2, chart.notes.size)
    }

    @Test
    fun `大连打 - 6 起 8 止`() {
        val file = TjaParser.parse(tja(body = "6,8"))
        val chart = file.charts[0]
        assertEquals(1, chart.rolls.size)
        assertEquals(true, chart.rolls[0].big)
    }

    @Test
    fun `气球 - 7 起 8 止 按 BALLOON 字段取数量`() {
        val file = TjaParser.parse(tja(body = "7,8", balloon = "12"))
        val chart = file.charts[0]
        assertEquals(1, chart.balloons.size)
        assertEquals(12, chart.balloons[0].requiredHits)
        assertEquals(0.0, chart.balloons[0].startMs, 0.001)
        assertEquals(2000.0, chart.balloons[0].endMs, 0.001)
    }

    @Test
    fun `多个气球按 BALLOON 顺序取数量`() {
        val file = TjaParser.parse(tja(body = "7,8,7,8", balloon = "5,8"))
        val balloons = file.charts[0].balloons
        assertEquals(2, balloons.size)
        assertEquals(5, balloons[0].requiredHits)
        assertEquals(8, balloons[1].requiredHits)
    }

    @Test
    fun `分支只取普通 N 段`() {
        val body = """
            1
            #BRANCHSTART p,1,2
            #N
            2
            #E
            3
            #M
            4
            #BRANCHEND
            1
        """.trimIndent()
        val file = TjaParser.parse(tja(body = body))
        val notes = file.charts[0].notes
        // 只应有 '1'、'2'、'1' 三个音符，不含大音符 3/4
        assertEquals(3, notes.size)
        assertTrue(notes.none { it.big })
        assertEquals(NoteColor.KA, notes[1].color)
    }

    @Test
    fun `省略 START END 的 TJA 也能解析`() {
        val text = """
            TITLE:无标记
            BPM:120
            OFFSET:0
            COURSE:Oni
            LEVEL:5
            1111
        """.trimIndent()
        val file = TjaParser.parse(text)
        assertEquals(1, file.charts.size)
        assertEquals(4, file.charts[0].notes.size)
    }

    @Test
    fun `久寿玉 9 按气球处理`() {
        val file = TjaParser.parse(tja(body = "9,8", balloon = "7"))
        val chart = file.charts[0]
        assertEquals(1, chart.balloons.size)
        assertEquals(7, chart.balloons[0].requiredHits)
    }

    @Test
    fun `内联 BPMCHANGE 从命令位置起生效`() {
        // "11#BPMCHANGE 240,11"：先应用内联命令（BPM=240），数据部分 "11"（2 字符）
        // divMs = 15000/240 = 62.5；每字符 = 62.5*16/2 = 500ms
        // 1@0, 1@500, 逗号推进 1000ms → 1500, 之后同 BPM：1@1500, 1@2000
        val file = TjaParser.parse(tja(body = "11#BPMCHANGE 240,11"))
        val times = file.charts[0].notes.map { it.timeMs }
        assertEquals(4, times.size)
        assertEquals(0.0, times[0], 0.001)
        assertEquals(500.0, times[1], 0.001)
        assertEquals(1500.0, times[2], 0.001)
        assertEquals(2000.0, times[3], 0.001)
    }

    @Test
    fun `行尾注释被正确剥离`() {
        // 关键：注释被删掉后，本行字符数不变 → 时间轴应与无注释版本一致
        // "1111,//55" 剥离后数据部分是 "1111,"（5 字符）
        // "1111," 本身就是 5 字符
        val withComment = TjaParser.parse(tja(body = "1111,//55" + "\n" + "1111"))
        val withoutComment = TjaParser.parse(tja(body = "1111," + "\n" + "1111"))
        val a = withComment.charts[0].notes.map { it.timeMs }
        val b = withoutComment.charts[0].notes.map { it.timeMs }
        assertEquals(b, a, "行尾注释不应影响时间轴")
        assertEquals(8, a.size)
    }

    @Test
    fun `编码回退 - Shift_JIS 日文标题`() {
        val text = "TITLE:テスト\nBPM:120\nOFFSET:0\nCOURSE:Oni\nLEVEL:1\n1\n"
        val bytes = text.toByteArray(charset("MS932"))
        val file = TjaParser.parse(bytes)
        assertEquals("テスト", file.title)
    }
}
