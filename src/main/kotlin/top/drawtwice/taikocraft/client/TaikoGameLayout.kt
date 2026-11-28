package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import top.drawtwice.taikocraft.TaikoCraft

/**
 * OpenTaiko 游戏画面的真实布局与贴图。
 *
 * ## 数据来源（不是自己编的）
 * 全部数值抄自 OpenTaiko 皮肤包：
 * `System/Open-World Memories/GameConfig.ini`
 *
 * ## 设计分辨率
 * `SkinConfig.ini` → `Resolution=1920,1080`
 *
 * ## 关键坐标（1P，1920×1080 基准）
 * | 项 | 值 |
 * |---|---|
 * | 谱面轨道区（Game_Lane_X/Y） | 499, 287 |
 * | 打击判定圈原点（Game_ScrollField_X/Y） | 523, 286 |
 * | 音符尺寸（Game_Notes_Size） | 195 × 195 |
 * | 4 拍像素跨度（Game_Notes_Interval） | 960 |
 * | 太鼓鼓面（Game_Taiko_X/Y） | 303, 324 |
 * | 鼓底色块大小（Base.png） | 180 × 200 |
 * | 太鼓外框（Game_Taiko_Frame_X/Y） | 495, 204 |
 * | 判定文字（Game_Judge_X/Y） | 554, 234 |
 * | 连击数字（Game_Taiko_Combo_X/Y） | 391, 406 |
 * | 分数（Game_Score_X/Y） | 25, 343 |
 * | 血条（Game_Gauge_X/Y） | 719, 208 |
 */
object TaikoGameLayout {

    // ---- 设计基准 ----
    const val DESIGN_W = 1920
    const val DESIGN_H = 1080

    // ---- 1P 坐标（GameConfig.ini）----
    const val LANE_X = 499
    const val LANE_Y = 287
    const val LANE_SUB_X = 499
    const val LANE_SUB_Y = 488

    /** 判定圈 / 音符原点（音符左上角） */
    const val SCROLL_FIELD_X = 523
    const val SCROLL_FIELD_Y = 286

    /** 音符边长（Game_Notes_Size=195,195） */
    const val NOTES_SIZE = 195

    /** 4 拍 = 960px（Game_Notes_Interval） */
    const val NOTES_INTERVAL = 960

    /**
     * 音符速度倍率。
     *
     * OpenTaiko 的 `Game_Notes_Interval = 960` 对应 scroll speed 1.0。
     * 坠尔实测我们这边偏慢，用 1.25 补偿（可随时再调）。
     */
    const val SCROLL_MULTIPLIER = 1.25

    /** 鼓面图（Game_Taiko_X/Y） */
    const val TAIKO_X = 303
    const val TAIKO_Y = 324

    /** 鼓外框 */
    const val TAIKO_FRAME_X = 495
    const val TAIKO_FRAME_Y = 204

    /** 判定文字锚点 */
    const val JUDGE_X = 554
    const val JUDGE_Y = 234

    /** 连击数字 */
    const val COMBO_X = 391
    const val COMBO_Y = 406

    /** 分数 */
    const val SCORE_X = 25
    const val SCORE_Y = 343

    /** 血条 */
    const val GAUGE_X = 719
    const val GAUGE_Y = 208
    const val GAUGE_W = 1050
    const val GAUGE_H = 76

    // ---- 2P 的偏移（OpenTaiko 2P 是同一套坐标 + y 偏移）----
    const val P2_DY = 264

    /**
     * 屏幕缩放：把 1920×1080 设计坐标映射到当前窗口。
     *
     * OpenTaiko 用 `screenScale = Resolution[0] / 1280.0` 单独缩放 X 和 Y，
     * 这里按窗口**宽度**等比缩放（保持长宽比，避免音符变成椭圆）。
     */
    fun scaleFor(width: Int): Float = width / DESIGN_W.toFloat()

    /** 1P/2P 的垂直偏移量（设计坐标） */
    fun playerYOffset(player: Int): Int = if (player == 2) P2_DY else 0
}

