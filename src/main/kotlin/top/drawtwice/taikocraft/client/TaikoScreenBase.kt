package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 所有 TaikoCraft 界面的基类。
 *
 * ## 为什么需要它
 * MC 1.21+ 的 `Screen.render()` 内部会调 `renderBackground()`，
 * 它会对**已画到屏上的内容做高斯模糊**。如果在 super.render() 之前画文字，
 * 文字会被糊掉，而按钮（super.render 内绘制）清晰。
 *
 * 本类：`renderBackground` 置空 + 保证自定义内容画在控件之后。
 */
abstract class TaikoScreenBase(title: Component) : Screen(title) {

    final override fun renderBackground(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 空实现：不使用原版背景（含模糊）
    }

    final override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackdrop(g)
        super.render(g, mouseX, mouseY, partialTick)
        renderContent(g, mouseX, mouseY, partialTick)
    }

    protected open fun renderBackdrop(g: GuiGraphics) {
        g.fill(0, 0, width, height, 0xF0000000.toInt())
    }

    protected abstract fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float)
}
