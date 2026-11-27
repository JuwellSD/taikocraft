package top.drawtwice.taikocraft.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackClockTest {

    // ------------------------------------------------------------------
    // 两个偏移的语义（本项目约定，参考坠尔的需求）
    //
    // 音押 audioOffsetMs：动整个音符 —— 视觉与判定**一起**平移
    // 目押 visualOffsetMs：只动视觉 —— 在判定基础上额外偏移渲染位置
    // ------------------------------------------------------------------

    @Test
    fun `音押偏移同时影响视觉和判定`() {
        val clock = PlaybackClock()
        clock.audioOffsetMs = 100.0
        clock.visualOffsetMs = 0.0
        clock.start()
        Thread.sleep(20)

        // 同一帧内读取，两个时间轴应相等（音押对二者作用相同）
        val judge = clock.chartTimeJudgeMs
        val visual = clock.chartTimeVisualMs
        assertEquals(judge, visual, 5.0, "无目押时，视觉与判定必须一致")
    }

    @Test
    fun `目押偏移只影响视觉 不影响判定`() {
        val clock = PlaybackClock()
        clock.audioOffsetMs = 0.0
        clock.visualOffsetMs = 120.0
        clock.start()

        // 同一帧读取
        val judge = clock.chartTimeJudgeMs
        val visual = clock.chartTimeVisualMs

        assertEquals(120.0, judge - visual, 5.0, "视觉应比判定早 120ms")
    }

    @Test
    fun `两个偏移独立叠加`() {
        val clock = PlaybackClock()
        clock.audioOffsetMs = 50.0
        clock.visualOffsetMs = 30.0
        clock.start()

        val judge = clock.chartTimeJudgeMs
        val visual = clock.chartTimeVisualMs
        // 视觉 = 判定 - 30
        assertEquals(30.0, judge - visual, 5.0)
    }

    @Test
    fun `音押让整个谱面平移（视觉与判定同步）`() {
        val base = PlaybackClock()
        base.start()
        Thread.sleep(10)
        val baseJudge = base.chartTimeJudgeMs
        val baseVisual = base.chartTimeVisualMs

        val shifted = PlaybackClock()
        shifted.audioOffsetMs = 200.0
        shifted.start()
        Thread.sleep(10)
        val shiftedJudge = shifted.chartTimeJudgeMs
        val shiftedVisual = shifted.chartTimeVisualMs

        // 音押 200 → 两个时间轴都减 200（忽略两次启动的微小时间差）
        assertEquals(200.0, baseJudge - shiftedJudge, 30.0, "判定应平移 200ms")
        assertEquals(200.0, baseVisual - shiftedVisual, 30.0, "视觉应平移 200ms")
    }

    @Test
    fun `目押不改变判定 --- 模拟击打时刻`() {
        val clock = PlaybackClock()
        clock.visualOffsetMs = 150.0
        clock.start()
        val judgeBefore = clock.chartTimeJudgeMs
        clock.visualOffsetMs = 0.0
        val judgeAfter = clock.chartTimeJudgeMs
        // 改目押后判定时间几乎不变（只差读取间隔）
        assertTrue(kotlin.math.abs(judgeAfter - judgeBefore) < 20.0,
            "目押不应影响判定时间：$judgeBefore -> $judgeAfter")
    }

    // ------------------------------------------------------------------
    // 时钟基础行为
    // ------------------------------------------------------------------

    @Test
    fun `位置随时间单调递增`() {
        val clock = PlaybackClock()
        clock.start()
        val a = clock.songPositionMs
        Thread.sleep(30)
        val b = clock.songPositionMs
        assertTrue(b > a, "位置必须严格递增：$a -> $b")
    }

    @Test
    fun `updateFromBackend 是主时钟来源`() {
        // 架构变更（2026-11-28）：音频后端位置成为主时钟，
        // 用于消除 520ms 的系统性音画延迟。
        val clock = PlaybackClock()
        clock.start()
        clock.updateFromBackend(1000.0)
        val pos = clock.songPositionMs
        assertEquals(1000.0, pos, 30.0, "应以上报的音频位置为基准")

        // 小幅前进应被接受
        clock.updateFromBackend(1050.0)
        assertEquals(1050.0, clock.songPositionMs, 30.0)

        // 大幅跳变（>200ms）也应接受（seek/恢复场景）
        clock.updateFromBackend(5000.0)
        assertEquals(5000.0, clock.songPositionMs, 30.0)
    }

    @Test
    fun `后端未上报时用系统时钟兜底`() {
        val clock = PlaybackClock()
        clock.start()
        val a = clock.songPositionMs
        Thread.sleep(25)
        val b = clock.songPositionMs
        assertTrue(b > a, "无后端数据时应由系统时钟推进")
    }

    @Test
    fun `TJA OFFSET 影响谱面时间轴`() {
        val noOffset = PlaybackClock(tjaOffsetMs = 0.0)
        noOffset.start()
        Thread.sleep(10)
        val base = noOffset.chartTimeJudgeMs

        val negOffset = PlaybackClock(tjaOffsetMs = -1000.0)
        negOffset.start()
        Thread.sleep(10)
        val shifted = negOffset.chartTimeJudgeMs

        // OFFSET -1000 → 谱面时间比音频位置小 1000
        assertEquals(-1000.0, shifted - base, 30.0)
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
