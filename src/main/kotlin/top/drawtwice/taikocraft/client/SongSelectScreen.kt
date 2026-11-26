package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.audio.OpenAlPlayback
import top.drawtwice.taikocraft.chart.Chart
import top.drawtwice.taikocraft.chart.OsuParser
import top.drawtwice.taikocraft.chart.SongLibrary
import top.drawtwice.taikocraft.chart.TjaChartFile
import top.drawtwice.taikocraft.chart.TjaParser
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * 选曲界面：扫描本地曲库 → 选择歌曲/难度 → 进入 [TaikoScreen]。
 */
class SongSelectScreen(private val previous: Screen? = null) : TaikoScreenBase(Component.literal("选曲")) {

    private val library = SongLibrary(SongLibrary.defaultRoot())
    private var songs: List<SongLibrary.SongEntry> = emptyList()
    private var scrollOffset = 0
    private var statusMessage = ""

    /** 每页条目数 */
    private val pageSize = 8

    override fun init() {
        songs = runCatching { library.listSongs() }
            .onFailure { statusMessage = "扫描曲库失败：${it.message}" }
            .getOrDefault(emptyList())

        addRenderableWidget(
            Button.builder(Component.literal("← 返回")) {
                minecraft?.setScreen(previous)
            }.bounds(10, height - 30, 80, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("导入曲目")) {
                minecraft?.setScreen(ImportScreen(this))
            }.bounds(96, height - 56, 80, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("曲包编辑器")) {
                minecraft?.setScreen(OszEditorScreen(this))
            }.bounds(width - 370, height - 30, 110, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("延迟校准")) {
                minecraft?.setScreen(
                    CalibrationScreen(this) {
                        TaikoCraft.LOGGER.info(
                            "偏移已应用：目押 {} ms，音押 {} ms",
                            TaikoCraftClient.visualOffsetMs,
                            TaikoCraftClient.audioOffsetMs,
                        )
                    },
                )
            }.bounds(width - 130, height - 30, 120, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("上一页")) { scrollOffset = (scrollOffset - pageSize).coerceAtLeast(0) }
                .bounds(10, height - 80, 80, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("下一页")) {
                if (scrollOffset + pageSize < songs.size) scrollOffset += pageSize
            }.bounds(96, height - 80, 80, 20).build(),
        )
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.drawCenteredString(font, "选曲", width / 2, 18, 0xFFFFFFFF.toInt())

        difficultyHitboxes.clear()
        if (songs.isEmpty()) {
            guiGraphics.drawCenteredString(
                font,
                "曲库为空。点「导入曲目」或把文件放进 config/taikocraft/import/",
                width / 2,
                height / 2 - 10,
                0xFFAAAAAA.toInt(),
            )
        } else {
            val visible = songs.drop(scrollOffset).take(pageSize)
            var y = 44
            for (song in visible) {
                guiGraphics.drawString(font, song.displayTitle, 24, y, 0xFFFFFFFF.toInt())
                y += 12
                if (song.difficulties.isEmpty()) {
                    guiGraphics.drawString(font, "  （没有可玩难度）", 24, y, 0xFF777777.toInt())
                    y += 14
                } else {
                    var x = 32
                    for (diff in song.difficulties) {
                        val label = "${diff.name} Lv.${diff.level}"
                        val btnWidth = font.width(label) + 12
                        guiGraphics.fill(x - 3, y - 2, x + btnWidth, y + 10, 0xFF2A2A33.toInt())
                        guiGraphics.drawString(font, label, x, y, 0xFF7FE87F.toInt())
                        difficultyHitboxes += Hitbox(song, diff, x - 3, y - 2, x + btnWidth, y + 10)
                        x += btnWidth + 6
                        if (x > width - 120) break
                    }
                    y += 20
                }
                y += 4
            }
        }

        if (statusMessage.isNotBlank()) {
            guiGraphics.drawString(font, statusMessage, 10, height - 50, 0xFFFF7777.toInt())
        }

        if (songs.isNotEmpty()) {
            guiGraphics.drawString(
                font,
                "${scrollOffset + 1}-${minOf(scrollOffset + pageSize, songs.size)} / ${songs.size}",
                190,
                height - 51,
                0xFFAAAAAA.toInt(),
            )
        }
    }

    private data class Hitbox(
        val song: SongLibrary.SongEntry,
        val diff: SongLibrary.DifficultyEntry,
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
    ) {
        fun contains(mx: Int, my: Int) = mx in x1..x2 && my in y1..y2
    }

    private val difficultyHitboxes = mutableListOf<Hitbox>()

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        val hit = difficultyHitboxes.firstOrNull { it.contains(mouseX.toInt(), mouseY.toInt()) }
            ?: return super.mouseClicked(mouseX, mouseY, button)
        startPlay(hit.song, hit.diff)
        return true
    }

    override fun isPauseScreen(): Boolean = false

    private fun startPlay(song: SongLibrary.SongEntry, diff: SongLibrary.DifficultyEntry) {
        val chartFile: TjaChartFile
        val chart: Chart
        try {
            when (diff.source.extension.lowercase()) {
                "tja" -> {
                    val parsed = TjaParser.parse(Files.readAllBytes(diff.source), song.audioFile?.name)
                    chartFile = parsed
                    chart = parsed.charts.getOrNull(diff.index)
                        ?: run { statusMessage = "难度索引越界"; return }
                }
                "osu" -> {
                    val bm = OsuParser.parseOsu(TjaParser.decodeAny(Files.readAllBytes(diff.source)), song.audioFile?.name)
                    chart = bm.chart
                    chartFile = TjaChartFile(
                        title = bm.title,
                        subtitle = bm.version,
                        audioFileName = bm.audioFileName,
                        charts = listOf(chart),
                    )
                }
                else -> return
            }
        } catch (t: Throwable) {
            TaikoCraft.LOGGER.error("读取谱面失败", t)
            statusMessage = "读取谱面失败：${t.message}"
            return
        }

        val audioPath = song.audioFile
        val playback = if (audioPath != null) {
            runCatching { OpenAlPlayback.create(audioPath) }
                .onFailure {
                    TaikoCraft.LOGGER.warn("音频加载失败，将以静默模式游玩：{}", it.message)
                }
                .getOrNull()
        } else null

        val session = PlaySession(chartFile, chart, playback)
        session.clock.visualOffsetMs = TaikoCraftClient.visualOffsetMs
        session.clock.audioOffsetMs = TaikoCraftClient.audioOffsetMs
        difficultyHitboxes.clear()
        minecraft?.setScreen(TaikoScreen(session, this))
    }
}
