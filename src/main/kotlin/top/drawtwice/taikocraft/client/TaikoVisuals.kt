package top.drawtwice.taikocraft.client

/**
 * TaikoCraft 视觉常量 —— 数值来源与推导过程全部记录在此。
 *
 * ## 设计基准
 * 1280×720（OpenTaiko 默认分辨率）。
 *
 * ## 实测比例（来自坠尔提供的实机截图，1920×1080）
 * | 元素 | 实测 | 占宽比例 | 1280 基准 |
 * |---|---|---|---|
 * | 音符直径 | ~64px | 3.33% | 42.7 |
 * | 相邻 16 分音符间距（相切） | ~64px | 3.33% | 42.7 |
 * | 4 拍跨度（16 个 16 分音符） | ~1024px | 53.3% | 682 |
 *
 * 这是**从参考图量出来的**，不是猜的。之前用 OpenTaiko 的
 * `Game_Notes_Interval = 960` 导致音符过大（960/1280 = 75% 屏宽，4 拍太稀疏）。
 *
 * ## 判定圈
 * 参考图里判定位置有一个**与音符同大小的圆圈轮廓**，命中时它被音符覆盖。
 * 早期版本把它画在 judgeX+26（错误），后来干脆删了（也错误）。
 * 正确做法：**与音符同中心、同半径画出轮廓**。
 */
object TaikoVisuals {

    const val REF_WIDTH = 1280.0
    const val REF_HEIGHT = 720.0

    // ---- 从实测比例换算 ----

    /** 音符直径占屏宽比例（实测 64/1920） */
    const val NOTE_DIAMETER_RATIO = 0.0333

    /** 4 拍跨度占屏宽比例（实测 1024/1920） */
    const val BEAT_SPAN_RATIO = 0.533

    /** 1P 轨道中心 Y 占屏高比例（参考图 ~33%） */
    const val LANE_Y_1P_RATIO = 0.33

    /** 2P 轨道中心 Y 占屏高比例（参考图 ~50%） */
    const val LANE_Y_2P_RATIO = 0.50

    /** 轨道左边界占屏宽比例（参考图 ~26%） */
    const val LANE_LEFT_RATIO = 0.26

    /** 轨道高度占屏高比例（参考图 ~10%） */
    const val LANE_HEIGHT_RATIO = 0.10

    // ---- 换算函数 ----

    fun noteDiameter(screenWidth: Int): Int =
        (screenWidth * NOTE_DIAMETER_RATIO).toInt().coerceAtLeast(18)

    fun noteRadius(screenWidth: Int): Int = noteDiameter(screenWidth) / 2

    /** 4 拍跨度（像素） */
    fun beatSpan(screenWidth: Int): Double = screenWidth * BEAT_SPAN_RATIO

    fun laneLeft(screenWidth: Int): Int = (screenWidth * LANE_LEFT_RATIO).toInt()

    fun laneCenter1P(screenHeight: Int): Int = (screenHeight * LANE_Y_1P_RATIO).toInt()

    fun laneCenter2P(screenHeight: Int): Int = (screenHeight * LANE_Y_2P_RATIO).toInt()

    fun laneHeight(screenHeight: Int): Int =
        (screenHeight * LANE_HEIGHT_RATIO).toInt().coerceAtLeast(30)
}
