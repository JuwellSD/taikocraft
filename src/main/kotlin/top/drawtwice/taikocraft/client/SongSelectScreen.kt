package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
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

    /** 延迟手动输入框 */
    private var visualBox: EditBox? = null
    private var audioBox: EditBox? = null

    /** Autoplay 开关（仅 OP 可用） */
    private var autoPlayEnabled = false

    /** 行2 标签的 x 坐标（由 init 计算，renderContent 使用） */
    private var visualLabelX = 0
    private var audioLabelX = 0

    override fun init() {
        songs = runCatching { library.listSongs() }
            .onFailure { statusMessage = "扫描曲库失败：${it.message}" }
            .getOrDefault(emptyList())

        // ============================================================
        // 布局：底部四行，行高 24，互不重叠
        //   行1: 上一页 / 下一页 / 导入曲目 / AUTO
        //   行2: 目押 [框] -5 +5   音押 [框] -5 +5
        //   行3: 返回 / 键位设置 / 曲包编辑器 / 曲库管理 / 延迟校准
        // ============================================================
        val row1 = height - 100
        val row2 = height - 72
        val row3 = height - 32
        val btnH = 20

        addRenderableWidget(
            Button.builder(Component.literal("上一页")) { scrollOffset = (scrollOffset - pageSize).coerceAtLeast(0) }
                .bounds(10, row1, 70, btnH).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("下一页")) {
                if (scrollOffset + pageSize < songs.size) scrollOffset += pageSize
            }.bounds(86, row1, 70, btnH).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("导入曲目")) {
                minecraft?.setScreen(ImportScreen(this))
            }.bounds(162, row1, 80, btnH).build(),
        )
        )
        addRenderableWidget(
            Button.builder(Component.literal("材质：${NoteSkinManager.current.displayName}")) { btn ->
                NoteSkinManager.current =
                    if (NoteSkinManager.current == NoteSkin.MC) NoteSkin.ORIGINAL else NoteSkin.MC
                btn.message = Component.literal("材质：${NoteSkinManager.current.displayName}")
                TaikoCraftClient.setOffsets(TaikoCraftClient.visualOffsetMs, TaikoCraftClient.audioOffsetMs)
            }.bounds(334, row1, 130, btnH).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("AUTO: 关")) { btn ->
                if (!AutoPlayPermission.canUse()) {
                    statusMessage = "AutoPlay 需要 OP 权限（等级 2）"
                    return@builder
                }
                autoPlayEnabled = !autoPlayEnabled
                btn.message = Component.literal(if (autoPlayEnabled) "AUTO: 开" else "AUTO: 关")
            }.bounds(248, row1, 80, btnH).build(),
        )

        // ---- 行2：延迟输入 ----
        // 布局（从右往左数，单位 px）：
        //   [目押标签 32][输入框 46][-5 30][+5 30] [间距 12] [音押标签 32][输入框 46][-5 30][+5 30]
        // 总宽 = 32+46+30+30 + 12 + 32+46+30+30 = 288
        val row2W = 288
        val row2X = width - row2W - 8
        var cx = row2X
        visualLabelX = cx; cx += 32
        visualBox = EditBox(font, cx, row2, 46, 18, Component.literal("目押")).apply {
            setMaxLength(6)
            setValue(TaikoCraftClient.visualOffsetMs.toInt().toString())
            setResponder { txt -> txt.toDoubleOrNull()?.let { applyOffsets(visual = it) } }
        }
        cx += 46
        addRenderableWidget(Button.builder(Component.literal("-5")) {
            applyOffsets(visual = TaikoCraftClient.visualOffsetMs - 5)
            visualBox?.setValue(TaikoCraftClient.visualOffsetMs.toInt().toString())
        }.bounds(cx, row2, 30, 18).build())
        cx += 30
        addRenderableWidget(Button.builder(Component.literal("+5")) {
            applyOffsets(visual = TaikoCraftClient.visualOffsetMs + 5)
            visualBox?.setValue(TaikoCraftClient.visualOffsetMs.toInt().toString())
        }.bounds(cx, row2, 30, 18).build())
        cx += 30 + 12
        audioLabelX = cx; cx += 32
        audioBox = EditBox(font, cx, row2, 46, 18, Component.literal("音押")).apply {
            setMaxLength(6)
            setValue(TaikoCraftClient.audioOffsetMs.toInt().toString())
            setResponder { txt -> txt.toDoubleOrNull()?.let { applyOffsets(audio = it) } }
        }
        cx += 46
        addRenderableWidget(Button.builder(Component.literal("-5")) {
            applyOffsets(audio = TaikoCraftClient.audioOffsetMs - 5)
            audioBox?.setValue(TaikoCraftClient.audioOffsetMs.toInt().toString())
        }.bounds(cx, row2, 30, 18).build())
        cx += 30
        addRenderableWidget(Button.builder(Component.literal("+5")) {
            applyOffsets(audio = TaikoCraftClient.audioOffsetMs + 5)
            audioBox?.setValue(TaikoCraftClient.audioOffsetMs.toInt().toString())
        }.bounds(cx, row2, 30, 18).build())

        visualBox?.let { addRenderableWidget(it) }
        audioBox?.let { addRenderableWidget(it) }

        // ---- 行3：功能入口，等宽排开 ----
        val entries = listOf<Pair<String, () -> Unit>>(
            "← 返回" to { minecraft?.setScreen(previous) },
            "键位设置" to { minecraft?.setScreen(KeyBindingScreen(this)) },
            "曲包编辑器" to { minecraft?.setScreen(OszEditorScreen(this)) },
            "曲库管理" to { minecraft?.setScreen(SongLibraryScreen(this)) },
            "延迟校准" to {
                minecraft?.setScreen(CalibrationScreen(this) {
                    TaikoCraft.LOGGER.info(
                        "偏移已应用：目押 {} ms，音押 {} ms",
                        TaikoCraftClient.visualOffsetMs, TaikoCraftClient.audioOffsetMs,
                    )
                })
            },
        )
        val w3 = 110
        val gap = 6
        val totalW = entries.size * w3 + (entries.size - 1) * gap
        var x3 = (width - totalW) / 2
        for ((label, action) in entries) {
            addRenderableWidget(Button.builder(Component.literal(label)) { action() }.bounds(x3, row3, w3, btnH).build())
            x3 += w3 + gap
        }
    }

    /** 只改传入的那个值，另一个保持不变 */
    private fun applyOffsets(visual: Double? = null, audio: Double? = null) {
        TaikoCraftClient.setOffsets(
            visual ?: TaikoCraftClient.visualOffsetMs,
            audio ?: TaikoCraftClient.audioOffsetMs,
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

        // 延迟输入标签（坐标由 init 计算，保证不重叠）
        val row2 = height - 72
        guiGraphics.drawString(font, "目押", visualLabelX, row2 + 5, 0xFFAAAAAA.toInt())
        guiGraphics.drawString(font, "音押", audioLabelX, row2 + 5, 0xFFAAAAAA.toInt())

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
        // Autoplay 二次校验（按钮可能被绕过）
        session.autoPlay = autoPlayEnabled && AutoPlayPermission.canUse()
        difficultyHitboxes.clear()
        minecraft?.setScreen(TaikoScreen(session, this))
    }
}
