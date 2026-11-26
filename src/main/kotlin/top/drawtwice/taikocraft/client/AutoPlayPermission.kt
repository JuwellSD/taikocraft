package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import top.drawtwice.taikocraft.TaikoCraft

/**
 * Autoplay 权限：只有 OP（权限等级 ≥ 2）可以开启。
 *
 * 单人存档：看玩家是否有 OP 权限（作弊开启即视为 OP）。
 * 专用服务器：看 `player.hasPermissions(2)`。
 * 无连接（主菜单）时禁用。
 */
object AutoPlayPermission {

    /** 当前玩家是否可开启 autoplay */
    fun canUse(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return hasOp(player)
    }

    private fun hasOp(player: LocalPlayer): Boolean = runCatching {
        // NeoForge 1.21.1：hasPermissions(int) 检查权限等级
        player.hasPermissions(2)
    }.getOrElse {
        TaikoCraft.LOGGER.warn("权限检查失败：{}", it.message)
        false
    }

    /** 权限等级说明（用于界面显示） */
    fun statusText(): String = when {
        Minecraft.getInstance().player == null -> "需要进入世界"
        canUse() -> "OP 权限 ✓"
        else -> "需要 OP 权限（等级 2）"
    }
}
