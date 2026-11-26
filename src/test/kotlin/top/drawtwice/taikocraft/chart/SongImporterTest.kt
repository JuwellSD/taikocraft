package top.drawtwice.taikocraft.chart

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SongImporterTest {

    private lateinit var tmp: Path
    private lateinit var songsRoot: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("taikocraft-import-test")
        songsRoot = tmp.resolve("songs")
        Files.createDirectories(songsRoot)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private val tja = """
        TITLE:测试曲
        BPM:120
        OFFSET:0
        COURSE:Oni
        LEVEL:8
        #START
        1111
        #END
    """.trimIndent()

    private val osu = """
        osu file format v14

        [General]
        AudioFilename: song.ogg
        Mode: 1

        [Metadata]
        Title:OSZ 测试曲
        TitleUnicode:OSZ 测试曲
        Artist:歌手
        ArtistUnicode:歌手
        Creator:Mapper
        Version:Inner Oni

        [Difficulty]
        OverallDifficulty:7

        [HitObjects]
        256,192,1000,1,1,0:0:0:0:
    """.trimIndent()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, d) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(d)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `tja 没有音频时拒绝导入并给出明确提示`() {
        val tjaFile = tmp.resolve("song.tja")
        Files.writeString(tjaFile, tja)
        val result = SongImporter.import(tjaFile, songsRoot)
        assertIs<SongImporter.Result.Fail>(result)
        assertTrue(result.reason.contains("必须附带音频") || result.reason.contains("附带音频"))
        assertEquals(0, songsRoot.listDirectoryEntries().size)
    }

    @Test
    fun `tja 附带 ogg 可以导入`() {
        val tjaFile = tmp.resolve("song.tja")
        Files.writeString(tjaFile, tja)
        val ogg = tmp.resolve("song.ogg")
        Files.write(ogg, ByteArray(128))

        val result = SongImporter.import(tjaFile, songsRoot, ogg)
        assertIs<SongImporter.Result.Ok>(result)
        assertEquals("测试曲", result.title)
        assertEquals(1, result.difficulties)
        assertTrue(result.songDir.resolve("chart.tja").exists())
        assertTrue(result.songDir.resolve("song.ogg").exists())
    }

    @Test
    fun `tja 附带 mp3 可以导入`() {
        val tjaFile = tmp.resolve("song.tja")
        Files.writeString(tjaFile, tja)
        val mp3 = tmp.resolve("song.mp3")
        Files.write(mp3, ByteArray(128))

        val result = SongImporter.import(tjaFile, songsRoot, mp3)
        assertIs<SongImporter.Result.Ok>(result)
        assertTrue(result.songDir.resolve("song.mp3").exists())
    }

    @Test
    fun `tja 附带非音频文件被拒绝`() {
        val tjaFile = tmp.resolve("song.tja")
        Files.writeString(tjaFile, tja)
        val txt = tmp.resolve("readme.txt")
        Files.writeString(txt, "not audio")

        val result = SongImporter.import(tjaFile, songsRoot, txt)
        assertIs<SongImporter.Result.Fail>(result)
    }

    @Test
    fun `osz 直接导入`() {
        val oszBytes = zip(mapOf("map.osu" to osu.toByteArray(), "song.ogg" to ByteArray(64)))
        val oszFile = tmp.resolve("pack.osz")
        Files.write(oszFile, oszBytes)

        val result = SongImporter.import(oszFile, songsRoot)
        assertIs<SongImporter.Result.Ok>(result)
        assertEquals("OSZ 测试曲", result.title)
        assertEquals(1, result.difficulties)
        assertTrue(result.songDir.resolve("song.ogg").exists())
        val osuFiles = result.songDir.listDirectoryEntries().filter { it.name.endsWith(".osu") }
        assertEquals(1, osuFiles.size)
        assertTrue(osuFiles[0].readText().contains("Version:Inner Oni"))
    }

    @Test
    fun `osz 缺音频时拒绝`() {
        val oszBytes = zip(mapOf("map.osu" to osu.toByteArray()))
        val oszFile = tmp.resolve("pack.osz")
        Files.write(oszFile, oszBytes)

        val result = SongImporter.import(oszFile, songsRoot)
        assertIs<SongImporter.Result.Fail>(result)
        assertTrue(result.reason.contains("没有找到音频"))
    }

    @Test
    fun `zip 内含 tja 与音频可以导入`() {
        val bytes = zip(
            mapOf(
                "曲目/chart.tja" to tja.toByteArray(),
                "曲目/song.ogg" to ByteArray(64),
            ),
        )
        val zipFile = tmp.resolve("bundle.zip")
        Files.write(zipFile, bytes)

        val result = SongImporter.import(zipFile, songsRoot)
        assertIs<SongImporter.Result.Ok>(result)
        assertEquals("测试曲", result.title)
        assertTrue(result.songDir.resolve("chart.tja").exists())
        assertTrue(result.songDir.resolve("song.ogg").exists())
    }

    @Test
    fun `zip 内含 osz 时按 osz 处理`() {
        val innerOsz = zip(mapOf("map.osu" to osu.toByteArray(), "song.ogg" to ByteArray(64)))
        val bytes = zip(mapOf("inner.osz" to innerOsz))
        val zipFile = tmp.resolve("bundle.zip")
        Files.write(zipFile, bytes)

        val result = SongImporter.import(zipFile, songsRoot)
        assertIs<SongImporter.Result.Ok>(result)
        assertEquals("OSZ 测试曲", result.title)
    }

    @Test
    fun `zip 缺音频时拒绝`() {
        val bytes = zip(mapOf("chart.tja" to tja.toByteArray()))
        val zipFile = tmp.resolve("bundle.zip")
        Files.write(zipFile, bytes)

        val result = SongImporter.import(zipFile, songsRoot)
        assertIs<SongImporter.Result.Fail>(result)
        assertTrue(result.reason.contains("没有音频"))
    }

    @Test
    fun `同名曲目自动加后缀不覆盖`() {
        val tjaFile = tmp.resolve("song.tja")
        Files.writeString(tjaFile, tja)
        val ogg = tmp.resolve("song.ogg")
        Files.write(ogg, ByteArray(64))

        val first = SongImporter.import(tjaFile, songsRoot, ogg)
        val second = SongImporter.import(tjaFile, songsRoot, ogg)
        assertIs<SongImporter.Result.Ok>(first)
        assertIs<SongImporter.Result.Ok>(second)
        assertTrue(first.songDir != second.songDir)
        assertEquals(2, songsRoot.listDirectoryEntries().size)
    }

    @Test
    fun `不支持的扩展名被拒绝`() {
        val file = tmp.resolve("song.txt")
        Files.writeString(file, "x")
        val result = SongImporter.import(file, songsRoot)
        assertIs<SongImporter.Result.Fail>(result)
    }

    @Test
    fun `曲名里的非法字符被替换`() {
        val weird = tja.replace("TITLE:测试曲", "TITLE:测/试\\曲:名")
        val tjaFile = tmp.resolve("weird.tja")
        Files.writeString(tjaFile, weird)
        val ogg = tmp.resolve("song.ogg")
        Files.write(ogg, ByteArray(64))

        val result = SongImporter.import(tjaFile, songsRoot, ogg)
        assertIs<SongImporter.Result.Ok>(result)
        assertTrue("/" !in result.songDir.name)
        assertTrue("\\" !in result.songDir.name)
    }
}
