package top.drawtwice.taikocraft

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * TaikoCraft —— 在 Minecraft 里开一家太鼓机厅。
 *
 * 主入口（**双端共用**，不含任何客户端类引用）。
 * 客户端逻辑在 [top.drawtwice.taikocraft.client.TaikoCraftClient]，
 * 它单独带 `@Mod(dist = [Dist.CLIENT])`，专用服务器不会加载。
 *
 * ## 事件注册（踩坑记录）
 * KotlinForForge 的 `@EventBusSubscriber` + `object` 自动注册有个坑：
 * KFF **不读 `bus` 属性**，而是按事件类型自动判断，然后整体 `register(obj)`；
 * 此时**方法绝对不能加 `@JvmStatic`**，否则抛
 * "Expected @SubscribeEvent method ... to NOT be static"。
 *
 * 本项目改为**显式注册**（构造器注入 IEventBus = MOD 总线），更可控。
 */
@Mod(TaikoCraft.MOD_ID)
class TaikoCraft(modEventBus: IEventBus) {

    companion object {
        const val MOD_ID: String = "taikocraft"

        @JvmField
        val LOGGER: Logger = LoggerFactory.getLogger("TaikoCraft")
    }

    init {
        LOGGER.info("TaikoCraft 加载中……（Phase 1）")
        // 这里只放双端通用的事件注册；客户端专属注册见 TaikoCraftClient
        @Suppress("UNUSED_EXPRESSION")
        modEventBus
    }
}
