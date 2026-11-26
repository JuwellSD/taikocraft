package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment

/**
 * 下落式谱面界面。
 *
 * ## 视觉（对齐参考视频的第二张图）
 * ```
 * ┌──────────┬──────────────────────────────────────────┐
 * │          │  ◎ ●●●●  ●●●  ●●●●  ●●●        │ ← 1P 轨道
 * │  橙色     ├──────────────────────────────────────────┤
 * │  信息区   │  ◎ ●●●●  ●●●  ●●●●  ●●●        │ ← 2P 轨道
 * └──────────┴──────────────────────────────────────────┘
 * ```
 *
 * **音符是「白边空心圆」**：
 * - 外圈：白色实心环（厚度约 3px）
 * - 中间：深色底
 * - 内芯：彩色实心圆（咚=红 / 咔=蓝）
 *
 * 早期版本画成实心圆是错的。
 */
class TaikoScreen(
    private val session: PlaySession,
    private val previous: Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft")) {

    // 参考图配色
    private val donColor = 0xFFE8453C.toInt()      // 咚：红
    private val kaColor = 0xFF7DD8DC.toInt()       // 咔：青蓝
    private val noteBgColor = 0xFF23262B.toInt()   // 音符内底色
    private val outerRingColor = 0xFFFFFFFF.toInt() // 外白环
    private val laneBg = 0xFF2B2E33.toInt()        // 轨道底
    private val laneTopBar = 0xFFE07820.toInt()    // 顶部橙色条
    private val laneBottomBar = 0xFFB0B0B0.toInt() // 底部灰条
    private val infoPanelBg = 0xFFE07A28.toInt()   // 左侧信息区（橙）
    private val centerStrip = 0xFF9A9A9A.toInt()   // 两轨之间的灰带
    private val barLineColor = 0xFFFFFFFF.toInt()  // 小节线（白竖线）
    private val judgeRingColor = 0xFFB8BCC0.toInt() // 判定线与音符 #129 的叠加

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

        val now = session.renderTimeMs

        // ---- 布局 ----
        val panelW = (width * 0.145).toInt().coerceAtLeast(90)   // 左侧信息区
        val laneLeft = panelW
        val laneRight = width - 4
        val laneTop = (height * 0.20).toInt()
        val laneBottom = (height * 0.80).toInt()
        val stripH = (height * 0.025).toInt().coerceAtLeast(8)    // 中间灰带
        val trackH = ((laneBottom - laneTop - stripH) / 2).coerceAtLeast(20)
        val lane1Top = laneTop
        val lane1Bottom = laneTop + trackH
        val lane2Top = lane1Bottom + stripH
        val lane2Bottom = lane2Top + trackH
        val laneWidth = (laneRight - laneLeft).coerceAtLeast(1)
        val pixelsPerMs = laneWidth / visibleAheadMs

        // ---- 左侧信息区（橙色面板）----
        guiGraphics.fill(0, lane1Top, panelW, lane1Bottom, infoPanelBg)
        guiGraphics.fill(0, lane2Top, panelW, lane2Bottom, infoPanelBg)

        // ---- 两条轨道 ----
        for ((top, bottom) in listOf(lane1Top to lane1Bottom, lane2Top to lane2Bottom)) {
            guiGraphics.fill(laneLeft, top, laneRight, bottom, laneBg)
            // 顶部橙色细条 / 底部灰条
            guiGraphics.fill(laneLeft, top, laneRight, top + 2, laneTopBar)
            guiGraphics.fill(laneLeft, bottom - 3, laneRight, bottom, laneBottomBar)
        }
        // 中间灰带
        guiGraphics.fill(laneLeft, lane1Bottom, laneRight, lane2Top, centerStrip)

        // ---- 小节线（白竖线）----
        for (bar in session.chart.barLines) {
            val d = bar.timeMs - now
            if (d > visibleAheadMs || d < -visibleBehindMs) continue
            val x = laneLeft + (d * pixelsPerMs).toInt()
            if (x < laneLeft || x > laneRight) continue
            guiGraphics.fill(x, lane1Top, x + 1, lane1Bottom, (0x80 shl 24) or 0xFFFFFF)
            guiGraphics.fill(x, lane2Top, x + 1, lane2Bottom, (0x80 shl 24) or 0xFFFFFF)
        }

        // ---- 判定线（左侧同心双环，参考图第二张）----
        drawJudgeMarker(guiGraphics, laneLeft + 26, (lane1Top + lane1Bottom) / 2, trackH)
        drawJudgeMarker(guiGraphics, laneLeft + 26, (lane2Top + lane2Bottom) / 2, trackH)

        // ---- 音符 ----
        val notes = session.judge.notesSnapshot()
        debugVisibleNotes = 0
        val cy1 = (lane1Top + lane1Bottom) / 2
        val cy2 = (lane2Top + lane2Bottom) / 2

        val windowStartMs = now - visibleBehindMs
        val windowEndMs = now + visibleAheadMs
        var idx = lowerBound(notes, windowStartMs)
        while (idx < notes.size) {
            val noteIndex = idx
            val note = notes[idx]
            if (note.timeMs > windowEndMs) break
            idx++

            val deltaMs = note.timeMs - now
            val x = laneLeft + (deltaMs * pixelsPerMs).toInt()
            if (x < laneLeft - 40 || x > laneRight + 40) continue

            val cy = if (note.player == 1) cy1 else cy2
            val judged = session.judge.isJudged(noteIndex)
            val r = if (note.big) 15 else 11
            val inner = if (note.color == NoteColor.DON) donColor else kaColor
            val alpha = if (judged) 0x55 else 0xFF
            drawHollowNote(guiGraphics, x, cy, r, inner, alpha)

            if (note.player == 1) debugVisibleNotes++
        }

        // ---- 判定文字（判定线右侧）----
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
            guiGraphics.drawCenteredString(font, text, laneLeft + 80, cy1 - 40, color)
        }

        // ---- 左上角调试信息（对齐参考图）----
        var dy = 4
        guiGraphics.drawString(font, "TaikoCraft", 6, dy, 0xFFAAAAAA.toInt()); dy += 11
        guiGraphics.drawString(font, "谱面播放进度: ${"%.0f".format(now)}ms", 6, dy, 0xFFFFFFFF.toInt()); dy += 11
        guiGraphics.drawString(font, "音频播放进度: ${"%.0f".format(session.clock.songPositionMs)}ms", 6, dy, 0xFFFFFFFF.toInt()); dy += 11
        guiGraphics.drawString(
            font,
            "音频播放时差: ${"%.0f".format(now - session.clock.songPositionMs)}ms",
            6, dy, 0xFFAAAAAA.toInt(),
        ); dy += 11
        guiGraphics.drawString(font, "目押 ${"%.0f".format(session.clock.visualOffsetMs)} / 音押 ${"%.0f".format(session.clock.audioOffsetMs)}ms", 6, dy, 0xFF888888.toInt()); dy += 11
        guiGraphics.drawString(font, "可见音符: $debugVisibleNotes", 6, dy, 0xFF888888.toInt())

        // ---- 左侧面板文字 ----
        guiGraphics.drawString(font, "1P", 8, lane1Top + 6, 0xFF3A2010.toInt())
        guiGraphics.drawString(font, "2P", 8, lane2Top + 6, 0xFF3A2010.toInt())
        guiGraphics.drawString(
            font, session.chartFile.displayTitle.take(14),
            6, lane2Bottom + 6, 0xFFAAAAAA.toInt(),
        )
        guiGraphics.drawString(
            font, "分数 ${session.judge.currentScore}",
            6, lane2Bottom + 18, 0xFFFFD24A.toInt(),
        )
        guiGraphics.drawString(
            font, "连击 ${session.judge.currentCombo}",
            6, lane2Bottom + 30, 0xFF7FE87F.toInt(),
        )
        guiGraphics.drawString(font, "ESC 返回选曲", 6, height - 14, 0xFF666666.toInt())
    }

    /** 判定线标记：同心双环（参考图左侧的灰色双圈） */
    private fun drawJudgeMarker(g: GuiGraphics, cx: Int, cy: Int, trackH: Int) {
        val r = (trackH * 0.38).toInt().coerceIn(10, 26)
        drawRing(g, cx, cy, r, 2, judgeRingColor and 0xB0FFFFFF)
        drawRing(g, cx, cy, r - 5, 2, judgeRingColor and 0x70FFFFFF)
    }

    /**
     * 画「白边空心圆」音符。
     *
     * 由外到内：
     * 1. 白色实心圆（半径 r）
     * 2. 深色底色圆（半径 r-3）
     * 3. 彩色内芯（半径 r-6）
     */
    private fun drawHollowNote(g: GuiGraphics, cx: Int, cy: Int, r: Int, innerColor: Int, alpha: Int) {
        val ringAlpha = (alpha shl 24)
        // 外白环
        drawDisc(g, cx, cy, r, ringAlpha or (outerRingColor and 0xFFFFFF), fillHeight = 2)
        // 深色底
        drawDisc(g, cx, cy, r - 3, alpha shl 24 or (noteBgColor and 0xFFFFFF), fillHeight = 2)
        // 彩色内芯
        drawDisc(g, cx, cy, (r - 6).coerceAtLeast(2), alpha shl 24 or (innerColor and 0xFFFFFF), fillHeight = 2)
    }

    /** 画圆环（只画边框） */
    private fun drawRing(g: GuiGraphics, cx: Int, cy: Int, r: Int, thickness: Int, color: Int) {
        val rOut2 = r * r
        val rIn = (r - thickness).coerceAtLeast(1)
        val rIn2 = rIn * rIn
        var dy = -r
        while (dy <= r) {
            val y = cy + dy
            val outerHalf = kotlin.math.sqrt((rOut2 - dy * dy).toDouble()).toInt()
            val innerHalf = if (kotlin.math.abs(dy) <= rIn) kotlin.math.sqrt((rIn2 - dy * dy).toDouble()).toInt() else 0
            // 左段 + 右段（中间挖空）
            g.fill(cx - outerHalf, y, cx - innerHalf, y + 2, color)
            g.fill(cx + innerHalf, y, cx + outerHalf + 1, y + 2, color)
            dy += 2
        }
    }

    /** 画实心圆（纵向步长 2px 提高性能） */
    private fun drawDisc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int, fillHeight: Int = 2) {
        if (r <= 0) return
        val r2 = r * r
        var dy = -r
        while (dy <= r) {
            val half = kotlin.math.sqrt((r2 - dy * dy).toDouble()).toInt()
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + fillHeight, color)
            dy += fillHeight
        }
    }

    /** 二分：第一个 timeMs >= target 的索引 */
    private fun lowerBound(notes: List<top.drawtwice.taikocraft.chart.Note>, targetMs: Double): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].timeMs < targetMs) lo = mid + 1 else hi = mid
        }
        return lo
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        session.stop()
        minecraft?.setScreen(previous ?: SongSelectScreen(null))
    }
}
