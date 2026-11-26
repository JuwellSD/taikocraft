package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment
import kotlin.math.abs

/**
 * 下落式谱面界面。
 *
 * 视觉参照 cankao.jpeg：
 * - 左侧：曲名 / 难度 / 得分 / 连击
 * - 右侧：判定条，音符从右往左（或从下往上）移动
 *
 * Phase 1 用简洁几何绘制（不引入美术资产，符合版权"空心化"策略）。
 */
class TaikoScreen(
    private val session: PlaySession,
    previous: Screen? = null,
) : Screen(Component.literal("TaikoCraft")) {

    // 配色（红咚 / 蓝咔，参照实机）
    private val donColor = 0xFFE8453C.toInt()
    private val kaColor = 0xFF2F9BE8.toInt()
    private val trackColor = 0xC0101010.toInt()
    private val laneColor = 0xFF3A3A3A.toInt()
    private val judgeLineColor = 0xFFFFFFFF.toInt()

    private var visibleHeight = 0
    private var visibleWidth = 0

    override fun init() {
        super.init()
        visibleWidth = width
        visibleHeight = height
        session.start()
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 全屏半透明底
        guiGraphics.fill(0, 0, width, height, 0xF0101014.toInt())

        session.tick()

        val now = session.clock.chartTimeMs
        val density = 0.35 // 每毫秒多少像素，决定下落速度
        val judgeLineX = (width * 0.18).toInt()
        val trackLeft = judgeLineX
        val trackRight = width - 12
        val trackTop = (height * 0.12).toInt()
        val trackBottom = (height * 0.92).toInt()
        val laneHeight = (trackBottom - trackTop) / 2

        // 两条判定轨（1P 上 / 2P 下），Phase 1 只用上轨
        for (lane in 0 until 2) {
            val top = trackTop + lane * laneHeight
            val bottom = top + laneHeight
            guiGraphics.fill(trackLeft, top, trackRight, bottom, trackColor)
            guiGraphics.fill(trackLeft, top, trackLeft + 3, bottom, judgeLineColor)
            guiGraphics.fill(trackLeft, top, trackRight, top + 1, laneColor)
            guiGraphics.fill(trackLeft, bottom - 1, trackRight, bottom, laneColor)
        }

        // 音符
        val notes = session.judge.notesSnapshot()
        for ((i, note) in notes.withIndex()) {
            if (session.judge.isJudged(i)) continue
            if (note.player != 1) continue
            val dx = (note.timeMs - now) * density
            val x = judgeLineX + dx
            if (x < trackLeft - 40 || x > trackRight + 40) continue

            val laneTop = if (note.player == 1) trackTop else trackTop + laneHeight
            val cy = laneTop + laneHeight / 2
            val r = if (note.big) 16 else 11
            val color = if (note.color == NoteColor.DON) donColor else kaColor

            // 外白圈 + 内彩色实心
            guiGraphics.fill(x - r - 2, cy - r - 2, x + r + 2, cy + r + 2, 0xFFFFFFFF.toInt())
            guiGraphics.fill(x - r, cy - r, x + r, cy + r, color)
            if (note.big) {
                // 大音符中心加白点
                guiGraphics.fill(x - 3, cy - 3, x + 3, cy + 3, 0xFFFFFFFF.toInt())
            }
        }

        // 判定文字
        val result = session.lastResult
        if (result != null && now - session.lastResultAtMs < 400) {
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
            guiGraphics.drawCenteredString(font, text, judgeLineX + 60, trackBottom + 8, color)
        }

        // 左侧信息区
        val left = 12
        var y = trackTop
        guiGraphics.drawString(font, session.chartFile.displayTitle, left, y, 0xFFFFFFFF.toInt())
        y += 14
        guiGraphics.drawString(font, "难度：${session.chart.courseName}  Lv.${session.chart.level}", left, y, 0xFFAAAAAA.toInt())
        y += 14
        guiGraphics.drawString(font, "分数：${session.judge.currentScore}", left, y, 0xFFFFD24A.toInt())
        y += 14
        guiGraphics.drawString(font, "连击：${session.judge.currentCombo}", left, y, 0xFF7FE87F.toInt())
        y += 14
        guiGraphics.drawString(font, "最高连击：${session.judge.maxComboSeen}", left, y, 0xFF7FE87F.toInt())

        // 进度条
        val progress = session.progress()
        val barTop = trackBottom + 24
        guiGraphics.fill(trackLeft, barTop, trackRight, barTop + 4, 0xFF333333.toInt())
        guiGraphics.fill(trackLeft, barTop, trackLeft + ((trackRight - trackLeft) * progress).toInt(), barTop + 4, 0xFF66CCFF.toInt())

        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        session.stop()
        super.onClose()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun absDelta(a: Double, b: Double): Double = abs(a - b)
}
