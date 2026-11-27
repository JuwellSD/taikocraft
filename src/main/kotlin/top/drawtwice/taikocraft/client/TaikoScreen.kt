package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 下落式谱面界面。
 *
 * ## 视觉尺寸全部来自 OpenTaiko 默认皮肤（1280×720 基准）
 * ```
 * Game_Notes_Size     = 130px    音符边长
 * Game_Notes_Interval = 960px    每 4 拍距离
 * Game_Lane_X         = 333      轨道左边界
 * Game_Lane_Y         = {192,368} 1P/2P 轨道中心 Y
 * ```
 * 见 [TaikoVisuals]。
 *
 * ## 判定线位置（关键修复）
 * **判定线 X 必须等于音符 x=0 的位置**，两者共用同一坐标原点。
 * 早期版本把判定圈画在 `laneLeft + 26`，而音符从 `laneLeft` 起算，
 * 导致命中点落在橙/灰交界线而不是圆圈内 —— 这是错的。
 */
class TaikoScreen(
    private val session: PlaySession,
    private val previous: Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft")) {

    // OpenTaiko 默认皮肤色
    private val donColor = 0xFFE8453C.toInt()
    private val kaColor = 0xFF4FC3D9.toInt()
    private val laneBg = 0xE62B2E33.toInt()
    private val laneEdge = 0xFFB0B0B0.toInt()
    private val panelBg = 0xFFE07A28.toInt()

    // 每帧计算
    private var judgeX = 0
    private var laneRight = 0
    private var laneH = 0
    private var center1P = 0
    private var center2P = 0
    private var noteR = 0
    private var pxPer4Beats = 0.0
    private var show2P = false

    override fun init() {
        super.init()
        session.start()
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.tick()
        val now = session.renderTimeMs
        val notes = session.judge.notesSnapshot()

        // ---------------- 布局（OpenTaiko 基准换算） ----------------
        judgeX = TaikoVisuals.laneX(width)
        laneRight = width - (width / 10).coerceAtLeast(30)
        val sc = TaikoVisuals.scaleFor(width)
        val hs = height / TaikoVisuals.REF_HEIGHT
        noteR = (TaikoVisuals.NOTE_SIZE_BASE * sc / 2).toInt().coerceAtLeast(8)
        pxPer4Beats = TaikoVisuals.NOTE_INTERVAL_BASE * sc
        laneH = (TaikoVisuals.LANE_HEIGHT_BASE * hs).toInt().coerceIn(28, 120)
        center1P = (TaikoVisuals.LANE_Y_1P_BASE * hs).toInt()
        center2P = (TaikoVisuals.LANE_Y_2P_BASE * hs).toInt()

        // 2P 轨道仅在存在 2P 音符时显示（坠尔要求）
        show2P = notes.any { it.player == 2 }

        // ---------------- 左侧面板 ----------------
        val panelRight = judgeX - 4
        if (panelRight > 30) {
            guiGraphics.fill(0, center1P - laneH / 2 - 4, panelRight, center1P + laneH / 2 + 4, panelBg)
            if (show2P) guiGraphics.fill(0, center2P - laneH / 2 - 4, panelRight, center2P + laneH / 2 + 4, panelBg)
        }

        // ---------------- 轨道 ----------------
        drawLane(guiGraphics, center1P)
        if (show2P) drawLane(guiGraphics, center2P)

        // ---------------- 小节线 ----------------
        for (bar in session.chart.barLines) {
            val n4 = (bar.timeMs - now) * bar.bpmAtBar / 240000.0
            val x = judgeX + (n4 * pxPer4Beats).toInt()
            if (x < judgeX || x > laneRight) continue
            guiGraphics.fill(x, center1P - laneH / 2, x + 1, center1P + laneH / 2, 0x50FFFFFF)
            if (show2P) guiGraphics.fill(x, center2P - laneH / 2, x + 1, center2P + laneH / 2, 0x50FFFFFF)
        }

        // ---------------- 长条 / 气球 ----------------
        drawRolls(guiGraphics, now)
        drawBalloons(guiGraphics, now)

        // ---------------- 音符 ----------------
        drawNotes(guiGraphics, now, notes)

        // ---------------- 背景闪光（轻，不干扰读谱） ----------------
        drawLaneFlash(guiGraphics)

        // ---------------- 判定线 ----------------
        guiGraphics.fill(judgeX, center1P - laneH / 2, judgeX + 2, center1P + laneH / 2, 0xFFFFFFFF.toInt())
        if (show2P) guiGraphics.fill(judgeX, center2P - laneH / 2, judgeX + 2, center2P + laneH / 2, 0xFFFFFFFF.toInt())

        // ---------------- 平面太鼓 ----------------
        drawFlatTaiko(guiGraphics)

        // ---------------- 判定文字 + 偏移调试 ----------------
        drawJudgeFeedback(guiGraphics)

        // ---------------- 信息 ----------------
        drawInfo(guiGraphics, now)
    }

    // ==================================================================
    // 轨道 & 音符
    // ==================================================================

    private fun drawLane(g: GuiGraphics, centerY: Int) {
        val top = centerY - laneH / 2
        val bottom = centerY + laneH / 2
        g.fill(judgeX, top, laneRight, bottom, laneBg)
        g.fill(judgeX, top, laneRight, top + 1, laneEdge)
        g.fill(judgeX, bottom - 1, laneRight, bottom, laneEdge)
    }

    private fun drawNotes(g: GuiGraphics, now: Double, notes: List<top.drawtwice.taikocraft.chart.Note>) {
        // 轨道宽度内能容纳的 4 拍数 → 换算成时间窗
        val maxN4 = (laneRight - judgeX) / pxPer4Beats
        val maxBpm = notes.maxOfOrNull { it.bpmAtNote } ?: 200.0
        val aheadMs = 240000.0 * maxN4 / maxBpm

        var i = lowerBound(notes, now - 200.0)
        while (i < notes.size) {
            val idx = i
            val note = notes[i]
            if (note.timeMs > now + aheadMs) break
            i++
            if (note.player == 2 && !show2P) continue

            val d = note.timeMs - now
            val n4 = d * note.bpmAtNote / 240000.0
            val x = judgeX + (n4 * pxPer4Beats).toInt()
            if (x < judgeX - noteR * 2 || x > laneRight + noteR * 2) continue

            val cy = if (note.player == 1) center1P else center2P
            val judged = session.judge.isJudged(idx)
            val alpha = if (judged) 45 else 255
            val color = if (note.color == NoteColor.DON) donColor else kaColor
            val r = if (note.big) (noteR * 5 / 4) else noteR
            drawNote(g, x, cy, r, color, alpha, note.big)
        }
    }

    /** 音符：按当前材质风格绘制（代码圆 / OpenTaiko 贴图） */
    private fun drawNote(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int, alpha: Int, big: Boolean) {
        val isDon = color == donColor
        NoteSkinManager.drawNote(g, cx, cy, r, isDon, big, alpha) { gg, x, y, rr, aa ->
            val a = aa.coerceIn(0, 255) shl 24
            drawDisc(gg, x, y, rr, a or 0xFFFFFF, 1)
            drawDisc(gg, x, y, (rr - 4).coerceAtLeast(2), a or (color and 0xFFFFFF), 1)
            if (big) drawDisc(gg, x, y, (rr / 5).coerceAtLeast(2), a or 0xFFFFFF, 1)
        }
    }

    // ==================================================================
    // 长条 / 气球（此前完全没渲染 —— 坠尔反馈"能打但不显示"）
    // ==================================================================

    private fun drawRolls(g: GuiGraphics, now: Double) {
        for (roll in session.chart.rolls) {
            if (roll.endMs < now - 500) continue
            val x1 = timeToX(roll.startMs, now)
            val x2 = timeToX(roll.endMs, now)
            if (x2 < judgeX - noteR) continue
            drawBar(g, x1, x2, center1P, 0xFFB84040.toInt())
        }
    }

    private fun drawBalloons(g: GuiGraphics, now: Double) {
        for (balloon in session.chart.balloons) {
            if (balloon.endMs < now - 500) continue
            val x1 = timeToX(balloon.startMs, now)
            val x2 = timeToX(balloon.endMs, now)
            if (x2 < judgeX - noteR) continue
            drawBar(g, x1, x2, center1P, 0xFF5BA3C9.toInt())

            // 中心显示剩余击打数
            val mid = (maxOf(x1, judgeX) + minOf(x2, laneRight)) / 2
            val remain = (balloon.requiredHits - session.judge.balloonHitsOf(balloon)).coerceAtLeast(0)
            drawDisc(g, mid, center1P, laneH / 2, 0xFFFFFFFF.toInt(), 1)
            drawDisc(g, mid, center1P, laneH / 2 - 3, 0xFF5BA3C9.toInt(), 1)
            g.drawCenteredString(font, remain.toString(), mid, center1P - 4, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawBar(g: GuiGraphics, x1: Int, x2: Int, cy: Int, color: Int) {
        val left = minOf(x1, x2).coerceAtLeast(judgeX)
        val right = maxOf(x1, x2).coerceAtMost(laneRight)
        if (right <= left) return
        val h = (noteR * 3 / 2).coerceAtLeast(8)
        val top = cy - h / 2
        g.fill(left, top - 2, right, top + h + 2, 0xFFFFFFFF.toInt())
        g.fill(left, top, right, top + h, color)
        drawDisc(g, left, cy, h / 2 + 2, 0xFFFFFFFF.toInt(), 1)
        drawDisc(g, right, cy, h / 2 + 2, 0xFFFFFFFF.toInt(), 1)
        drawDisc(g, left, cy, h / 2, color, 1)
        drawDisc(g, right, cy, h / 2, color, 1)
    }

    /** 时刻 → 屏幕 x（用该时刻所在 BPM 的节拍距离） */
    private fun timeToX(timeMs: Double, now: Double): Int {
        val d = timeMs - now
        return judgeX + (d * bpmAt(timeMs) / 240000.0 * pxPer4Beats).toInt()
    }

    private fun bpmAt(timeMs: Double): Double {
        val notes = session.judge.notesSnapshot()
        if (notes.isEmpty()) return session.chart.bpm
        return notes[lowerBound(notes, timeMs).coerceIn(0, notes.size - 1)].bpmAtNote
    }

    // ==================================================================
    // 背景闪光（对齐 OpenTaiko LaneFlash：100ms 衰减，很轻）
    // ==================================================================

    private fun drawLaneFlash(g: GuiGraphics) {
        val r = session.lastResult ?: return
        val age = session.judgeTimeMs - session.lastResultAtMs
        if (age < 0 || age > 100) return
        val alpha = ((1 - age / 100.0) * 55).toInt().coerceIn(0, 60)
        if (alpha <= 0) return
        val c = if (r.color == NoteColor.DON) 0xE8453C else 0x4FC3D9
        g.fill(judgeX, center1P - laneH / 2, laneRight, center1P + laneH / 2, (alpha shl 24) or c)
        if (show2P) g.fill(judgeX, center2P - laneH / 2, laneRight, center2P + laneH / 2, (alpha shl 24) or c)
    }

    // ==================================================================
    // 平面太鼓（左侧，实时显示咚/咔状态）
    // ==================================================================

    private fun drawFlatTaiko(g: GuiGraphics) {
        val cx = (judgeX / 2).coerceAtLeast(30)
        val cy = center1P
        val r = (noteR * 2).coerceIn(24, 70)
        val innerR = (r * 55 / 100).coerceAtLeast(10)

        // 咔区（外环）
        drawDisc(g, cx, cy, r, 0xFFE8E2DA.toInt(), 2)
        // 咚区（内圈）
        drawDisc(g, cx, cy, innerR, 0xFFC9BEB2.toInt(), 2)

        val result = session.lastResult ?: return
        val age = session.judgeTimeMs - session.lastResultAtMs
        if (age in 0.0..90.0) {
            val a = ((1 - age / 90.0) * 220).toInt().coerceIn(0, 255) shl 24
            val c = if (result.color == NoteColor.DON) 0xFFE8453C.toInt() else 0xFF4FC3D9.toInt()
            if (result.color == NoteColor.DON) {
                drawDisc(g, cx, cy, innerR, a or (c and 0xFFFFFF), 2)
            } else {
                drawRing(g, cx, cy, r - 2, (r - innerR).coerceAtLeast(4), a or (c and 0xFFFFFF))
            }
        }
    }

    // ==================================================================
    // 判定反馈 + 偏移调试（坠尔要求：知道打早还是打晚）
    // ==================================================================

    private fun drawJudgeFeedback(g: GuiGraphics) {
        val result = session.lastResult ?: return
        val age = session.judgeTimeMs - session.lastResultAtMs
        if (age < 0 || age > 600) return

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
        val fx = judgeX + noteR * 2
        g.drawString(font, text, fx, center1P - noteR - 4, color)

        // ★ 偏移调试：正 = 打晚，负 = 打早
        val noteTime = result.noteTimeMs ?: return
        val off = session.lastResultAtMs - noteTime
        if (abs(off) > 400) return
        val sign = if (off >= 0) "+" else "−"
        val ms = "%.1f".format(abs(off))
        val c = when {
            abs(off) <= 25 -> 0xFFFFD24A.toInt()
            abs(off) <= 75 -> 0xFFFFFFFF.toInt()
            else -> 0xFF999999.toInt()
        }
        g.drawString(font, "$sign$ms ms", fx, center1P - noteR + 8, c)
    }

    // ==================================================================
    // 信息面板
    // ==================================================================

    private fun drawInfo(g: GuiGraphics, now: Double) {
        var y = 4
        g.drawString(font, "TaikoCraft", 4, y, 0xFFAAAAAA.toInt()); y += 10
        g.drawString(font, "谱面 ${"%.0f".format(now)}ms", 4, y, 0xFFCCCCCC.toInt()); y += 10
        g.drawString(font, "音频 ${"%.0f".format(session.clock.songPositionMs)}ms", 4, y, 0xFFCCCCCC.toInt()); y += 10
        g.drawString(
            font, "时差 ${"%.0f".format(now - session.clock.songPositionMs - session.clock.tjaOffsetMs)}ms",
            4, y, 0xFF888888.toInt(),
        ); y += 10
        g.drawString(
            font, "目 ${session.clock.visualOffsetMs.toInt()} 音 ${session.clock.audioOffsetMs.toInt()}",
            4, y, 0xFF888888.toInt(),
        ); y += 14
        g.drawString(font, "分 ${session.judge.currentScore}", 4, y, 0xFFFFD24A.toInt()); y += 11
        g.drawString(font, "连 ${session.judge.currentCombo}", 4, y, 0xFF7FE87F.toInt()); y += 11
        g.drawString(font, "高 ${session.judge.maxComboSeen}", 4, y, 0xFF7FE87F.toInt()); y += 11
        if (session.autoPlay) g.drawString(font, "AUTO", 4, y, 0xFFFFD24A.toInt())
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private fun drawDisc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int, step: Int) {
        if (r <= 0) return
        val r2 = r * r
        var dy = -r
        while (dy <= r) {
            val half = sqrt((r2 - dy * dy).toDouble()).toInt()
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + step, color)
            dy += step
        }
    }

    private fun drawRing(g: GuiGraphics, cx: Int, cy: Int, r: Int, thickness: Int, color: Int) {
        if (r <= 0) return
        val out2 = r * r
        val inR = (r - thickness).coerceAtLeast(1)
        val in2 = inR * inR
        var dy = -r
        while (dy <= r) {
            val o = sqrt((out2 - dy * dy).toDouble()).toInt()
            val i = if (abs(dy) <= inR) sqrt((in2 - dy * dy).toDouble()).toInt() else 0
            g.fill(cx - o, cy + dy, cx - i, cy + dy + 2, color)
            g.fill(cx + i, cy + dy, cx + o + 1, cy + dy + 2, color)
            dy += 2
        }
    }

    private fun lowerBound(notes: List<top.drawtwice.taikocraft.chart.Note>, target: Double): Int {
        var lo = 0
        var hi = notes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].timeMs < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        session.stop()
        minecraft?.setScreen(previous ?: SongSelectScreen(null))
    }
}
