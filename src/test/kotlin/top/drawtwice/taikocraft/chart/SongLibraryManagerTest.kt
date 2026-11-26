package top.drawtwice.taikocraft.chart

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongLibraryManagerTest {

    private lateinit var tmp: Path
    private lateinit var songsRoot: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("taikocraft-lib-test")
        songsRoot = tmp.resolve("songs")
        Files.createDirectories(songsRoot)
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun makeSong(name: String): Path {
        val dir = songsRoot.resolve(name)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("chart.tja"), "TITLE:$name\nBPM:120\nOFFSET:0\nCOURSE:Oni\nLEVEL:8\n#START\n1,1\n#END\n")
        Files.write(dir.resolve("song.ogg"), ByteArray(16))
        return dir
    }

    @Test
    fun `删除整首曲目移入回收站`() {
        val dir = makeSong("测试曲")
        val moved = SongLibraryManager.deleteSong(songsRoot, dir)
        assertFalse(dir.exists(), "原目录应被移走")
        assertTrue(moved.exists(), "回收站中应有副本")
        assertTrue(moved.startsWith(SongLibraryManager.trashDir(songsRoot)))
        assertEquals(0, songsRoot.listDirectoryEntries().count { Files.isDirectory(it) && it.name != ".trash" })
    }

    @Test
    fun `拒绝删除曲库外的目录`() {
        val outside = tmp.resolve("outside")
        Files.createDirectories(outside)
        assertFailsWith<IllegalArgumentException> {
            SongLibraryManager.deleteSong(songsRoot, outside)
        }
    }

    @Test
    fun `回收站可恢复`() {
        val dir = makeSong("可恢复")
        val moved = SongLibraryManager.deleteSong(songsRoot, dir)
        val restored = SongLibraryManager.restore(songsRoot, moved)
        assertTrue(restored.exists())
        assertTrue(restored.resolve("chart.tja").exists())
    }

    @Test
    fun `列出回收站内容`() {
        makeSong("A")
        makeSong("B")
        songsRoot.listDirectoryEntries().forEach { SongLibraryManager.deleteSong(songsRoot, it) }
        assertEquals(2, SongLibraryManager.listTrash(songsRoot).size)
    }

    @Test
    fun `从多难度 TJA 删除指定 COURSE`() {
        val dir = songsRoot.resolve("多难度")
        Files.createDirectories(dir)
        val tja = """
            TITLE:多难度
            BPM:120
            OFFSET:0

            COURSE:Easy
            LEVEL:3
            #START
            1,
            #END

            COURSE:Hard
            LEVEL:8
            #START
            1,1,
            #END

            COURSE:Oni
            LEVEL:10
            #START
            1,1,1,
            #END
        """.trimIndent()
        val f = dir.resolve("chart.tja")
        Files.writeString(f, tja)

        val rest = SongLibraryManager.removeTjaCourse(f, "Hard")
        assertEquals(listOf("Easy", "Oni"), rest)

        val after = f.readText()
        assertFalse(after.contains("COURSE:Hard"))
        assertTrue(after.contains("COURSE:Easy"))
        assertTrue(after.contains("COURSE:Oni"))
    }

    @Test
    fun `删除不存在的难度报错`() {
        val dir = makeSong("x")
        val f = dir.resolve("chart.tja")
        assertFailsWith<IllegalArgumentException> {
            SongLibraryManager.removeTjaCourse(f, "不存在的难度")
        }
    }

    @Test
    fun `删最后一个难度后曲目目录也清掉`() {
        val dir = songsRoot.resolve("最后难度")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("a.osu"), "osu file format v14\n")
        val osu = dir.resolve("a.osu")
        SongLibraryManager.deleteOsuFile(osu)
        assertFalse(dir.exists(), "没有谱面后目录应被删除")
    }

    @Test
    fun `写读显示标题`() {
        val dir = makeSong("原名")
        SongLibraryManager.setDisplayTitle(dir, "自定义标题")
        assertEquals("自定义标题", SongLibraryManager.readDisplayTitle(dir))
    }

    @Test
    fun `显示标题含引号也能正确往返`() {
        val dir = makeSong("x")
        SongLibraryManager.setDisplayTitle(dir, "带\"引号\"的标题")
        assertEquals("带\"引号\"的标题", SongLibraryManager.readDisplayTitle(dir))
    }
}
