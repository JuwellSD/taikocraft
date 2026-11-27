package top.drawtwice.taikocraft.client

/**
 * TaikoCraft 视觉常量 —— **全部来自 OpenTaiko 的皮肤默认值**。
 *
 * 来源：`OpenTaiko/src/Common/CSkin.cs`
 * ```
 * Game_Notes_Size      = { 130, 130 }   // 音符宽高（px）
 * Game_Notes_Interval  = 960            // 每 4 个四分音符的像素距离
 * Game_Lane_X          = { 333, 333 }   // 轨道起点 X
 * Game_Lane_Y          = { 192, 368 }   // 1P/2P 轨道起点 Y
 * ```
 * 这些坐标基于 **1280×720 的参考分辨率**。
 * 本项目用 `scale = screenWidth / 1280` 等比映射到任意窗口。
 *
 * ## 不要自己发明数值
 * 坠尔的要求：**还原游戏时必须参考原作的美术，不要自己硬开发**。
 * 任何视觉参数都应该能追溯到 OpenTaiko 的皮肤定义或原作截图，
 * 而不是「看起来差不多」。
 */
object TaikoVisuals {

    // ---- OpenTaiko 皮肤默认值（1280×720 基准）----

    /** 音符外框边长（px @ 1280 宽） */
    const val NOTE_SIZE_BASE = 130

    /** 每 4 个四分音符的滚动距离（px @ 1280 宽） */
    const val NOTE_INTERVAL_BASE = 960

    /** 轨道左边界 X（px @ 1280 宽） */
    const val LANE_X_BASE = 333

    /** 1P / 2P 轨道 Y（px @ 720 高） */
    const val LANE_Y_1P_BASE = 192
    const val LANE_Y_2P_BASE = 368

    /** 参考分辨率 */
    const val REF_WIDTH = 1280.0
    const val REF_HEIGHT = 720.0

    /** 底部判定条高度（OpenTaiko 的 Lane 贴图区域，约 176px 高的上半部分用于音符） */
    const val LANE_HEIGHT_BASE = 96

    // ---- 由基准值换算到实际窗口 ----

    /** 按宽度等比缩放 */
    fun scaleFor(screenWidth: Int): Double = screenWidth / REF_WIDTH

    fun noteSize(screenWidth: Int): Int =
        (NOTE_SIZE_BASE * scaleFor(screenWidth)).toInt().coerceAtLeast(16)

    fun noteInterval(screenWidth: Int): Int =
        (NOTE_INTERVAL_BASE * scaleFor(screenWidth)).toInt().coerceAtLeast(80)

    fun laneX(screenWidth: Int): Int =
        (LANE_X_BASE * scaleFor(screenWidth)).toInt()

    fun laneY1P(screenWidth: Int, screenHeight: Int): Int =
        (LANE_Y_1P_BASE * (screenHeight / REF_HEIGHT)).toInt()

    fun laneY2P(screenWidth: Int, screenHeight: Int): Int =
        (LANE_Y_2P_BASE * (screenHeight / REF_HEIGHT)).toInt()

    fun laneHeight(screenWidth: Int): Int =
        (LANE_HEIGHT_BASE * scaleFor(screenWidth)).toInt().coerceAtLeast(24)
}
