package top.drawtwice.taikocraft.judge

import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.Note
import top.drawtwice.taikocraft.chart.NoteColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JudgeEngineTest {

    private fun chart(vararg notes: Note, rolls: List<top.drawtwice.taikocraft.chart.RollSegment> = emptyList()) =
        Chart(
            course = 3, courseName = "Oni", level = 8, bpm = 120.0, offsetMs = 0.0,
            notes = notes.toList(),
            rolls = rolls,
        )

    @Test
    fun `良 - 25ms 内`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON)))
        val r = e.press(InputEvent(NoteColor.DON, Side.LEFT, 1025.0))
        assertNotNull(r)
        assertEquals(Judgment.PERFECT, r.judgment)
        assertEquals(1, e.currentCombo)
        assertEquals(300L, e.currentScore)
    }

    @Test
    fun `可 - 26 到 75ms`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON)))
        val r = e.press(InputEvent(NoteColor.DON, Side.LEFT, 1075.0))
        assertEquals(Judgment.GOOD, r?.judgment)
        assertEquals(100L, e.currentScore)
    }

    @Test
    fun `不可 - 76 到 108ms`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.KA)))
        val r = e.press(InputEvent(NoteColor.KA, Side.RIGHT, 892.0)) // 早 108ms
        assertEquals(Judgment.POOR, r?.judgment)
        assertEquals(50L, e.currentScore)
    }

    @Test
    fun `超出窗口不判定`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON)))
        assertNull(e.press(InputEvent(NoteColor.DON, Side.LEFT, 1200.0)))
        assertEquals(0, e.currentCombo)
    }

    @Test
    fun `advanceTo 把过期音符判 Miss 并断连击`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON), Note(2000.0, NoteColor.DON)))
        e.press(InputEvent(NoteColor.DON, Side.LEFT, 1000.0))
        assertEquals(1, e.currentCombo)
        e.advanceTo(2500.0)
        assertEquals(0, e.currentCombo)
        assertEquals(2, e.judgedCount()) // 第一个已判定；第二个 2000+108=2108 < 2500，判 Miss
    }

    @Test
    fun `大音符单手只得基础分 双手补全双倍`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON, big = true)))
        val first = e.press(InputEvent(NoteColor.DON, Side.LEFT, 1000.0))
        assertNotNull(first)
        assertEquals(true, first.big)
        assertEquals(false, first.bigCompleted)
        assertEquals(300L, e.currentScore)

        val second = e.press(InputEvent(NoteColor.DON, Side.RIGHT, 1030.0))
        assertNotNull(second)
        assertEquals(true, second.bigCompleted)
        // 补全后追加一次得分 → 300 + 600
        assertEquals(900L, e.currentScore)
    }

    @Test
    fun `大音符双手超时则只算单键`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON, big = true)))
        e.press(InputEvent(NoteColor.DON, Side.LEFT, 1000.0))
        // 间隔 80ms > 50ms 窗口，落在音符判定窗内但补全应失败
        val second = e.press(InputEvent(NoteColor.DON, Side.RIGHT, 1080.0))
        assertNull(second)
        assertEquals(300L, e.currentScore)
    }

    @Test
    fun `连打区间内击打恒 Perfect`() {
        val roll = top.drawtwice.taikocraft.chart.RollSegment(1000.0, 2000.0)
        val e = JudgeEngine(chart(rolls = listOf(roll)))
        val r = e.press(InputEvent(NoteColor.DON, Side.LEFT, 1500.0))
        assertNotNull(r)
        assertEquals(Judgment.PERFECT, r.judgment)
        assertTrue(r.fromRollOrBalloon)
        assertEquals(100L, e.currentScore)
    }

    @Test
    fun `同侧 40ms 冷却防连打误触`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON), Note(1010.0, NoteColor.DON)))
        assertNotNull(e.press(InputEvent(NoteColor.DON, Side.LEFT, 1000.0)))
        assertNull(e.press(InputEvent(NoteColor.DON, Side.LEFT, 1010.0)))
    }

    @Test
    fun `不判 Miss 时连击保持`() {
        val e = JudgeEngine(chart(Note(1000.0, NoteColor.DON), Note(3000.0, NoteColor.DON)))
        e.press(InputEvent(NoteColor.DON, Side.LEFT, 1000.0))
        e.advanceTo(1108.0) // 未过期
        assertEquals(1, e.currentCombo)
        assertTrue(e.currentScore > 0)
    }
}
