package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import top.drawtwice.taikocraft.client.input.KeyMapping
import top.drawtwice.taikocraft.client.input.KeyScheme
import top.drawtwice.taikocraft.client.input.TaikoKeyConfig

/**
 * 键位设置界面。
 *
 * 支持：
 * - 两套预设：新手（左咔 D / 左咚 F / 右咚 J / 右咔 K）、老手（R F J I）
 * - 四个键位单独自定义：点击后按下任意键完成绑定
 * - 冲突检测（同一键不能绑两个动作）
 * - 恢复默认
 */
class KeyBindingScreen(private val previous: Screen? = null) : TaikoScreenBase(Component.literal("键位设置")) {

    private enum class Slot(val label: String) {
        LEFT_KA("左咔"),
        LEFT_DON("左咚"),
        RIGHT_DON("右咚"),
        RIGHT_KA("右咔"),
    }

    /** 等待按键的槽位 */
    private var listening: Slot? = null
    private var status = ""

    /** 当前绑定（可变，保存时写回 TaikoKeyConfig） */
    private var leftDon = TaikoKeyConfig.mapping.leftDon
    private var leftKa = TaikoKeyConfig.mapping.leftKa
    private var rightDon = TaikoKeyConfig.mapping.rightDon
    private var rightKa = TaikoKeyConfig.mapping.rightKa

    private val hitboxes = mutableListOf<Pair<Slot, IntArray>>() // slot -> [x1,y1,x2,y2]

    override fun init() {
        syncFromConfig()
        val cx = width / 2
        addRenderableWidget(
            Button.builder(Component.literal("新手预设")) { applyScheme(KeyScheme.BEGINNER) }
                .bounds(cx - 220, 44, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("老手预设")) { applyScheme(KeyScheme.EXPERT) }
                .bounds(cx - 110, 44, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("保存")) { save() }
                .bounds(cx - 60, height - 30, 80, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("← 返回")) {
                listening = null
                minecraft?.setScreen(previous)
            }.bounds(width - 90, height - 30, 80, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("恢复默认")) {
                leftDon = GLFW.GLFW_KEY_F; leftKa = GLFW.GLFW_KEY_D
                rightDon = GLFW.GLFW_KEY_J; rightKa = GLFW.GLFW_KEY_K
                status = "已恢复新手默认（未保存）"
            }.bounds(10, height - 30, 90, 20).build(),
        )
    }

    private fun syncFromConfig() {
        leftDon = TaikoKeyConfig.mapping.leftDon
        leftKa = TaikoKeyConfig.mapping.leftKa
        rightDon = TaikoKeyConfig.mapping.rightDon
        rightKa = TaikoKeyConfig.mapping.rightKa
    }

    private fun applyScheme(scheme: KeyScheme) {
        val m = KeyMapping.of(scheme)
        leftDon = m.leftDon; leftKa = m.leftKa
        rightDon = m.rightDon; rightKa = m.rightKa
        status = "已套用${scheme.displayName}"
    }

    private fun keyOf(slot: Slot): Int = when (slot) {
        Slot.LEFT_KA -> leftKa
        Slot.LEFT_DON -> leftDon
        Slot.RIGHT_DON -> rightDon
        Slot.RIGHT_KA -> rightKa
    }

    private fun setKey(slot: Slot, key: Int) {
        when (slot) {
            Slot.LEFT_KA -> leftKa = key
            Slot.LEFT_DON -> leftDon = key
            Slot.RIGHT_DON -> rightDon = key
            Slot.RIGHT_KA -> rightKa = key
        }
    }

