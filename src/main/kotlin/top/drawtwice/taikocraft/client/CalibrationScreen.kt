package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 延迟校准（**双通道分离**，坠尔的设计要求）。
 *
 * ## 为什么要分开
 * 纯视觉校准不是音游。显示器和声卡的延迟是不同的两回事：
 *
 * | 模式 | 玩家凭据 | 调的是 | 影响 |
 * |---|---|---|---|
 * | **目押（视觉）** | 看着节拍**图形**按 | `visualOffsetMs` | 只改音符画在哪 |
 * | **音押（听觉）** | 闭眼**听**节拍按 | `audioOffsetMs` | 只改判定时刻 |
 *
 * 两种模式各采 16 个样本取中位数。可以只调一个，也可以都调。
 *
 * 注意：本界面用**系统时钟**产生节拍并采样的，采样结果是「人的反应延迟 + 显示/音频管线延迟」，
 * 不需要真的播放音频也能测目押；音押模式建议玩家戴耳机闭眼跟着内心的节拍按——
 * 实际使用中更精确的做法是进入游戏后按实际音乐微调，本界面提供的是快速起点。
 */
class CalibrationScreen(
    private val previous: Screen? = null,
    private val onApplied: () -> Unit = {},
) : TaikoScreenBase(Component.literal("延迟校准")) {

    private enum class Mode(val label: String, val hint: String) {
        VISUAL("目押（看图形）", "看着蓝色方块，它出现的瞬间按"),
        AUDIO("音押（听声音）", "闭上眼睛，凭感觉跟节拍按"),
    }

    private var mode = Mode.VISUAL

    private val beatIntervalMs = 600.0
    private val maxSamples = 16

    private val visualSamples = mutableListOf<Double>()
    private val audioSamples = mutableListOf<Double>()

    private var startNanos = 0L
    private var lastBeatIndex = -1
    private var lastPulseAtMs = 0.0

    private var recommendedVisual = 0.0
    private var recommendedAudio = 0.0

    private var currentVisual = 0.0
    private var currentAudio = 0.0

    private fun samples() = if (mode == Mode.VISUAL) visualSamples else audioSamples
    private fun recommended() = if (mode == Mode.VISUAL) recommendedVisual else recommendedAudio

    override fun init() {
        startNanos = System.nanoTime()
        visualSamples.clear()
        audioSamples.clear()
        lastBeatIndex = -1
        lastPulseAtMs = 0.0
        currentVisual = TaikoCraftClient.visualOffsetMs
        currentAudio = TaikoCraftClient.audioOffsetMs

        val cx = width / 2
        val by = height - 30

        addRenderableWidget(
            Button.builder(Component.literal(Mode.VISUAL.label)) {
                mode = Mode.VISUAL
                resetBeat()
            }.bounds(cx - 210, 40, 130, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal(Mode.AUDIO.label)) {
                mode = Mode.AUDIO
                resetBeat()
            }.bounds(cx - 70, 40, 130, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("清零当前采样")) {
                samples().clear()
                if (mode == Mode.VISUAL) recommendedVisual = 0.0 else recommendedAudio = 0.0
                resetBeat()
            }.bounds(cx + 70, 40, 130, 20).build(),
        )

        addRenderableWidget(
            Button.builder(Component.literal("应用")) {
                val v = if (visualSamples.size >= 5) recommendedVisual else currentVisual
                val a = if (audioSamples.size >= 5) recommendedAudio else currentAudio
                TaikoCraftClient.setOffsets(v, a)
                onApplied()
                minecraft?.setScreen(previous)
            }.bounds(cx - 160, by, 90, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("全部清零")) {
                TaikoCraftClient.setOffsets(0.0, 0.0)
                currentVisual = 0.0
                currentAudio = 0.0
                visualSamples.clear()
                audioSamples.clear()
                recommendedVisual = 0.0
                recommendedAudio = 0.0
            }.bounds(cx - 60, by, 90, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("返回")) {
                minecraft?.setScreen(previous)
            }.bounds(cx + 40, by, 90, 20).build(),
        )
    }

    private fun resetBeat() {
        startNanos = System.nanoTime()
        lastBeatIndex = -1
        lastPulseAtMs = 0.0
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val nowMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val beatIndex = (nowMs / beatIntervalMs).toInt()
        if (beatIndex != lastBeatIndex) {
            lastBeatIndex = beatIndex
            lastPulseAtMs = beatIndex * beatIntervalMs
        }
        val sincePulse = nowMs - lastPulseAtMs

        // 节拍视觉提示（目押用方块，音押只做微弱提示）
        val pulseAlpha = when {
            sincePulse !in 0.0..150.0 -> 0
            mode == Mode.VISUAL -> (255 * (1.0 - sincePulse / 150.0)).toInt()
            else -> (60 * (1.0 - sincePulse / 150.0)).toInt()
        }
        if (pulseAlpha > 0) {
            val r = 44
            val size = if (mode == Mode.VISUAL) r else 16
            guiGraphics.fill(
                width / 2 - size, height / 2 - size,
                width / 2 + size, height / 2 + size,
                (pulseAlpha shl 24) or 0x66CCFF,
            )
        }

        // 采样（接受节拍前后 ±300ms）
        val list = samples()
        for (hit in KeyInputBuffer.drain()) {
            if (list.size >= maxSamples) continue
            var delta = nowMs - lastPulseAtMs
            if (delta > beatIntervalMs / 2) delta -= beatIntervalMs
            if (abs(delta) <= beatIntervalMs / 2) {
                list += delta
            }
            @Suppress("UNUSED_EXPRESSION") hit.color
            @Suppress("UNUSED_EXPRESSION") hit.side
        }
        if (list.size >= 5) {
            val med = -list.sorted()[list.size / 2]
            if (mode == Mode.VISUAL) recommendedVisual = med else recommendedAudio = med
        }

        val cx = width / 2

        // 两个当前值（醒目）
        guiGraphics.pose().pushPose()
        guiGraphics.pose().translate(20f, 70f, 0f)
        guiGraphics.pose().scale(1.2f, 1.2f, 1f)
        guiGraphics.drawString(
            font, "目押（视觉）：${currentVisual.roundToInt()} ms", 0, 0,
            if (currentVisual == 0.0) 0xFFAAAAAA.toInt() else 0xFF7FE87F.toInt(),
        )
        guiGraphics.pose().popPose()

        guiGraphics.pose().pushPose()
        guiGraphics.pose().translate(20f, 92f, 0f)
        guiGraphics.pose().scale(1.2f, 1.2f, 1f)
        guiGraphics.drawString(
            font, "音押（判定）：${currentAudio.roundToInt()} ms", 0, 0,
            if (currentAudio == 0.0) 0xFFAAAAAA.toInt() else 0xFF7FE87F.toInt(),
        )
        guiGraphics.pose().popPose()

        // 当前模式说明
        guiGraphics.drawCenteredString(font, "当前模式：${mode.label}", cx, height / 3 - 44, 0xFFFFFFFF.toInt())
        guiGraphics.drawCenteredString(font, mode.hint, cx, height / 3 - 26, 0xFFAAAAAA.toInt())
        guiGraphics.drawCenteredString(
            font, "已采样：${list.size} / $maxSamples", cx, height / 3 + 64, 0xFF7FE87F.toInt(),
        )
        if (list.size >= 3) {
            guiGraphics.drawCenteredString(
                font, "平均偏差：${list.average().roundToInt()} ms", cx, height / 3 + 82, 0xFFFFD24A.toInt(),
            )
            guiGraphics.drawCenteredString(
                font, "推荐：${recommended().roundToInt()} ms", cx, height / 3 + 100, 0xFFFFD24A.toInt(),
            )
        }

        // 模式提示（音押模式下提示用耳机）
        if (mode == Mode.AUDIO) {
            guiGraphics.drawCenteredString(
                font, "提示：音押校准的理想做法是听着真实音乐游戏内微调；", cx, height - 60, 0xFF888888.toInt(),
            )
            guiGraphics.drawCenteredString(
                font, "本界面用系统节拍近似测量「反应延迟」，误差较大。", cx, height - 48, 0xFF888888.toInt(),
            )
        }
    }

    override fun isPauseScreen(): Boolean = false
}
