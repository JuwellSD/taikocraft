package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.chart.SongLibrary
import top.drawtwice.taikocraft.chart.SongLibraryManager

/**
 * 曲库管理界面：删曲目 / 删单个难度 / 改显示标题。
 *
 * 危险操作走二次确认（与曲包编辑器一致的多重倒计时）。
 */
class SongLibraryScreen(private val previous: Screen? = null) : TaikoScreenBase(Component.literal("曲库管理")) {

    private val library = SongLibrary(SongLibrary.defaultRoot())
    private var songs: List<SongLibrary.SongEntry> = emptyList()
    private var selected = 0
    private var status = ""

    /** 待确认的删除操作 */
    private enum class Danger(val label: String, val steps: Int, val ms: Long) {
        SONG("删除整首曲目", 2, 5_000L),
        DIFFICULTY("删除该难度", 2, 5_000L),
    }

    private var pendingDanger: Danger? = null
    private var pendingStep = 0
    private var pendingSince = 0L
    private var pendingDiffIndex = -1

    private data class RowHit(val index: Int, val y: Int)
    private val rowHits = mutableListOf<RowHit>()

    override fun init() {
        reload()
        val cx = width / 2
        addRenderableWidget(
            Button.builder(Component.literal("删除整首")) { onDanger(Danger.SONG) }
                .bounds(cx - 240, height - 30, 110, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("删除选中难度")) { onDanger(Danger.DIFFICULTY) }
                .bounds(cx - 120, height - 30, 110, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("取消确认")) {
                pendingDanger = null
                pendingDiffIndex = -1
                status = "已取消"
            }.bounds(cx, height - 30, 90, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("← 返回")) { minecraft?.setScreen(previous) }
                .bounds(width - 90, height - 30, 80, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("刷新")) { reload() }
                .bounds(cx + 100, height - 30, 80, 20).build(),
        )
    }

    private fun reload() {
        songs = runCatching { library.listSongs() }
            .onFailure {
                status = "扫描失败：${it.message}"
                TaikoCraft.LOGGER.error("扫描曲库失败", it)
            }
            .getOrDefault(emptyList())
        selected = selected.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
    }

    private fun currentSong(): SongLibrary.SongEntry? = songs.getOrNull(selected)

    private fun onDanger(d: Danger) {
        val song = currentSong()
        if (song == null) {
            status = "曲库为空"
            return
        }

        // 第一次点击
        if (pendingDanger != d) {
            if (d == Danger.DIFFICULTY) {
                status = "请先点选要删除的难度"
                return
            }
            pendingDanger = d
            pendingStep = 1
            pendingSince = System.nanoTime()
            status = "再次点击「${d.label}」确认（1/${d.steps}）"
            return
        }

        // 违规：倒计时未结束
        val elapsed = (System.nanoTime() - pendingSince) / 1_000_000
        if (elapsed < d.ms) {
            val left = ((d.ms - elapsed) / 1000.0).let { "%.1f".format(it) }
            status = "倒计时未结束（${left}s）"
            return
        }

        if (pendingStep < d.steps) {
            pendingStep++
            pendingSince = System.nanoTime()
            status = "已确认 ${pendingStep - 1}/${d.steps}，请继续"
            return
        }

        // 最终执行
        pendingDanger = null
        pendingStep = 0
        execute(d, song)
    }

    private fun execute(d: Danger, song: SongLibrary.SongEntry) {
        runCatching {
            when (d) {
                Danger.SONG -> {
                    val moved = SongLibraryManager.deleteSong(SongLibrary.defaultRoot(), song.directory)
                    status = "已移入回收站：${moved.fileName}"
                }
                Danger.DIFFICULTY -> {
                    val diff = song.difficulties.getOrNull(pendingDiffIndex)
                        ?: run { status = "未选中难度"; return }
                    when (diff.source.extension.lowercase()) {
                        "tja" -> {
                            val rest = SongLibraryManager.removeTjaCourse(diff.source, diff.name)
                            status = "已删除难度「${diff.name}」，剩余：${rest.joinToString("/")}"
                        }
                        "osu" -> {
                            SongLibraryManager.deleteOsuFile(diff.source)
                            status = "已删除难度「${diff.name}」"
                        }
                        else -> status = "不支持的谱面格式"
                    }
                }
            }
        }.onFailure {
            status = "操作失败：${it.message}"
            TaikoCraft.LOGGER.error("曲库操作失败", it)
        }
        pendingDiffIndex = -1
        reload()
    }

    override fun renderContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.drawCenteredString(font, "曲库管理", width / 2, 16, 0xFFFFFFFF.toInt())
        guiGraphics.drawString(font, "曲库目录：${SongLibrary.defaultRoot()}", 10, 32, 0xFF888888.toInt())

        rowHits.clear()
        if (songs.isEmpty()) {
            guiGraphics.drawCenteredString(font, "曲库为空", width / 2, height / 2, 0xFFAAAAAA.toInt())
        } else {
            var y = 52
            val maxRows = ((height - 110 - y) / 20).coerceAtLeast(1)
            for (i in songs.indices.take(maxRows)) {
                val song = songs[i]
                val isSel = i == selected
                if (isSel) guiGraphics.fill(6, y - 2, width - 6, y + 14, 0x40FFAA00)
                guiGraphics.drawString(
                    font, song.displayTitle.take(40), 12, y,
                    if (isSel) 0xFFFFD24A.toInt() else 0xFFDDDDDD.toInt(),
                )
                guiGraphics.drawString(
                    font, "  ${song.difficulties.size} 个难度",
                    width - 130, y, 0xFF888888.toInt(),
                )
                rowHits += RowHit(i, y)
                y += 20
            }

            // 选中歌曲的难度列表
            val song = currentSong()
            if (song != null) {
                val listY = height - 108
                guiGraphics.drawString(font, "难度（点选后按删除）：", 12, listY, 0xFFAAAAAA.toInt())
                var dx = 12
                var dy = listY + 14
                song.difficulties.forEachIndexed { di, diff ->
                    val label = "${diff.name} Lv.${diff.level}"
                    val w = font.width(label) + 12
                    if (dx + w > width - 12) { dx = 12; dy += 20 }
                    val sel = di == pendingDiffIndex
                    guiGraphics.fill(dx, dy - 2, dx + w, dy + 12, if (sel) 0x60FF4444 else 0xFF2A2A33.toInt())
                    guiGraphics.drawString(font, label, dx + 4, dy, if (sel) 0xFFFF8888.toInt() else 0xFF7FE87F.toInt())
                    difficultyHitboxes += DifficultyHitbox(di, dx, dy - 2, dx + w, dy + 12)
                    dx += w + 6
                }
            }
        }

        if (status.isNotBlank()) {
            guiGraphics.drawString(font, status, 12, height - 52, 0xFF7FE87F.toInt())
        }
    }

    private data class DifficultyHitbox(val index: Int, val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
        fun contains(mx: Int, my: Int) = mx in x1..x2 && my in y1..y2
    }

    private val difficultyHitboxes = mutableListOf<DifficultyHitbox>()

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)

        // 点难度
        difficultyHitboxes.firstOrNull { it.contains(mouseX.toInt(), mouseY.toInt()) }?.let { hit ->
            pendingDiffIndex = hit.index
            status = "已选中难度 #${hit.index + 1}，点「删除选中难度」"
            return true
        }

        // 点歌曲行
        rowHits.firstOrNull { mouseY.toInt() in it.y..(it.y + 12) }?.let { hit ->
            selected = hit.index
            pendingDiffIndex = -1
            pendingDanger = null
            return true
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun isPauseScreen(): Boolean = false
}
