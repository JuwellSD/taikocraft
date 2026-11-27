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
 * GLFW 回调级按键捕获。
 * 只在 [TaikoScreen]（游玩）和 [CalibrationScreen]（校准）记录。
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
