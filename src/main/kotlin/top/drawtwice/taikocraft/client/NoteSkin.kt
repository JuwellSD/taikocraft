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
        if (current == NoteSkin.ORIGINAL && alpha >= 200 && !big) {
            // 原版风格：取 Notes.png 的第 0（咚）或第 1（咔）帧
            val frame = if (isDon) 0 else 1
            val size = r * 2
            val x = cx - r
            val y = cy - r
            runCatching {
                g.blit(
                    ORIGINAL_NOTES,
                    x, y, size, size,
                    frame * NOTES_FRAME_W, 0,
                    NOTES_FRAME_W, NOTES_FRAME_H,
                    NOTES_FRAME_W * NOTES_FRAMES, NOTES_FRAME_H,
                )
            }.onFailure {
                // 贴图缺失时回退到代码绘制
                fallback(g, cx, cy, r, alpha, if (isDon) 0xFFE8453C.toInt() else 0xFF4FC3D9.toInt())
            }
        } else {
            fallback(g, cx, cy, r, alpha, if (isDon) 0xFFE8453C.toInt() else 0xFF4FC3D9.toInt())
        }
    }

    /** 原版风格的连打条是否可用 */
    fun hasOriginalRoll(): Boolean = current == NoteSkin.ORIGINAL

    fun originalRoll(): ResourceLocation = ORIGINAL_ROLL

    fun originalBalloon(): ResourceLocation = ORIGINAL_BALLOON

    fun originalHitCircle(): ResourceLocation = ORIGINAL_HITCIRCLE
}
