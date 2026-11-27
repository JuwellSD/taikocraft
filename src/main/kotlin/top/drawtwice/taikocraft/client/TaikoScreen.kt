package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 游戏界面。
 *
 * ## 尺寸（从坠尔实机截图实测）
 * 音符直径 3.33% 屏宽，4 拍跨度 53.3% 屏宽。
 *
 * ## 打击圈
 * 判定位置画**与音符同大小的圆圈轮廓**，音符到达时正好覆盖它。
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
    private var lastRef: Any? = null

    override fun init() {
        super.init()
        session.start()
    }

    override fun renderBackdrop(g: GuiGraphics) {
        g.fill(0, 0, width, height, 0xFF101014.toInt())
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.tick()
        val now = session.renderTimeMs
        val notes = session.judge.notesSnapshot()

        judgeX = (width * 0.26).toInt()
        laneRight = width - (width * 0.05).toInt()
        noteR = (width * 0.0333 / 2).toInt().coerceAtLeast(9)
        beatSpan = width * 0.533
        laneH = (height * 0.10).toInt().coerceAtLeast(30)
        center1P = (height * 0.33).toInt()
        center2P = (height * 0.50).toInt()
        show2P = notes.any { it.player == 2 }

        drawLane(g, center1P)
        if (show2P) drawLane(g, center2P)

        // 小节线
        for (bar in session.chart.barLines) {
            val n4 = (bar.timeMs - now) * bar.bpmAtBar / 240000.0
            val x = judgeX + (n4 * beatSpan).toInt()
            if (x < judgeX || x > laneRight) continue
            g.fill(x, center1P - laneH / 2, x + 1, center1P + laneH / 2, 0x40FFFFFF)
            if (show2P) g.fill(x, center2P - laneH / 2, x + 1, center2P + laneH / 2, 0x40FFFFFF)
        }

        drawRolls(g, now)
        drawBalloons(g, now)
        drawNotes(g, now, notes)
        drawLaneFlash(g)
        updatePadFlash()
        drawFlatTaiko(g)
        drawFeedback(g)
        drawInfo(g, now)
    }

    private fun drawLane(g: GuiGraphics, cy: Int) {
        g.fill(judgeX, cy - laneH / 2, laneRight, cy + laneH / 2, 0xE62B2E33.toInt())
        g.fill(judgeX, cy - laneH / 2, laneRight, cy - laneH / 2 + 1, 0xFFB0B0B0.toInt())
        g.fill(judgeX, cy + laneH / 2 - 1, laneRight, cy + laneH / 2, 0xFFB0B0B0.toInt())
    }

    private fun drawNotes(g: GuiGraphics, now: Double, notes: List<top.drawtwice.taikocraft.chart.Note>) {
        // 打击圈（音符之前画，被覆盖即命中）
        drawRing(g, judgeX, center1P, noteR + 2, 3, 0xC0FFFFFF.toInt())
        if (show2P) drawRing(g, judgeX, center2P, noteR + 2, 3, 0xC0FFFFFF.toInt())

        val maxN4 = (laneRight - judgeX) / beatSpan
        val maxBpm = notes.maxOfOrNull { it.bpmAtNote } ?: 200.0
        val ahead = 240000.0 * maxN4 / maxBpm

        var i = lowerBound(notes, now - 200.0)
        while (i < notes.size) {
            val idx = i
            val n = notes[i]
            if (n.timeMs > now + ahead) break
            i++
            if (n.player == 2 && !show2P) continue
            val d = n.timeMs - now
            val x = judgeX + (d * n.bpmAtNote / 240000.0 * beatSpan).toInt()
            if (x < judgeX - noteR * 2 || x > laneRight + noteR * 2) continue
            val cy = if (n.player == 1) center1P else center2P
            val alpha = if (session.judge.isJudged(idx)) 45 else 255
            val r = if (n.big) noteR * 5 / 4 else noteR
            val c = if (n.color == NoteColor.DON) donColor else kaColor
            val a = alpha shl 24
            drawDisc(g, x, cy, r, a or 0xFFFFFF, 1)
            drawDisc(g, x, cy, (r - 4).coerceAtLeast(2), a or (c and 0xFFFFFF), 1)
            if (n.big) drawDisc(g, x, cy, (r / 5).coerceAtLeast(2), a or 0xFFFFFF, 1)
        }
    }

    private fun drawRolls(g: GuiGraphics, now: Double) {
        for (roll in session.chart.rolls) {
            if (roll.endMs < now - 500) continue
            val x1 = xOf(roll.startMs, now)
            val x2 = xOf(roll.endMs, now)
            drawBar(g, x1, x2, center1P, 0xFFB84040.toInt())
        }
    }

    private fun drawBalloons(g: GuiGraphics, now: Double) {
        for (b in session.chart.balloons) {
            if (b.endMs < now - 500) continue
            val x1 = xOf(b.startMs, now)
            val x2 = xOf(b.endMs, now)
            drawBar(g, x1, x2, center1P, 0xFF5BA3C9.toInt())
            val mid = (maxOf(x1, judgeX) + minOf(x2, laneRight)) / 2
            val remain = (b.requiredHits - session.judge.balloonHitsOf(b)).coerceAtLeast(0)
            drawDisc(g, mid, center1P, laneH / 2, 0xFFFFFFFF.toInt(), 1)
            drawDisc(g, mid, center1P, laneH / 2 - 3, 0xFF5BA3C9.toInt(), 1)
            g.drawCenteredString(font, remain.toString(), mid, center1P - 4, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawBar(g: GuiGraphics, x1: Int, x2: Int, cy: Int, color: Int) {
        val l = minOf(x1, x2).coerceAtLeast(judgeX)
        val r = maxOf(x1, x2).coerceAtMost(laneRight)
        if (r <= l) return
        val h = (noteR * 3 / 2).coerceAtLeast(8)
        val top = cy - h / 2
        g.fill(l, top - 2, r, top + h + 2, 0xFFFFFFFF.toInt())
        g.fill(l, top, r, top + h, color)
        drawDisc(g, l, cy, h / 2 + 2, 0xFFFFFFFF.toInt(), 1)
        drawDisc(g, r, cy, h / 2 + 2, 0xFFFFFFFF.toInt(), 1)
    }

    private fun xOf(t: Double, now: Double): Int {
        val notes = session.judge.notesSnapshot()
        val bpm = if (notes.isEmpty()) session.chart.bpm
        else notes[lowerBound(notes, t).coerceIn(0, notes.size - 1)].bpmAtNote
        return judgeX + ((t - now) * bpm / 240000.0 * beatSpan).toInt()
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

    private fun updatePadFlash() {
        for (i in padFlash.indices) if (padFlash[i] > 0) padFlash[i] -= 16.0
        val r = session.lastResult ?: return
        val age = session.judgeTimeMs - session.lastResultAtMs
        if (age < 0 || age > 50) return
        if (lastRef === r) return
        lastRef = r
        if (r.judgment == Judgment.MISS) return
        if (r.color == NoteColor.DON) { padFlash[1] = 100.0; padFlash[2] = 100.0 }
        else { padFlash[0] = 100.0; padFlash[3] = 100.0 }
    }

    private fun drawFlatTaiko(g: GuiGraphics) {
        val cx = (judgeX / 2).coerceAtLeast(30)
        val cy = center1P
        val r = (noteR * 2).coerceIn(24, 64)
        val inner = (r * 55 / 100).coerceAtLeast(10)
        drawDisc(g, cx, cy, r, 0xFFE8E2DA.toInt(), 2)
        drawDisc(g, cx, cy, inner, 0xFFC9BEB2.toInt(), 2)

        for (i in padFlash.indices) {
            val t = padFlash[i]
            if (t <= 0) continue
            val a = ((t / 100.0).coerceIn(0.0, 1.0) * 220).toInt() shl 24
            when (i) {
                0 -> drawHalf(g, cx, cy, inner, r, a or 0xFF4FC3D9.toInt(), true)
                3 -> drawHalf(g, cx, cy, inner, r, a or 0xFF4FC3D9.toInt(), false)
                1 -> drawHalf(g, cx, cy, 0, inner, a or 0xFFE8453C.toInt(), true)
                2 -> drawHalf(g, cx, cy, 0, inner, a or 0xFFE8453C.toInt(), false)
            }
        }
    }

    private fun drawHalf(g: GuiGraphics, cx: Int, cy: Int, inR: Int, outR: Int, color: Int, left: Boolean) {
        if (outR <= 0) return
        var dy = -outR
        while (dy <= outR) {
            val o = sqrt((outR.toDouble() * outR - dy * dy).coerceAtLeast(0.0)).toInt()
            val i = if (inR > 0 && abs(dy) <= inR)
                sqrt((inR.toDouble() * inR - dy * dy).coerceAtLeast(0.0)).toInt() else 0
            if (left) g.fill(cx - o, cy + dy, cx - i, cy + dy + 2, color)
            else g.fill(cx + i, cy + dy, cx + o + 1, cy + dy + 2, color)
            dy += 2
        }
    }

    private fun drawFeedback(g: GuiGraphics) {
        val r = session.lastResult ?: return
        val age = session.judgeTimeMs - session.lastResultAtMs
        if (age < 0 || age > 600) return
        val text = when (r.judgment) {
            Judgment.PERFECT -> "良"; Judgment.GOOD -> "可"
            Judgment.POOR -> "不可"; Judgment.MISS -> "不可"
        }
        val c = when (r.judgment) {
            Judgment.PERFECT -> 0xFFFFD24A.toInt(); Judgment.GOOD -> 0xFFFFFFFF.toInt()
            Judgment.POOR -> 0xFFAAAAAA.toInt(); Judgment.MISS -> 0xFFFF5555.toInt()
        }
        val fx = judgeX + noteR * 3
        g.drawString(font, text, fx, center1P - noteR - 4, c)
        val nt = r.noteTimeMs ?: return
        val off = session.lastResultAtMs - nt
        if (abs(off) > 400) return
        val sign = if (off >= 0) "+" else "−"
        g.drawString(
            font, "$sign${"%.1f".format(abs(off))}ms", fx, center1P - noteR + 8,
            if (abs(off) <= 25) 0xFFFFD24A.toInt() else 0xFF999999.toInt(),
        )
    }

    private fun drawInfo(g: GuiGraphics, now: Double) {
        var y = 4
        g.drawString(font, "谱面 ${"%.0f".format(now)}ms", 4, y, 0xFFCCCCCC.toInt()); y += 10
        g.drawString(font, "音频 ${"%.0f".format(session.clock.songPositionMs)}ms", 4, y, 0xFFCCCCCC.toInt()); y += 10
        g.drawString(font, "目 ${session.clock.visualOffsetMs.toInt()} 音 ${session.clock.audioOffsetMs.toInt()}", 4, y, 0xFF888888.toInt()); y += 12
        g.drawString(font, "分 ${session.judge.currentScore}", 4, y, 0xFFFFD24A.toInt()); y += 11
        g.drawString(font, "连 ${session.judge.currentCombo}", 4, y, 0xFF7FE87F.toInt())
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

    private fun drawRing(g: GuiGraphics, cx: Int, cy: Int, r: Int, th: Int, color: Int) {
        if (r <= 0) return
        val o2 = r.toLong() * r
        val inR = (r - th).coerceAtLeast(1)
        val i2 = inR.toLong() * inR
        var dy = -r
        while (dy <= r) {
            val o = sqrt((o2 - dy.toLong() * dy).toDouble()).toInt()
            val i = if (abs(dy) <= inR) sqrt((i2 - dy.toLong() * dy).toDouble()).toInt() else 0
            g.fill(cx - o, cy + dy, cx - i, cy + dy + 2, color)
            g.fill(cx + i, cy + dy, cx + o + 1, cy + dy + 2, color)
            dy += 2
        }
    }

    private fun lowerBound(notes: List<top.drawtwice.taikocraft.chart.Note>, t: Double): Int {
        var lo = 0; var hi = notes.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (notes[m].timeMs < t) lo = m + 1 else hi = m
        }
        return lo
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        session.stop()
        minecraft?.setScreen(previous ?: SongSelectScreen(null))
    }
}
