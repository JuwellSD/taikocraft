package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.chart.SongLibrary

/**
 * 选曲界面 —— 按 OpenTaiko `song_select_core/Config/layout.json` 复刻。
 *
 * ## 布局（1920×1080 基准，等比缩放到窗口）
 * ```
 * 斜向歌单：bar i 位于 origin(660,500) + i × offset(45,120)
 * 选中条额外右移 selected_x_diff(50)
 * 上下各 rows_each_side(5) 条
 * ```
 */
class SongSelectScreen(
    private val previous: Screen? = null,
) : TaikoScreenBase(Component.literal("TaikoCraft 选曲")) {

    private var songs: List<SongLibrary.SongEntry> = emptyList()
    private var selected = 0
    private var status = ""

    override fun init() {
        SongLibrary.ensureDirs()
        songs = runCatching { SongLibrary.listSongs() }
            .onFailure { status = "扫描失败：${it.message}"; TaikoCraft.LOGGER.error("曲库扫描失败", it) }
            .getOrDefault(emptyList())
        selected = selected.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
    }

    override fun renderBackdrop(g: GuiGraphics) {
        g.fill(0, 0, width, height, 0xFF101014.toInt())
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // 缩放：layout.json 基于 1920×1080
        val sx = width / 1920f
        val sy = height / 1080f
        fun px(v: Int) = (v * sx).toInt()
        fun py(v: Int) = (v * sy).toInt()

        // ---- 斜向歌单 ----
        val ox = px(SongSelectLayout.listOriginX)
        val oy = py(SongSelectLayout.listOriginY)
        val dx = px(SongSelectLayout.listOffsetX)
        val dy = py(SongSelectLayout.listOffsetY)
        val rows = SongSelectLayout.listRowsEachSide
        val selDiff = px(SongSelectLayout.listSelectedXDiff)

        if (songs.isEmpty()) {
            g.drawCenteredString(
                font, "曲库为空", width / 2, height / 2 - 20, 0xFFFFFFFF.toInt(),
            )
            g.drawCenteredString(
                font, "把曲目文件夹放到：${SongLibrary.songsDir()}", width / 2, height / 2, 0xFFAAAAAA.toInt(),
            )
            g.drawCenteredString(
                font, "每个文件夹放 .tja/.osu + 音频（ogg/mp3/wav）", width / 2, height / 2 + 18, 0xFF888888.toInt(),
            )
        } else {
            // 从远到近绘制（远的先画，近的覆盖）
            for (i in -rows..rows) {
                val idx = selected + i
                if (idx !in songs.indices) continue
                val song = songs[idx]
                val isSel = i == 0

                val x = ox + i * dx + if (isSel) selDiff else 0
                val y = oy + i * dy

                // 选中条：绿色带（layout: select_band_color = 72,210,104, alpha 170）
                if (isSel) {
                    g.fill(x - px(10), y - py(28), x + px(700), y + py(28),
                        (if (isSel) 170 shl 24 else 0) or 0x48D268)
                }

                // 条目底色
                val barAlpha = if (isSel) 0xE0 else 0x90
                g.fill(x, y - py(24), x + px(680), y + py(24), (barAlpha shl 24) or 0x1A1A20)

                // 标题（受 title_max_width 限制）
                val label = song.displayTitle
                val maxChars = (SongSelectLayout.listTitleMaxWidth / 14).coerceAtLeast(8)
                val shown = if (label.length > maxChars) label.take(maxChars - 1) + "…" else label
                val tx = x + px(SongSelectLayout.listTextOffsetX.let { if (it < 0) 0 else it })
                val ty = y - py(8)
                g.drawString(font, shown, maxOf(tx, px(20)), ty, if (isSel) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())

                // 难度等级标签
                val diffLabel = song.difficulties.joinToString(" / ") { "${it.name} Lv.${it.level}" }
                if (diffLabel.isNotEmpty()) {
                    g.drawString(
                        font, diffLabel, maxOf(tx, px(20)), ty + py(20),
                        if (isSel) 0xFFFFD24A.toInt() else 0xFF888888.toInt(),
                    )
                }
            }
        }

        // ---- 底部提示 ----
        val hintY = height - 40
        g.drawString(
            font, "↑↓ 选择    Enter 开始    C 延迟校准    ESC 返回",
            20, hintY, 0xFF888888.toInt(),
        )
        if (songs.isNotEmpty()) {
            g.drawString(font, "${selected + 1} / ${songs.size}", 20, hintY - 20, 0xFFAAAAAA.toInt())
        }
        // 当前延迟（坠尔要求：一眼能看见现在是多少）
        g.drawString(
            font,
            "目押 ${TaikoCraftClient.visualOffsetMs.toInt()} ms    音押 ${TaikoCraftClient.audioOffsetMs.toInt()} ms",
            20, hintY - 40, 0xFF7FE87F.toInt(),
        )
        if (status.isNotEmpty()) {
            g.drawString(font, status, 20, hintY - 60, 0xFFFF6666.toInt())
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            265 -> { if (selected > 0) selected--; return true }              // UP
            264 -> { if (selected < songs.size - 1) selected++; return true } // DOWN
            257, 335 -> { startSelected(); return true }                       // ENTER
            67 -> {                                                            // C：延迟校准
                minecraft?.setScreen(CalibrationScreen(this))
                return true
            }
            256 -> { minecraft?.setScreen(previous); return true }             // ESC
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun startSelected() {
        val song = songs.getOrNull(selected) ?: return
        if (song.difficulties.isEmpty()) { status = "这首歌没有可玩难度"; return }
        minecraft?.setScreen(DifficultySelectScreen(song, this))
    }

    override fun isPauseScreen(): Boolean = false
}
