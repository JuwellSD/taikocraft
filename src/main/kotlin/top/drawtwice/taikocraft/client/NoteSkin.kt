package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import top.drawtwice.taikocraft.TaikoCraft

/**
 * 材质风格。
 *
 * | 风格 | 来源 |
 * |---|---|
 * | [MC] | 本项目代码绘制（白边空心圆，简洁） |
 * | [ORIGINAL] | OpenTaiko 默认皮肤贴图（Notes.png 精灵表） |
 *
 * ## 贴图来源与许可
 * `assets/taikocraft/textures/taiko/original/` 下的 PNG 来自
 * **OpenTaiko 默认皮肤**（`System/Open-World Memories/Graphics/5_Game/Notes Skin (Taiko)/1/`）。
 * OpenTaiko 本体为 MIT 许可；贴图归属见其 `System/Open-World Memories/README.txt`：
 * TJAPlayer3 Default skin / Freepik / cien (@CienpixeL) / PNG Egg (Non-commercial)。
 * ⚠️ 若本 mod 未来商用，需替换掉 Non-commercial 的资源并复核。
 *
 * ## 精灵表布局
 * `Notes.png` = 1170×195，6 帧 × 195px：
 * - 帧 0: 小咚（红）
 * - 帧 1: 小咔（蓝）
 * - 帧 2: 大咚
 * - 帧 3: 大咔
 * - 帧 4-5: 其它变体
 *
 * `Roll.png` = 2505×306：连打条（含左右端与中段）
 * `Balloon.png` = 300×240：气球
 */
enum class NoteSkin(val displayName: String, val description: String) {
    MC("MC 风格", "代码绘制的白边空心圆，简洁"),
    ORIGINAL("原版风格", "OpenTaiko 默认皮肤贴图");

    companion object {
        fun fromName(name: String?): NoteSkin =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MC
    }
}

/** 材质管理器 */
object NoteSkinManager {

    private val ORIGINAL_NOTES = ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "textures/taiko/original/notes.png")
    private val ORIGINAL_ROLL = ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "textures/taiko/original/roll.png")
    private val ORIGINAL_BALLOON = ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "textures/taiko/original/balloon.png")
    private val ORIGINAL_HITCIRCLE = ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "textures/taiko/original/hitcircle.png")

    /** 当前材质（由设置在运行中修改） */
    var current: NoteSkin = NoteSkin.MC
        set(value) {
            field = value
            if (value == NoteSkin.ORIGINAL) verifyTextures()
        }

    private var verified = false

    /**
     * 校验贴图是否真的能加载。
     * 之前「原版风格什么都不显示」的根因就是文件名大小写不匹配导致的静默失败。
     */
    fun verifyTextures() {
        if (verified) return
        verified = true
        val mc = Minecraft.getInstance()
        val rm = mc.resourceManager
        for ((name, loc) in listOf(
            "notes" to ORIGINAL_NOTES,
            "roll" to ORIGINAL_ROLL,
            "balloon" to ORIGINAL_BALLOON,
            "hitcircle" to ORIGINAL_HITCIRCLE,
        )) {
            val exists = runCatching { rm.getResource(loc).isPresent }.getOrDefault(false)
            if (exists) {
                TaikoCraft.LOGGER.info("贴图 OK：{}", name)
            } else {
                TaikoCraft.LOGGER.error("★ 贴图缺失：{}（{}）—— 将回退到代码绘制", name, loc)
            }
        }
    }

    // 精灵表尺寸（用于计算 UV）
    private const val NOTES_FRAMES = 6
    private const val NOTES_FRAME_W = 195
    private const val NOTES_FRAME_H = 195

    /**
     * 绘制一个音符。
     *
     * @param big 大音符（在精灵表里用第 2/3 帧）
     */
    fun drawNote(
        g: GuiGraphics,
        cx: Int, cy: Int, r: Int,
        isDon: Boolean, big: Boolean, alpha: Int,
        fallback: (GuiGraphics, Int, Int, Int, Int) -> Unit,
    ) {
        // 根据当前材质风格决定：ORIGINAL 用贴图，MC 用代码绘制
        val useTexture = current == NoteSkin.ORIGINAL
        if (useTexture) {
            // 原版风格：Notes.png 的帧 0（咚）/ 帧 1（咔）
            val frame = if (isDon) 0 else 1
            val size = r * 2
            val ok = runCatching {
                g.blit(
                    ORIGINAL_NOTES,
                    cx - r, cy - r,
                    size.toFloat(), size.toFloat(),
                    frame * NOTES_FRAME_W, 0,
                    NOTES_FRAME_W, NOTES_FRAME_H,
                )
                true
            }.getOrDefault(false)
            if (ok) return
        }
        // 回退：代码绘制
        fallback(g, cx, cy, r, alpha)
    }

    /**
     * 画长条（Roll）或气球（Balloon）。
     *
     * 用 `roll.png` / `balloon.png` 的中间段平铺 + 端头圆。
     * 返回 true 表示已用贴图绘制。
     */
    fun drawRollOrBalloon(
        g: GuiGraphics, left: Int, right: Int, cy: Int, laneH: Int, isRoll: Boolean,
    ): Boolean {
        if (right <= left) return false
        val tex = if (isRoll) ORIGINAL_ROLL else ORIGINAL_BALLOON
        val texW = if (isRoll) ROLL_TEX_W else BALLOON_TEX_W
        val texH = if (isRoll) ROLL_TEX_H else BALLOON_TEX_H
        if (texW <= 0 || texH <= 0) return false
        @Suppress("UNUSED_VARIABLE") val unusedH = texH

        val h = (laneH * 0.8).toInt().coerceAtLeast(10)
        return runCatching {
            // 中间段：取贴图中心 1/3 区域横向拉伸平铺
            val u = texW / 3
            val v = 0
            val uW = texW / 3
            val vH = texH
            val midLeft = left + h / 2
            val midRight = right - h / 2
            if (midRight > midLeft) {
                g.blit(tex, midLeft, cy - h / 2, (midRight - midLeft).toFloat(), h.toFloat(), u, v, uW, vH)
            }
            // 两端：取贴图左右各 1/6 作为端头
            g.blit(tex, left, cy - h / 2, h.toFloat(), h.toFloat(), 0, v, uW / 2, vH)
            g.blit(tex, right - h, cy - h / 2, h.toFloat(), h.toFloat(), texW - uW / 2, v, uW / 2, vH)
            true
        }.getOrDefault(false)
    }

    // 贴图尺寸（从文件读到的实际值）
    private const val ROLL_TEX_W = 2505
    private const val ROLL_TEX_H = 306
    private const val BALLOON_TEX_W = 300
    private const val BALLOON_TEX_H = 240

    /** 原版风格的连打条是否可用 */
    fun hasOriginalRoll(): Boolean = current == NoteSkin.ORIGINAL

    fun originalRoll(): ResourceLocation = ORIGINAL_ROLL

    fun originalBalloon(): ResourceLocation = ORIGINAL_BALLOON

    fun originalHitCircle(): ResourceLocation = ORIGINAL_HITCIRCLE
}
