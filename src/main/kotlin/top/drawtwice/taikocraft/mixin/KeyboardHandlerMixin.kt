package top.drawtwice.taikocraft.mixin

import net.minecraft.client.KeyboardHandler
import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import top.drawtwice.taikocraft.client.CalibrationScreen
import top.drawtwice.taikocraft.client.TaikoScreen
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import top.drawtwice.taikocraft.client.input.TaikoKeyConfig
import top.drawtwice.taikocraft.client.input.TimedHit

/**
 * 捕获 GLFW 回调级按键（早于 tick，不丢精度）。
 *
 * 只在这两种界面记录：
 * - [TaikoScreen]        —— 游玩中的击打
 * - [CalibrationScreen]  —— 延迟校准的敲击
 *
 * 其它界面（选曲/设置/曲库）不记录，避免按键积压后一次性涌入。
 */
@Mixin(KeyboardHandler::class)
class KeyboardHandlerMixin {

    @Inject(method = ["keyPress"], at = [At("HEAD")])
    fun onKeyPress(window: Long, key: Int, scancode: Int, action: Int, modifiers: Int, ci: CallbackInfo) {
        if (action != 1) return

        val screen = Minecraft.getInstance().screen
        if (screen !is TaikoScreen && screen !is CalibrationScreen) return

        val mapped = TaikoKeyConfig.mapping.toColorSide(key) ?: return
        KeyInputBuffer.push(TimedHit(mapped.first, mapped.second, System.nanoTime()))
    }
}
