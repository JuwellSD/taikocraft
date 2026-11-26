package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment

/**
 * 下落式谱面界面。
 *
 * ## 坐标
 * ```
 * x = 判定线 + (音符时间 − 渲染时间) × 像素速度
 * ```
 * 像素速度按窗口宽度动态计算（[visibleAheadMs] 毫秒铺满整条轨道）。
 *
 * ## 圆形音符
 * Minecraft 的 `GuiGraphics.fill` 只能画矩形，圆形用「圆盘填充」：
 * 对每一行 y 计算该行圆的半宽 `sqrt(r² − dy²)`，画一条横线。
 * 一个 r=12 的圆约 24 次 fill，性能可接受。
 */
class TaikoScreen(
    private val session: PlaySession,
    private val previous: Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft")) {

    private val donColor = 0xFFE8453C.toInt()
    private val kaColor = 0xFF2F9BE8.toInt()
    private val trackColor = 0xE0141418.toInt()
    private val laneColor = 0xFF3A3A3A.toInt()
    private val judgeLineColor = 0xFFFFFFFF.toInt()

    /** 轨道上可见的「未来时长」 */
    private val visibleAheadMs = 3_000.0
    private val visibleBehindMs = 250.0

    private var debugVisibleNotes = 0

    override fun init() {
        super.init()
        session.start()
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.tick()

        // ★ 渲染时间：受「目押偏移」影响，与判定时间分离
        val now = session.renderTimeMs

        val judgeLineX = (width * 0.16).toInt()
        val trackRight = width - 8
        val trackTop = (height * 0.10).toInt()
        val trackBottom = (height * 0.88).toInt()
        val laneHeight = (trackBottom - trackTop) / 2
        val trackWidth = (trackRight - judgeLineX).coerceAtLeast(1)
        val pixelsPerMs = trackWidth / visibleAheadMs

        // 两条判定轨
        for (lane in 0 until 2) {
            val top = trackTop + lane * laneHeight
            val bottom = top + laneHeight
            guiGraphics.fill(judgeLineX, top, trackRight, bottom, trackColor)
            guiGraphics.fill(judgeLineX, top, judgeLineX + 3, bottom, judgeLineColor)
            guiGraphics.fill(judgeLineX, top, trackRight, top + 1, laneColor)
            guiGraphics.fill(judgeLineX, bottom - 1, trackRight, bottom, laneColor)
        }

        // ---- 音符（圆形）----
        val notes = session.judge.notesSnapshot()
        debugVisibleNotes = 0
        val cy = trackTop + laneHeight / 2

        // ★ 性能：notes 按时间升序，用二分找到「第一个进入窗口的音符」，
        //   只遍历窗口内的那几个，而不是全部 888 个（每帧 200fps × 888 = 17.7 万次循环）
        val windowStartMs = now - visibleBehindMs
        val windowEndMs = now + visibleAheadMs
        var idx = lowerBound(notes, windowStartMs)
        while (idx < notes.size) {
            val noteIndex = idx
            val note = notes[idx]
            if (note.timeMs > windowEndMs) break
            idx++
            if (note.player != 1) continue

            val deltaMs = note.timeMs - now
            val x = judgeLineX + (deltaMs * pixelsPerMs).toInt()
            if (x < judgeLineX - 40 || x > trackRight + 40) continue

            val judged = session.judge.isJudged(noteIndex)
            val r = if (note.big) 15 else 11
            val baseColor = if (note.color == NoteColor.DON) donColor else kaColor

            drawDisc(guiGraphics, x, cy, r + 3, 0xFFFFFFFF.toInt())
            drawDisc(guiGraphics, x, cy, r, if (judged) (baseColor and 0x40FFFFFF) else baseColor)
            if (note.big) {
                drawDisc(guiGraphics, x - 4, cy, 2, 0xFFFFFFFF.toInt())
                drawDisc(guiGraphics, x + 4, cy, 2, 0xFFFFFFFF.toInt())
            }
            debugVisibleNotes++
        }

        // ---- 判定文字 ----
        val result = session.lastResult
        if (result != null && session.judgeTimeMs - session.lastResultAtMs < 450) {
            val text = when (result.judgment) {
                Judgment.PERFECT -> "良"
                Judgment.GOOD -> "可"
                Judgment.POOR -> "不可"
                Judgment.MISS -> "不可"
            }
            val color = when (result.judgment) {
                Judgment.PERFECT -> 0xFFFFD24A.toInt()
                Judgment.GOOD -> 0xFFFFFFFF.toInt()
                Judgment.POOR -> 0xFFAAAAAA.toInt()
                Judgment.MISS -> 0xFFFF5555.toInt()
            }
            val scale = if (result.judgment == Judgment.PERFECT) 1.5f else 1.0f
            guiGraphics.pose().pushPose()
            guiGraphics.pose().translate((judgeLineX + 50).toFloat(), (cy - 36).toFloat(), 0f)
            guiGraphics.pose().scale(scale, scale, 1f)
            guiGraphics.drawString(font, text, 0, 0, color)
            guiGraphics.pose().popPose()
        }

        // ---- 左侧信息区 ----
        val left = 10
        var y = trackTop
        guiGraphics.drawString(font, session.chartFile.displayTitle, left, y, 0xFFFFFFFF.toInt())
        y += 14
        guiGraphics.drawString(font, "难度：${session.chart.courseName}  Lv.${session.chart.level}", left, y, 0xFFAAAAAA.toInt())
        y += 14
        guiGraphics.drawString(font, "音符总数：${notes.size}", left, y, 0xFF888888.toInt())
        y += 14
        guiGraphics.drawString(font, "分数：${session.judge.currentScore}", left, y, 0xFFFFD24A.toInt())
        y += 14
        guiGraphics.drawString(font, "连击：${session.judge.currentCombo}", left, y, 0xFF7FE87F.toInt())
        y += 14
        guiGraphics.drawString(font, "最高连击：${session.judge.maxComboSeen}", left, y, 0xFF7FE87F.toInt())

        // ---- 调试行 ----
        y += 18
        guiGraphics.drawString(
            font,
            "时间 ${"%.0f".format(now)} / ${"%.0f".format(session.chart.lastEventMs)} ms",
            left, y, 0xFF666666.toInt(),
        )
        y += 11
        guiGraphics.drawString(
            font,
            "目押 ${"%.0f".format(session.clock.visualOffsetMs)}  音押 ${"%.0f".format(session.clock.audioOffsetMs)} ms",
            left, y, 0xFF666666.toInt(),
        )
        y += 11
        guiGraphics.drawString(font, "可见音符：$debugVisibleNotes", left, y, 0xFF666666.toInt())

        // ---- 进度条 ----
        val progress = session.progress()
        val barTop = trackBottom + 18
        guiGraphics.fill(judgeLineX, barTop, trackRight, barTop + 4, 0xFF333333.toInt())
        guiGraphics.fill(
            judgeLineX, barTop,
            judgeLineX + (trackWidth * progress).toInt(), barTop + 4,
            0xFF66CCFF.toInt(),
        )

        guiGraphics.drawString(font, "ESC 返回选曲", left, height - 18, 0xFF666666.toInt())
    }

    /** 二分：找到第一个 timeMs >= target 的索引 */
    private fun lowerBound(notes: List<top.drawtwice.taikocraft.chart.Note>, targetMs: Double): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].timeMs < targetMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 画实心圆：逐行 fill，**纵向步长 2 像素**（视觉上几乎无差别，绘制调用减半）。
     */
    private fun drawDisc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int) {
        if (r <= 0) return
        val r2 = r * r
        var dy = -r
        while (dy <= r) {
            val half = kotlin.math.sqrt((r2 - dy * dy).toDouble()).toInt()
            if (half >= 0) {
                g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 2, color)
            }
            dy += 2
        }
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        session.stop()
        // ★ 返回选曲界面，而不是直接回到游戏
        minecraft?.setScreen(previous ?: SongSelectScreen(null))
    }
}
