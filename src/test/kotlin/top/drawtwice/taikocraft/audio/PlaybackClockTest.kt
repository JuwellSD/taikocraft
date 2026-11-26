package top.drawtwice.taikocraft.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackClockTest {

    @Test
    fun `位置随时间单调递增`() {
        val clock = PlaybackClock()
        clock.start()
        val a = clock.songPositionMs
        Thread.sleep(30)
        val b = clock.songPositionMs
        assertTrue(b > a, "位置必须严格递增：$a -> $b")
        assertTrue(b - a >= 20.0, "30ms 后至少前进 20ms，实际 ${b - a}")
    }

    @Test
    fun `updateFromBackend 不改变位置（防突进）`() {
        val clock = PlaybackClock()
        clock.start()
        Thread.sleep(20)
        val before = clock.songPositionMs
        // 后端报一个完全不同的值，也不应该影响主时钟
        clock.updateFromBackend(999999.0)
        val after = clock.songPositionMs
        assertTrue(after >= before, "位置不应回退：$before -> $after")
        assertTrue(after - before < 100.0, "后端值不应造成突进：$before -> $after")
    }

    @Test
    fun `syncToBackend 在误差范围内不纠正`() {
        val clock = PlaybackClock()
        clock.start()
        Thread.sleep(20)
        val before = clock.songPositionMs
        clock.syncToBackend(before + 10.0, toleranceMs = 80.0) // 10ms 偏差，不纠正
        val after = clock.songPositionMs
        assertTrue(kotlin.math.abs(after - before) < 50.0)
    }

    @Test
    fun `syncToBackend 超过容差才纠正`() {
        val clock = PlaybackClock()
        clock.start()
        Thread.sleep(20)
        clock.syncToBackend(5000.0, toleranceMs = 80.0) // 严重偏离
        // 纠正后位置应接近 5000
        assertTrue(clock.songPositionMs > 4900.0, "应纠正到 5000 附近，实际 ${clock.songPositionMs}")
    }

    @Test
    fun `渲染时间 = 音频位置 + 前导 - 目押偏移`() {
        val clock = PlaybackClock(leadInMs = 100.0)
        clock.visualOffsetMs = 50.0
        clock.start()
        // 先读位置，再读渲染时间；允许微小的时钟推进误差
        val pos = clock.songPositionMs
        val visual = clock.chartTimeVisualMs
        assertEquals(100.0 - 50.0, visual - pos, 15.0)
    }

    @Test
    fun `判定时间 = 音频位置 + 前导 - 音押偏移`() {
        val clock = PlaybackClock(leadInMs = 100.0)
        clock.audioOffsetMs = 80.0
        clock.start()
        val pos = clock.songPositionMs
        val judge = clock.chartTimeJudgeMs
        assertEquals(100.0 - 80.0, judge - pos, 15.0)
    }

    @Test
    fun `目押与音押互相独立`() {
        val clock = PlaybackClock()
        clock.start()
        // 同一时刻读 visual 与 judge 的差值，避免时钟推进造成误差
        clock.visualOffsetMs = 200.0
        clock.audioOffsetMs = 0.0
        var diff = clock.chartTimeJudgeMs - clock.chartTimeVisualMs
        assertEquals(200.0, diff, 10.0, "目押应比判定时间早 200ms")

        clock.visualOffsetMs = 0.0
        clock.audioOffsetMs = 120.0
        diff = clock.chartTimeJudgeMs - clock.chartTimeVisualMs
        assertEquals(-120.0, diff, 10.0, "音押 120 应让判定时间比渲染早 120ms")
    }

    @Test
    fun `universalOffsetMs 同时设置两者 兼容旧代码`() {
        val clock = PlaybackClock()
        clock.universalOffsetMs = 75.0
        assertEquals(75.0, clock.visualOffsetMs, 0.001)
        assertEquals(75.0, clock.audioOffsetMs, 0.001)
    }

    @Test
    fun `暂停后位置冻结`() {
        val clock = PlaybackClock()
        clock.start()
        Thread.sleep(20)
        clock.pause()
        assertFalse(clock.isPlaying)
        val frozen = clock.songPositionMs
        Thread.sleep(20)
        assertEquals(frozen, clock.songPositionMs, 1.0)

        clock.resume()
        assertTrue(clock.isPlaying)
        Thread.sleep(20)
        assertTrue(clock.songPositionMs > frozen, "恢复后应继续前进")
    }

    @Test
    fun `停止后归零`() {
        val clock = PlaybackClock()
        clock.start(2000.0)
        clock.stop()
        assertEquals(0.0, clock.songPositionMs, 0.001)
        assertFalse(clock.isPlaying)
    }
}
