package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.audio.MetronomeSound
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 延迟校准界面。
 *
 * ## 两种校准模式（坠尔要求）
 * | 模式 | 说明 |
 * |---|---|
 * | **太鼓音符闪烁** | 屏幕中央闪烁太鼓音符，跟着敲。间隔 1200ms（给高延迟设备留足余地） |
 * | **无尽小关卡** | 简单谱面持续下落，看到音符到判定线时敲，更像真实游玩 |
 *
 * ## 两种偏移（沿用药丸设计）
 * - **目押**：只影响视觉，判定不变
 * - **音押**：视觉与判定一起平移
 *
 * ## 采样规则
 * - 只认游戏内设置的四键（由 Mixin 保证，其它键不会进缓冲）
 * - **无采样上限**（坠尔要求，方便精细调整）
 * - 接受节拍前后 ±600ms 的偏差（环绕计算）
 */
class CalibrationScreen(
    private val previous: Screen? = null,
    private val onApplied: () -> Unit = {},
) : TaikoScreenBase(Component.literal("延迟校准")) {

    private enum class Mode(val label: String) {
        PULSE("太鼓音符闪烁"),
        MINI_CHART("无尽小关卡"),
    }

    /** 校准的是哪个偏移 */
    private enum class Target(val label: String) {
        VISUAL("目押（只改视觉）"),
        AUDIO("音押（视觉+判定）"),
    }

    private var mode = Mode.PULSE
    private var target = Target.AUDIO

    /** 节拍间隔：1200ms（原 600ms 对高延迟设备太短） */
    private val beatIntervalMs = 1200.0

    private val samples = mutableListOf<Double>()

    private var startNanos = 0L
    private var lastBeatIndex = -1
    private var lastPulseAtMs = 0.0

    private var currentVisual = 0.0
    private var currentAudio = 0.0

    // 小关卡模式：模拟音符
    private data class MiniNote(val timeMs: Double, val color: NoteColor)
    private var miniNotes: List<MiniNote> = emptyList()

    override fun init() {
        startNanos = System.nanoTime()
        lastBeatIndex = -1
        lastPulseAtMs = 0.0
        currentVisual = TaikoCraftClient.visualOffsetMs
        currentAudio = TaikoCraftClient.audioOffsetMs
        MetronomeSound.init()
        MetronomeSound.setVolume(1f)

        // 小关卡：每 1200ms 一个音符，交替红蓝，无限延伸
        miniNotes = (0 until 120).map { i ->
            MiniNote(i * beatIntervalMs, if (i % 2 == 0) NoteColor.DON else NoteColor.KA)
        }

        val cx = width / 2
        // 模式切换
        addRenderableWidget(Button.builder(Component.literal("模式：${mode.label}")) { btn ->
            mode = if (mode == Mode.PULSE) Mode.MINI_CHART else Mode.PULSE
            btn.message = Component.literal("模式：${mode.label}")
            resetSampling()
        }.bounds(cx - 230, 30, 140, 20).build())

        // 目标偏移切换
        addRenderableWidget(Button.builder(Component.literal("校准：${target.label}")) { btn ->
            target = if (target == Target.VISUAL) Target.AUDIO else Target.VISUAL
            btn.message = Component.literal("校准：${target.label}")
        }.bounds(cx - 80, 30, 150, 20).build())

        addRenderableWidget(Button.builder(Component.literal("清零采样")) {
            resetSampling()
        }.bounds(cx + 80, 30, 90, 20).build())

        addRenderableWidget(Button.builder(Component.literal("应用推荐值")) {
            applyRecommended()
            onApplied()
            minecraft?.setScreen(previous)
        }.bounds(cx - 160, height - 34, 110, 20).build())

        addRenderableWidget(Button.builder(Component.literal("返回")) {
            minecraft?.setScreen(previous)
        }.bounds(cx + 20, height - 34, 80, 20).build())
    }

    private fun resetSampling() {
        samples.clear()
        startNanos = System.nanoTime()
        lastBeatIndex = -1
        lastPulseAtMs = 0.0
    }

    private fun applyRecommended() {
        if (samples.isEmpty()) return
        val median = -samples.sorted()[samples.size / 2]
        when (target) {
            Target.VISUAL -> TaikoCraftClient.setOffsets(median, TaikoCraftClient.audioOffsetMs)
            Target.AUDIO -> TaikoCraftClient.setOffsets(TaikoCraftClient.visualOffsetMs, median)
        }
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val nowMs = (System.nanoTime() - startNanos) / 1_000_000.0
        val beatIndex = (nowMs / beatIntervalMs).toInt()
        if (beatIndex != lastBeatIndex) {
            lastBeatIndex = beatIndex
            lastPulseAtMs = beatIndex * beatIntervalMs
            MetronomeSound.play(accent = beatIndex % 4 == 0)
        }

        // ---- 采样：无上限，接受 ±600ms ----
        for (hit in KeyInputBuffer.drain()) {
            var delta = nowMs - lastPulseAtMs
            if (delta > beatIntervalMs / 2) delta -= beatIntervalMs
            if (abs(delta) <= beatIntervalMs / 2) samples += delta
            @Suppress("UNUSED_EXPRESSION") hit.color
            @Suppress("UNUSED_EXPRESSION") hit.side
        }

        when (mode) {
            Mode.PULSE -> renderPulseMode(guiGraphics, nowMs)
            Mode.MINI_CHART -> renderMiniChart(guiGraphics, nowMs)
        }

        renderStats(guiGraphics)
    }

    // ------------------------------------------------------------------
    // 模式 1：太鼓音符闪烁
    // ------------------------------------------------------------------

    private fun renderPulseMode(g: GuiGraphics, nowMs: Double) {
        val sinceBeat = nowMs - lastPulseAtMs
        val cx = width / 2
        val cy = height / 2

        // 音符闪烁：600ms 内从大到小、由亮到暗
        if (sinceBeat in 0.0..600.0) {
            val t = (sinceBeat / 600.0).toFloat()
            val r = (60 + 40 * t).toInt()
            val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            drawTaikoNote(g, cx, cy, r, NoteColor.DON, alpha, big = true)
        }

        // 常驻静止音符（提示位置）
        drawTaikoNote(g, cx, cy, 50, NoteColor.DON, 60, big = true)

        g.drawCenteredString(font, "跟着闪烁的音符敲击（${TaikoKeyConfigHint.hint()}）", cx, cy + 120, 0xFFAAAAAA.toInt())
        g.drawCenteredString(font, "间隔 ${beatIntervalMs.toInt()}ms，每 4 拍重音", cx, cy + 136, 0xFF777777.toInt())
    }

    // ------------------------------------------------------------------
    // 模式 2：无尽小关卡
    // ------------------------------------------------------------------

    private fun renderMiniChart(g: GuiGraphics, nowMs: Double) {
        val laneLeft = (width * 0.2).toInt()
        val laneRight = (width * 0.8).toInt()
        val cy = height / 2
        val pxPerMs = (laneRight - laneLeft) / (beatIntervalMs * 2.5)

        g.fill(laneLeft, cy - 30, laneRight, cy + 30, 0xE62B2E33.toInt())
        g.fill(laneLeft, cy - 30, laneLeft + 2, cy + 30, 0xFFFFFFFF.toInt())

        // 音符从右往左
        for (note in miniNotes) {
            val d = note.timeMs - nowMs
            if (d < -400 || d > beatIntervalMs * 2.5) continue
            val x = laneLeft + (d * pxPerMs).toInt()
            if (x < laneLeft - 40 || x > laneRight + 40) continue
            drawTaikoNote(g, x, cy, 22, note.color, 255, big = false)
        }

        g.drawCenteredString(
            font, "音符到达左侧白线时敲击（${TaikoKeyConfigHint.hint()}）",
            width / 2, cy + 80, 0xFFAAAAAA.toInt(),
        )
    }

    // ------------------------------------------------------------------
    // 统计与结果
    // ------------------------------------------------------------------

    private fun renderStats(g: GuiGraphics) {
        val cx = width / 2
        val y = height - 100

        g.drawCenteredString(font, "目标：${target.label}", cx, y - 20, 0xFFFFFFFF.toInt())
        g.drawCenteredString(font, "已采样：${samples.size} 次（无上限）", cx, y, 0xFF7FE87F.toInt())

        if (samples.isNotEmpty()) {
            val avg = samples.average()
            val med = samples.sorted()[samples.size / 2]
            g.drawCenteredString(
                font, "平均偏差 ${avg.roundToInt()} ms   中位数 ${med.roundToInt()} ms",
                cx, y + 16, 0xFFFFD24A.toInt(),
            )
            g.drawCenteredString(
                font, "推荐值：${(-med).roundToInt()} ms",
                cx, y + 32, 0xFFFFD24A.toInt(),
            )
        }

        g.drawCenteredString(
            font,
            "当前：目押 ${currentVisual.roundToInt()} ms   音押 ${currentAudio.roundToInt()} ms",
            cx, y + 54, 0xFF888888.toInt(),
        )
    }

    // ------------------------------------------------------------------
    // 绘图
    // ------------------------------------------------------------------

    /** 画太鼓音符（白边空心圆，颜色 = 咚红 / 咔蓝） */
    private fun drawTaikoNote(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: NoteColor, alpha: Int, big: Boolean) {
        if (r <= 0) return
        val a = alpha.coerceIn(0, 255) shl 24
        val c = if (color == NoteColor.DON) 0xFFE8453C.toInt() else 0xFF4FC3D9.toInt()
        drawDisc(g, cx, cy, r, a or 0xFFFFFF, 1)
        drawDisc(g, cx, cy, r - 4, a or (c and 0xFFFFFF), 1)
        if (big) drawDisc(g, cx, cy, (r / 5).coerceAtLeast(2), a or 0xFFFFFF, 1)
    }

    private fun drawDisc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int, step: Int) {
        if (r <= 0) return
        val r2 = r * r
        var dy = -r
        while (dy <= r) {
            val half = sqrt((r2 - dy * dy).toDouble()).toInt()
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + step, color)
            dy += step
        }
    }

    override fun isPauseScreen(): Boolean = false
}

/** 键位提示文案 */
private object TaikoKeyConfigHint {
    fun hint(): String {
        val m = top.drawtwice.taikocraft.client.input.TaikoKeyConfig.mapping
        fun k(i: Int) = top.drawtwice.taikocraft.client.input.KeyMapping.keyName(i)
        return "左咔${k(m.leftKa)} 左咚${k(m.leftDon)} 右咚${k(m.rightDon)} 右咔${k(m.rightKa)}"
    }
}
