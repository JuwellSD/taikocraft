package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.chart.SongLibrary

/**
 * 选曲界面 —— 按 OpenTaiko `song_select_core` 的真实布局与贴图复刻。
 *
 * ## 坐标来源
 * `Config/layout.json`（1920×1080 基准）：
 * ```
 * song_list.origin_x = 660, origin_y = 500
 * song_list.offset_x = 45,  offset_y = 120
 * song_list.rows_each_side = 5
 * song_list.selected_x_diff = 50
 * song_list.text_offset_x = -65, text_offset_y = 15
 * song_list.title_max_width = 525
 * song_list.barleft_x_offset = -67
 * ```
 *
 * ## 贴图用法（来自 draw_songselect.lua:259-266）
 * `bar.png`（765×130）以**中心**定位：
 * ```
 * lx = xpos - barW/2
 * ly = ypos - barH/2
 * barleft.png 画在 lx + BARLEFT_X_OFFSET(-67), ly
 * ```
 *
 * ## 绘制顺序
 * 从远到近（i = -rows..rows），近的盖住远的 —— 和 OpenTaiko 一致。
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
        // 背景：bg0.png（1920×1080 彩虹渐变）
        TexDraw.full(g, SongSelectTextures.bg0, 0, 0, width, height, 1920, 1080)
        // 压一层暗色，否则白色斜条在亮的背景上看不清
        g.fill(0, 0, width, height, 0x55000000)
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        val s = width / 1920f

        if (songs.isEmpty()) {
            drawEmptyHint(g)
            drawFooter(g)
            return
        }

        drawSongList(g, s)
        drawSongInfo(g, s)
        drawFooter(g)
        if (status.isNotEmpty()) {
            g.drawString(font, status, (20 * s).toInt(), height - (100 * s).toInt(), 0xFFFF6666.toInt())
        }
    }

    // ================= 斜向歌单 =================

    private fun drawSongList(g: GuiGraphics, s: Float) {
        val ox = SongSelectLayout.listOriginX * s
        val oy = SongSelectLayout.listOriginY * s
        val dx = SongSelectLayout.listOffsetX * s
        val dy = SongSelectLayout.listOffsetY * s
        val rows = SongSelectLayout.listRowsEachSide
        val selDiff = SongSelectLayout.listSelectedXDiff * s

        // bar.png 尺寸（765×130），按窗口缩放
        val barW = (765 * s).toInt()
        val barH = (130 * s).toInt()

        // 从远到近：i 从大到小画？OpenTaiko 从 --rows 到 +rows，
        // 后画的（+rows，最下方）会盖住上面的。保持同样顺序。
        for (i in -rows..rows) {
            val idx = selected + i
            if (idx !in songs.indices) continue
            val song = songs[idx]
            val isSel = i == 0

            val xpos = (ox + i * dx + if (isSel) selDiff else 0f).toInt()
            val ypos = (oy + i * dy).toInt()

            // 选中条：绿色半透明带（layout: select_band_color 72,210,104 alpha 170）
            if (isSel) {
                TexDraw.tinted(g, 0.28f, 0.82f, 0.41f, 0.67f) {
                    TexDraw.full(
                        g, SongSelectTextures.bar,
                        xpos - barW / 2, ypos - barH / 2, barW, barH,
                        765, 130,
                    )
                }
            } else {
                // 未选中：半透明
                TexDraw.tinted(g, 1f, 1f, 1f, if (isSel) 1f else 0.72f) {
                    TexDraw.full(
                        g, SongSelectTextures.bar,
                        xpos - barW / 2, ypos - barH / 2, barW, barH,
                        765, 130,
                    )
                }
            }

            // barleft.png（左侧装饰，x 偏移 -67）
            val blX = xpos - barW / 2 + (SongSelectLayout.barLeftXOffset * s).toInt()
            val blW = (120 * s).toInt()
            TexDraw.full(
                g, SongSelectTextures.barLeft, blX, ypos - barH / 2, blW, barH, 120, 130,
            )

            // 标题（text_offset_x = -65, text_offset_y = 15）
            val tx = idx.let { xpos + (SongSelectLayout.listTextOffsetX * s).toInt() }
            val ty = ypos + (SongSelectLayout.listTextOffsetY * s).toInt() - (barH / 2)
            val label = song.displayTitle
            val maxPx = (SongSelectLayout.listTitleMaxWidth * s).toInt()
            val shown = truncateToWidth(label, maxPx)
            g.drawString(
                font, shown, tx, ty,
                if (isSel) 0xFFFFFFFF.toInt() else 0xFF444444.toInt(),
            )
        }
    }

    // ================= 右侧歌曲信息 =================

    private fun drawSongInfo(g: GuiGraphics, s: Float) {
        val song = songs.getOrNull(selected) ?: return

        // bg_songinfo.png（1006×1080）贴在右侧
        val infoX = (1920 - 1006) * s
        TexDraw.full(
            g, SongSelectTextures.bgSongInfo,
            infoX.toInt(), 0, (1006 * s).toInt(), height, 1006, 1080,
        )

        // 难度列表（difficulties_origin_x = 1790, y = 154, gap_y = 130）
        val diffs = song.difficulties
        for ((i, d) in diffs.take(4).withIndex()) {
            val dx = (1790 * s).toInt()
            val dy = ((154 + i * 130) * s).toInt()
            // sinfo_difficulties_N.png（120×100）
            val tex = when (d.course.coerceIn(0, 4)) {
                0 -> SongSelectTextures.sinfoDiff0
                1 -> SongSelectTextures.sinfoDiff1
                2 -> SongSelectTextures.sinfoDiff2
                3 -> SongSelectTextures.sinfoDiff3
                else -> SongSelectTextures.sinfoDiff4
            }
            TexDraw.full(g, tex, dx, dy, (120 * s).toInt(), (100 * s).toInt(), 120, 100)
            // 等级数字
            g.drawString(
                font, d.level.toString(),
                dx + (30 * s).toInt(), dy + (40 * s).toInt(),
                0xFFFFFFFF.toInt(),
            )
        }

        // 曲名（右上）
        g.drawString(
            font, song.displayTitle,
            (1100 * s).toInt(), (60 * s).toInt(),
            0xFF222222.toInt(),
        )
        // 难度名 + 等级
        val diffText = diffs.joinToString("  ") { "${it.name} ${it.level}" }
        if (diffText.isNotEmpty()) {
            g.drawString(
                font, diffText,
                (1100 * s).toInt(), (120 * s).toInt(),
                0xFF555555.toInt(),
            )
        }
    }

    // ================= 底部 =================

    private fun drawEmptyHint(g: GuiGraphics) {
        g.drawCenteredString(font, "曲库为空", width / 2, height / 2 - 20, 0xFFFFFFFF.toInt())
        g.drawCenteredString(
            font, "把曲目文件夹放到：${SongLibrary.songsDir()}",
            width / 2, height / 2, 0xFFAAAAAA.toInt(),
        )
        g.drawCenteredString(
            font, "每个文件夹放 .tja/.osu + 音频（ogg/mp3/wav）",
            width / 2, height / 2 + 18, 0xFF888888.toInt(),
        )
    }

    private fun drawFooter(g: GuiGraphics) {
        val s = width / 1920f
        val y = height - (46 * s).toInt()
        val lh = (22 * s).toInt().coerceAtLeast(12)

        g.drawString(
            font, "↑↓ 选择   Enter 开始   C 延迟校准   T 音符风格   ESC 返回",
            (24 * s).toInt(), y, 0xFF888888.toInt(),
        )
        if (songs.isNotEmpty()) {
            g.drawString(
                font, "${selected + 1} / ${songs.size}",
                (24 * s).toInt(), y - lh, 0xFFAAAAAA.toInt(),
            )
        }
        g.drawString(
            font,
            "目押 ${TaikoCraftClient.visualOffsetMs.toInt()}ms   音押 ${TaikoCraftClient.audioOffsetMs.toInt()}ms   风格 ${NoteStyleManager.current.displayName}",
            (24 * s).toInt(), y - lh * 2, 0xFF7FE87F.toInt(),
        )
    }

    /** 按像素宽度截断字符串（用字体宽度而不是字符数） */
    private fun truncateToWidth(text: String, maxPx: Int): String {
        if (font.width(text) <= maxPx) return text
        var end = text.length
        while (end > 0 && font.width(text.take(end) + "…") > maxPx) end--
        return if (end <= 0) "…" else text.take(end) + "…"
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            265 -> { if (selected > 0) selected--; return true }
            264 -> { if (selected < songs.size - 1) selected++; return true }
            257, 335 -> { startSelected(); return true }
            67 -> { minecraft?.setScreen(CalibrationScreen(this)); return true }
            84 -> {
                val next = if (NoteStyleManager.current == NoteStyle.SIMPLE) NoteStyle.MC else NoteStyle.SIMPLE
                TaikoCraftClient.setNoteStyle(next)
                return true
            }
            256 -> { minecraft?.setScreen(previous); return true }
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
