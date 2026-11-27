package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.chart.BarLine
import top.drawtwice.taikocraft.chart.Note
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Judgment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 游戏界面 —— **完全按 OpenTaiko 皮肤包布局重做**。
 *
 * ## 所有数值来源（不是自己编的）
 * `OpenTaiko/System/Open-World Memories/GameConfig.ini`：
 * ```
 * Game_Lane_X=499,499      Game_Lane_Y=287,552
 * Game_ScrollField_X=523   Game_ScrollField_Y=286
 * Game_Notes_Size=195,195
 * Game_Notes_Interval=960        <- 每 4 拍的像素数
 * Game_Taiko_X=303         Game_Taiko_Y=324
 * Game_Judge_X=554         Game_Judge_Y=234
 * Game_Taiko_Combo_X=391   Game_Taiko_Combo_Y=406
 * ```
 *
 * ## 音符滚动公式（`NotesManager.getN4Beats` + `PxFromN4BeatsX`）
 * ```
 * n4Beats = Δms × bpm / 240000        (EScrollMode.Normal)
 * x = n4Beats × Game_Notes_Interval × screenScale
 * ```
 *
 * ## 慢音符不许「从中间冒出」
 * 可见性不能用一个基于 `maxBpm` 的时间窗一刀切 —— 慢 BPM 的音符走得慢，
 * 在同样的时间窗里它**已经进入屏幕**了，却被时间窗截掉不画，下一帧才出现在中间。
 * 正确做法：时间窗按 **minBpm**（走得最慢的）算，再对每个音符单独判断是否已出屏。
 *
 * ## 左侧鼓的四个判定区
 * OpenTaiko 的 `Don.png` / `Ka.png` 是**整张 180×200 的鼓面高亮图**：
 * - `Don.png` 左半 → 左咚，右半 → 右咚
 * - `Ka.png` 左半 → 左咔，右半 → 右咔
 * 按 `nBrightness × 73` 作不透明度贴到鼓上，四个区域天然分开。
 * 厚度由图片本身决定 —— **不再用代码画圈**，所以鼓边自然很细。
 */
