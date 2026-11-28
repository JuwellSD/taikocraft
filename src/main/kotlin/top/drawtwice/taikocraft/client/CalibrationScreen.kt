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
 * 延迟校准 —— 按 OpenTaiko `CActCalibrationMode.cs` 复刻。
 *
 * ## 时钟（2026-11-28 关键修复）
 * 坠尔实测：**跟音乐点 = -130ms，跟画面点 = +100ms**，差 230ms。
 * 根因是旧实现里声音用 `MetronomeSound`（每次 `alBufferData` 现敲 + `alSourcePlay`），
 * 画面用 `System.nanoTime()` —— 两条独立时间源，声卡缓冲的 230ms 完全没算进去。
 *
 * 现在：**整段节拍预合成成一条音轨**（[ClickTrack]），交给 [OpenAlPlayback]，
 * 画面位置**直接读 `playback.positionMs()`**。声音和画面共用同一个时间源，
 * 不可能再出现系统性偏差 —— 跟音乐点和跟画面点会得到同一个数值。
 *
 * ## OpenTaiko 原逻辑
 * ```
 * CalibrateTick = new CCounter(0, 500, 1, Timer)   // 0..500ms 循环
 * CurrentOffset = -(value > 250 ? value - 500 : value)   // 折到 ±250
 * ```
 * 屏幕上是一条横向滚动带：`Bar.png`（小节线）+ `Notes.png` 的咚音符，
 * 以 500ms 周期滚过判定圈。音符压到判定圈的瞬间敲鼓即为 0 偏差。
 *
 * ## 三按钮
 * 0 = 取消 / 1 = 打点 / 2 = 保存，左右方向键切换。
 *
 * ## 无采样上限
 * 坠尔要求：取消 16 次限制，想敲多少次都行。
 */
