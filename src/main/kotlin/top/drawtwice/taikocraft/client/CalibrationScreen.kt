package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 延迟校准界面（骨架版，阶段 2 会按 OpenTaiko `2_Config/Calibration` 复刻）。
 *
 * - 节拍间隔 1200ms
 * - 接受 ±600ms 偏差，**无采样上限**
 * - 只认游戏内设置的四键（由 Mixin 保证）
 */
class CalibrationScreen(
    private val previous: Screen? = null,
    private val onApplied: () -> Unit = {},
) : TaikoScreenBase(Component.literal("延迟校准")) {

    private val beatMs = 1200.0
    private val samples = mutableListOf<Double>()
    private var startNanos = 0L
    private var lastBeat = -1
    private var lastPulse = 0.0
    private var targetAudio = true

    override fun init() {
        startNanos = System.nanoTime()
        lastBeat = -1
        lastPulse = 0.0
    }

    override fun renderBackdrop(g: GuiGraphics) {
        g.fill(0, 0, width, height, 0xFF101014.toInt())
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val now = (System.nanoTime() - startNanos) / 1_000_000.0
        val beat = (now / beatMs).toInt()
        if (beat != lastBeat) { lastBeat = beat; lastPulse = beat * beatMs }

        // 采样
        for (hit in KeyInputBuffer.drain()) {
            var d = now - lastPulse
            if (d > beatMs / 2) d -= beatMs
            if (abs(d) <= beatMs / 2) samples += d
            @Suppress("UNUSED_EXPRESSION") hit.color
        }

        // 节拍音符闪烁
        val since = now - lastPulse
        if (since in 0.0..600.0) {
            val t = (since / 600.0).toFloat()
            val r = (50 + 30 * t).toInt()
            val a = ((1f - t) * 255).toInt().coerceIn(0, 255) shl 24
            drawDisc(g, width / 2, height / 2, r, a or 0xFFFFFF, 1)
            drawDisc(g, width / 2, height / 2, r - 5, a or 0xFFE8453C, 1)
        }
        drawDisc(g, width / 2, height / 2, 44, 0x50FFFFFF, 2)

        val cx = width / 2
        g.drawCenteredString(font, "延迟校准（敲 D/F/J/K）", cx, height / 3 - 60, 0xFFFFFFFF.toInt())
        g.drawCenteredString(font, "已采样：${samples.size} 次（无上限）", cx, height / 3 + 80, 0xFF7FE87F.toInt())
        if (samples.isNotEmpty()) {
            val med = samples.sorted()[samples.size / 2]
            g.drawCenteredString(
                font, "中位数 ${med.roundToInt()}ms → 推荐 ${(-med).roundToInt()}ms",
                cx, height / 3 + 100, 0xFFFFD24A.toInt(),
            )
        }
        g.drawString(font, "ENTER 应用   ESC 返回", 20, height - 40, 0xFF888888.toInt())
    }

    private fun drawDisc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int, step: Int) {
        if (r <= 0) return
        val r2 = r.toLong() * r
        var dy = -r
        while (dy <= r) {
            val h = sqrt((r2 - dy.toLong() * dy).toDouble()).toInt()
            g.fill(cx - h, cy + dy, cx + h + 1, cy + dy + step, color)
            dy += step
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            257, 335 -> {
                if (samples.isNotEmpty()) {
                    val med = samples.sorted()[samples.size / 2]
                    val rec = -med
                    TaikoCraftClient.setOffsets(
                        if (targetAudio) TaikoCraftClient.visualOffsetMs else rec,
                        if (targetAudio) rec else TaikoCraftClient.audioOffsetMs,
                    )
                }
                onApplied()
                minecraft?.setScreen(previous)
                return true
            }
            256 -> { minecraft?.setScreen(previous); return true }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false
}
