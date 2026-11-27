package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 下落式谱面界面（按坠尔提供的实机截图比例重写）。
 *
 * ## 尺寸来源
 * 见 [TaikoVisuals]：音符直径 3.33% 屏宽、4 拍跨度 53.3% 屏宽
 * （从 1920×1080 截图上量出来的）。
 *
 * ## 打击圈
 * 判定位置画**与音符同大小的圆圈轮廓**，音符到点正好覆盖它。
 * 之前这圈被我误删，现已恢复。
 *
 * ## 平面太鼓
 * 4 个独立区域（左咔/左咚/右咚/右咔），**只在判定命中时闪**，
 * 不是按下就闪。
 */
class TaikoScreen(
    private val session: PlaySession,
    private val previous: Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft")) {

    private val donColor = 0xFFE8453C.toInt()
    private val kaColor = 0xFF4FC3D9.toInt()

    private var judgeX = 0
    private var laneRight = 0
    private var center1P = 0
    private var center2P = 0
    private var laneH = 0
    private var noteR = 0
    private var beatSpan = 0.0
    private var show2P = false

    private val padFlash = DoubleArray(4)
    private var lastResultRef: Any? = null

    override fun init() {
        super.init()
        session.start()
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.tick()
        val now = session.renderTimeMs
        val notes = session.judge.notesSnapshot()

        judgeX = TaikoVisuals.laneLeft(width)
        laneRight = width - (width * 0.05).toInt()
        noteR = TaikoVisuals.noteRadius(width)
        beatSpan = TaikoVisuals.beatSpan(width)
        laneH = TaikoVisuals.laneHeight(height)
        center1P = TaikoVisuals.laneCenter1P(height)
        center2P = TaikoVisuals.laneCenter2P(height)
        show2P = notes.any { it.player == 2 }

        drawLane(guiGraphics, center1P)
        if (show2P) drawLane(guiGraphics, center2P)

        for (bar in session.chart.barLines) {
            val n4 = (bar.timeMs - now) * bar.bpmAtBar / 240000.0
            val x = judgeX + (n4 * beatSpan).toInt()
            if (x < judgeX || x > laneRight) continue
            guiGraphics.fill(x, center1P - laneH / 2, x + 1, center1P + laneH / 2, 0x40FFFFFF)
            if (show2P) guiGraphics.fill(x, center2P - laneH / 2, x + 1, center2P + laneH / 2, 0x40FFFFFF)
        }

        drawRolls(guiGraphics, now)
        drawBalloons(guiGraphics, now)
        drawNotes(guiGraphics, now, notes)
        drawLaneFlash(guiGraphics)
        updatePadFlash()
        drawFlatTaiko(guiGraphics)
        drawJudgeFeedback(guiGraphics)
        drawInfo(guiGraphics, now)
    }

    private fun drawLane(g: GuiGraphics, centerY: Int) {
        val top = centerY - laneH / 2
        val bottom = centerY + laneH / 2
        g.fill(judgeX, top, laneRight, bottom, 0xE62B2E33.toInt())
        g.fill(judgeX, top, laneRight, top + 1, 0xFFB0B0B0.toInt())
        g.fill(judgeX, bottom - 1, laneRight, bottom, 0xFFB0B0B0.toInt())
    }

    /** 打击圈：与音符同大小的轮廓，音符到达时覆盖它 */
    private fun drawJudgeCircle(g: GuiGraphics, cy: Int) {
        drawRing(g, judgeX, cy, noteR + 2, 3, 0xC0FFFFFF.toInt())
    }

    private fun drawNotes(g: GuiGraphics, now: Double, notes: List<top.drawtwice.taikocraft.chart.Note>) {
        drawJudgeCircle(g, center1P)
        if (show2P) drawJudgeCircle(g, center2P)

        val maxN4 = (laneRight - judgeX) / beatSpan
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
            val x = judgeX + (n4 * beatSpan).toInt()
            if (x < judgeX - noteR * 2 || x > laneRight + noteR * 2) continue

            val cy = if (note.player == 1) center1P else center2P
            val judged = session.judge.isJudged(idx)
            val alpha = if (judged) 45 else 255
            val r = if (note.big) (noteR * 5 / 4) else noteR
            drawNote(g, x, cy, r, note.color == NoteColor.DON, note.big, alpha)
        }
    }

    private fun drawNote(g: GuiGraphics, cx: Int, cy: Int, r: Int, isDon: Boolean, big: Boolean, alpha: Int) {
        NoteSkinManager.drawNote(g, cx, cy, r, isDon, big, alpha) { g, x, y, rr, aa ->
            val a = aa.coerceIn(0, 255) shl 24
            val c = if (isDon) donColor else kaColor
            drawDisc(g, x, y, rr, a or 0xFFFFFF, 1)
            drawDisc(g, x, y, (rr - 4).coerceAtLeast(2), a or (c and 0xFFFFFF), 1)
            if (big) drawDisc(g, x, y, (rr / 5).coerceAtLeast(2), a or 0xFFFFFF, 1)
        }
    }

    private fun drawRolls(g: GuiGraphics, now: Double) {
        for (roll in session.chart.rolls) {
            if (roll.endMs < now - 500) continue
            val x1 = timeToX(roll.startMs, now)
            val x2 = timeToX(roll.endMs, now)
            if (x2 < judgeX - noteR) continue
            drawBar(g, x1, x2, center1P, true)
        }
    }

    private fun drawBalloons(g: GuiGraphics, now: Double) {
        for (balloon in session.chart.balloons) {
            if (balloon.endMs < now - 500) continue
            val x1 = timeToX(balloon.startMs, now)
            val x2 = timeToX(balloon.endMs, now)
            if (x2 < judgeX - noteR) continue
            drawBar(g, x1, x2, center1P, false)

            val mid = (maxOf(x1, judgeX) + minOf(x2, laneRight)) / 2
            val remain = (balloon.requiredHits - session.judge.balloonHitsOf(balloon)).coerceAtLeast(0)
            drawDisc(g, mid, center1P, laneH / 2, 0xFFFFFFFF.toInt(), 1)
            drawDisc(g, mid, center1P, laneH / 2 - 3, 0xFF5BA3C9.toInt(), 1)
            g.drawCenteredString(font, remain.toString(), mid, center1P - 4, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawBar(g: GuiGraphics, x1: Int, x2: Int, cy: Int, isRoll: Boolean) {
        val left = minOf(x1, x2).coerceAtLeast(judgeX)
        val right = maxOf(x1, x2).coerceAtMost(laneRight)
        if (right <= left) return

        if (NoteSkinManager.drawRollOrBalloon(g, left, right, cy, laneH, isRoll)) return

        val h = (noteR * 3 / 2).coerceAtLeast(8)
        val top = cy - h / 2
        val color = if (isRoll) 0xFFB84040.toInt() else 0xFF5BA3C9.toInt()
        g.fill(left, top - 2, right, top + h + 2, 0xFFFFFFFF.toInt())
        g.fill(left, top, right, top + h, color)
        drawDisc(g, left, cy, h / 2 + 2, 0xFFFFFFFF.toInt(), 1)
        drawDisc(g, right, cy, h / 2 + 2, 0xFFFFFFFF.toInt(), 1)
        drawDisc(g, left, cy, h / 2, color, 1)
        drawDisc(g, right, cy, h / 2, color, 1)
    }

    private fun timeToX(timeMs: Double, now: Double): Int {
        val d = timeMs - now
        return judgeX + (d * bpmAt(timeMs) / 240000.0 * beatSpan).toInt()
    }

    private fun bpmAt(timeMs: Double): Double {
        val notes = session.judge.notesSnapshot()
        if (notes.isEmpty()) return session.chart.bpm
        return notes[lowerBound(notes, timeMs).coerceIn(0, notes.size - 1)].bpmAtNote
    }

    private fun drawLaneFlash(g: GuiGraphics) {
        val r = session.lastResult ?: return
        val age = session.judgeTimeMs - session.lastResultAtMs
        if (age < 0 || age > 100) return
        val alpha = ((1 - age / 100.0) * 50).toInt().coerceIn(0, 55)
        if (alpha <= 0) return
        val c = if (r.color == NoteColor.DON) 0xE8453C else 0x4FC3D9
        g.fill(judgeX, center1P - laneH / 2, laneRight, center1P + laneH / 2, (alpha shl 24) or c)
        if (show2P) g.fill(judgeX, center2P - laneH / 2, laneRight, center2P + laneH / 2, (alpha shl 24) or c)
    }

    /** 4 区闪光：只在判定命中时触发 */
    private fun updatePadFlash() {
        for (i in padFlash.indices) {
            if (padFlash[i] > 0) padFlash[i] -= 16.0
        }
        val result = session.lastResult ?: return
        val age = session.judgeTimeMs - session.lastResultAtMs
        if (age < 0 || age > 50) return
        if (lastResultRef === result) return
        lastResultRef = result
        if (result.judgment == Judgment.MISS) return

        if (result.color == NoteColor.DON) {
            padFlash[1] = 100.0
            padFlash[2] = 100.0
        } else {
            padFlash[0] = 100.0
            padFlash[3] = 100.0
        }
    }

    private fun drawFlatTaiko(g: GuiGraphics) {
        val cx = (judgeX / 2).coerceAtLeast(30)
        val cy = center1P
        val r = (noteR * 2).coerceIn(24, 64)
        val innerR = (r * 55 / 100).coerceAtLeast(10)

        drawDisc(g, cx, cy, r, 0xFFE8E2DA.toInt(), 2)
        drawDisc(g, cx, cy, innerR, 0xFFC9BEB2.toInt(), 2)

        for (i in padFlash.indices) {
            val t = padFlash[i]
            if (t <= 0) continue
            val a = ((t / 100.0).coerceIn(0.0, 1.0) * 220).toInt() shl 24
            when (i) {
                0 -> drawHalf(g, cx, cy, innerR, r, a or 0xFF4FC3D9.toInt(), true)
                3 -> drawHalf(g, cx, cy, innerR, r, a or 0xFF4FC3D9.toInt(), false)
                1 -> drawHalf(g, cx, cy, 0, innerR, a or 0xFFE8453C.toInt(), true)
                2 -> drawHalf(g, cx, cy, 0, innerR, a or 0xFFE8453C.toInt(), false)
            }
        }
    }

    private fun drawHalf(g: GuiGraphics, cx: Int, cy: Int, innerR: Int, outerR: Int, color: Int, left: Boolean) {
        if (outerR <= 0) return
        var dy = -outerR
        while (dy <= outerR) {
            val y = cy + dy
            val o = sqrt((outerR.toDouble() * outerR - dy * dy).coerceAtLeast(0.0)).toInt()
            val i = if (innerR > 0 && abs(dy) <= innerR)
                sqrt((innerR.toDouble() * innerR - dy * dy).coerceAtLeast(0.0)).toInt() else 0
            if (left) g.fill(cx - o, y, cx - i, y + 2, color)
            else g.fill(cx + i, y, cx + o + 1, y + 2, color)
            dy += 2
        }
    }

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
        val fx = judgeX + noteR * 3
        g.drawString(font, text, fx, center1P - noteR - 4, color)

        val noteTime = result.noteTimeMs ?: return
        val off = session.lastResultAtMs - noteTime
        if (abs(off) > 400) return
        val sign = if (off >= 0) "+" else "−"
        g.drawString(
            font, "$sign${"%.1f".format(abs(off))}ms", fx, center1P - noteR + 8,
            if (abs(off) <= 25) 0xFFFFD24A.toInt() else 0xFF999999.toInt(),
        )
    }

    private fun drawInfo(g: GuiGraphics, now: Double) {
        var y = 4
        g.drawString(font, "TaikoCraft", 4, y, 0xFFAAAAAA.toInt()); y += 10
        g.drawString(font, "谱面 ${"%.0f".format(now)}ms", 4, y, 0xFFCCCCCC.toInt()); y += 10
        g.drawString(font, "音频 ${"%.0f".format(session.clock.songPositionMs)}ms", 4, y, 0xFFCCCCCC.toInt()); y += 10
        g.drawString(
            font, "时差 ${"%.0f".format(now - session.clock.songPositionMs - session.clock.tjaOffsetMs)}ms",
            4, y, 0xFF888888.toInt(),
        ); y += 10
        g.drawString(font, "目 ${session.clock.visualOffsetMs.toInt()} 音 ${session.clock.audioOffsetMs.toInt()}", 4, y, 0xFF888888.toInt()); y += 14
        g.drawString(font, "分 ${session.judge.currentScore}", 4, y, 0xFFFFD24A.toInt()); y += 11
        g.drawString(font, "连 ${session.judge.currentCombo}", 4, y, 0xFF7FE87F.toInt()); y += 11
        if (session.autoPlay) g.drawString(font, "AUTO", 4, y, 0xFFFFD24A.toInt())
    }

    private fun drawDisc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int, step: Int) {
        if (r <= 0) return
        val r2 = r.toLong() * r
        var dy = -r
        while (dy <= r) {
            val half = sqrt((r2 - dy.toLong() * dy).toDouble()).toInt()
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + step, color)
            dy += step
        }
    }

    private fun drawRing(g: GuiGraphics, cx: Int, cy: Int, r: Int, thickness: Int, color: Int) {
        if (r <= 0) return
        val out2 = r.toLong() * r
        val inR = (r - thickness).coerceAtLeast(1)
        val in2 = inR.toLong() * inR
        var dy = -r
        while (dy <= r) {
            val o = sqrt((out2 - dy.toLong() * dy).toDouble()).toInt()
            val i = if (abs(dy) <= inR) sqrt((in2 - dy.toLong() * dy).toDouble()).toInt() else 0
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
