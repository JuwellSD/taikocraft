package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment

/**
 * 下落式谱面界面。
 *
 * 视觉参照 cankao.jpeg：
 * - 左侧：曲名 / 难度 / 得分 / 连击
 * - 右侧：判定条，音符从左往右滚动，判定线在左侧
 *
 * 坐标约定：判定线固定在 [judgeLineX]；
 * 音符的屏幕位置 = 判定线 + (音符时间 − 当前时间) × 速度，
 * 所以「未来」的音符在判定线**右侧**，随时间向左移动，到判定线时正好该敲。
 */
class TaikoScreen(
    private val session: PlaySession,
    private val previous: net.minecraft.client.gui.screens.Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft")) {

    // 配色（红咚 / 蓝咔，参照实机）
    private val donColor = 0xFFE8453C.toInt()
    private val kaColor = 0xFF2F9BE8.toInt()
    private val trackColor = 0xE0141418.toInt()
    private val laneColor = 0xFF3A3A3A.toInt()
    private val judgeLineColor = 0xFFFFFFFF.toInt()

    /** 多少像素/毫秒。0.35 → 1 秒 350px，太慢；提高到 0.55 */
    private val pixelsPerMs = 0.55

    /** 只绘制这个时间窗内的音符（毫秒）：提前 8 秒可见，晚 0.4 秒仍显示 */
    private val visibleAheadMs = 8_000.0
    private val visibleBehindMs = 400.0

    private var debugVisibleNotes = 0

    override fun init() {
        super.init()
        session.start()
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.tick()

        val now = session.clock.chartTimeMs
        val judgeLineX = (width * 0.16).toInt()
        val trackRight = width - 12
        val trackTop = (height * 0.10).toInt()
        val trackBottom = (height * 0.90).toInt()
        val laneHeight = (trackBottom - trackTop) / 2

        // 两条判定轨（1P 上 / 2P 下），Phase 1 只用上轨
        for (lane in 0 until 2) {
            val top = trackTop + lane * laneHeight
            val bottom = top + laneHeight
            guiGraphics.fill(judgeLineX, top, trackRight, bottom, trackColor)
            guiGraphics.fill(judgeLineX, top, judgeLineX + 3, bottom, judgeLineColor)
            guiGraphics.fill(judgeLineX, top, trackRight, top + 1, laneColor)
            guiGraphics.fill(judgeLineX, bottom - 1, trackRight, bottom, laneColor)
        }

        // 音符
        val notes = session.judge.notesSnapshot()
        debugVisibleNotes = 0
        for ((i, note) in notes.withIndex()) {
            if (session.judge.isJudged(i)) continue
            if (note.player != 1) continue

            val deltaMs = note.timeMs - now
            // 时间窗筛选：未来 8 秒 ~ 过去 0.4 秒
            if (deltaMs > visibleAheadMs || deltaMs < -visibleBehindMs) continue

            val x = judgeLineX + (deltaMs * pixelsPerMs).toInt()
            if (x < judgeLineX - 40 || x > trackRight + 20) continue

            val cy = trackTop + laneHeight / 2
            val r = if (note.big) 15 else 10
            val color = if (note.color == NoteColor.DON) donColor else kaColor

            // 外白圈 + 内彩色实心
            guiGraphics.fill(x - r - 2, cy - r - 2, x + r + 2, cy + r + 2, 0xFFFFFFFF.toInt())
            guiGraphics.fill(x - r, cy - r, x + r, cy + r, color)
            if (note.big) {
                guiGraphics.fill(x - 3, cy - 3, x + 3, cy + 3, 0xFFFFFFFF.toInt())
            }
            debugVisibleNotes++
        }

        // 判定文字
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
            guiGraphics.pose().translate((judgeLineX + 80).toFloat(), (trackTop + laneHeight / 2 - 30).toFloat(), 0f)
            guiGraphics.pose().scale(scale, scale, 1f)
            guiGraphics.drawString(font, text, 0, 0, color)
            guiGraphics.pose().popPose()
        }

        // 左侧信息区
        val left = 12
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

        // 调试/状态行
        y += 18
        guiGraphics.drawString(
            font,
            "时间 ${"%.0f".format(now)}ms / ${"%.0f".format(session.chart.lastEventMs)}ms",
            left, y, 0xFF666666.toInt(),
        )
        y += 12
        guiGraphics.drawString(font, "可见音符：$debugVisibleNotes", left, y, 0xFF666666.toInt())

        // 进度条
        val progress = session.progress()
        val barTop = trackBottom + 20
        guiGraphics.fill(judgeLineX, barTop, trackRight, barTop + 4, 0xFF333333.toInt())
        guiGraphics.fill(
            judgeLineX, barTop,
            judgeLineX + ((trackRight - judgeLineX) * progress).toInt(), barTop + 4,
            0xFF66CCFF.toInt(),
        )

        guiGraphics.drawString(font, "ESC 退出", left, height - 20, 0xFF666666.toInt())
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        session.stop()
        super.onClose()
    }
}
