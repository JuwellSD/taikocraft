package top.drawtwice.taikocraft

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * TaikoCraft —— 在 Minecraft 里开一家太鼓机厅。
 *
 * 主入口（双端共用）。客户端专用逻辑在 [top.drawtwice.taikocraft.client.TaikoCraftClient]。
 */
@Mod(TaikoCraft.MOD_ID)
object TaikoCraft {

    const val MOD_ID: String = "taikocraft"

    @JvmField
    val LOGGER: Logger = LoggerFactory.getLogger("TaikoCraft")

    /**
     * 构造器由 KotlinForForge 注入（IEventBus / ModContainer 均可）。
     * 用显式构造器而不是 init 块，方便后续注册 DeferredRegister 与事件。
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(modEventBus: IEventBus, modContainer: ModContainer) {
        LOGGER.info("TaikoCraft 加载中……（Phase 1 骨架）")
        NeoForge.EVENT_BUS.register(this)
    }
}
