package top.drawtwice.taikocraft.mixin

import net.minecraft.client.KeyboardHandler
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import top.drawtwice.taikocraft.client.input.KeyInputBuffer
import top.drawtwice.taikocraft.client.input.TaikoKeyConfig
import top.drawtwice.taikocraft.client.input.TimedHit

/**
 * 捕获 GLFW 回调级按键（早于 tick，不丢精度）。
 *
 * NeoForge 1.21.1 的 `KeyboardHandler` 方法名为 `keyPress`，
 * 参数为 (long window, int key, int scancode, int action, int modifiers)。
 * 这里取 HEAD 注入，只记录按下（action == 1）。
 */
@Mixin(KeyboardHandler::class)
class KeyboardHandlerMixin {

    @Inject(method = ["keyPress"], at = [At("HEAD")])
    fun taikocraft$onKeyPress(window: Long, key: Int, scancode: Int, action: Int, modifiers: Int, ci: CallbackInfo) {
        if (action != 1) return
        val mapped = TaikoKeyConfig.mapping.toColorSide(key) ?: return
        KeyInputBuffer.push(TimedHit(mapped.first, mapped.second, System.nanoTime()))
    }
}
