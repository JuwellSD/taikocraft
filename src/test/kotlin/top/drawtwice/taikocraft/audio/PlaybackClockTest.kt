package top.drawtwice.taikocraft.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackClockTest {

    @Test
    fun `渲染时间 = 音频位置 + 前导 - 目押偏移`() {
        val clock = PlaybackClock(leadInMs = 100.0)
        clock.start()
        clock.updateFromBackend(500.0)
        assertEquals(600.0, clock.chartTimeVisualMs, 0.001)

        clock.visualOffsetMs = 50.0
        assertEquals(550.0, clock.chartTimeVisualMs, 0.001)
    }

    @Test
    fun `判定时间 = 音频位置 + 前导 - 音押偏移`() {
        val clock = PlaybackClock(leadInMs = 100.0)
        clock.start()
        clock.updateFromBackend(500.0)
        assertEquals(600.0, clock.chartTimeJudgeMs, 0.001)

        clock.audioOffsetMs = 80.0
        assertEquals(520.0, clock.chartTimeJudgeMs, 0.001)
    }

    @Test
    fun `目押与音押互相独立`() {
        val clock = PlaybackClock()
        clock.start()
        clock.updateFromBackend(1000.0)

        clock.visualOffsetMs = 200.0
        clock.audioOffsetMs = 0.0
        assertEquals(800.0, clock.chartTimeVisualMs, 0.001)
        assertEquals(1000.0, clock.chartTimeJudgeMs, 0.001)

        clock.visualOffsetMs = 0.0
        clock.audioOffsetMs = 120.0
        assertEquals(1000.0, clock.chartTimeVisualMs, 0.001)
        assertEquals(880.0, clock.chartTimeJudgeMs, 0.001)
    }

    @Test
    fun `universalOffsetMs 同时设置两者 兼容旧代码`() {
        val clock = PlaybackClock()
        clock.universalOffsetMs = 75.0
        assertEquals(75.0, clock.visualOffsetMs, 0.001)
        assertEquals(75.0, clock.audioOffsetMs, 0.001)
        assertEquals(75.0, clock.universalOffsetMs, 0.001)
    }

    @Test
    fun `暂停后位置冻结`() {
        val clock = PlaybackClock()
        clock.start()
        clock.updateFromBackend(1234.0)
        clock.pause()
        assertFalse(clock.isPlaying)
        clock.updateFromBackend(9999.0)
        assertEquals(1234.0, clock.chartTimeVisualMs, 0.001)

        clock.resume()
        assertTrue(clock.isPlaying)
        clock.updateFromBackend(1300.0)
        assertEquals(1300.0, clock.chartTimeVisualMs, 0.001)
    }

    @Test
    fun `停止后归零`() {
        val clock = PlaybackClock()
        clock.start(2000.0)
        clock.stop()
        assertEquals(0.0, clock.chartTimeVisualMs, 0.001)
        assertFalse(clock.isPlaying)
    }

    @Test
    fun `负偏移让时间提前`() {
        val clock = PlaybackClock()
        clock.start()
        clock.updateFromBackend(1000.0)
        clock.visualOffsetMs = -80.0
        clock.audioOffsetMs = -80.0
        assertEquals(1080.0, clock.chartTimeVisualMs, 0.001)
        assertEquals(1080.0, clock.chartTimeJudgeMs, 0.001)
    }
}
