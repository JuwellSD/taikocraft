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
    fun `小节时长按 BPM 计算 - 120bpm 四拍 2000ms`() {
        // 120 BPM 4/4：整小节 240000/120 = 2000ms
        // "1," = 1 个音符字符 + 结束逗号 → 小节结束于 2000ms
        val chart = TjaParser.parse(tja(body = "1,")).charts[0]
        assertEquals(1, chart.barLines.size)
        assertEquals(2000.0, chart.barLines[0].timeMs, 0.001)
        assertEquals(0.0, chart.notes[0].timeMs, 0.001)
    }

    @Test
    fun `音符在小节内的位置 - 4 字符平分 2000ms`() {
        val times = TjaParser.parse(tja(body = "1111")).charts[0].notes.map { it.timeMs }
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), times)
    }

    @Test
    fun `字符数决定分格 - 8 字符平分 2000ms`() {
        // "11111111," 有 8 个音符字符 @ 2000ms 小节 → 每格 250ms
        val chart = TjaParser.parse(tja(body = "11111111,")).charts[0]
        val times = chart.notes.map { it.timeMs }
        assertEquals(8, times.size)
        assertEquals(listOf(0.0, 250.0, 500.0, 750.0, 1000.0, 1250.0, 1500.0, 1750.0), times)
        assertEquals(2000.0, chart.barLines[0].timeMs, 0.001)
    }

    @Test
    fun `跨行累加 div 数`() {
        // 两行各 4 字符、都不含逗号 → 合成一个小节 8 div
        val chart = TjaParser.parse(tja(body = "1111" + "\n" + "1111,")).charts[0]
        val times = chart.notes.map { it.timeMs }
        assertEquals(8, times.size)
        assertEquals(listOf(0.0, 250.0, 500.0, 750.0, 1000.0, 1250.0, 1500.0, 1750.0), times)
        assertEquals(2000.0, chart.barLines[0].timeMs, 0.001)
    }

    @Test
    fun `空小节推进一整小节`() {
        val chart = TjaParser.parse(tja(body = ",,")).charts[0]
        assertEquals(2, chart.barLines.size)
        assertEquals(2000.0, chart.barLines[0].timeMs, 0.001)
        assertEquals(4000.0, chart.barLines[1].timeMs, 0.001)
        assertTrue(chart.notes.isEmpty())
    }

    @Test
    fun `OFFSET 为正时谱面提前`() {
        val times = TjaParser.parse(tja(offset = "1", body = "1111")).charts[0].notes.map { it.timeMs }
        assertEquals(listOf(-1000.0, -500.0, 0.0, 500.0), times)
    }

    @Test
    fun `BPMCHANGE 后小节时长变化`() {
        val body = "1," + "\n" + "#BPMCHANGE 240" + "\n" + "1,"
        val chart = TjaParser.parse(tja(body = body)).charts[0]
        // 第一小节 @120 = 2000ms；第二小节 @240 = 1000ms
        assertEquals(2, chart.barLines.size)
        assertEquals(2000.0, chart.barLines[0].timeMs, 0.001)
        assertEquals(3000.0, chart.barLines[1].timeMs, 0.001)
    }

    @Test
    fun `MEASURE 改变小节长度 - 3 4 拍`() {
        val chart = TjaParser.parse(tja(body = "#MEASURE 3/4" + "\n" + "1,")).charts[0]
        // 120 BPM + 3/4：240000/120 × 3/4 = 1500ms
        assertEquals(1, chart.barLines.size)
        assertEquals(1500.0, chart.barLines[0].timeMs, 0.001)
    }

    @Test
    fun `DELAY 使后续整体后移`() {
        val body = "1," + "\n" + "#DELAY 1" + "\n" + "1,"
        val chart = TjaParser.parse(tja(body = body)).charts[0]
        val notes = chart.notes
        assertEquals(0.0, notes[0].timeMs, 0.001)
        assertEquals(3000.0, notes[1].timeMs, 0.001) // 2000 + 1000 delay
    }

    @Test
    fun `多难度分块`() {
        val text = """
            TITLE:多难度
            BPM:120
            WAVE:song.ogg
            OFFSET:0

            COURSE:Easy
            LEVEL:3
            #START
            1,
            #END

            COURSE:Oni
            LEVEL:10
            #START
            1111,
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
    fun `大音符与颜色映射`() {
        val notes = TjaParser.parse(tja(body = "1234")).charts[0].notes
        assertEquals(NoteColor.DON, notes[0].color)
        assertEquals(false, notes[0].big)
        assertEquals(NoteColor.KA, notes[1].color)
        assertEquals(true, notes[2].big)
        assertEquals(NoteColor.KA, notes[3].color)
        assertEquals(true, notes[3].big)
    }

    @Test
    fun `连打起止 5 到 8`() {
        // "1,5,8,2," → 5 个字符 + 3 个逗号，每个小节 1 div
        // divsPerMeasure = [2, 2, 2, 1]（每段 1 字符 + 逗号结算）
        val chart = TjaParser.parse(tja(body = "1,5,8,2,")).charts[0]
        assertEquals(1, chart.rolls.size)
        assertEquals(500.0, chart.rolls[0].startMs, 0.001)
        assertEquals(2500.0, chart.rolls[0].endMs, 0.001)
        assertEquals(false, chart.rolls[0].big)
        assertEquals(2, chart.notes.size)
    }

    @Test
    fun `大连打 6 到 8`() {
        val chart = TjaParser.parse(tja(body = "6,8,")).charts[0]
        assertEquals(1, chart.rolls.size)
        assertEquals(true, chart.rolls[0].big)
    }

    @Test
    fun `气球按 BALLOON 字段取数量`() {
        val chart = TjaParser.parse(tja(body = "7,8,", balloon = "12")).charts[0]
        assertEquals(1, chart.balloons.size)
        assertEquals(12, chart.balloons[0].requiredHits)
        assertEquals(0.0, chart.balloons[0].startMs, 0.001)
        assertEquals(1000.0, chart.balloons[0].endMs, 0.001)
    }

    @Test
    fun `多个气球按顺序取数量`() {
        val chart = TjaParser.parse(tja(body = "7,8,7,8,", balloon = "5,8")).charts[0]
        assertEquals(2, chart.balloons.size)
        assertEquals(5, chart.balloons[0].requiredHits)
        assertEquals(8, chart.balloons[1].requiredHits)
    }

    @Test
    fun `分支只取普通 N 段`() {
        val body = """
            1,
            #BRANCHSTART p,1,2
            #N
            2,
            #E
            3,
            #M
            4,
            #BRANCHEND
            1,
        """.trimIndent()
        val notes = TjaParser.parse(tja(body = body)).charts[0].notes
        assertEquals(3, notes.size)
        assertTrue(notes.none { it.big })
        assertEquals(NoteColor.KA, notes[1].color)
    }

    @Test
    fun `省略 START END 也能解析`() {
        val text = """
            TITLE:无标记
            BPM:120
            OFFSET:0
            COURSE:Oni
            LEVEL:5
            1111,
        """.trimIndent()
        val file = TjaParser.parse(text)
        assertEquals(1, file.charts.size)
        assertEquals(4, file.charts[0].notes.size)
    }

    @Test
    fun `久寿玉 9 按气球处理`() {
        val chart = TjaParser.parse(tja(body = "9,8,", balloon = "7")).charts[0]
        assertEquals(1, chart.balloons.size)
        assertEquals(7, chart.balloons[0].requiredHits)
    }

    @Test
    fun `行尾注释被剥离`() {
        // "1111,//55" → 数据 "1111,"（注释剥离后与原行完全一致）
        val withC = TjaParser.parse(tja(body = "1111,//55" + "\n" + "1111,")).charts[0]
        val withoutC = TjaParser.parse(tja(body = "1111," + "\n" + "1111,")).charts[0]
        assertEquals(withoutC.notes.map { it.timeMs }, withC.notes.map { it.timeMs })
        assertEquals(withoutC.barLines.map { it.timeMs }, withC.barLines.map { it.timeMs })
        assertEquals(8, withC.notes.size)
    }

    @Test
    fun `编码回退 - Shift_JIS`() {
        val text = "TITLE:テスト\nBPM:120\nOFFSET:0\nCOURSE:Oni\nLEVEL:1\n1,\n"
        val file = TjaParser.parse(text.toByteArray(charset("MS932")))
        assertEquals("テスト", file.title)
    }

    @Test
    fun `真实谱面回归 - Yuugen no Ran Hard 前 12 小节`() {
        // 夹具来自真实 TJA（160 BPM, OFFSET -1.816, 12 字符/小节）
        val text = TjaParserTest::class.java.getResourceAsStream("/tja/yuugen_hard_head.tja")!!
            .readBytes()
        val file = TjaParser.parse(text)
        val chart = file.charts[0]

        assertEquals("Yuugen no Ran", file.title)
        assertEquals(-1816.0, chart.offsetMs, 0.001)
        assertEquals(160.0, chart.bpm, 0.001)

        // 期望值由 OpenTaiko 的 divsPerMeasure 模型独立算出
        // 每小节 12 div，160 BPM → 每小节 1500ms；OFFSET -1.816 → 整体 +1816ms
        assertEquals(89, chart.notes.size)

        val first16 = chart.notes.take(16).map { it.timeMs }
        val expected = listOf(
            1816.0, 1941.0, 2066.0, 2191.0,
            2566.0, 2691.0, 2816.0, 2941.0,
            3316.0, 3441.0, 3566.0, 3691.0,
            4066.0, 4441.0, 4816.0, 4941.0,
        )
        assertEquals(expected, first16, "音符时间轴必须与 OpenTaiko 一致")

        val bars = chart.barLines.take(12).map { it.timeMs }
        assertEquals(
            listOf(
                3316.0, 4816.0, 6316.0, 7816.0, 9316.0, 10816.0,
                12316.0, 13816.0, 15316.0, 16816.0, 18316.0, 19116.0,
            ),
            bars,
        )
    }
}
