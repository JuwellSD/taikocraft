package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.client.event.ClientTickEvent
import top.drawtwice.taikocraft.TaikoCraft

/**
 * 客户端 **GAME 总线**事件：每 tick 检查 F8 是否按下。
 *
 * 必须与 [TaikoCraftClient] 分开：MOD 总线不接收 `ClientTickEvent`（那是 GAME 总线事件），
 * 注册到错误的总线上会静默不触发。
 */
@EventBusSubscriber(modid = TaikoCraft.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = [Dist.CLIENT])
object TaikoClientTickHandler {

    private var wasPressed = false

    @JvmStatic
    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) {
            wasPressed = true // 有界面时吞掉，避免关闭界面时误触发
            return
        }
        val pressed = TaikoCraftClient.openMenuKey.isDown
        if (pressed && !wasPressed) {
            mc.setScreen(SongSelectScreen(null))
        }
        wasPressed = pressed
    }
}
