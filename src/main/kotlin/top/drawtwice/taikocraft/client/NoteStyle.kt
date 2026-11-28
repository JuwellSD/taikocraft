package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import top.drawtwice.taikocraft.TaikoCraft

/**
 * 音符风格 —— **只影响 4 类元素**：
 * 咚 / 咔 / 大咚 / 大咔、连打条、气球、打中爆开特效。
 *
 * | 风格 | 说明 | 贴图来源 |
 * |---|---|---|
 * | [SIMPLE] 简约风格 | **OpenTaiko 原版皮肤**（默认） | `original/` |
 * | [MC] MC 风格 | 像素风方块质感 | `mc/` |
 *
 * 其他元素（轨道、小节线、鼓、判定文字、分数）**不受影响**。
 *
 * ## ⚠️ 为什么不再代码绘制音符（2026-11-28）
 * 之前简约风格用 `g.fill` 逐行画圆：一个 195px 音符约 200 次绘制调用，
 * 一屏 20 个音符 = 4000+ 次/帧 —— 这就是「刷新率上不到 200」的真正原因。
 * 现在两种风格都走贴图，一个音符 = 1 次 blit。
 */
enum class NoteStyle(val displayName: String) {
    SIMPLE("简约风格"),
    MC("MC 风格");

    companion object {
        fun fromName(n: String?): NoteStyle =
            entries.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: SIMPLE
    }
}

/**
 * 一套音符贴图资源。
 *
 * | 项 | 简约（OpenTaiko） | MC |
 * |---|---|---|
 * | notes | 1170×195，6 列，格 195 | 1170×195，6 列，格 195 |
 * | rolls | 1170×195，6 列，格 195 | 1170×195，6 列，格 195 |
 * | balloon | 1365×195，7 列（头/身/尾 = 0/1/2） | 585×195，3 列 |
 * | explosion | 2730×780，7×2，格 390 | 1365×390，7×2，格 195 |
 */
data class NoteSkinSet(
    val notes: ResourceLocation,
    val notesCols: Int,
    val noteCell: Int,
    val rolls: ResourceLocation,
    val rollsCols: Int,
    val balloon: ResourceLocation,
    val balloonCols: Int,
    val explosion: ResourceLocation,
    val explosionCols: Int,
    val explosionRows: Int,
    val explosionCell: Int,
)

object NoteStyleManager {

    /** 当前风格（由设置修改，绘制时实时读取） */
    var current: NoteStyle = NoteStyle.SIMPLE
        set(value) {
            field = value
            if (value == NoteStyle.MC) verifyTextures()
        }

    private var verified = false

    private fun tex(path: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, path)

    /** 简约风格 = OpenTaiko 原版皮肤 */
    private val SIMPLE_SET = NoteSkinSet(
        notes = tex("textures/taiko/original/notes.png"),
        notesCols = 6, noteCell = 195,
        rolls = tex("textures/taiko/original/rolls.png"),
        rollsCols = 6,
        balloon = tex("textures/taiko/original/balloons.png"),
        balloonCols = 7,
        explosion = tex("textures/taiko/original/hit/explosion.png"),
        explosionCols = 7, explosionRows = 2, explosionCell = 390,
    )

    /** MC 风格 = 像素风方块 */
    private val MC_SET = NoteSkinSet(
        notes = tex("textures/taiko/mc/notes.png"),
        notesCols = 6, noteCell = 195,
        rolls = tex("textures/taiko/mc/rolls.png"),
        rollsCols = 6,
        balloon = tex("textures/taiko/mc/balloon.png"),
        balloonCols = 3,
        explosion = tex("textures/taiko/mc/explosion.png"),
        explosionCols = 7, explosionRows = 2, explosionCell = 195,
    )

    fun skin(): NoteSkinSet = if (current == NoteStyle.MC) MC_SET else SIMPLE_SET

    /** 爆开特效总时长（毫秒） */
    const val EXPLOSION_MS = 210.0

    private fun verifyTextures() {
        if (verified) return
        verified = true
        val rm = Minecraft.getInstance().resourceManager
        for ((name, loc) in listOf(
            "mc notes" to MC_SET.notes,
            "mc rolls" to MC_SET.rolls,
            "mc balloon" to MC_SET.balloon,
            "mc explosion" to MC_SET.explosion,
        )) {
            val ok = runCatching { rm.getResource(loc).isPresent }.getOrDefault(false)
            if (ok) TaikoCraft.LOGGER.info("MC 风格贴图 OK：{}", name)
            else TaikoCraft.LOGGER.warn("MC 风格贴图缺失：{}（{}）", name, loc)
        }
    }
}
