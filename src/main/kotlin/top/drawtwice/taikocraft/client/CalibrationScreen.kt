package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.audio.ClickTrack
import top.drawtwice.taikocraft.audio.OpenAlPlayback
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 延迟校准 —— **内嵌一个定拍关卡让玩家实际演奏**（坠尔的要求）。
 *
 * ## 为什么不用自己发明的测量流程
 * 之前的做法是「音符滚过判定圈，玩家敲」，但那条流程和真实游戏用的是
 * 两套不同的时钟路径，所以测出来的值和游戏里对不上。
 *
 * 现在：**直接跑一个真实谱面**（[CalibrationChart]，120BPM 每拍一个音符），
 * 玩家正常打，我们从**实际判定偏差**里统计中位数。
 * 这跟游戏内判定走的是同一条代码路径，所以结果天然一致。
 *
 * ## 流程
 * 1. 进入 → 节拍音开始，音符开始落下
 * 2. 玩家跟着打（前 4 拍是空拍，让玩家进状态）
 * 3. 每次击打记录「早/晚多少 ms」
 * 4. 打完 32 拍 → 显示推荐值
 * 5. ENTER 确认 → 把推荐值**加到**当前设置上（不是覆盖）
 *
 * ## 手动微调
 * `,` `.` 调目押，`;` `'` 调音押，默认 1ms，Shift 10ms。
 */
