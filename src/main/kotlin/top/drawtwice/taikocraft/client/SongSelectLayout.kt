package top.drawtwice.taikocraft.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import top.drawtwice.taikocraft.TaikoCraft

/**
 * OpenTaiko 选曲界面的布局配置（`song_select_core/Config/layout.json`）。
 *
 * 坐标基准：**1920×1080**。渲染时按窗口尺寸等比缩放。
 *
 * 不自己发明数值 —— 全部从 JSON 读取。
 */
object SongSelectLayout {

    private var loaded: JsonObject? = null

    private fun load(): JsonObject {
        loaded?.let { return it }
        val obj = runCatching {
            val res = Minecraft.getInstance().resourceManager
                .getResource(ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "songselect/config/layout.json"))
                .orElseThrow()
            res.open().use { JsonParser.parseReader(it.bufferedReader()).asJsonObject }
        }.getOrElse {
            TaikoCraft.LOGGER.error("layout.json 加载失败：{}", it.message)
            JsonObject()
        }
        loaded = obj
        return obj
    }

    private fun section(name: String): JsonObject =
        load().getAsJsonObject(name) ?: JsonObject()

    fun int(sec: String, key: String, def: Int): Int =
        runCatching { section(sec).get(key).asInt }.getOrDefault(def)

    fun double(sec: String, key: String, def: Double): Double =
        runCatching { section(sec).get(key).asDouble }.getOrDefault(def)

    // ---- 常用值（带默认值，防止 JSON 缺失）----

    val listOriginX get() = int("song_list", "origin_x", 660)
    val listOriginY get() = int("song_list", "origin_y", 500)
    val listOffsetX get() = int("song_list", "offset_x", 45)
    val listOffsetY get() = int("song_list", "offset_y", 120)
    val listRowsEachSide get() = int("song_list", "rows_each_side", 5)
    val listTextOffsetX get() = int("song_list", "text_offset_x", -65)
    val listTextOffsetY get() = int("song_list", "text_offset_y", 15)
    val listTitleMaxWidth get() = int("song_list", "title_max_width", 525)
    val listSelectedXDiff get() = int("song_list", "selected_x_diff", 50)
    val listSelectedArrowGap get() = int("song_list", "selected_arrow_gap", 925)
    val backdropWidth get() = int("song_list", "backdrop_width", 980)
    val backdropAlpha get() = int("song_list", "backdrop_alpha", 90)
    val barLeftXOffset get() = int("song_list", "barleft_x_offset", -67)

    val fontSize get() = int("fonts", "text", 28)
    val fontSmall get() = int("fonts", "small", 18)
    val fontLarge get() = int("fonts", "large", 40)

    val waitX get() = int("wait_screen", "x", 960)
    val waitY get() = int("wait_screen", "y", 540)
}

/** 选曲界面的贴图路径（来自 song_select_core/Textures/） */
object SongSelectTextures {
    private fun tex(name: String) =
        ResourceLocation.fromNamespaceAndPath(TaikoCraft.MOD_ID, "songselect/textures/$name")

    val bar = tex("bar.png")
    val barLeft = tex("barleft.png")
    val bg0 = tex("bg0.png")
    val bg1 = tex("bg1.png")
    val bgHeader = tex("bg_header.png")
    val bgSongInfo = tex("bg_songinfo.png")
    val arrowL = tex("selected-arrow-l.png")
    val arrowR = tex("selected-arrow-r.png")
    val sinfoDiff0 = tex("sinfo_difficulties_0.png")
    val sinfoDiff1 = tex("sinfo_difficulties_1.png")
    val placeholderPortrait = tex("placeholder_portrait.png")
    val preimage = tex("preimage.png")
    val load = tex("load.png")
}
