package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.OsuParser
import top.drawtwice.taikocraft.chart.SongLibrary
import top.drawtwice.taikocraft.chart.TjaChartFile
import top.drawtwice.taikocraft.chart.TjaParser
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * 难度选择界面。
 *
 * 布局参考 OpenTaiko `layout.json` 的 `difficulty_select` 段。
 */
class DifficultySelectScreen(
    private val song: SongLibrary.SongEntry,
    private val previous: Screen? = null,
) : TaikoScreenBase(Component.literal("选择难度")) {

    private var selected = 0
    private var status = ""

    override fun renderBackdrop(g: GuiGraphics) {
        g.fill(0, 0, width, height, 0xFF101014.toInt())
    }

    override fun renderContent(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.drawCenteredString(font, song.displayTitle, width / 2, height / 2 - 120, 0xFFFFFFFF.toInt())

        val diffs = song.difficulties
        if (diffs.isEmpty()) {
            g.drawCenteredString(font, "没有可玩难度", width / 2, height / 2, 0xFF888888.toInt())
            return
        }

        val spacing = 70
        val startY = height / 2 - (diffs.size - 1) * spacing / 2
        diffs.forEachIndexed { i, d ->
            val y = startY + i * spacing
            val isSel = i == selected
            if (isSel) {
                g.fill(width / 2 - 160, y - 22, width / 2 + 160, y + 22, 0xAA48D268.toInt())
            }
            g.drawCenteredString(
                font, "${d.name}   Lv.${d.level}",
                width / 2, y - 6,
                if (isSel) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt(),
            )
        }

        g.drawString(font, "↑↓ 选择    Enter 开始    ESC 返回", 20, height - 40, 0xFF888888.toInt())
        if (status.isNotEmpty()) {
            g.drawString(font, status, 20, height - 60, 0xFFFF6666.toInt())
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            265 -> { if (selected > 0) selected--; return true }
            264 -> { if (selected < song.difficulties.size - 1) selected++; return true }
            257, 335 -> { startPlay(); return true }
            256 -> { minecraft?.setScreen(previous); return true }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun startPlay() {
        val diff = song.difficulties.getOrNull(selected) ?: return
        try {
            val (chartFile, chart) = when (diff.source.extension.lowercase()) {
                "tja" -> {
                    val parsed = TjaParser.parse(Files.readAllBytes(diff.source), song.audioFile?.name)
                    parsed to (parsed.charts.getOrNull(diff.index) ?: run { status = "难度索引越界"; return })
                }
                "osu" -> {
                    val bm = OsuParser.parseOsu(
                        TjaParser.decodeAny(Files.readAllBytes(diff.source)), song.audioFile?.name,
                    )
                    TjaChartFile(bm.title, bm.version, bm.audioFileName, charts = listOf(bm.chart)) to bm.chart
                }
                else -> return
            }
            val session = PlaySession(chartFile, chart, song.audioFile)
            minecraft?.setScreen(TaikoScreen(session, this))
        } catch (t: Throwable) {
            status = "读取谱面失败：${t.message}"
            TaikoCraft.LOGGER.error("读取谱面失败", t)
        }
    }

    override fun isPauseScreen(): Boolean = false
}
