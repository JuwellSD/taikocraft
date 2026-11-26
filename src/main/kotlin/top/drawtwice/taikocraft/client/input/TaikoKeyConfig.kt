package top.drawtwice.taikocraft.client.input

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Side

/**
 * 键位配置。默认两套方案（见设计文档 7.2）：
 *
 * - 新手：左咔 D / 左咚 F / 右咚 J / 右咔 K
 * - 老手：左咔 R / 左咚 F / 右咚 J / 右咔 I
 *
 * 大音符需要「两侧同时按」才能满分（文档 6.2）。
 */
enum class KeyScheme(val displayName: String) {
    BEGINNER("新手（D F J K）"),
    EXPERT("老手（R F J I）"),
    CUSTOM("自定义");

    companion object {
        fun fromName(name: String?): KeyScheme =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BEGINNER
    }
}

data class KeyMapping(
    val leftDon: Int,
    val leftKa: Int,
    val rightDon: Int,
    val rightKa: Int,
) {
    fun toColorSide(key: Int): Pair<NoteColor, Side>? = when (key) {
        leftDon -> NoteColor.DON to Side.LEFT
        leftKa -> NoteColor.KA to Side.LEFT
        rightDon -> NoteColor.DON to Side.RIGHT
        rightKa -> NoteColor.KA to Side.RIGHT
        else -> null
    }

    companion object {
        // GLFW 键码：D=68 F=70 I=73 J=74 K=75 R=82
        private const val KEY_D = 68
        private const val KEY_F = 70
        private const val KEY_I = 73
        private const val KEY_J = 74
        private const val KEY_K = 75
        private const val KEY_R = 82

        fun of(scheme: KeyScheme): KeyMapping = when (scheme) {
            // 新手：左咔 D，左咚 F，右咚 J，右咔 K
            KeyScheme.BEGINNER -> KeyMapping(leftDon = KEY_F, leftKa = KEY_D, rightDon = KEY_J, rightKa = KEY_K)
            // 老手：左咔 R，左咚 F，右咚 J，右咔 I
            KeyScheme.EXPERT -> KeyMapping(leftDon = KEY_F, leftKa = KEY_R, rightDon = KEY_J, rightKa = KEY_I)
            KeyScheme.CUSTOM -> of(KeyScheme.BEGINNER)
        }

        /** 旧版 GLFW 键码转 InputConstants.Key 便于显示 */
        fun keyName(glfwKey: Int): String =
            runCatching { InputConstants.getKey(glfwKey, -1).displayName.string }.getOrDefault("键$glfwKey")
    }
}

/** 运行时键位状态（由设置界面修改） */
object TaikoKeyConfig {
    var scheme: KeyScheme = KeyScheme.BEGINNER
    var mapping: KeyMapping = KeyMapping.of(KeyScheme.BEGINNER)

    fun applyScheme(newScheme: KeyScheme) {
        scheme = newScheme
        mapping = KeyMapping.of(newScheme)
    }
}