    private fun save() {
        // 冲突检测
        val all = listOf(leftKa to Slot.LEFT_KA, leftDon to Slot.LEFT_DON, rightDon to Slot.RIGHT_DON, rightKa to Slot.RIGHT_KA)
        val dup = all.groupBy { it.first }.filterValues { it.size > 1 }
        if (dup.isNotEmpty()) {
            val keys = dup.keys.joinToString("、") { KeyMapping.keyName(it) }
            status = "按键冲突：$keys 被重复绑定"
            return
        }
        TaikoKeyConfig.mapping = KeyMapping(
            leftDon = leftDon, leftKa = leftKa,
            rightDon = rightDon, rightKa = rightKa,
        )
        TaikoKeyConfig.scheme = KeyScheme.CUSTOM
        // 触发持久化（复用偏移保存逻辑）
        TaikoCraftClient.setOffsets(TaikoCraftClient.visualOffsetMs, TaikoCraftClient.audioOffsetMs)
        status = "已保存"
        TaikoCraftLogger.info("键位已保存：左咔={} 左咚={} 右咚={} 右咔={}",
            KeyMapping.keyName(leftKa), KeyMapping.keyName(leftDon),
            KeyMapping.keyName(rightDon), KeyMapping.keyName(rightKa))
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.drawCenteredString(font, "键位设置", width / 2, 16, 0xFFFFFFFF.toInt())
        guiGraphics.drawCenteredString(
            font,
            if (listening != null) "请按下要绑定的按键…（ESC 取消）" else "点击下方键位框可重新绑定",
            width / 2, 30,
            if (listening != null) 0xFFFFD24A.toInt() else 0xFFAAAAAA.toInt(),
        )

        hitboxes.clear()
        val cy = height / 2 - 30
        val boxW = 120
        val boxH = 40
        val gap = 12
        val positions = listOf(
            Slot.LEFT_KA to width / 2 - boxW * 2 - gap * 2 - 40,
            Slot.LEFT_DON to width / 2 - boxW - gap - 40,
            Slot.RIGHT_DON to width / 2 + 40,
            Slot.RIGHT_KA to width / 2 + boxW + gap + 40,
        )

        for ((slot, x) in positions) {
            drawKeyBox(guiGraphics, slot, x, cy, boxW, boxH)
            hitboxes += slot to intArrayOf(x, cy, x + boxW, cy + boxH)
        }

        // UI 提示
        guiGraphics.drawCenteredString(
            font, "大音符需在 50ms 内同时按下两侧相同颜色的键", width / 2, cy + boxH + 24, 0xFF888888.toInt(),
        )
        guiGraphics.drawCenteredString(
            font, "新手：左咔 D / 左咚 F / 右咚 J / 右咔 K", width / 2, cy + boxH + 40, 0xFF666666.toInt(),
        )
        guiGraphics.drawCenteredString(
            font, "老手：左咔 R / 左咚 F / 右咚 J / 右咔 I", width / 2, cy + boxH + 54, 0xFF666666.toInt(),
        )

        if (status.isNotBlank()) {
            guiGraphics.drawString(font, status, 10, height - 52, 0xFF7FE87F.toInt())
        }
    }

    private fun drawKeyBox(g: GuiGraphics, slot: Slot, x: Int, y: Int, w: Int, h: Int) {
        val active = listening == slot
        val bg = if (active) 0xFF3A3020.toInt() else 0xFF2A2A33.toInt()
        val border = if (active) 0xFFFFD24A.toInt() else 0xFF555555.toInt()
        g.fill(x, y, x + w, y + h, bg)
        g.fill(x, y, x + w, y + 1, border)
        g.fill(x, y + h - 1, x + w, y + h, border)
        g.fill(x, y, x + 1, y + h, border)
        g.fill(x + w - 1, y, x + w, y + h, border)

        g.drawCenteredString(font, slot.label, x + w / 2, y + 6, 0xFFAAAAAA.toInt())
        val keyName = if (active) "按下…" else KeyMapping.keyName(keyOf(slot))
        g.drawCenteredString(font, keyName, x + w / 2, y + 22, if (active) 0xFFFFD24A.toInt() else 0xFFFFFFFF.toInt())
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        val hit = hitboxes.firstOrNull { (_, b) ->
            mouseX.toInt() in b[0]..b[2] && mouseY.toInt() in b[1]..b[3]
        }
        if (hit != null) {
            listening = hit.first
            status = ""
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    /** 捕获按键：Screen 的 keyPressed 在键盘事件时被调用 */
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val slot = listening
        if (slot != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listening = null
                status = "已取消"
                return true
            }
            setKey(slot, keyCode)
            listening = null
            status = "已绑定 ${slot.label} → ${KeyMapping.keyName(keyCode)}（记得保存）"
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun isPauseScreen(): Boolean = false
}

/** 日志代理，避免直接依赖 TaikoCraft 的日志器命名 */
private object TaikoCraftLogger {
    fun info(template: String, vararg args: Any?) =
        top.drawtwice.taikocraft.TaikoCraft.LOGGER.info(template, *args)
}
