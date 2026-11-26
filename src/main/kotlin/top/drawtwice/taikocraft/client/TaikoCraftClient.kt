package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW
import top.drawtwice.taikocraft.TaikoCraft

/**
 * 客户端入口：注册按键、打开选曲界面。
 * `dist = [Dist.CLIENT]` 保证专用服务器不加载本类。
 */
@Mod(value = TaikoCraft.MOD_ID, dist = [Dist.CLIENT])
object TaikoCraftClient {

    /** 全局延迟偏移（毫秒），由校准界面写入 */
    @JvmField
    var universalOffsetMs: Double = 0.0

    private val openMenuKey = KeyMapping(
        "key.taikocraft.open_menu",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_F8,
        "key.categories.taikocraft",
    )

    private var wasPressed = false

    @JvmStatic
    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        TaikoCraft.LOGGER.info("TaikoCraft 客户端就绪，玩家：{}", Minecraft.getInstance().user.name)
    }

    @JvmStatic
    @SubscribeEvent
    fun onRegisterKeys(event: RegisterKeyMappingsEvent) {
        event.register(openMenuKey)
    }

    @JvmStatic
    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) {
            wasPressed = true // 有界面时吞掉，避免关闭界面时触发
            return
        }
        val pressed = openMenuKey.isDown
        if (pressed && !wasPressed) {
            mc.setScreen(taikocraftScreen())
        }
        wasPressed = pressed
    }

    private fun taikocraftScreen(): SongSelectScreen = SongSelectScreen(null)
}
