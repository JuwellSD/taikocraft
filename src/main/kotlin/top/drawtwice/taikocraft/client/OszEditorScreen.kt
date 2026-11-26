package top.drawtwice.taikocraft.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import top.drawtwice.taikocraft.TaikoCraft
import top.drawtwice.taikocraft.chart.OszEditor
import top.drawtwice.taikocraft.chart.OszStorage
import java.nio.file.Path

/**
 * 曲包编辑器：改歌名 / 歌手 / 难度名，删除难度，导出新 osz。
 *
 * 编辑的是**元数据**，不碰谱面本身。参照坠尔的需求：
 * "osz 格式里面的难度有时候会很乱"。
 */
class OszEditorScreen(
    private val previous: Screen? = null,
) : Screen(Component.literal("曲包编辑器")) {

    private var packages: List<Path> = emptyList()
    private var packageIndex = 0
    private var editor: OszEditor? = null
    private var difficultyIndex = 0
    private var status = ""

    private var titleBox: EditBox? = null
    private var artistBox: EditBox? = null
    private var versionBox: EditBox? = null

    override fun init() {
        OszStorage.ensureDirectories()
        packages = runCatching { OszStorage.listPackages() }
            .onFailure { status = "扫描失败：${it.message}" }
            .getOrDefault(emptyList())

        if (packages.isEmpty()) {
            status = "把 .osz 放到 ${OszStorage.inputDir()}"
        } else {
            if (packageIndex !in packages.indices) packageIndex = 0
            loadPackage(packages[packageIndex])
        }

        buildLayout()
    }

    private fun loadPackage(path: Path) {
        editor = runCatching { OszEditor.load(path) }
            .onFailure {
                status = "读取失败：${it.message}"
                TaikoCraft.LOGGER.error("读取 osz 失败：{}", path, it)
            }
            .getOrNull()
        difficultyIndex = 0
        refreshEditors()
    }

    private fun currentDifficulty(): OszEditor.Difficulty? =
        editor?.difficulties?.getOrNull(difficultyIndex)

    private fun buildLayout() {
        clearWidgets()
        val top = 44

        // 曲包切换
        addRenderableWidget(
            Button.builder(Component.literal("◀ 曲包")) {
                if (packages.isNotEmpty()) {
                    packageIndex = (packageIndex - 1 + packages.size) % packages.size
                    loadPackage(packages[packageIndex])
                    buildLayout()
                }
            }.bounds(10, top, 70, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("曲包 ▶")) {
                if (packages.isNotEmpty()) {
                    packageIndex = (packageIndex + 1) % packages.size
                    loadPackage(packages[packageIndex])
                    buildLayout()
                }
            }.bounds(86, top, 70, 20).build(),
        )

        // 难度切换
        addRenderableWidget(
            Button.builder(Component.literal("◀ 难度")) {
                if (difficultyIndex > 0) {
                    difficultyIndex--
                    refreshEditors()
                }
            }.bounds(190, top, 70, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("难度 ▶")) {
                val max = (editor?.difficulties?.size ?: 1) - 1
                if (difficultyIndex < max) {
                    difficultyIndex++
                    refreshEditors()
                }
            }.bounds(266, top, 70, 20).build(),
        )

        // 输入框
        val boxX = 120
        val boxW = width - 140
        titleBox = EditBox(font, boxX, 78, boxW, 18, Component.literal("歌名")).apply {
            setMaxLength(200)
            setValue(currentDifficulty()?.title ?: "")
            setResponder { currentDifficulty()?.setTitle(it) }
        }
        artistBox = EditBox(font, boxX, 106, boxW, 18, Component.literal("歌手")).apply {
            setMaxLength(200)
            setValue(currentDifficulty()?.artist ?: "")
            setResponder { currentDifficulty()?.setArtist(it) }
        }
        versionBox = EditBox(font, boxX, 134, boxW, 18, Component.literal("难度名")).apply {
            setMaxLength(200)
            setValue(currentDifficulty()?.version ?: "")
            setResponder { currentDifficulty()?.setVersion(it) }
        }
        titleBox?.let { addRenderableWidget(it) }
        artistBox?.let { addRenderableWidget(it) }
        versionBox?.let { addRenderableWidget(it) }

        // 删除/恢复
        addRenderableWidget(
            Button.builder(Component.literal("删除此难度")) {
                val d = currentDifficulty() ?: return@builder
                if (d.isDeleted) d.restore() else d.delete()
            }.bounds(120, 162, 100, 20).build(),
        )

        // 导出
        addRenderableWidget(
            Button.builder(Component.literal("导出修改后的 osz")) {
                exportPackage()
            }.bounds(10, height - 30, 160, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("← 返回")) {
                minecraft?.setScreen(previous)
            }.bounds(width - 90, height - 30, 80, 20).build(),
        )
    }

    private fun refreshEditors() {
        val d = currentDifficulty()
        titleBox?.setValue(d?.title ?: "")
        artistBox?.setValue(d?.artist ?: "")
        versionBox?.setValue(d?.version ?: "")
    }

    private fun exportPackage() {
        val ed = editor
        if (ed == null) {
            status = "没有可导出的曲包"
            return
        }
        if (ed.difficulties.all { it.isDeleted }) {
            status = "所有难度都被删除了，无法导出空包"
            return
        }
        val source = packages.getOrNull(packageIndex)
        if (source == null) {
            status = "曲包路径丢失"
            return
        }
        runCatching {
            val target = OszStorage.outputPathFor(source)
            OszStorage.save(ed.export(), target)
            status = "已导出：${target.fileName}"
        }.onFailure {
            status = "导出失败：${it.message}"
            TaikoCraft.LOGGER.error("导出 osz 失败", it)
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(0, 0, width, height, 0xF0101014.toInt())
        guiGraphics.drawCenteredString(font, "曲包编辑器", width / 2, 18, 0xFFFFFFFF.toInt())

        if (packages.isEmpty()) {
            guiGraphics.drawCenteredString(font, status, width / 2, height / 2, 0xFFAAAAAA.toInt())
        } else {
            val name = packages.getOrNull(packageIndex)?.fileName?.toString() ?: ""
            guiGraphics.drawString(font, "曲包：$name", 10, 32, 0xFFFFD24A.toInt())

            val d = currentDifficulty()
            if (d != null) {
                val state = if (d.isDeleted) "【将删除】" else if (d.hasEdits) "【已修改】" else "【未修改】"
                val color = when {
                    d.isDeleted -> 0xFFFF6666.toInt()
                    d.hasEdits -> 0xFF7FE87F.toInt()
                    else -> 0xFFAAAAAA.toInt()
                }
                guiGraphics.drawString(
                    font,
                    "难度 ${difficultyIndex + 1}/${editor?.difficulties?.size ?: 0}：${d.fileName} $state",
                    190,
                    32,
                    color,
                )
                guiGraphics.drawString(font, "原难度名：${d.originalVersion}", 12, 192, 0xFF888888.toInt())
                guiGraphics.drawString(font, "谱面音符数：${d.parsed.chart.notes.size}", 12, 206, 0xFF888888.toInt())
            }

            // 难度列表概览
            var y = 230
            guiGraphics.drawString(font, "难度列表：", 12, y - 16, 0xFFFFFFFF.toInt())
            editor?.difficulties?.forEachIndexed { i, diff ->
                val mark = when {
                    diff.isDeleted -> "✗"
                    diff.hasEdits -> "✎"
                    else -> "·"
                }
                val c = when {
                    i == difficultyIndex -> 0xFFFFD24A.toInt()
                    diff.isDeleted -> 0xFF666666.toInt()
                    else -> 0xFFAAAAAA.toInt()
                }
                guiGraphics.drawString(font, "$mark ${diff.fileName} — ${diff.version}", 20, y, c)
                y += 12
            }
        }

        if (status.isNotBlank()) {
            guiGraphics.drawString(font, status, 180, height - 26, 0xFF7FE87F.toInt())
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun isPauseScreen(): Boolean = false
}
