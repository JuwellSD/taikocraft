package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment

/**
 * 下落式谱面界面。
 *
 * 视觉参照 cankao.jpeg：左侧信息区，右侧双轨判定条。
 *
 * ## 坐标约定（重要）
 * 判定线固定在轨道**左侧**，音符从右往左滚：
 * ```
 * x = 判定线 + (音符时间 − 当前时间) × 速度
 * ```
 * **速度按窗口宽度动态计算**，保证「未来 [visibleAheadMs] 毫秒」恰好铺满整条轨道。
 *
 * 踩过的坑：早期版本用固定 0.55 px/ms，在窄窗口（GUI scale 2/3）下
 * 8 秒 × 0.55 = 4400px 远超轨道宽度，所有音符都画在屏幕外 → 「可见音符 0」。
 */
class TaikoScreen(
    private val session: PlaySession,
    @Suppress("UNUSED_PARAMETER") previous: Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft")) {

    // 配色（红咚 / 蓝咔，参照实机）
    private val donColor = 0xFFE8453C.toInt()
    private val kaColor = 0xFF2F9BE8.toInt()
    private val trackColor = 0xE0141418.toInt()
    private val laneColor = 0xFF3A3A3A.toInt()
    private val judgeLineColor = 0xFFFFFFFF.toInt()

    /** 轨道上可见的「未来时长」：3 秒铺满整条轨道 */
    private val visibleAheadMs = 3_000.0
    /** 判定线左侧仍显示已过音符的时长 */
    private val visibleBehindMs = 250.0

    private var debugVisibleNotes = 0

    override fun init() {
        super.init()
        session.start()
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.tick()

        val now = session.clock.chartTimeMs
        val judgeLineX = (width * 0.16).toInt()
        val trackRight = width - 8
        val trackTop = (height * 0.10).toInt()
        val trackBottom = (height * 0.88).toInt()
        val laneHeight = (trackBottom - trackTop) / 2
        val trackWidth = (trackRight - judgeLineX).coerceAtLeast(1)

        /** 动态速度：3 秒铺满轨道宽度 */
        val pixelsPerMs = trackWidth / visibleAheadMs

        // 两条判定轨（1P 上 / 2P 下），Phase 1 只用上轨
        for (lane in 0 until 2) {
            val top = trackTop + lane * laneHeight
            val bottom = top + laneHeight
            guiGraphics.fill(judgeLineX, top, trackRight, bottom, trackColor)
            guiGraphics.fill(judgeLineX, top, judgeLineX + 3, bottom, judgeLineColor)
            guiGraphics.fill(judgeLineX, top, trackRight, top + 1, laneColor)
            guiGraphics.fill(judgeLineX, bottom - 1, trackRight, bottom, laneColor)
        }

        // ---- 音符 ----
        val notes = session.judge.notesSnapshot()
        debugVisibleNotes = 0
        val cy = trackTop + laneHeight / 2
        for ((i, note) in notes.withIndex()) {
            if (note.player != 1) continue

            val deltaMs = note.timeMs - now
            if (deltaMs > visibleAheadMs) continue      // 还太远
            if (deltaMs < -visibleBehindMs) continue    // 已过太久

            val x = judgeLineX + (deltaMs * pixelsPerMs).toInt()
            if (x < judgeLineX - 30 || x > trackRight + 30) continue

            val judged = session.judge.isJudged(i)
            val r = if (note.big) 15 else 10
            val baseColor = if (note.color == NoteColor.DON) donColor else kaColor
            // 已判定（Miss 过）的音符画成半透明，方便调试
            val color = if (judged) baseColor and 0x40FFFFFF else baseColor

            guiGraphics.fill(x - r - 2, cy - r - 2, x + r + 2, cy + r + 2, 0xFFFFFFFF.toInt())
            guiGraphics.fill(x - r, cy - r, x + r, cy + r, color)
            if (note.big) {
                guiGraphics.fill(x - 3, cy - 3, x + 3, cy + 3, 0xFFFFFFFF.toInt())
            }
            debugVisibleNotes++
        }

        // ---- 判定文字 ----
        val result = session.lastResult
        if (result != null && now - session.lastResultAtMs < 450) {
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
            guiGraphics.pose().translate((judgeLineX + 60).toFloat(), (cy - 34).toFloat(), 0f)
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

        // ---- 调试行（定位问题时看这里）----
        y += 18
        val off = session.clock.universalOffsetMs
        guiGraphics.drawString(
            font,
            "时间 ${"%.0f".format(now)} / ${"%.0f".format(session.chart.lastEventMs)} ms",
            left, y, 0xFF666666.toInt(),
        )
        y += 11
        guiGraphics.drawString(font, "偏移 ${"%.0f".format(off)} ms", left, y, 0xFF666666.toInt())
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

        guiGraphics.drawString(font, "ESC 退出", left, height - 18, 0xFF666666.toInt())
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        session.stop()
        super.onClose()
    }
}
