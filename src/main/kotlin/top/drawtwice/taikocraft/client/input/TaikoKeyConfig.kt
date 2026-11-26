package top.drawtwice.taikocraft.client.input

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Side

/**
 * 键位配置。默认两套方案（见设计文档 7.2）：
 *
 * - 新手（DJFK）：左侧 D=咚 F=咔，右侧 J=咚 K=咔
 * - 老手（DFJK）：左侧 D=咚 F=咔，右侧 J=咔 K=咚（对称手型，实机常见）
 *
 * 大音符需要「两侧同时按」才能满分（文档 6.2）。
 */
enum class KeyScheme(val displayName: String) {
    BEGINNER("新手（D J F K）"),
    EXPERT("老手（D F J K）"),
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
        fun of(scheme: KeyScheme): KeyMapping = when (scheme) {
            // GLFW 键码：D=68 F=70 J=74 K=75
            KeyScheme.BEGINNER -> KeyMapping(leftDon = 68, leftKa = 74, rightDon = 70, rightKa = 75)
            KeyScheme.EXPERT -> KeyMapping(leftDon = 68, leftKa = 70, rightDon = 75, rightKa = 74)
            KeyScheme.CUSTOM -> BEGINNER_MAPPING
        }

        private val BEGINNER_MAPPING = KeyMapping(leftDon = 68, leftKa = 74, rightDon = 70, rightKa = 75)

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
