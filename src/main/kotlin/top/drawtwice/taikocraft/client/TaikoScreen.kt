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

    /**
     * 每「4 个四分音符」占多少像素（OpenTaiko 的 Game_Notes_Interval）。
     * 变速实现：音符位置 = Δms × bpmAtNote / 240000 × pxPer4Beats
     * BPM 越高 → 同样的时间距离对应更多的 4 分音符 → 音符跑得更远（视觉加速）
     */

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
        // ★ 判定线位置：音符 x=0 时正好在这里。必须与音符绘制起点一致
        val judgeX = laneLeft
        // 自适应：让基准 BPM 下的「4 拍」约占轨道宽度的 1/4
        val pxPer4Beats = (laneWidth / 4.0).coerceAtLeast(40.0)

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
        // ★ 小节线用它自己所在位置的 BPM，否则变速段会与音符错位
        for (bar in session.chart.barLines) {
            val d = bar.timeMs - now
            val n4 = d * bar.bpmAtBar / 240000.0
            val x = laneLeft + (n4 * pxPer4Beats).toInt()
            if (x < laneLeft || x > laneRight) continue
            guiGraphics.fill(x, lane1Top, x + 1, lane1Bottom, 0x80FFFFFF.toInt())
            guiGraphics.fill(x, lane2Top, x + 1, lane2Bottom, 0x80FFFFFF.toInt())
        }

        // ---- 判定线（左侧同心双环，参考图第二张）----
        drawJudgeMarker(guiGraphics, judgeX, (lane1Top + lane1Bottom) / 2, trackH)
        drawJudgeMarker(guiGraphics, judgeX, (lane2Top + lane2Bottom) / 2, trackH)

        // ---- 音符 ----
        val notes = session.judge.notesSnapshot()
        debugVisibleNotes = 0
        val cy1 = (lane1Top + lane1Bottom) / 2
        val cy2 = (lane2Top + lane2Bottom) / 2

        // 时间窗只需覆盖「最大 BPM 下轨道能显示的距离」
        val maxBpm = notes.maxOfOrNull { it.bpmAtNote } ?: 120.0
        val aheadMs = 240000.0 * (laneWidth / pxPer4Beats) / maxBpm
        val windowStartMs = now - 250.0
        val windowEndMs = now + aheadMs * 1.05
        var idx = lowerBound(notes, windowStartMs)
        while (idx < notes.size) {
            val noteIndex = idx
            val note = notes[idx]
            if (note.timeMs > windowEndMs) break
            idx++

            // ★ 变速：位置按「4 分音符距离」计算（OpenTaiko Normal 模式）
            val deltaMs = note.timeMs - now
            val n4Beats = deltaMs * note.bpmAtNote / 240000.0
            val x = laneLeft + (n4Beats * pxPer4Beats).toInt()
            if (x < laneLeft - 40 || x > laneRight + 40) continue

            val cy = if (note.player == 1) cy1 else cy2
            val judged = session.judge.isJudged(noteIndex)
            val r = if (note.big) 15 else 11
            val inner = if (note.color == NoteColor.DON) donColor else kaColor
            val alpha = if (judged) 0x55 else 0xFF
            drawHollowNote(guiGraphics, x, cy, r, inner, alpha)

            if (note.player == 1) debugVisibleNotes++
        }

        // ---- 命中特效：打中即爆开 ----
        // 位置固定在判定线附近（命中瞬间音符就在那里）
        for (fx in session.hitFx) {
            val age = session.judgeTimeMs - fx.startMs
            if (age < 0 || age > 400) continue          // 400ms 生命周期
            val t = (age / 400.0).toFloat()             // 0..1 进度
            val baseX = judgeX
            val baseY = if (fx.color == NoteColor.DON) cy1 else cy1
            val col = if (fx.color == NoteColor.DON) donColor else kaColor
            val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255) shl 24
            val maxR = if (fx.big) 34 else 24
            val r = (6 + maxR * t).toInt()

            // 1) 扩散圆环
            drawRing(guiGraphics, baseX, baseY, r, 3, alpha or (col and 0xFFFFFF))
            // 2) 四向爆开线条
            val len = (4 + 18 * t).toInt()
            val lw = (3 * (1f - t)).toInt().coerceAtLeast(1)
            val c = alpha or (col and 0xFFFFFF)
            guiGraphics.fill(baseX - r - len, baseY - lw / 2, baseX - r, baseY + lw / 2 + 1, c)
            guiGraphics.fill(baseX + r, baseY - lw / 2, baseX + r + len, baseY + lw / 2 + 1, c)
            guiGraphics.fill(baseX - lw / 2, baseY - r - len, baseX + lw / 2 + 1, baseY - r, c)
            guiGraphics.fill(baseX - lw / 2, baseY + r, baseX + lw / 2 + 1, baseY + r + len, c)
            // 3) 中心闪光（前 100ms）
            if (age < 100) {
                val flashA = ((1f - age.toFloat() / 100f) * 200).toInt().coerceIn(0, 255) shl 24
                drawDisc(guiGraphics, baseX, baseY, (maxR * 0.5).toInt(), flashA or 0xFFFFFF, fillHeight = 1)
            }
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
            guiGraphics.drawCenteredString(font, text, judgeX + 70, cy1 - 40, color)
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
        dy += 11
        if (session.autoPlay) {
            guiGraphics.drawString(font, "★ AUTO PLAY (OP)", 6, dy, 0xFFFFD24A.toInt())
        }

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
        // 与小音符同大小（小音符 r=11）
        val r = 13
        drawRing(g, cx, cy, r, 2, judgeRingColor and 0xB0FFFFFF.toInt())
        drawRing(g, cx, cy, r - 5, 2, judgeRingColor and 0x70FFFFFF.toInt())
    }

    /**
     * 画「白边空心圆」音符（参考视频第二张图）。
     *
     * 由外到内 5 层：
     * ```
     *   ┌──────────── 外白环（厚约 3-4px，最亮）
     *   │ ┌────────── 深色描边（1px，把白环和内底分开）
     *   │ │ ┌──────── 深灰内底
     *   │ │ │ ┌────── 彩色内芯（咚红 / 咔青）
     *   │ │ │ │ ┌──── 高光（左上角小亮点）
     * ```
     */
    private fun drawHollowNote(g: GuiGraphics, cx: Int, cy: Int, r: Int, innerColor: Int, alpha: Int) {
        if (alpha <= 0) return
        val a = alpha.coerceIn(0, 255) shl 24
        // 1. 外白环（实心白圆，后面会被内层覆盖中间）
        drawDisc(g, cx, cy, r, a or 0xFFFFFF, fillHeight = 1)
        // 2. 深色描边
        drawDisc(g, cx, cy, r - 3, a or 0x1A1A1A, fillHeight = 1)
        // 3. 深灰内底
        drawDisc(g, cx, cy, r - 4, a or 0x2A2E35, fillHeight = 1)
        // 4. 彩色内芯
        val innerR = (r - 7).coerceAtLeast(2)
        drawDisc(g, cx, cy, innerR, a or (innerColor and 0xFFFFFF), fillHeight = 1)
        // 5. 高光（左上角，纯视觉，不改变判定）
        if (r >= 10) {
            drawDisc(g, cx - innerR / 3, cy - innerR / 3, (innerR / 3).coerceAtLeast(1), a or 0xFFFFFF, fillHeight = 1)
        }
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
