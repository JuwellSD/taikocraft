package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import top.drawtwice.taikocraft.TaikoCraft

/**
 * 客户端专用入口。
 * `dist = [Dist.CLIENT]` 保证专用服务器不会加载本类。
 */
@Mod(value = TaikoCraft.MOD_ID, dist = [Dist.CLIENT])
@EventBusSubscriber(modid = TaikoCraft.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = [Dist.CLIENT])
object TaikoCraftClient {

    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent) {
        TaikoCraft.LOGGER.info("TaikoCraft 客户端就绪，玩家：{}", Minecraft.getInstance().user.name)
    }
}
