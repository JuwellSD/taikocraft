package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.audio.PlaybackClock
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import top.drawtwice.taikocraft.judge.Side
import kotlin.math.roundToInt

/**
 * 延迟校准界面（文档 6.1）。
 *
 * 流程：节拍器每 600ms 一次 → 玩家跟着敲 → 收集偏差 → 取中位数作为推荐偏移。
 *
 * 说明：这里不播放真实音频（避免依赖音频加载），用视觉节拍 + 系统时钟。
 * 玩家敲击时刻减去节拍时刻即为 **输出+输入总延迟**，取中位数后取负作为校正值。
 */
class CalibrationScreen(
    private val previous: Screen? = null,
    private val onApplied: (Double) -> Unit = {},
) : TaikoScreenBase(Component.literal("延迟校准")) {

    private val beatIntervalMs = 600.0
    private val maxSamples = 16

    private val samples = mutableListOf<Double>()
    private var startNanos = 0L
    private var lastBeatIndex = -1
    private var lastPulseAtMs = 0.0

    private var recommended = 0.0
    private var manualValue = 0.0

    override fun init() {
        startNanos = System.nanoTime()
        samples.clear()
        addRenderableWidget(
            Button.builder(Component.literal("应用推荐值")) {
                onApplied(recommended)
                minecraft?.setScreen(previous)
            }.bounds(width / 2 - 110, height - 70, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("返回")) {
                minecraft?.setScreen(previous)
            }.bounds(width / 2 + 10, height - 70, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("-5ms")) { manualValue -= 5.0 }.bounds(width / 2 - 160, height - 40, 60, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("+5ms")) { manualValue += 5.0 }.bounds(width / 2 + 100, height - 40, 60, 20).build(),
        )
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {

        val nowMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val beatIndex = (nowMs / beatIntervalMs).toInt()
        if (beatIndex != lastBeatIndex) {
            lastBeatIndex = beatIndex
            lastPulseAtMs = beatIndex * beatIntervalMs
        }
        val sincePulse = nowMs - lastPulseAtMs
        if (sincePulse in 0.0..120.0) {
            val alpha = (255 * (1.0 - sincePulse / 120.0)).toInt().coerceIn(0, 255)
            val r = 40
            guiGraphics.fill(width / 2 - r, height / 2 - r, width / 2 + r, height / 2 + r, (alpha shl 24) or 0x66CCFF)
        }

        // 消费敲击
        for (hit in KeyInputBuffer.drain()) {
            val delta = nowMs - lastPulseAtMs
            // 只接受节拍后 0–300ms 内的敲击，且距离节拍足够近
            if (delta in 0.0..300.0) {
                val signed = if (delta > 150.0) delta - beatIntervalMs else delta
                if (samples.size < maxSamples) samples += signed
            }
            @Suppress("UNUSED_EXPRESSION")
            hit.color
            @Suppress("UNUSED_EXPRESSION")
            hit.side
        }
        if (samples.size >= 5) {
            recommended = -samples.sorted()[samples.size / 2]
        }

        guiGraphics.drawCenteredString(font, "延迟校准", width / 2, height / 3, 0xFFFFFFFF.toInt())
        guiGraphics.drawCenteredString(
            font,
            "跟着蓝色节拍敲击鼓面（任意键）",
            width / 2,
            height / 3 + 16,
            0xFFAAAAAA.toInt(),
        )
        guiGraphics.drawCenteredString(
            font,
            "已采样：${samples.size} / $maxSamples",
            width / 2,
            height / 3 + 36,
            0xFF7FE87F.toInt(),
        )
        if (samples.size >= 3) {
            guiGraphics.drawCenteredString(
                font,
                "平均偏差：${samples.average().roundToInt()} ms",
                width / 2,
                height / 3 + 52,
                0xFFFFD24A.toInt(),
            )
            guiGraphics.drawCenteredString(
                font,
                "推荐偏移：${recommended.roundToInt()} ms",
                width / 2,
                height / 3 + 68,
                0xFFFFD24A.toInt(),
            )
        }
        guiGraphics.drawCenteredString(
            font,
            "手动微调：${manualValue.roundToInt()} ms",
            width / 2,
            height - 55,
            0xFFFFFFFF.toInt(),
        )
    }

    override fun isPauseScreen(): Boolean = false

    /** 把校准值换算成 PlaybackClock 的 universalOffsetMs */
    fun resultingOffset(): Double = recommended + manualValue

    @Suppress("unused")
    private fun unusedSide(): Side = Side.LEFT
}
