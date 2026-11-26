package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 所有 TaikoCraft 界面的基类。
 *
 * ## 为什么需要它（踩坑记录）
 * Minecraft 1.21+ 的 `Screen#render` 会在内部调用 `renderBackground()`，
 * 而它会对**已经画到屏上的内容做高斯模糊**（menu background blur）。
 *
 * 于是出现这样的现象：
 * ```
 * render() {
 *   fill(背景)      // ①
 *   drawString(文字) // ②  ← 被模糊
 *   super.render() {
 *     renderBackground()  // ③ 触发 blur，把 ①② 一起糊掉
 *     renderWidgets()     // ④ 按钮在 blur 之后画 → 清晰
 *   }
 * }
 * ```
 * 表现就是：**按钮看得清，自己画的文字一片模糊**。
 *
 * ## 本类的规则
 * 1. `renderBackground` 置空 —— 不用原版背景，也就不触发模糊
 * 2. 子类用 [renderBackdrop] 画背景（最底层）
 * 3. 子类用 [renderContent] 画文字等自定义内容（**在控件之后**，永远在最上层）
 */
abstract class TaikoScreenBase(title: Component) : Screen(title) {

    /** 禁用原版背景（含模糊效果） */
    final override fun renderBackground(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // 故意留空：背景由子类在 renderBackdrop 里自己画
    }

    final override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 1) 背景（最底层）
        renderBackdrop(guiGraphics)
        // 2) 原版控件（按钮、输入框）
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        // 3) 自定义内容：文字/列表等，必须在控件之后，避免被任何后续绘制影响
        renderContent(guiGraphics, mouseX, mouseY, partialTick)
    }

    /** 画背景。默认全屏深色，子类可覆盖。 */
    protected open fun renderBackdrop(guiGraphics: GuiGraphics) {
        guiGraphics.fill(0, 0, width, height, 0xF0101014.toInt())
    }

    /** 画自定义内容（文字、列表等）。在 super.render() 之后调用，保证清晰。 */
    protected abstract fun renderContent(
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    )
}