class CalibrationScreen(
    private val previous: Screen? = null,
    private val onApplied: () -> Unit = {},
) : TaikoScreenBase(Component.literal("延迟校准")) {

    /** 与 OpenTaiko `CCounter(0, 500, 1)` 一致 */
    private val cycleMs = ClickTrack.BEAT_MS.toDouble()

    private var playback: OpenAlPlayback? = null
    private var loopCount = 0
    private var loadError: String? = null

    private val offsets = mutableListOf<Int>()
    private var lastOffset = 0
    private var buttonIndex = 1

    private var scale = 1f

    override fun init() {
        runCatching { OpenAlPlayback.createFrom(ClickTrack.build()) }
            .onSuccess {
                playback = it
                it.setVolume(1f)
                it.play()
            }
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

    /** **唯一时钟**：音频后端真实播放位置（毫秒，已含循环累加） */
    private fun nowMs(): Double {
        val p = playback ?: return 0.0
        if (p.tick()) {
            // 播完一轮 → 重开，累加位置保证单调
            loopCount++
            p.stop()
            runCatching { OpenAlPlayback.createFrom(ClickTrack.build()) }
                .onSuccess { playback = it; it.play() }
                .onFailure { playback = null }
            return loopCount * ClickTrack.durationMs.toDouble()
        }
        return loopCount * ClickTrack.durationMs.toDouble() + p.positionMs()
    }

    override fun renderBackdrop(g: GuiGraphics) {
        scale = TaikoGameLayout.scaleFor(width)
        TexDraw.full(g, CALIB_BG, 0, 0, width, height, 1920, 1080)
    }

    private fun tickValue(now: Double): Double = now % cycleMs

    /** OpenTaiko `CurrentOffset()`：把 0..500 折到 −250..+250 再取负 */
    private fun currentOffset(v: Double): Int {
        val folded = if (v > 250) v - 500 else v
        return -folded.roundToInt()
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val now = nowMs()
        val v = tickValue(now)

        drawScrollField(g, v)
        drawDrum(g)
        TexDraw.full(g, CALIB_FG_ZH, 0, 0, width, height, 1920, 1080)
        drawInfo(g, v)
        drawButtons(g)
        loadError?.let { g.drawString(font, it, 20, height - 20, 0xFFFF6666.toInt()) }
    }

    /**
     * OpenTaiko 的滚动带：每 500px 一组「小节线 + 咚音符」随 tick 左移。
     */
    private fun drawScrollField(g: GuiGraphics, tick: Double) {
        val originX = (TaikoGameLayout.SCROLL_FIELD_X * scale).toInt()
        val originY = (TaikoGameLayout.SCROLL_FIELD_Y * scale).toInt()
        val size = (TaikoGameLayout.NOTES_SIZE * scale).toInt()
        val step = (500 * scale).toInt().coerceAtLeast(1)
        val barW = (4 * scale).toInt().coerceAtLeast(1)

        var x = originX
        while (x < width + step) {
            val drawX = x - (tick * scale).toInt()
            TexDraw.full(g, TaikoGameTextures.barLine, drawX, originY, barW, size, 4, 270)
            TexDraw.sub(
                g, TaikoGameTextures.notes, drawX, originY, size, size,
                0, 0, 195, 195,
                TaikoGameTextures.NOTES_COLS * 195, 195,
            )
            x += step
        }

        TexDraw.tinted(g, 1f, 1f, 1f, 0.85f) {
            TexDraw.full(
                g, TaikoGameTextures.hitCircle, originX, originY,
                size, size, 195, 195,
            )
        }
    }

    private fun drawDrum(g: GuiGraphics) {
        val ox = (TaikoGameLayout.TAIKO_X * scale).toInt()
        val oy = (TaikoGameLayout.TAIKO_Y * scale).toInt()
        val w = (180 * scale).toInt()
        val h = (200 * scale).toInt()
        TexDraw.full(g, TaikoGameTextures.drumBase, ox, oy, w, h, 180, 200)
    }

    private fun drawInfo(g: GuiGraphics, v: Double) {
        val sorted = offsets.sorted()
        val median = if (sorted.isEmpty()) 0 else sorted[sorted.size / 2]
        val lowest = sorted.firstOrNull() ?: 0
        val highest = sorted.lastOrNull() ?: 0

        val x = (TaikoGameLayout.JUDGE_X * scale).toInt()
        var y = (TaikoGameLayout.JUDGE_Y * scale).toInt()
        val lh = 12

        g.drawString(font, "中位数偏移 : $median ms", x, y, 0xFF55FFFF.toInt()); y += lh
        g.drawString(font, "最小偏移   : $lowest ms", x, y, 0xFFFFFFFF.toInt()); y += lh
        g.drawString(font, "最大偏移   : $highest ms", x, y, 0xFFFFFFFF.toInt()); y += lh
        g.drawString(font, "上次偏移   : $lastOffset ms", x, y, 0xFFFFFFFF.toInt()); y += lh
        g.drawString(font, "采样次数   : ${offsets.size}（无上限）", x, y, 0xFFFFFFFF.toInt()); y += lh
        g.drawString(font, "当前偏移   : ${currentOffset(v)} ms", x, y, 0xFF88FF88.toInt())
    }

    private fun drawButtons(g: GuiGraphics) {
        val labels = arrayOf("取消", "打点", "保存")
        val baseY = height - 54
        val bw = 150
        val gap = 24
        val startX = width / 2 - (labels.size * bw + (labels.size - 1) * gap) / 2
        for (i in labels.indices) {
            val bx = startX + i * (bw + gap)
            val sel = i == buttonIndex
            g.fill(bx, baseY, bx + bw, baseY + 34, if (sel) 0xFF48D268.toInt() else 0x99202020.toInt())
            g.drawCenteredString(
                font, labels[i], bx + bw / 2, baseY + 13,
                if (sel) 0xFF000000.toInt() else 0xFFDDDDDD.toInt(),
            )
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        for (hit in KeyInputBuffer.drain()) {
            @Suppress("UNUSED_EXPRESSION") hit.color
            addOffset()
        }
        when (keyCode) {
            263 -> { buttonIndex = (buttonIndex - 1).coerceAtLeast(0); return true }
            262 -> { buttonIndex = (buttonIndex + 1).coerceAtMost(2); return true }
            257, 335 -> { decide(); return true }
            256 -> { minecraft?.setScreen(previous); return true }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun decide() {
        when (buttonIndex) {
            0 -> minecraft?.setScreen(previous)
            1 -> addOffset()
            2 -> {
                if (offsets.isNotEmpty()) {
                    val sorted = offsets.sorted()
                    // OpenTaiko: nGlobalOffsetMs = GetMedianOffset()
                    // 游戏内 chartTimeJudgeMs = songPos + tjaOffset - audioOffset
                    // 校准得到的是「玩家比音符晚多少」，所以要取负作为音押偏移
                    val rec = -sorted[sorted.size / 2].toDouble()
                    TaikoCraftClient.setOffsets(TaikoCraftClient.visualOffsetMs, rec)
                }
                onApplied()
                minecraft?.setScreen(previous)
            }
        }
    }

    private fun addOffset() {
        val off = currentOffset(tickValue(nowMs()))
        offsets += off
        lastOffset = off
    }

    override fun isPauseScreen(): Boolean = false
}

private val CALIB_BG = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
    "taikocraft", "textures/taiko/opentaiko/calib/bg.png",
)
private val CALIB_FG_ZH = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
    "taikocraft", "textures/taiko/opentaiko/calib/fg_zh.png",
)
