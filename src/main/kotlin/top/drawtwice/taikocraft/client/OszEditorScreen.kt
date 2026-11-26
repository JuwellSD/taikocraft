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
 *
 * 危险操作的保护（坠尔指定）：
 * - **多选删除**：连续确认 2 次，每次 5 秒倒计时
 * - **只保留当前难度**：连续确认 3 次，每次 7 秒倒计时
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

    /** 多选模式：点击难度行切换勾选 */
    private var multiSelectMode = false
    private val selectedForDelete = mutableSetOf<String>()

    /** 危险操作的待确认状态 */
    private enum class ConfirmAction(
        val totalSteps: Int,
        val stepDurationMs: Long,
        val idleLabel: String,
    ) {
        MULTI_DELETE(2, 5_000L, "删除选中"),
        KEEP_ONLY(3, 7_000L, "只保留当前"),
    }

    private class PendingConfirm(val action: ConfirmAction) {
        var step = 1
        var stepStartedAtNanos = System.nanoTime()

        val total: Int get() = action.totalSteps
        val durationMs: Long get() = action.stepDurationMs

        fun elapsedMs(): Long = (System.nanoTime() - stepStartedAtNanos) / 1_000_000
        fun remainingMs(): Long = (durationMs - elapsedMs()).coerceAtLeast(0)
        fun ready(): Boolean = remainingMs() == 0L

        fun advance() {
            step++
            stepStartedAtNanos = System.nanoTime()
        }
    }

    private var pending: PendingConfirm? = null

    private var multiSelectButton: Button? = null
    private var multiDeleteButton: Button? = null
    private var keepOnlyButton: Button? = null
    private var cancelButton: Button? = null

    /** 难度行命中区（render 时计算，mouseClicked 使用） */
    private data class RowHit(val index: Int, val y: Int)
    private val rowHits = mutableListOf<RowHit>()
    private var listScroll = 0

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
        multiSelectMode = false
        selectedForDelete.clear()
        pending = null
        listScroll = 0
        refreshEditors()
    }

    private fun currentDifficulty(): OszEditor.Difficulty? =
        editor?.difficulties?.getOrNull(difficultyIndex)

    private fun buildLayout() {
        clearWidgets()
        val top = 44

        addRenderableWidget(
            Button.builder(Component.literal("◀ 曲包")) {
                if (packages.isNotEmpty()) {
                    cancelPending(quiet = true)
                    packageIndex = (packageIndex - 1 + packages.size) % packages.size
                    loadPackage(packages[packageIndex])
                    buildLayout()
                }
            }.bounds(10, top, 70, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("曲包 ▶")) {
                if (packages.isNotEmpty()) {
                    cancelPending(quiet = true)
                    packageIndex = (packageIndex + 1) % packages.size
                    loadPackage(packages[packageIndex])
                    buildLayout()
                }
            }.bounds(86, top, 70, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("◀ 难度")) {
                if (difficultyIndex > 0) {
                    difficultyIndex--
                    pending = null
                    refreshEditors()
                }
            }.bounds(190, top, 70, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("难度 ▶")) {
                val max = (editor?.difficulties?.size ?: 1) - 1
                if (difficultyIndex < max) {
                    difficultyIndex++
                    pending = null
                    refreshEditors()
                }
            }.bounds(266, top, 70, 20).build(),
        )

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

        addRenderableWidget(
            Button.builder(Component.literal("删除/恢复此难度")) {
                val d = currentDifficulty() ?: return@builder
                if (d.isDeleted) d.restore() else d.delete()
                pending = null
            }.bounds(120, 162, 130, 20).build(),
        )

        // ---- 多选删除相关 ----
        multiSelectButton = Button.builder(Component.literal("多选模式：关")) {
            multiSelectMode = !multiSelectMode
            if (!multiSelectMode) selectedForDelete.clear()
            pending = null
        }.bounds(10, height - 96, 100, 20).build()
        addRenderableWidget(multiSelectButton!!)

        multiDeleteButton = Button.builder(Component.literal("删除选中")) {
            onDangerClick(ConfirmAction.MULTI_DELETE)
        }.bounds(114, height - 96, 120, 20).build()
        addRenderableWidget(multiDeleteButton!!)

        keepOnlyButton = Button.builder(Component.literal("只保留当前")) {
            onDangerClick(ConfirmAction.KEEP_ONLY)
        }.bounds(238, height - 96, 120, 20).build()
        addRenderableWidget(keepOnlyButton!!)

        cancelButton = Button.builder(Component.literal("取消确认")) {
            cancelPending()
        }.bounds(362, height - 96, 90, 20).build()
        addRenderableWidget(cancelButton!!)

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

    // ------------------------------------------------------------------
    // 危险操作：多重倒计时确认
    // ------------------------------------------------------------------

    private fun onDangerClick(action: ConfirmAction) {
        val current = pending
        if (current != null) {
            if (current.action != action) {
                status = "已有未完成的确认，先取消或继续"
                return
            }
            if (!current.ready()) {
                status = "倒计时未结束（还剩 ${(current.remainingMs() / 1000.0).toInt() + 1} 秒）"
                return
            }
            if (current.step < current.total) {
                current.advance()
                status = "已确认 ${current.step - 1}/${current.total}，请继续"
                return
            }
            // 最后一步：执行
            execute(action)
            pending = null
            return
        }

        // 首次点击：先检查前置条件
        when (action) {
            ConfirmAction.MULTI_DELETE -> {
                val all = editor?.difficulties ?: return
                if (!multiSelectMode) {
                    status = "先打开多选模式并勾选难度"
                    return
                }
                if (selectedForDelete.isEmpty()) {
                    status = "先勾选要删除的难度"
                    return
                }
                if (selectedForDelete.size >= all.size) {
                    status = "不能删除全部难度（至少保留一个）"
                    return
                }
            }
            ConfirmAction.KEEP_ONLY -> {
                val all = editor?.difficulties ?: return
                if (all.size <= 1) {
                    status = "只有一个难度，无需操作"
                    return
                }
            }
        }
        pending = PendingConfirm(action)
        status = ""
    }

    private fun execute(action: ConfirmAction) {
        val ed = editor ?: return
        when (action) {
            ConfirmAction.MULTI_DELETE -> {
                val count = selectedForDelete.size
                ed.difficulties.filter { it.fileName in selectedForDelete }.forEach { it.delete() }
                selectedForDelete.clear()
                multiSelectMode = false
                status = "已标记删除 $count 个难度（记得点「导出修改后的 osz」）"
            }
            ConfirmAction.KEEP_ONLY -> {
                val keep = currentDifficulty() ?: return
                var removed = 0
                ed.difficulties.forEach {
                    if (it !== keep && !it.isDeleted) {
                        it.delete()
                        removed++
                    }
                }
                status = "已只保留「${keep.version}」，其余 $removed 个标记删除（记得导出）"
            }
        }
    }

    private fun cancelPending(quiet: Boolean = false) {
        if (pending != null && !quiet) status = "已取消确认"
        pending = null
    }

    private fun confirmButtonLabel(action: ConfirmAction, extra: String = ""): String {
        val p = pending
        if (p == null || p.action != action) return action.idleLabel + extra
        return if (p.ready()) {
            "确认 ${p.step}/${p.total}" + if (p.step == p.total) "（最后一步）" else ""
        } else {
            val secs = (p.remainingMs() / 1000.0).let { "%.1f".format(it) }
            "确认 ${p.step}/${p.total} · ${secs}s"
        }
    }

    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------

    private val listTop = 226

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(0, 0, width, height, 0xF0101014.toInt())
        guiGraphics.drawCenteredString(font, "曲包编辑器", width / 2, 18, 0xFFFFFFFF.toInt())

        // 按钮文字/可见性实时更新
        multiSelectButton?.setMessage(Component.literal("多选模式：${if (multiSelectMode) "开" else "关"}"))
        multiDeleteButton?.setMessage(
            Component.literal(confirmButtonLabel(ConfirmAction.MULTI_DELETE, " (${selectedForDelete.size})")),
        )
        keepOnlyButton?.setMessage(Component.literal(confirmButtonLabel(ConfirmAction.KEEP_ONLY)))
        cancelButton?.visible = pending != null
        cancelButton?.active = pending != null

        if (packages.isEmpty()) {
            guiGraphics.drawCenteredString(font, status, width / 2, height / 2, 0xFFAAAAAA.toInt())
        } else {
            val name = packages.getOrNull(packageIndex)?.fileName?.toString() ?: ""
            guiGraphics.drawString(font, "曲包：$name", 10, 32, 0xFFFFD24A.toInt())

            val d = currentDifficulty()
            if (d != null) {
                val state = when {
                    d.isDeleted -> "【将删除】"
                    d.hasEdits -> "【已修改】"
                    else -> "【未修改】"
                }
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
                guiGraphics.drawString(font, "原难度名：${d.originalVersion}", 12, 190, 0xFF888888.toInt())
                guiGraphics.drawString(font, "谱面音符数：${d.parsed.chart.notes.size}", 12, 204, 0xFF888888.toInt())
            }

            // 难度列表（可滚动）
            guiGraphics.drawString(font, "难度列表：", 12, listTop - 14, 0xFFFFFFFF.toInt())
            if (multiSelectMode) {
                guiGraphics.drawString(font, "（多选模式：点击行勾选）", 80, listTop - 14, 0xFF7FE87F.toInt())
            }

            rowHits.clear()
            val maxRows = ((height - 110 - listTop) / 12).coerceAtLeast(1)
            val diffs = editor?.difficulties ?: emptyList()
            listScroll = listScroll.coerceIn(0, (diffs.size - maxRows).coerceAtLeast(0))
            var y = listTop
            for (i in listScroll until minOf(diffs.size, listScroll + maxRows)) {
                val diff = diffs[i]
                val mark = when {
                    diff.isDeleted -> "✗"
                    diff.hasEdits -> "✎"
                    else -> "·"
                }
                val box = if (diff.fileName in selectedForDelete) "☑" else "☐"
                val prefix = if (multiSelectMode) "$box " else ""
                val c = when {
                    i == difficultyIndex -> 0xFFFFD24A.toInt()
                    diff.isDeleted -> 0xFF666666.toInt()
                    else -> 0xFFAAAAAA.toInt()
                }
                guiGraphics.drawString(font, "$prefix$mark ${diff.fileName} — ${diff.version}", 20, y, c)
                rowHits += RowHit(i, y)
                y += 12
            }
            if (diffs.size > maxRows) {
                guiGraphics.drawString(
                    font,
                    "滚轮翻页  ${listScroll + 1}-${minOf(diffs.size, listScroll + maxRows)}/${diffs.size}",
                    20,
                    y,
                    0xFF777777.toInt(),
                )
            }
        }

        if (status.isNotBlank()) {
            guiGraphics.drawString(font, status, 170, height - 52, 0xFF7FE87F.toInt())
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && multiSelectMode) {
            val hit = rowHits.firstOrNull { mouseY.toInt() in it.y..(it.y + 10) }
            if (hit != null) {
                val diff = editor?.difficulties?.getOrNull(hit.index) ?: return super.mouseClicked(mouseX, mouseY, button)
                if (diff.isDeleted) {
                    status = "已标记删除的难度不能勾选"
                } else if (!selectedForDelete.add(diff.fileName)) {
                    selectedForDelete.remove(diff.fileName)
                }
                pending = null
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0) return false
        listScroll = (listScroll - scrollY.toInt()).coerceAtLeast(0)
        return true
    }

    override fun isPauseScreen(): Boolean = false
}
