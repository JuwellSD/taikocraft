package top.drawtwice.taikocraft.client

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.common.NeoForge
import org.lwjgl.glfw.GLFW
import top.drawtwice.taikocraft.TaikoCraft

/**
 * 客户端入口（**只在客户端加载**）。
 *
 * 采用**显式注册**而不是 `@EventBusSubscriber` 自动扫描，原因见 [TaikoCraft] 的注释：
 * KFF 的自动注册不读 `bus` 属性、且要求方法非静态，容易踩坑。
 *
 * - 构造器注入的 `modEventBus` = **MOD 总线** → FMLClientSetup / RegisterKeyMappings
 * - `NeoForge.EVENT_BUS` = **GAME 总线** → ClientTickEvent
 */
@Mod(value = TaikoCraft.MOD_ID, dist = [Dist.CLIENT])
class TaikoCraftClient(modEventBus: IEventBus) {

    companion object {
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

        private var wasPressed = false
    }

    init {
        // MOD 总线
        modEventBus.addListener(this::onClientSetup)
        modEventBus.addListener(this::onRegisterKeyMappings)
        // GAME 总线
        NeoForge.EVENT_BUS.addListener(this::onClientTick)
    }

    private fun onClientSetup(event: FMLClientSetupEvent) {
        TaikoCraft.LOGGER.info("TaikoCraft 客户端就绪，玩家：{}", Minecraft.getInstance().user.name)
    }

    private fun onRegisterKeyMappings(event: RegisterKeyMappingsEvent) {
        event.register(openMenuKey)
    }

    private fun onClientTick(event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        if (mc.screen != null) {
            wasPressed = true // 有界面时吞掉，避免关闭界面时误触发
            return
        }
        val pressed = openMenuKey.isDown
        if (pressed && !wasPressed) {
            mc.setScreen(SongSelectScreen(null))
        }
        wasPressed = pressed
    }
}
