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

    override fun init() {
        super.init()
        session.start()
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

            when (NoteStyleManager.current) {
                NoteStyle.SIMPLE -> {
                    // 简约风格：白边空心圆，代码绘制
                    NoteStyleManager.drawSimpleNote(
                        g, x + noteSize / 2, y + noteSize / 2, noteSize / 2,
                        isDon = n.color == NoteColor.DON, big = n.big,
                    )
                }
                NoteStyle.MC -> {
                    val col = when {
                        n.color == NoteColor.DON && n.big -> 2
                        n.color == NoteColor.KA && n.big -> 3
                        n.color == NoteColor.DON -> 0
                        else -> 1
                    }
                    TexDraw.sub(
                        g, NoteStyleManager.mcNotes, x, y, noteSize, noteSize,
                        col * NoteStyleManager.MC_NOTE_CELL, 0,
                        NoteStyleManager.MC_NOTE_CELL, NoteStyleManager.MC_NOTE_CELL,
                        NoteStyleManager.MC_NOTES_W, NoteStyleManager.MC_NOTES_H,
                    )
                }
            }
        }
    }

    // ================= 连打 / 气球 =================

    private fun drawRolls(g: GuiGraphics, now: Double) {
        for (roll in session.chart.rolls) {
            if (roll.endMs < now - 300) continue
            if (roll.player == 2 && !show2P) continue
            val y = if (roll.player == 1) noteOriginY1P else noteOriginY2P
            val bpm = bpmAt(roll.startMs)
            when (NoteStyleManager.current) {
                NoteStyle.SIMPLE -> {
                    val left = noteOriginX + (((roll.startMs - now) * bpm / 240000.0) * interval).toInt()
                    val right = noteOriginX + (((roll.endMs - now) * bpm / 240000.0) * interval).toInt()
                    NoteStyleManager.drawSimpleRoll(
                        g, max(left, noteOriginX), min(right + noteSize, width),
                        y + noteSize / 2, noteSize * 2 / 3, roll.big,
                    )
                }
                NoteStyle.MC -> drawSegment(
                    g, now, roll.startMs, roll.endMs,
                    NoteStyleManager.mcRolls, 6,
                    if (roll.big) 1 else 0, y, bpm,
                    NoteStyleManager.MC_ROLLS_W, NoteStyleManager.MC_ROLLS_H,
                )
            }
        }
    }

    private fun drawBalloons(g: GuiGraphics, now: Double) {
        for (b in session.chart.balloons) {
            if (b.endMs < now - 300) continue
            if (b.player == 2 && !show2P) continue
            val y = if (b.player == 1) noteOriginY1P else noteOriginY2P
            val bpm = bpmAt(b.startMs)
            when (NoteStyleManager.current) {
                NoteStyle.SIMPLE -> {
                    val left = noteOriginX + (((b.startMs - now) * bpm / 240000.0) * interval).toInt()
                    val right = noteOriginX + (((b.endMs - now) * bpm / 240000.0) * interval).toInt()
                    NoteStyleManager.drawSimpleRoll(
                        g, max(left, noteOriginX), min(right + noteSize, width),
                        y + noteSize / 2, noteSize * 2 / 3, false,
                    )
                    val cx = ((max(left, noteOriginX) + min(right + noteSize, width)) / 2)
                    NoteStyleManager.drawSimpleBalloon(g, cx, y + noteSize / 2, noteSize / 3)
                    val remain = (b.requiredHits - session.judge.balloonHitsOf(b)).coerceAtLeast(0)
                    g.drawCenteredString(font, remain.toString(), cx, y + noteSize / 2 - 4, 0xFF000000.toInt())
                }
                NoteStyle.MC -> {
                    drawSegment(
                        g, now, b.startMs, b.endMs,
                        NoteStyleManager.mcBalloon, 3, 0, y, bpm,
                        NoteStyleManager.MC_BALLOON_W, NoteStyleManager.MC_BALLOON_H,
                    )
                    val remain = (b.requiredHits - session.judge.balloonHitsOf(b)).coerceAtLeast(0)
                    val xm = noteOriginX + (((b.startMs + b.endMs) / 2 - now) * bpm / 240000.0 * interval).toInt()
                    if (xm in noteOriginX..width) {
                        g.drawCenteredString(font, remain.toString(), xm, y + noteSize / 2 - 4, 0xFFFFFFFF.toInt())
                    }
                }
            }
        }
    }

    /**
     * 画「头 + 身 + 尾」。
     *
     * `rolls.png` / `balloons.png` 每 3 列一组：`[头, 身, 尾]`，第 n 组起始列 = n×3。
     *
     * ## 关键：身和尾必须**拉伸**，不能平铺
     * 贴图的身列是左右无留白的纯色带，尾列是左端圆角、右端平齐。
     * 所以身体是一整段被水平拉伸的，尾部单独贴在末端 —— 平铺会看到
     * 一格一格的接缝（坠尔截图里那条「一段一段」的长条）。
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
        texW: Int,
        texH: Int,
    ) {
        val headX = noteOriginX + (((startMs - now) * bpm / 240000.0) * interval).toInt()
        val tailX = noteOriginX + (((endMs - now) * bpm / 240000.0) * interval).toInt()
        val headCol = group * 3
        val bodyCol = headCol + 1
        val tailCol = headCol + 2
        val cell = texH

        // 身：从头部**中心**到尾部，拉伸
        val bodyLeft = max(headX + noteSize / 2, noteOriginX)
        val bodyRight = min(tailX + noteSize / 2, width)
        if (bodyRight > bodyLeft) {
            TexDraw.sub(
                g, tex, bodyLeft, y, bodyRight - bodyLeft, noteSize,
                bodyCol * cell, 0, cell, cell, texW, texH,
            )
        }
        // 头
        if (headX > noteOriginX - noteSize && headX < width) {
            TexDraw.sub(g, tex, headX, y, noteSize, noteSize, headCol * cell, 0, cell, cell, texW, texH)
        }
        // 尾（贴一次，不再重复）
        if (tailX > noteOriginX - noteSize && tailX < width) {
            TexDraw.sub(g, tex, tailX, y, noteSize, noteSize, tailCol * cell, 0, cell, cell, texW, texH)
        }
        @Suppress("UNUSED_VARIABLE") val unused = cols
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

    /**
     * 打击特效。
     *
     * `explosion.png` 是 **7 列 × 2 行**的精灵表（每格 390×390）：
     * - 第 0 行 = 良（紫）
     * - 第 1 行 = 可（白）
     * - 不可 **不画特效**（OpenTaiko `CActImplFireworks.cs:184` 的 Miss/Bad 分支是空的）
     *
     * 旧实现把整张 2730×780 压进一个音符方形里 —— 屏幕上那团
     * 紫色和白色的「0000」就是整张精灵表被压扁的结果。
     */
    private fun drawExplosions(g: GuiGraphics, judgeNow: Double) {
        for (fx in session.hitFx) {
            val age = judgeNow - fx.startMs
            if (age < 0 || age > TaikoGameTextures.EXPLOSION_MS) continue
            val t = (age / TaikoGameTextures.EXPLOSION_MS).coerceIn(0.0, 0.9999)
            val cy = if (fx.player == 1) noteOriginY1P else noteOriginY2P
            val cx = noteOriginX + noteSize / 2
            val centerY = cy + noteSize / 2

            when (NoteStyleManager.current) {
                NoteStyle.SIMPLE -> {
                    NoteStyleManager.drawSimpleExplosion(g, cx, centerY, t.toFloat(), fx.big)
                }
                NoteStyle.MC -> {
                    val frame = (t * NoteStyleManager.MC_EXPLOSION_COLS).toInt()
                        .coerceIn(0, NoteStyleManager.MC_EXPLOSION_COLS - 1)
                    val row = if (fx.judgment == Judgment.PERFECT) 0 else 1
                    val size = (noteSize * if (fx.big) 1.9f else 1.5f).toInt()
                    TexDraw.sub(
                        g, NoteStyleManager.mcExplosion,
                        cx - size / 2, centerY - size / 2, size, size,
                        frame * NoteStyleManager.MC_EXPLOSION_CELL,
                        row * NoteStyleManager.MC_EXPLOSION_CELL,
                        NoteStyleManager.MC_EXPLOSION_CELL, NoteStyleManager.MC_EXPLOSION_CELL,
                        NoteStyleManager.MC_EXPLOSION_W, NoteStyleManager.MC_EXPLOSION_H,
                    )
                }
            }
        }
    }

    // ================= 左侧平面太鼓（四个判定区）=================

    /**
     * 左侧平面太鼓（**四个判定区**）。
     *
     * ## 高亮来源（2026-11-28 修正）
     * **只看玩家实际按了哪个键**（`session.padPresses`），不看音符、不看判定。
     * 敲错色、空打、敲到没有音符的位置 —— 都照样亮。
     * 这才是「键位示意」应有的语义。
     *
     * ## 渐隐（坠尔要求）
     * 每次按键记录 `System.nanoTime()`，亮度按
     * `1 - 经过时间 / 400ms` 线性淡出，不再突兀地「啪」一下消失。
     *
     * ## 贴图结构（实测像素 alpha）
     * - `don.png`（180×200）：**实心圆**，alpha 集中在核心 r<40 → 鼓面
     * - `ka.png`（180×200）：**中空环**，alpha 集中在外环 60~85，中心透明 → 鼓边
     *
     * ## 四区怎么来的
     * OpenTaiko `CActImplMtaiko.cs:407-420` 把左右两半分别贴在原位置，
     * 两次拼起来正好是整张图 —— 左半边亮 = 左手，右半边亮 = 右手。
     */
    private fun drawFlatDrum(g: GuiGraphics, player: Int) {
        val ox = sx(TaikoGameLayout.TAIKO_X)
        val oy = sx(TaikoGameLayout.TAIKO_Y + TaikoGameLayout.playerYOffset(player))
        val w = sx(180)
        val h = sx(200)
        val halfW = w / 2

        TexDraw.full(g, TaikoGameTextures.drumBase, ox, oy, w, h, 180, 200)

        // 计算四个区各自的渐隐亮度 0f..1f
        val bright = FloatArray(4)
        val now = System.nanoTime()
        for (p in session.padPresses) {
            if (p.player != player) continue
            val age = (now - p.atNanos) / 1_000_000.0
            if (age < 0 || age > PAD_FADE_MS) continue
            val level = (1.0 - age / PAD_FADE_MS).toFloat().coerceIn(0f, 1f)
            // 0 左咔 / 1 右咔 / 2 左咚 / 3 右咚
            val idx = when {
                p.color == NoteColor.KA && p.side == Side.LEFT -> 0
                p.color == NoteColor.KA -> 1
                p.side == Side.LEFT -> 2
                else -> 3
            }
            if (level > bright[idx]) bright[idx] = level
        }

        // 鼓边（Ka）：区域 0 左、1 右
        for (i in 0..1) {
            val a = bright[i]
            if (a <= 0f) continue
            TexDraw.tinted(g, 1f, 1f, 1f, a) {
                TexDraw.sub(
                    g, TaikoGameTextures.drumKa,
                    ox + i * halfW, oy, halfW, h,
                    i * 90, 0, 90, 200, 180, 200,
                )
            }
        }
        // 鼓面（Don）：区域 2 左、3 右
        for (i in 0..1) {
            val a = bright[2 + i]
            if (a <= 0f) continue
            TexDraw.tinted(g, 1f, 1f, 1f, a) {
                TexDraw.sub(
                    g, TaikoGameTextures.drumDon,
                    ox + i * halfW, oy, halfW, h,
                    i * 90, 0, 90, 200, 180, 200,
                )
            }
        }
    }

    private companion object {
        /** 平面太鼓渐隐时长（毫秒） */
        const val PAD_FADE_MS = 400.0
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
