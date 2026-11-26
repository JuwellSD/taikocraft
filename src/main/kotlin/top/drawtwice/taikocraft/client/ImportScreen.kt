package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.chart.SongImporter
import top.drawtwice.taikocraft.chart.SongLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/**
 * 导入界面。
 *
 * Minecraft 没有系统级文件选择器，因此采用「投放目录 + 列表点选」的方式：
 * 玩家把文件放进 `config/taikocraft/import/`，这里列出可导入的条目，点击即导入。
 *
 * 支持（坠尔确认的规则）：
 * - `.osz`              → 直接导入（本身是压缩包）
 * - `.tja` + 音频        → 需要同一目录下有 `.ogg` / `.mp3` / `.wav`
 * - `.zip`              → 内含 tja+音频，或内含 osz
 */
data class Importable(val file: Path, val companionAudio: Path?)

class ImportScreen(private val previous: Screen? = null) : Screen(Component.literal("导入曲目")) {

    private var items: List<Importable> = emptyList()
    private var status = ""
    private var statusColor = 0xFF7FE87F.toInt()
    private var rowHits = mutableListOf<Pair<Int, Int>>() // index -> y
    private var scrollOffset = 0

    override fun init() {
        ImportStore.ensure()
        rescan()
        addRenderableWidget(
            Button.builder(Component.literal("刷新")) { rescan() }
                .bounds(10, height - 30, 70, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("← 返回")) {
                minecraft?.setScreen(previous)
            }.bounds(width - 90, height - 30, 80, 20).build(),
        )
    }

    private fun rescan() {
        rowHits.clear()
        items = runCatching { ImportStore.scan() }
            .onFailure {
                status = "扫描失败：${it.message}"
                statusColor = 0xFFFF6666.toInt()
            }
            .getOrDefault(emptyList())
        if (items.isEmpty() && status.isBlank()) {
            status = "把文件放到 ${ImportStore.importDir()}"
            statusColor = 0xFFAAAAAA.toInt()
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(0, 0, width, height, 0xF0101014.toInt())
        guiGraphics.drawCenteredString(font, "导入曲目", width / 2, 18, 0xFFFFFFFF.toInt())

        guiGraphics.drawString(font, "投放目录：${ImportStore.importDir()}", 12, 38, 0xFF888888.toInt())
        guiGraphics.drawString(font, "支持 .osz / .tja（需同目录音频）/ .zip", 12, 52, 0xFF888888.toInt())

        rowHits.clear()
        val listTop = 74
        val maxRows = ((height - 110 - listTop) / 26).coerceAtLeast(1)
        scrollOffset = scrollOffset.coerceIn(0, (items.size - maxRows).coerceAtLeast(0))

        var y = listTop
        for (i in scrollOffset until minOf(items.size, scrollOffset + maxRows)) {
            val item = items[i]
            val name = item.file.name
            val ext = item.file.extension.lowercase()
            val hint = when {
                ext == "tja" && item.companionAudio == null -> "缺少音频（需同目录 .ogg/.mp3/.wav）"
                ext == "tja" -> "音频：${item.companionAudio?.name}"
                ext == "osz" -> "osz 压缩包"
                ext == "zip" -> "zip 压缩包"
                else -> ""
            }
            val color = if (ext == "tja" && item.companionAudio == null) 0xFFFF8888.toInt() else 0xFFDDDDDD.toInt()
            guiGraphics.drawString(font, name, 20, y, color)
            guiGraphics.drawString(font, hint, 20, y + 11, 0xFF777777.toInt())
            rowHits += i to y
            y += 26
        }

        if (items.size > maxRows) {
            guiGraphics.drawString(
                font,
                "滚轮翻页  ${scrollOffset + 1}-${minOf(items.size, scrollOffset + maxRows)}/${items.size}",
                20,
                y + 4,
                0xFF777777.toInt(),
            )
        }

        if (status.isNotBlank()) {
            guiGraphics.drawString(font, status, 90, height - 46, statusColor)
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button)
        val my = mouseY.toInt()
        val hit = rowHits.firstOrNull { my in it.second..(it.second + 20) } ?: return super.mouseClicked(mouseX, mouseY, button)
        doImport(items[hit.first])
        return true
    }

    private fun doImport(item: Importable) {
        val songsRoot = SongLibrary.defaultRoot()
        val result = runCatching {
            SongImporter.import(item.file, songsRoot, item.companionAudio)
        }.getOrElse { SongImporter.Result.Fail("导入异常：${it.message}") }

        when (result) {
            is SongImporter.Result.Ok -> {
                status = "✓ 已导入「${result.title}」（${result.difficulties} 个难度）"
                statusColor = 0xFF7FE87F.toInt()
                // 导入成功后把文件移到 imported/ 避免重复导入
                ImportStore.moveToImported(item.file)
                rescan()
            }
            is SongImporter.Result.Fail -> {
                status = "✗ ${result.reason}"
                statusColor = 0xFFFF6666.toInt()
                TaikoCraft.LOGGER.warn("导入失败：{} -> {}", item.file, result.reason)
            }
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0) return false
        scrollOffset = (scrollOffset - scrollY.toInt()).coerceAtLeast(0)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}

/** 导入投放目录管理 */
object ImportStore {
    fun importDir(): Path = Path.of("config", "taikocraft", "import")
    private fun importedDir(): Path = importDir().resolve("imported")

    fun ensure() {
        Files.createDirectories(importDir())
        Files.createDirectories(importedDir())
    }

    fun scan(): List<Importable> {
        ensure()
        val dir = importDir()
        val files = Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it) }.toList()
        }
        val audioByStem = files
            .filter { it.extension.lowercase() in setOf("ogg", "mp3", "wav") }
            .groupBy { it.nameWithoutExtension.lowercase() }

        return files
            .filter { it.extension.lowercase() in setOf("osz", "tja", "zip") }
            .sortedBy { it.name }
            .map { f ->
                val companion = if (f.extension.lowercase() == "tja") {
                    audioByStem[f.nameWithoutExtension.lowercase()]?.firstOrNull()
                } else null
                Importable(f, companion)
            }
    }

    fun moveToImported(file: Path) {
        runCatching {
            Files.move(
                file,
                importedDir().resolve(file.name),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
