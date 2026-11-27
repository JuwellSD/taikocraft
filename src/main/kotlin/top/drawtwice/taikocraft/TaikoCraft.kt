package top.drawtwice.taikocraft

import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * TaikoCraft —— 在 Minecraft 里开一家太鼓机厅。
 *
 * ## 架构说明（2026-11-28 重写）
 * 界面的布局、坐标、配色**全部来自 OpenTaiko**（`song_select_core/Config/layout.json`），
 * 不自己发明数值。
 *
 * ## 事件注册
 * 使用**显式注册**（构造器注入 IEventBus = MOD 总线）。
 * 不使用 `@EventBusSubscriber` 自动扫描 —— KFF 不读 bus 属性，
 * 且混用 MOD/GAME 事件时会因为方法可见性报错。
 */
@Mod(TaikoCraft.MOD_ID)
class TaikoCraft(modEventBus: IEventBus) {

    companion object {
        const val MOD_ID: String = "taikocraft"

        @JvmField
        val LOGGER: Logger = LoggerFactory.getLogger("TaikoCraft")
    }

    init {
        LOGGER.info("TaikoCraft 加载中（OpenTaiko 界面复刻版）")
    }
}
