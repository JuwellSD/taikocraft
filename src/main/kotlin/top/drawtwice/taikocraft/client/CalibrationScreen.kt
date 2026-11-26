package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 延迟校准界面（文档 6.1）。
 *
 * 流程：节拍器每 600ms 一次 → 玩家跟着敲 → 收集偏差 → 取中位数作为推荐偏移。
 *
 * 采样规则（已修正）：
 * - 接受节拍**前后** ±[beatInterval/2] 内的敲击，取环绕到 [-300, +300] 的偏差
 * - 之前只接受「节拍之后 0–300ms」，提前按的全被丢弃 → 表现为「连按采不到」
 *
 * 持久化：结果由 [TaikoCraftClient.universalOffsetMs] 保存，并写入 config 文件。
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

    /** 从全局配置带入的当前值，展示用 */
    private var currentGlobal: Double = 0.0

    override fun init() {
        startNanos = System.nanoTime()
        samples.clear()
        lastBeatIndex = -1
        lastPulseAtMs = 0.0
        currentGlobal = TaikoCraftClient.universalOffsetMs
        // 手动微调默认接着当前全局值
        manualValue = 0.0

        addRenderableWidget(
            Button.builder(Component.literal("应用推荐值")) {
                val final = recommended + manualValue
                TaikoCraftClient.setUniversalOffset(final)
                onApplied(final)
                minecraft?.setScreen(previous)
            }.bounds(width / 2 - 170, height - 70, 110, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("清零")) {
                samples.clear()
                recommended = 0.0
                manualValue = 0.0
                startNanos = System.nanoTime()
                lastBeatIndex = -1
            }.bounds(width / 2 - 50, height - 70, 60, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("返回")) {
                minecraft?.setScreen(previous)
            }.bounds(width / 2 + 20, height - 70, 80, 20).build(),
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

        // 消费敲击：接受节拍**前后** ±beatInterval/2 的偏差，环绕到 [-300, +300]
        for (hit in KeyInputBuffer.drain()) {
            if (samples.size >= maxSamples) continue
            var delta = nowMs - lastPulseAtMs
            // 环绕：如果离上一个节拍更近，就算负偏差
            if (delta > beatIntervalMs / 2) delta -= beatIntervalMs
            if (abs(delta) <= beatIntervalMs / 2) {
                samples += delta
            }
            @Suppress("UNUSED_EXPRESSION") hit.color
            @Suppress("UNUSED_EXPRESSION") hit.side
        }
        if (samples.size >= 5) {
            recommended = -samples.sorted()[samples.size / 2]
        }

        val cx = width / 2
        guiGraphics.drawCenteredString(font, "延迟校准", cx, height / 3 - 40, 0xFFFFFFFF.toInt())
        guiGraphics.drawCenteredString(
            font, "跟着蓝色节拍敲击（任意键，前后都可以）", cx, height / 3 - 20, 0xFFAAAAAA.toInt(),
        )
        guiGraphics.drawCenteredString(
            font, "已采样：${samples.size} / $maxSamples", cx, height / 3 + 40, 0xFF7FE87F.toInt(),
        )
        if (samples.size >= 3) {
            guiGraphics.drawCenteredString(
                font, "平均偏差：${samples.average().roundToInt()} ms", cx, height / 3 + 58, 0xFFFFD24A.toInt(),
            )
            guiGraphics.drawCenteredString(
                font, "推荐偏移：${recommended.roundToInt()} ms", cx, height / 3 + 76, 0xFFFFD24A.toInt(),
            )
        }

        // 当前生效值与手动微调
        guiGraphics.drawCenteredString(
            font, "当前已生效：${currentGlobal.roundToInt()} ms", cx, height / 3 + 104, 0xFF888888.toInt(),
        )
        guiGraphics.drawCenteredString(
            font, "手动微调：${manualValue.roundToInt()} ms", cx, height - 55, 0xFFFFFFFF.toInt(),
        )
        guiGraphics.drawCenteredString(
            font,
            "应用后 = 推荐 ${recommended.roundToInt()} + 手动 ${manualValue.roundToInt()} = ${(recommended + manualValue).roundToInt()} ms",
            cx, height - 90, 0xFF66CCFF.toInt(),
        )
    }

    override fun isPauseScreen(): Boolean = false
}
