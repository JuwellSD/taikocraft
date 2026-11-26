package top.drawtwice.taikocraft

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * TaikoCraft —— 在 Minecraft 里开一家太鼓机厅。
 *
 * 主入口（双端共用）。客户端专用逻辑在 [top.drawtwice.taikocraft.client.TaikoCraftClient]。
 *
 * 注意：KotlinForForge 要求 mod class 恰好有 1 个公开构造器，
 * 参数只能是 IEventBus / ModContainer / Dist 等受支持类型。
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
        NeoForge.EVENT_BUS.register(this)
    }
}
