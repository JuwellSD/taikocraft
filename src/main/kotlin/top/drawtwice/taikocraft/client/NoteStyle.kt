package top.drawtwice.taikocraft.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import top.drawtwice.taikocraft.TaikoCraft
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 音符风格（只影响：咚/咔/大咚/大咔 + 连打条 + 气球 + 爆开特效）。
 *
 * | 风格 | 说明 |
 * |---|---|
 * | [SIMPLE] 简约风格 | 代码绘制的白边空心圆，当前默认 |
 * | [MC] MC 风格 | 我的世界方块质感 |
 *
 * 其他元素（轨道、小节线、鼓、判定文字、分数）**不受此选项影响**。
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
 * 音符风格管理器。
 *
 * ## 简约风格（[NoteStyle.SIMPLE]）
 * 全部用代码画，不需要任何贴图：
 * - 音符：白边空心圆（咚=红边，咔=蓝边）
 * - 连打条：白色描边 + 黄底的圆角条
 * - 气球：白色描边 + 青色圆
 * - 爆开：白色圆环扩散
 *
 * ## MC 风格（[NoteStyle.MC]）
 * 用 `textures/taiko/mc/` 下的贴图（16×16 像素风）。
 *
 * ## 运行中切换
 * [current] 由设置修改，绘制时实时读取，不需要重进游戏。
 */
object NoteStyleManager {

    var current: NoteStyle = NoteStyle.SIMPLE
        set(value) {
            field = value
            if (value == NoteStyle.MC) verifyTextures()
        }

    private var verified = false

    private fun tex(name: String): ResourceLocation =
        ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "textures/taiko/mc/$name")

    val mcNotes = tex("notes.png")
    val mcRolls = tex("rolls.png")
    val mcBalloon = tex("balloon.png")
    val mcExplosion = tex("explosion.png")

    /** MC 风格精灵表尺寸 */
    const val MC_NOTES_W = 1170
    const val MC_NOTES_H = 195
    const val MC_NOTE_CELL = 195
    /** MC 连打条：6 列（头/身/尾 × 小/大） */
    const val MC_ROLLS_W = 1170
    const val MC_ROLLS_H = 195
    /** MC 气球：3 列（头/身/尾） */
    const val MC_BALLOON_W = 585
    const val MC_BALLOON_H = 195
    /** MC 爆开：7 列 × 2 行，每格 195×195 */
    const val MC_EXPLOSION_W = 1365
    const val MC_EXPLOSION_H = 390
    const val MC_EXPLOSION_CELL = 195
    const val MC_EXPLOSION_COLS = 7
    const val MC_EXPLOSION_MS = 210.0

    private fun verifyTextures() {
        if (verified) return
        verified = true
        val rm = Minecraft.getInstance().resourceManager
        for ((name, loc) in listOf(
            "mc notes" to mcNotes,
            "mc rolls" to mcRolls,
            "mc balloon" to mcBalloon,
            "mc explosion" to mcExplosion,
        )) {
            val ok = runCatching { rm.getResource(loc).isPresent }.getOrDefault(false)
            if (ok) TaikoCraft.LOGGER.info("MC 风格贴图 OK：{}", name)
            else TaikoCraft.LOGGER.warn("MC 风格贴图缺失：{}（{}）", name, loc)
        }
    }

    // ================= 简约风格（代码绘制）=================

    /**
     * 画一个简约风格音符。
     *
     * 白边空心圆：外圈白描边，内圈彩色，中心镂空露底色。
     */
    fun drawSimpleNote(
        g: GuiGraphics,
        cx: Int, cy: Int, r: Int,
        isDon: Boolean, big: Boolean,
    ) {
        if (r <= 0) return
        val white = 0xFFFFFFFF.toInt()
        val color = if (isDon) 0xFFE8453C.toInt() else 0xFF4FC3D9.toInt()
        // 外白边
        drawRing(g, cx, cy, r, (r / 5).coerceAtLeast(2), white)
        // 中间彩色环
        drawRing(g, cx, cy, (r * 3 / 4), (r / 3).coerceAtLeast(3), color)
        // 大音符：加一根中心竖杠（白色）
        if (big) {
            val w = (r / 5).coerceAtLeast(2)
            g.fill(cx - w, cy - r / 2, cx + w, cy + r / 2, white)
        }
    }

    private fun drawRing(g: GuiGraphics, cx: Int, cy: Int, r: Int, th: Int, color: Int) {
        if (r <= 0 || th <= 0) return
        val o2 = r.toLong() * r
        val inR = (r - th).coerceAtLeast(1)
        val i2 = inR.toLong() * inR
        var dy = -r
        while (dy <= r) {
            val o = sqrt((o2 - dy.toLong() * dy).toDouble()).toInt()
            val i = if (abs(dy) <= inR) sqrt((i2 - dy.toLong() * dy).toDouble()).toInt() else 0
            g.fill(cx - o, cy + dy, cx - i, cy + dy + 2, color)
            g.fill(cx + i, cy + dy, cx + o + 1, cy + dy + 2, color)
            dy += 2
        }
    }

    /** 简约风格连打条 */
    fun drawSimpleRoll(g: GuiGraphics, left: Int, right: Int, cy: Int, h: Int, big: Boolean) {
        if (right <= left) return
        val top = cy - h / 2
        val white = 0xFFFFFFFF.toInt()
        val color = if (big) 0xFFD94F4F.toInt() else 0xFFE8A03C.toInt()
        g.fill(left, top - 2, right, top + h + 2, white)
        g.fill(left, top, right, top + h, color)
        // 两端圆头
        drawDisc(g, left, cy, h / 2 + 2, white)
        drawDisc(g, right, cy, h / 2 + 2, white)
        drawDisc(g, left, cy, h / 2, color)
        drawDisc(g, right, cy, h / 2, color)
    }

    /** 简约风格气球 */
    fun drawSimpleBalloon(g: GuiGraphics, cx: Int, cy: Int, r: Int) {
        if (r <= 0) return
        val white = 0xFFFFFFFF.toInt()
        drawDisc(g, cx, cy, r + 2, white)
        drawDisc(g, cx, cy, r, 0xFF5BA3C9.toInt())
        drawDisc(g, cx, cy, (r / 2).coerceAtLeast(2), 0xFFFFFFFF.toInt())
    }

    /** 简约风格爆开特效：白色圆环由内向外扩散并淡出 */
    fun drawSimpleExplosion(g: GuiGraphics, cx: Int, cy: Int, progress: Float, big: Boolean) {
        val maxR = (24 * if (big) 1.8f else 1.3f).toInt()
        val r = (maxR * progress).toInt().coerceAtLeast(2)
        val alpha = ((1f - progress).coerceIn(0f, 1f) * 255).toInt()
        if (alpha <= 0) return
        drawRing(g, cx, cy, r, (r / 4).coerceAtLeast(2), (alpha shl 24) or 0xFFFFFF)
    }

    private fun drawDisc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int) {
        if (r <= 0) return
        val r2 = r.toLong() * r
        var dy = -r
        while (dy <= r) {
            val half = sqrt((r2 - dy.toLong() * dy).toDouble()).toInt()
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color)
            dy += 1
        }
    }
}
