package top.drawtwice.taikocraft.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackClockTest {

    @Test
    fun `图表时间 = 音频位置 + 前导 - 全局延迟`() {
        val clock = PlaybackClock(leadInMs = 100.0)
        clock.start()
        clock.updateFromBackend(500.0)
        assertEquals(600.0, clock.chartTimeMs, 0.001)

        clock.universalOffsetMs = 50.0
        assertEquals(550.0, clock.chartTimeMs, 0.001)
    }

    @Test
    fun `暂停后位置冻结`() {
        val clock = PlaybackClock()
        clock.start()
        clock.updateFromBackend(1234.0)
        clock.pause()
        assertFalse(clock.isPlaying)
        clock.updateFromBackend(9999.0) // 暂停时后端上报应被忽略
        assertEquals(1234.0, clock.chartTimeMs, 0.001)

        clock.resume()
        assertTrue(clock.isPlaying)
        clock.updateFromBackend(1300.0)
        assertEquals(1300.0, clock.chartTimeMs, 0.001)
    }

    @Test
    fun `停止后归零`() {
        val clock = PlaybackClock()
        clock.start(2000.0)
        clock.stop()
        assertEquals(0.0, clock.chartTimeMs, 0.001)
        assertFalse(clock.isPlaying)
    }

    @Test
    fun `负延迟让谱面时间提前`() {
        val clock = PlaybackClock()
        clock.start()
        clock.updateFromBackend(1000.0)
        clock.universalOffsetMs = -80.0
        assertEquals(1080.0, clock.chartTimeMs, 0.001)
    }
}
