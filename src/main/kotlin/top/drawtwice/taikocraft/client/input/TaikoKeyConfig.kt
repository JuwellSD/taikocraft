package top.drawtwice.taikocraft.client.input

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW
import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Side

/**
 * 键位配置。
 * - 新手：左咔 D / 左咚 F / 右咚 J / 右咔 K
 * - 老手：左咔 R / 左咚 F / 右咚 J / 右咔 I
 */
enum class KeyScheme(val displayName: String) {
    BEGINNER("新手（D F J K）"),
    EXPERT("老手（R F J I）"),
    CUSTOM("自定义");

    companion object {
        fun fromName(n: String?): KeyScheme = entries.firstOrNull { it.name.equals(n, true) } ?: BEGINNER
    }
}

data class KeyMapping(
    val leftDon: Int, val leftKa: Int, val rightDon: Int, val rightKa: Int,
) {
    fun toColorSide(key: Int): Pair<NoteColor, Side>? = when (key) {
        leftDon -> NoteColor.DON to Side.LEFT
        leftKa -> NoteColor.KA to Side.LEFT
        rightDon -> NoteColor.DON to Side.RIGHT
        rightKa -> NoteColor.KA to Side.RIGHT
        else -> null
    }

    companion object {
        private const val D = 68
        private const val F = 70
        private const val I = 73
        private const val J = 74
        private const val K = 75
        private const val R = 82

        fun of(scheme: KeyScheme): KeyMapping = when (scheme) {
            KeyScheme.BEGINNER -> KeyMapping(leftDon = F, leftKa = D, rightDon = J, rightKa = K)
            KeyScheme.EXPERT -> KeyMapping(leftDon = F, leftKa = R, rightDon = J, rightKa = I)
            KeyScheme.CUSTOM -> KeyMapping(leftDon = F, leftKa = D, rightDon = J, rightKa = K)
        }

        fun keyName(glfwKey: Int): String =
            runCatching { InputConstants.getKey(glfwKey, -1).displayName.string }.getOrDefault("键$glfwKey")
    }
}

/** 运行时键位（由设置修改） */
object TaikoKeyConfig {
    var scheme: KeyScheme = KeyScheme.BEGINNER
    var mapping: KeyMapping = KeyMapping.of(KeyScheme.BEGINNER)

    fun apply(scheme: KeyScheme) {
        this.scheme = scheme
        mapping = KeyMapping.of(scheme)
    }
}
