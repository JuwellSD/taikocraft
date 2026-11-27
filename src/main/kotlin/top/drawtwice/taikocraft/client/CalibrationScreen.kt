package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.audio.MetronomeSound
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 延迟校准 —— 按 OpenTaiko `CActCalibrationMode.cs` 复刻。
 *
 * ## OpenTaiko 的做法（源码直读）
 * ```
 * CalibrateTick = new CCounter(0, 500, 1, Timer)   // 0..500ms 循环
 * CurrentOffset = -(value > 250 ? value - 500 : value)   // 折到 ±250
 * ```
 * 屏幕上是 **一条横向滚动带**：`Bar.png`（小节线）+ `Notes.png` 的咚音符，
 * 以 500ms 周期从右往左滚过判定圈。玩家在音符压到判定圈的瞬间敲鼓，
 * 记录 `CurrentOffset`，多次取中位数。
 *
 * ## 界面（真实贴图）
 * - `2_Config/Calibration/BG.png`（1920×1080）
 * - `2_Config/Calibration/FG.png`（1920×1080，前景框）
 * - `FG_zh.png`（中文版前景）
 * - `5_Game/12_Lane` 轨道
 * - `5_Game/6_Taiko/Base.png` / `Don.png` / `Ka.png` 鼓
 *
 * ## 三按钮（`buttonIndex`）
 * 0 = 取消 / 1 = 打点 / 2 = 保存，左右方向键切换。
 *
 * ## 无采样上限
 * 坠尔要求：取消 16 次上限，随便敲多少次。
 */
class CalibrationScreen(
    private val previous: Screen? = null,
    private val onApplied: () -> Unit = {},
) : TaikoScreenBase(Component.literal("延迟校准")) {

    /** OpenTaiko: CCounter(0, 500, 1) —— 500ms 一个周期 */
    private val cycleMs = 500.0

    private var startNanos = 0L
    private var lastClickBeat = -1

    private val offsets = mutableListOf<Int>()
    private var lastOffset = 0
    private var buttonIndex = 1

    private var scale = 1f

    override fun init() {
        startNanos = System.nanoTime()
        lastClickBeat = -1
        MetronomeSound.init()
    }

    override fun renderBackdrop(g: GuiGraphics) {
        scale = TaikoGameLayout.scaleFor(width)
        // BG 铺满
        TexDraw.full(g, CALIB_BG, 0, 0, width, height, 1920, 1080)
    }

    /** 当前周期内的毫秒位置 0..500 */
    private fun tickValue(nowMs: Double): Double = nowMs % cycleMs

    /** OpenTaiko `CurrentOffset()`：把 0..500 折到 −250..+250 再取负 */
    private fun currentOffset(v: Double): Int {
        val folded = if (v > 250) v - 500 else v
        return -folded.roundToInt()
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val nowMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val v = tickValue(nowMs)
        val beat = (nowMs / cycleMs).toInt()

        // 每拍播放一次节拍音
        if (beat != lastClickBeat) {
            lastClickBeat = beat
            MetronomeSound.play(accent = beat % 4 == 0)
        }

        // ---- 轨道 + 滚动音符带（OpenTaiko 的判定圈与 Bar 循环）----
        drawScrollField(g, v)

        // ---- 鼓（左侧平面太鼓，四区）----
        drawDrum(g)

        // ---- 前景框 ----
        val fgY = 0
        TexDraw.full(g, CALIB_FG_ZH, 0, fgY, width, height, 1920, 1080)

        // ---- 文字数据（OpenTaiko 原样字段）----
        drawInfo(g)

        // ---- 三按钮高亮 ----
        drawButtons(g)
    }

    /**
     * OpenTaiko 的滚动带：
     * ```
     * for (x = nScrollFieldX[0]; x < GameWindowSize.Width + 500; x += 500)
     *     Bar 画在 (x - CalibrateTick.CurrentValue) + (NotesSize - BarWidth)/2
     *     Notes[0] 的咚画在 (x - CalibrateTick.CurrentValue)
     * ```
     * 即每 500px 一组「小节线 + 音符」，整体随 tick 左移，循环出现。
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
            // 小节线
            TexDraw.full(g, TaikoGameTextures.barLine, drawX, originY, barW, size, 4, 270)
            // 咚音符（notes.png 第 0 列）
            TexDraw.sub(
                g, TaikoGameTextures.notes, drawX, originY, size, size,
                0, 0, 195, 195,
                TaikoGameTextures.NOTES_COLS * 195, 195,
            )
            x += step
        }

        // 判定圈
        TexDraw.tinted(g, 1f, 1f, 1f, 0.85f) {
            TexDraw.full(
                g, TaikoGameTextures.hitCircle, originX, originY,
                size, size, 195, 195,
            )
        }
    }

    /** 左侧平面太鼓（静态，校准界面不跟着敲） */
    private fun drawDrum(g: GuiGraphics) {
        val ox = (TaikoGameLayout.TAIKO_X * scale).toInt()
        val oy = (TaikoGameLayout.TAIKO_Y * scale).toInt()
        val w = (180 * scale).toInt()
        val h = (200 * scale).toInt()
        TexDraw.full(g, TaikoGameTextures.drumBase, ox, oy, w, h, 180, 200)
    }

    private fun drawInfo(g: GuiGraphics) {
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
        val nowMs = (System.nanoTime() - startNanos) / 1_000_000.0
        g.drawString(font, "当前偏移   : ${currentOffset(tickValue(nowMs))} ms", x, y, 0xFF88FF88.toInt())
    }

    /** 三个按钮：0 取消 / 1 打点 / 2 保存 */
    private fun drawButtons(g: GuiGraphics) {
        val labels = arrayOf("取消", "打点", "保存")
        val baseY = height - 54
        val total = 3
        val bw = 150
        val gap = 24
        val startX = width / 2 - (total * bw + (total - 1) * gap) / 2
        for (i in 0 until total) {
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
        // 四个判定键都算「打点」（Mixin 已过滤，只认四键）
        for (hit in KeyInputBuffer.drain()) {
            @Suppress("UNUSED_EXPRESSION") hit.color
            addOffset()
        }
        when (keyCode) {
            263 -> { buttonIndex = (buttonIndex - 1).coerceAtLeast(0); return true }  // LEFT
            262 -> { buttonIndex = (buttonIndex + 1).coerceAtMost(2); return true }    // RIGHT
            257, 335 -> { decide(); return true }                                       // ENTER
            256 -> { minecraft?.setScreen(previous); return true }                      // ESC
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
                    val rec = sorted[sorted.size / 2].toDouble()
                    TaikoCraftClient.setOffsets(
                        TaikoCraftClient.visualOffsetMs, rec,
                    )
                }
                onApplied()
                minecraft?.setScreen(previous)
            }
        }
    }

    private fun addOffset() {
        val nowMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val off = currentOffset(tickValue(nowMs))
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