class CalibrationScreen(
    private val previous: Screen? = null,
    private val onApplied: () -> Unit = {},
) : TaikoScreenBase(Component.literal("延迟校准")) {

    // ---- 音频节拍（与谱面 BPM 同步）----
    private var playback: OpenAlPlayback? = null
    private var loopCount = 0
    private var loadError: String? = null

    // ---- 谱面 ----
    private var chart = CalibrationChart.build().charts.first()
    private var finished = false

    /** 每次击打的偏差（ms，正=晚于音符） */
    private val deltas = mutableListOf<Double>()

    // ---- 手动值（工作副本）----
    private var manualVisual = 0.0
    private var manualAudio = 0.0

    private var scale = 1f

    override fun init() {
        manualVisual = TaikoCraftClient.visualOffsetMs
        manualAudio = TaikoCraftClient.audioOffsetMs
        finished = false
        deltas.clear()
        runCatching { OpenAlPlayback.createFrom(ClickTrack.build()) }
            .onSuccess { playback = it; it.setVolume(1f); it.play() }
            .onFailure {
                loadError = "节拍音初始化失败：${it.message}"
                TaikoCraft.LOGGER.error("校准节拍音初始化失败", it)
            }
    }

    override fun removed() {
        playback?.stop()
        playback = null
        super.removed()
    }

    /** 谱面播放位置（毫秒） */
    private fun nowMs(): Double {
        val p = playback ?: return fallbackNanos()
        if (p.tick()) {
            loopCount++
            val base = loopCount.toDouble() * ClickTrack.durationMs
            p.stop()
            runCatching { OpenAlPlayback.createFrom(ClickTrack.build()) }
                .onSuccess { playback = it; it.play() }
                .onFailure { playback = null }
            return base
        }
        return loopCount.toDouble() * ClickTrack.durationMs + p.positionMs()
    }

    private var fallbackStart = 0L
    private fun fallbackNanos(): Double {
        if (fallbackStart == 0L) fallbackStart = System.nanoTime()
        return (System.nanoTime() - fallbackStart) / 1_000_000.0
    }

    override fun renderBackdrop(g: GuiGraphics) {
        scale = TaikoGameLayout.scaleFor(width)
        g.fill(0, 0, width, height, 0xFF000000.toInt())
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val now = nowMs()

        // 击打已在 keyPressed 里处理（用 GLFW 回调级时间戳，更准）
        if (!finished && now > CalibrationChart.durationMs) finished = true

        drawNotes(g, now)
        drawHitCircle(g)
        drawDebug(g, now)
        drawButtons(g)
        loadError?.let { g.drawString(font, it, 20, height - 20, 0xFFFF6666.toInt()) }
    }

    /**
     * 记录一次击打。
     *
     * 用 [TimedHit.nanoTime]（GLFW 回调时刻）换算成谱面时间，
     * 而不是用下一帧的 `now` —— 后者会引入一帧（最多 1/60 秒）的误差。
     */
    private fun recordHit(color: top.drawtwice.taikocraft.chart.NoteColor, hitNanos: Long) {
        val hitChartMs = nowMs() + (hitNanos - System.nanoTime()) / 1_000_000.0
        // 找最近的同色音符
        val nearest = chart.notes
            .filter { it.color == color }
            .minByOrNull { abs(it.timeMs - hitChartMs) }
            ?: return
        val d = hitChartMs - nearest.timeMs
        // 只接受判定窗内的（避免空打污染统计）
        if (abs(d) <= 150.0) deltas += d
    }

    private fun drawHitCircle(g: GuiGraphics) {
        val ox = (TaikoGameLayout.SCROLL_FIELD_X * scale).toInt()
        val oy = (TaikoGameLayout.SCROLL_FIELD_Y * scale).toInt()
        val size = (TaikoGameLayout.NOTES_SIZE * scale).toInt()
        TexDraw.tinted(g, 1f, 1f, 1f, 0.6f) {
            TexDraw.full(g, TaikoGameTextures.hitCircle, ox, oy, size, size, 195, 195)
        }
    }

    private fun drawNotes(g: GuiGraphics, now: Double) {
        val skin = NoteStyleManager.skin()
        val ox = (TaikoGameLayout.SCROLL_FIELD_X * scale).toInt()
        val oy = (TaikoGameLayout.SCROLL_FIELD_Y * scale).toInt()
        val size = (TaikoGameLayout.NOTES_SIZE * scale).toInt()
        val interval = (TaikoGameLayout.NOTES_INTERVAL *
            TaikoGameLayout.SCROLL_MULTIPLIER * scale).toFloat()

        for (n in chart.notes) {
            val d = n.timeMs - now
            if (d < -400) continue
            val x = ox + (d * n.bpmAtNote / 240000.0 * interval).toInt()
            if (x > width || x < ox - size) continue
            val col = when {
                n.color == top.drawtwice.taikocraft.chart.NoteColor.DON && n.big -> 2
                n.color == top.drawtwice.taikocraft.chart.NoteColor.KA && n.big -> 3
                n.color == top.drawtwice.taikocraft.chart.NoteColor.DON -> 0
                else -> 1
            }
            TexDraw.sub(
                g, skin.notes, x, oy, size, size,
                col * skin.noteCell, 0, skin.noteCell, skin.noteCell,
                skin.notesCols * skin.noteCell, skin.noteCell,
            )
        }
    }

    private fun drawDebug(g: GuiGraphics, now: Double) {
        val x = (TaikoGameLayout.JUDGE_X * scale).toInt()
        var y = (TaikoGameLayout.JUDGE_Y * scale).toInt()
        val lh = 12

        val sorted = deltas.sorted()
        val median = if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]

        g.drawString(font, "采样次数 : ${deltas.size}", x, y, 0xFFFFFFFF.toInt()); y += lh
        g.drawString(font, "中位数   : ${median.roundToInt()} ms", x, y, 0xFF55FFFF.toInt()); y += lh
        g.drawString(font, "最小/最大: ${sorted.firstOrNull()?.roundToInt() ?: 0} / ${sorted.lastOrNull()?.roundToInt() ?: 0} ms", x, y, 0xFFAAAAAA.toInt()); y += lh
        if (finished || deltas.size >= 4) {
            val rec = -median    // 玩家偏晚 → 判定要提前 → 负值
            g.drawString(font, "→ 推荐音押 ${rec.roundToInt()} ms", x, y, 0xFFFFD24A.toInt()); y += lh
            val finalAudio = (manualAudio + rec).roundToInt()
            g.drawString(font, "→ 确认后 ${TaikoCraftClient.audioOffsetMs.toInt()} → $finalAudio ms", x, y, 0xFF7FE87F.toInt())
        }
        y += lh
        g.drawString(font, "目押 ${manualVisual.toInt()} ms  , / .", x, y, 0xFFFFD24A.toInt()); y += lh
        g.drawString(font, "音押 ${manualAudio.toInt()} ms  ; / '", x, y, 0xFF7FE87F.toInt()); y += lh
        g.drawString(font, "进度 ${(now / CalibrationChart.durationMs * 100).coerceIn(0.0,100.0).toInt()}%", x, y, 0xFF777777.toInt())
    }

    private fun drawButtons(g: GuiGraphics) {
        val labels = arrayOf("重来", "确认并保存", "取消")
        val bw = 170
        val gap = 20
        val baseY = height - 54
        val startX = width / 2 - (labels.size * bw + (labels.size - 1) * gap) / 2
        for (i in labels.indices) {
            val bx = startX + i * (bw + gap)
            val sel = i == 1
            g.fill(bx, baseY, bx + bw, baseY + 34, if (sel) 0xFF48D268.toInt() else 0x99202020.toInt())
            g.drawCenteredString(
                font, labels[i], bx + bw / 2, baseY + 13,
                if (sel) 0xFF000000.toInt() else 0xFFDDDDDD.toInt(),
            )
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val step = if ((modifiers and 0x1) != 0) 10.0 else 1.0

        // 先取出按键缓冲（Mixin 已把四键写进去了）
        val hits = KeyInputBuffer.drain()

        when (keyCode) {
            44 -> { manualVisual -= step; return true }   // ,
            46 -> { manualVisual += step; return true }   // .
            59 -> { manualAudio -= step; return true }    // ;
            39 -> { manualAudio += step; return true }    // '
            82 -> { resetRun(); return true }             // R 重来
            257, 335 -> { applyAndExit(); return true }   // ENTER
            256 -> { minecraft?.setScreen(previous); return true } // ESC
        }

        // 击打键 → 记录偏差
        for (hit in hits) recordHit(hit.color, hit.nanoTime)
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun resetRun() {
        deltas.clear()
        finished = false
        loopCount = 0
        fallbackStart = 0L
        playback?.stop()
        playback = null
        runCatching { OpenAlPlayback.createFrom(ClickTrack.build()) }
            .onSuccess { playback = it; it.setVolume(1f); it.play() }
    }

    private fun applyAndExit() {
        val sorted = deltas.sorted()
        val rec = if (sorted.isEmpty()) 0.0 else -sorted[sorted.size / 2]
        // 坠尔要求：把推荐值**加到**当前设置上
        val newAudio = TaikoCraftClient.audioOffsetMs + rec
        TaikoCraftClient.setOffsets(manualVisual, newAudio)
        onApplied()
        minecraft?.setScreen(previous)
    }

    override fun isPauseScreen(): Boolean = false
}