class TaikoScreen(
    private val session: PlaySession,
    private val previous: Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft")) {

    private var scale = 1f
    private fun sx(v: Int) = (v * scale).toInt()

    private var noteOriginX = 0
    private var noteOriginY1P = 0
    private var noteOriginY2P = 0
    private var noteSize = 0
    private var interval = 1f
    private var laneX = 0
    private var laneY1P = 0
    private var laneY2P = 0
    private var show2P = false

    /** 谱面最慢 BPM（时间窗用它算，保证慢音符提前出现） */
    private val minBpm: Double by lazy {
        session.chart.notes.minOfOrNull { it.bpmAtNote } ?: session.chart.bpm
    }

    /** 四个判定区亮度 0..8（OpenTaiko `tMtaikoEvent` 置 8，每 20ms −1） */
    private val padBrightness = IntArray(8)
    private var flashTickNanos = 0L
    private var lastFxResult: Any? = null

    override fun init() {
        super.init()
        session.start()
        flashTickNanos = System.nanoTime()
    }

    override fun renderBackdrop(g: GuiGraphics) {
        g.fill(0, 0, width, height, 0xFF000000.toInt())
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.tick()
        val now = session.renderTimeMs
        val judgeNow = session.judgeTimeMs
        val notes = session.judge.notesSnapshot()

        scale = TaikoGameLayout.scaleFor(width)
        noteOriginX = sx(TaikoGameLayout.SCROLL_FIELD_X)
        noteOriginY1P = sx(TaikoGameLayout.SCROLL_FIELD_Y)
        noteOriginY2P = noteOriginY1P + sx(TaikoGameLayout.P2_DY)
        noteSize = sx(TaikoGameLayout.NOTES_SIZE)
        interval = TaikoGameLayout.NOTES_INTERVAL * scale
        laneX = sx(TaikoGameLayout.LANE_X)
        laneY1P = sx(TaikoGameLayout.LANE_Y)
        laneY2P = sx(TaikoGameLayout.LANE_Y + TaikoGameLayout.P2_DY)
        show2P = notes.any { it.player == 2 }

        tickPadBrightness()

        drawLanes(g)
        drawBarLines(g, now)
        drawRolls(g, now)
        drawBalloons(g, now)
        drawHitCircle(g, 1)
        if (show2P) drawHitCircle(g, 2)
        drawNotes(g, now, notes)
        drawExplosions(g, judgeNow)
        drawFlatDrum(g, 1)
        if (show2P) drawFlatDrum(g, 2)
        drawJudgeText(g, judgeNow)
        drawComboAndScore(g)
        drawDebug(g, now)
    }

    // ================= 轨道 =================

    private fun drawLanes(g: GuiGraphics) {
        val w = sx(1422)
        val h = sx(195)
        TexDraw.full(g, TaikoGameTextures.laneMain, laneX, laneY1P, w, h, 1422, 195)
        if (show2P) TexDraw.full(g, TaikoGameTextures.laneMain, laneX, laneY2P, w, h, 1422, 195)

        val subW = sx(1421)
        val subH = sx(39)
        TexDraw.full(
            g, TaikoGameTextures.laneSub, laneX,
            sx(TaikoGameLayout.LANE_SUB_Y), subW, subH, 1421, 39,
        )
        if (show2P) TexDraw.full(
            g, TaikoGameTextures.laneSub, laneX,
            sx(TaikoGameLayout.LANE_SUB_Y + TaikoGameLayout.P2_DY), subW, subH, 1421, 39,
        )
    }

    private fun drawBarLines(g: GuiGraphics, now: Double) {
        val barW = max(1, sx(4))
        for (bar in session.chart.barLines) {
            for (player in 1..2) {
                if (player == 2 && !show2P) continue
                val mult = bpmMultOf(session.chart.barLines, bar)
                val n4 = (bar.timeMs - now) * bar.bpmAtBar / 240000.0
                @Suppress("UNUSED_VARIABLE") val unused = mult
                val x = noteOriginX + (n4 * interval).toInt() + noteSize / 2 - barW / 2
                if (x < laneX || x > width) continue
                TexDraw.full(
                    g, TaikoGameTextures.barLine, x,
                    if (player == 1) noteOriginY1P else noteOriginY2P,
                    barW, noteSize, 4, 270,
                )
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun bpmMultOf(list: List<BarLine>, bar: BarLine): Double = 1.0

    // ================= 判定圈 =================

    private fun drawHitCircle(g: GuiGraphics, player: Int) {
        val y = if (player == 1) noteOriginY1P else noteOriginY2P
        TexDraw.tinted(g, 1f, 1f, 1f, 0.55f) {
            TexDraw.full(
                g, TaikoGameTextures.hitCircle, noteOriginX, y,
                noteSize, noteSize, 195, 195,
            )
        }
    }

    // ================= 音符 =================

    private fun drawNotes(g: GuiGraphics, now: Double, notes: List<Note>) {
        // 时间窗按最慢 BPM 算 → 慢音符不会被截掉
        val maxN4 = if (interval <= 0f) 0.0 else (width - noteOriginX) / interval.toDouble()
        val aheadMs = 240000.0 * maxN4 / minBpm.coerceAtLeast(1.0)

        var i = lowerBound(notes, now - 250.0)
        while (i < notes.size) {
            val idx = i
            val n = notes[i]
            if (n.timeMs > now + aheadMs) break
            i++
            if (n.player == 2 && !show2P) continue
            if (session.judge.isJudged(idx)) continue // 已打中：消失

            val d = n.timeMs - now
            val x = noteOriginX + (d * n.bpmAtNote / 240000.0 * interval).toInt()
            // 每个音符单独判断是否还在屏幕内
            if (x > width || x < noteOriginX - noteSize) continue

            val y = if (n.player == 1) noteOriginY1P else noteOriginY2P
            val col = when {
                n.color == NoteColor.DON && n.big -> 2
                n.color == NoteColor.KA && n.big -> 3
                n.color == NoteColor.DON -> 0
                else -> 1
            }
            TexDraw.sub(
                g, TaikoGameTextures.notes, x, y, noteSize, noteSize,
                col * 195, 0, 195, 195,
                TaikoGameTextures.NOTES_COLS * 195, 195,
            )
        }
    }

    // ================= 连打 / 气球 =================

    private fun drawRolls(g: GuiGraphics, now: Double) {
        for (roll in session.chart.rolls) {
            if (roll.endMs < now - 300) continue
            if (roll.player == 2 && !show2P) continue
            drawSegment(
                g, now, roll.startMs, roll.endMs,
                TaikoGameTextures.rolls, TaikoGameTextures.ROLLS_COLS,
                if (roll.big) 1 else 0,
                if (roll.player == 1) noteOriginY1P else noteOriginY2P,
                bpmAt(roll.startMs),
            )
        }
    }

    private fun drawBalloons(g: GuiGraphics, now: Double) {
        for (b in session.chart.balloons) {
            if (b.endMs < now - 300) continue
            if (b.player == 2 && !show2P) continue
            val bpm = bpmAt(b.startMs)
            drawSegment(
                g, now, b.startMs, b.endMs,
                TaikoGameTextures.balloons, TaikoGameTextures.BALLOON_COLS, 0,
                if (b.player == 1) noteOriginY1P else noteOriginY2P,
                bpm,
            )
            val remain = (b.requiredHits - session.judge.balloonHitsOf(b)).coerceAtLeast(0)
            val xm = noteOriginX + (((b.startMs + b.endMs) / 2 - now) * bpm / 240000.0 * interval).toInt()
            if (xm in noteOriginX..width) {
                val cy = (if (b.player == 1) noteOriginY1P else noteOriginY2P) + noteSize / 2
                g.drawCenteredString(font, remain.toString(), xm, cy - 4, 0xFFFFFFFF.toInt())
            }
        }
    }

    /**
     * 画「头 + 身 + 尾」。
     *
     * `rolls.png` / `balloons.png` 是每 3 列一组：`[头, 身, 尾]`，第 n 组起始列 = n×3。
     */
    private fun drawSegment(
        g: GuiGraphics,
        now: Double,
        startMs: Double,
        endMs: Double,
        tex: net.minecraft.resources.ResourceLocation,
        cols: Int,
        group: Int,
        y: Int,
        bpm: Double,
    ) {
        val headX = noteOriginX + (((startMs - now) * bpm / 240000.0) * interval).toInt()
        val tailX = noteOriginX + (((endMs - now) * bpm / 240000.0) * interval).toInt()
        val headCol = group * 3
        val bodyCol = headCol + 1
        val tailCol = headCol + 2
        val texW = cols * 195

        // 身（先画，被头尾盖住两端）
        val bodyLeft = max(headX + noteSize / 2, noteOriginX)
        val bodyRight = min(tailX + noteSize / 2, width)
        if (bodyRight > bodyLeft) {
            TexDraw.sub(
                g, tex, bodyLeft, y, bodyRight - bodyLeft, noteSize,
                bodyCol * 195, 0, 195, 195, texW, 195,
            )
        }
        // 头
        if (headX > noteOriginX - noteSize && headX < width) {
            TexDraw.sub(g, tex, headX, y, noteSize, noteSize, headCol * 195, 0, 195, 195, texW, 195)
        }
        // 尾
        if (tailX > noteOriginX - noteSize && tailX < width) {
            TexDraw.sub(g, tex, tailX, y, noteSize, noteSize, tailCol * 195, 0, 195, 195, texW, 195)
        }
    }

    /** 取某个时刻的 BPM（沿用小节线记录的 BPM） */
    private fun bpmAt(t: Double): Double {
        val bars = session.chart.barLines
        if (bars.isEmpty()) return session.chart.bpm
        var lo = 0
        var hi = bars.size - 1
        var best = 0
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (bars[m].timeMs <= t) { best = m; lo = m + 1 } else hi = m - 1
        }
        return bars[best].bpmAtBar
    }

    // ================= 打击特效 =================

    private fun drawExplosions(g: GuiGraphics, judgeNow: Double) {
        for (fx in session.hitFx) {
            val age = judgeNow - fx.startMs
            if (age < 0 || age > 180) continue
            val t = (age / 180.0).coerceIn(0.0, 1.0)
            val size = (noteSize * (1.0 + t * 0.7)).toInt()
            val alpha = ((1.0 - t) * 0.9).toFloat()
            val cy = if (fx.player == 1) noteOriginY1P else noteOriginY2P
            val x = noteOriginX + noteSize / 2 - size / 2
            val y = cy + noteSize / 2 - size / 2
            TexDraw.tinted(g, 1f, 1f, 1f, alpha) {
                TexDraw.full(
                    g,
                    if (fx.big) TaikoGameTextures.explosionBig else TaikoGameTextures.explosion,
                    x, y, size, size, 195, 195,
                )
            }
        }
    }

    // ================= 左侧平面太鼓（四个判定区）=================

    /**
     * OpenTaiko `CActImplMtaiko.Draw()` 顺序：
     * 1. `Base.png` 打底（鼓身）
     * 2. `Ka.png` 左半 / 右半（鼓边 = 外圈）
     * 3. `Don.png` 左半 / 右半（鼓面 = 内圈）
     *
     * 不透明度 = `brightness × 73 / 255`，brightness 每次击打置 8、每 20ms −1。
     * 图片本身决定鼓边粗细 —— 所以是原版的细边。
     */
    private fun drawFlatDrum(g: GuiGraphics, player: Int) {
        val ox = sx(TaikoGameLayout.TAIKO_X)
        val oy = sx(TaikoGameLayout.TAIKO_Y + TaikoGameLayout.playerYOffset(player))
        val w = sx(180)
        val h = sx(200)
        val base = (player - 1) * 4

        TexDraw.full(g, TaikoGameTextures.drumBase, ox, oy, w, h, 180, 200)

        val half = w / 2
        // 鼓边（Ka）：0 左、1 右
        for (i in 0..1) {
            val b = padBrightness[base + i]
            if (b <= 0) continue
            val a = min(1f, b * 73 / 255f)
            TexDraw.tinted(g, 1f, 1f, 1f, a) {
                TexDraw.sub(
                    g, TaikoGameTextures.drumKa, ox + i * half, oy, half, h,
                    i * 90, 0, 90, 200, 180, 200,
                )
            }
        }
        // 鼓面（Don）：2 左、3 右
        for (i in 0..1) {
            val b = padBrightness[base + 2 + i]
            if (b <= 0) continue
            val a = min(1f, b * 73 / 255f)
            TexDraw.tinted(g, 1f, 1f, 1f, a) {
                TexDraw.sub(
                    g, TaikoGameTextures.drumDon, ox + i * half, oy, half, h,
                    i * 90, 0, 90, 200, 180, 200,
                )
            }
        }
    }

    private fun tickPadBrightness() {
        val now = System.nanoTime()
        if (flashTickNanos == 0L) flashTickNanos = now
        while (now - flashTickNanos >= 20_000_000L) {
            for (i in padBrightness.indices) if (padBrightness[i] > 0) padBrightness[i]--
            flashTickNanos += 20_000_000L
        }
        val r = session.lastResult ?: return
        if (lastFxResult === r) return
        lastFxResult = r
        if (r.judgment == Judgment.MISS) return
        val b = (r.player - 1).coerceIn(0, 1) * 4
        val left = r.side == top.drawtwice.taikocraft.judge.Side.LEFT
        if (r.color == NoteColor.DON) {
            if (r.big) { padBrightness[b + 2] = 8; padBrightness[b + 3] = 8 }
            else padBrightness[b + if (left) 2 else 3] = 8
        } else {
            if (r.big) { padBrightness[b] = 8; padBrightness[b + 1] = 8 }
            else padBrightness[b + if (left) 0 else 1] = 8
        }
    }

    // ================= 判定文字 / 调试 =================

    private fun drawJudgeText(g: GuiGraphics, judgeNow: Double) {
        val r = session.lastResult ?: return
        val age = judgeNow - session.lastResultAtMs
        if (age < 0 || age > 700) return
        val text = when (r.judgment) {
            Judgment.PERFECT -> "良"
            Judgment.GOOD -> "可"
            Judgment.POOR -> "不可"
            Judgment.MISS -> "不可"
        }
        val c = when (r.judgment) {
            Judgment.PERFECT -> 0xFFFFD24A.toInt()
            Judgment.GOOD -> 0xFFFFFFFF.toInt()
            Judgment.POOR -> 0xFFAAAAAA.toInt()
            Judgment.MISS -> 0xFFFF5555.toInt()
        }
        val fade = if (age > 500) ((700 - age) / 200.0).toFloat() else 1f
        val x = sx(TaikoGameLayout.JUDGE_X)
        val y = sx(TaikoGameLayout.JUDGE_Y)
        TexDraw.tinted(g, 1f, 1f, 1f, fade.coerceIn(0f, 1f)) {
            g.drawString(font, text, x, y, c)
        }

        // 调试：早/晚多少 ms（坠尔要求保留）
        val nt = r.noteTimeMs ?: return
        val off = session.lastResultAtMs - nt
        if (abs(off) > 400) return
        val sign = if (off >= 0) "+" else "−"
        val col = if (abs(off) <= 25) 0xFFFFD24A.toInt() else 0xFF999999.toInt()
        g.drawString(font, "$sign${"%.1f".format(abs(off))}ms", x, y + 14, col)
    }

    private fun drawComboAndScore(g: GuiGraphics) {
        val combo = session.judge.currentCombo
        if (combo > 0) {
            g.drawString(
                font, combo.toString(),
                sx(TaikoGameLayout.COMBO_X), sx(TaikoGameLayout.COMBO_Y),
                0xFFFFD24A.toInt(),
            )
        }
        g.drawString(
            font, session.judge.currentScore.toString(),
            sx(TaikoGameLayout.SCORE_X), sx(TaikoGameLayout.SCORE_Y),
            0xFFFFFFFF.toInt(),
        )
    }

    /** 调试信息（坠尔要求保留） */
    private fun drawDebug(g: GuiGraphics, now: Double) {
        var y = 4
        g.drawString(font, "谱面 ${"%.0f".format(now)}ms", 4, y, 0xFF88FF88.toInt()); y += 10
        g.drawString(font, "音频 ${"%.0f".format(session.clock.songPositionMs)}ms", 4, y, 0xFF88FF88.toInt()); y += 10
        g.drawString(font, "目 ${session.clock.visualOffsetMs.toInt()} 音 ${session.clock.audioOffsetMs.toInt()}", 4, y, 0xFF888888.toInt()); y += 10
        g.drawString(font, "分数 ${session.judge.currentScore}  连击 ${session.judge.currentCombo}", 4, y, 0xFFFFD24A.toInt()); y += 10
        val st = session.audioStatus
        if (st.isNotEmpty()) g.drawString(font, st, 4, y, 0xFFFF6666.toInt())
    }

    private fun lowerBound(notes: List<Note>, t: Double): Int {
        var lo = 0
        var hi = notes.size
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