/** OpenTaiko 游戏画面贴图（已从皮肤包导入） */
object TaikoGameTextures {
    private fun tex(path: String) =
        ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "textures/taiko/$path")

    // ---- 鼓（6_Taiko）----
    val drumBase = tex("opentaiko/base.png")      // 180×200，鼓身 + 鼓面
    val drumDon = tex("opentaiko/don.png")        // 180×200，咚高亮（左半/右半）
    val drumKa = tex("opentaiko/ka.png")          // 180×200，咔高亮（外圈左右半边）
    val frame = tex("opentaiko/frame.png")        // 1426×337，机台外框

    // ---- 轨道（12_Lane）----
    val laneMain = tex("opentaiko/lane_main.png") // 1422×195，主轨道
    val laneSub = tex("opentaiko/lane_sub.png")   // 1421×39，副轨道分隔条
    val laneGogo = tex("opentaiko/lane_gogo.png") // 1420×195，GOGO 区轨道

    // ---- 小节线（5_Game/Bar.png，4×270 纯色柱）----
    val barLine = tex("opentaiko/bar.png")

    // ---- 音符（notes 皮肤包）----
    // notes.png: 1170×195 = 6 列 × 195，每列一个音符
    // 列序：Don, Ka, BigDon, BigKa, KaDon, ADLib
    val notes = tex("original/notes.png")
    const val NOTES_COLS = 6
    const val NOTES_CELL = 195

    /** 打击圈（hitcircle.png 195×195） */
    val hitCircle = tex("original/hitcircle.png")

    // rolls.png: 1170×195 = 6 列
    // 列序：小连打头, 小连打身, 小连打尾, 大连打头, 大连打身, 大连打尾
    val rolls = tex("original/rolls.png")
    const val ROLLS_COLS = 6

    // balloons.png: 1365×195 = 7 列
    // 气球(2) 久寿玉(2) 导火索(3)
    val balloons = tex("original/balloons.png")
    const val BALLOON_COLS = 7

    // ---- 打击特效 ----
    // explosion.png: 2730×780 = 7 列 × 2 行，每格 390×390
    // 行序：第 0 行 = 良（紫），第 1 行 = 可（白）
    val explosion = tex("original/hit/explosion.png")
    const val EXPLOSION_COLS = 7
    const val EXPLOSION_ROWS = 2
    const val EXPLOSION_CELL = 390
    /** 特效总时长（毫秒），7 帧均分 */
    const val EXPLOSION_MS = 210.0

    // ---- 血条 ----
    val gaugeBase = tex("opentaiko/gauge_base.png")
    val gaugeFill = tex("opentaiko/gauge_fill.png")

    // ---- 判定文字（Judge.png：150×700 = 1 列 × 5 行）----
    val judge = tex("opentaiko/judge.png")
}

/**
 * 贴图绘制辅助。
 *
 * ## 为什么单独封装
 * MC 1.21.1 的 `GuiGraphics` 有 5 个 `blit` 重载，签名差异极大：
 *
 * | 参数数 | 签名 |
 * |---|---|
 * | 6 | `(rl, x, y, u, v, w, h)` |
 * | 7 | `(rl, x, y, w, h, u, v)` |
 * | 9 | `(rl, x, y, w, h, u, v, uW, vH)` |
 * | 10 | `(rl, x, y, w, h, u, v, uW, vH, texW, texH)` → 实为 10 参 |
 *
 * 历史踩坑记录：曾 6 次猜错签名。这里统一走 **10 参数版本**（明确给出贴图总尺寸），
 * 编译期由 Kotlin 类型系统兜底。签名已核对 Yarn 映射：
 * `drawTexture(Identifier, int, int, int, int, float, float, int, int, int, int)`
 */
object TexDraw {

    /**
     * 画贴图的一个子矩形，并缩放到目标尺寸。
     *
     * @param dx 目标左上角 X
     * @param dy 目标左上角 Y
     * @param dw 目标宽
     * @param dh 目标高
     * @param u 源矩形左上角 X（贴图像素）
     * @param v 源矩形左上角 Y
     * @param uw 源矩形宽
     * @param vh 源矩形高
     * @param tw 贴图总宽
     * @param th 贴图总高
     */
    fun sub(
        g: GuiGraphics,
        rl: ResourceLocation,
        dx: Int, dy: Int, dw: Int, dh: Int,
        u: Int, v: Int, uw: Int, vh: Int,
        tw: Int, th: Int,
    ) {
        if (dw <= 0 || dh <= 0 || uw <= 0 || vh <= 0 || tw <= 0 || th <= 0) return
        g.blit(rl, dx, dy, dw, dh, u.toFloat(), v.toFloat(), uw, vh, tw, th)
    }

    /** 整张贴图缩放到目标矩形 */
    fun full(
        g: GuiGraphics,
        rl: ResourceLocation,
        dx: Int, dy: Int, dw: Int, dh: Int,
        tw: Int, th: Int,
    ) = sub(g, rl, dx, dy, dw, dh, 0, 0, tw, th, tw, th)

    /**
     * 带透明度/色调地画（用顶点色 * 贴图色）。
     *
     * MC 的 `blit` 不支持着色，必须 `setShaderColor` 全局调制。
     * 用完**必须**复位，否则后续所有绘制都会染色。
     */
    inline fun tinted(
        g: GuiGraphics,
        r: Float, gr: Float, b: Float, a: Float,
        draw: () -> Unit,
    ) {
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(r, gr, b, a)
        try {
            draw()
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        }
    }
}
