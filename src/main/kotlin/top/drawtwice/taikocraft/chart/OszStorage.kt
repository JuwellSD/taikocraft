package top.drawtwice.taikocraft.chart

import java.nio.file.Files
import java.nio.file.Path

/**
 * osz 曲包文件管理：扫描目录、保存导出。
 *
 * 目录约定：
 * - `config/taikocraft/osz/`          ← 放待编辑的 .osz
 * - `config/taikocraft/osz/out/`      ← 编辑结果输出
 */
object OszStorage {

    fun inputDir(): Path = Path.of("config", "taikocraft", "osz")

    fun outputDir(): Path = inputDir().resolve("out")

    fun listPackages(): List<Path> {
        val dir = inputDir()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".osz", ignoreCase = true) }
                .sorted()
                .toList()
        }
    }

    /** 输出文件名：原名加后缀，避免覆盖原包 */
    fun outputPathFor(original: Path): Path {
        val base = original.fileName.toString().removeSuffix(".osz").removeSuffix(".OSZ")
        var candidate = outputDir().resolve("${base}_edited.osz")
        var i = 2
        while (Files.exists(candidate)) {
            candidate = outputDir().resolve("${base}_edited_$i.osz")
            i++
        }
        return candidate
    }

    fun save(bytes: ByteArray, target: Path) {
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    /** 确保目录存在，便于玩家放入文件 */
    fun ensureDirectories() {
        Files.createDirectories(inputDir())
        Files.createDirectories(outputDir())
    }
}
