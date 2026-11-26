package top.drawtwice.taikocraft.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import org.lwjgl.glfw.GLFW
import top.drawtwice.taikocraft.TaikoCraft

/**
 * 客户端 **MOD 总线**事件：初始化、按键注册。
 * `dist = [Dist.CLIENT]` 保证专用服务器不加载本类。
 */
@Mod(value = TaikoCraft.MOD_ID, dist = [Dist.CLIENT])
@EventBusSubscriber(modid = TaikoCraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object TaikoCraftClient {

    /** 全局延迟偏移（毫秒），由校准界面写入 */
    @JvmField
    var universalOffsetMs: Double = 0.0

    @JvmField
    val openMenuKey: KeyMapping = KeyMapping(
        "key.taikocraft.open_menu",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_F8,
        "key.categories.taikocraft",
    )

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
}
