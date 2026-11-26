package top.drawtwice.taikocraft.chart

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OszEditorTest {

    private val osuA = """
        osu file format v14

        [General]
        AudioFilename: song.ogg
        Mode: 1

        [Metadata]
        Title:原来的歌名
        TitleUnicode:原来的歌名
        Artist:原来的歌手
        ArtistUnicode:原来的歌手
        Creator:Mapper
        Version:Inner Oni
        Source:
        Tags:tag1 tag2

        [Difficulty]
        OverallDifficulty:7

        [HitObjects]
        256,192,1000,1,1,0:0:0:0:
    """.trimIndent()

    private val osuB = osuA.replace("Inner Oni", "Kantan").replace("1000,", "2000,")

    private fun buildOsz(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readZip(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                map[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return map
    }

    @Test
    fun `列出难度`() {
        val editor = OszEditor.load(buildOsz("a.osu" to osuA, "b.osu" to osuB, "song.ogg" to "audio"))
        assertEquals(2, editor.difficulties.size)
        assertEquals("Inner Oni", editor.difficulties[0].originalVersion)
        assertEquals("Kantan", editor.difficulties[1].originalVersion)
        assertEquals("song.ogg", editor.audioFileName)
    }

    @Test
    fun `改歌曲名 - 同时改 Title 与 TitleUnicode`() {
        val editor = OszEditor.load(buildOsz("a.osu" to osuA, "song.ogg" to "audio"))
        editor.difficulties[0].setTitle("新歌名")
        val exported = readZip(editor.export())
        val text = exported["a.osu"]!!
        assertTrue(text.contains("Title:新歌名"))
        assertTrue(text.contains("TitleUnicode:新歌名"))
        // 其他字段不受影响
        assertTrue(text.contains("Artist:原来的歌手"))
        assertTrue(text.contains("Tags:tag1 tag2"))
    }

    @Test
    fun `改歌手与难度名`() {
        val editor = OszEditor.load(buildOsz("a.osu" to osuA, "song.ogg" to "audio"))
        editor.difficulties[0].setArtist("新歌手").setVersion("改后的难度")
        val text = readZip(editor.export())["a.osu"]!!
        assertTrue(text.contains("Artist:新歌手"))
        assertTrue(text.contains("ArtistUnicode:新歌手"))
        assertTrue(text.contains("Version:改后的难度"))
    }

    @Test
    fun `删除难度 - 只删目标 osu 其它文件保留`() {
        val editor = OszEditor.load(buildOsz("a.osu" to osuA, "b.osu" to osuB, "song.ogg" to "audio"))
        editor.difficulties[1].delete()
        val exported = readZip(editor.export())
        assertTrue(exported.containsKey("a.osu"))
        assertTrue(!exported.containsKey("b.osu"))
        assertTrue(exported.containsKey("song.ogg"))
    }

    @Test
    fun `删除后恢复`() {
        val editor = OszEditor.load(buildOsz("a.osu" to osuA, "b.osu" to osuB, "song.ogg" to "audio"))
        editor.difficulties[1].delete().restore()
        val exported = readZip(editor.export())
        assertEquals(3, exported.size)
    }

    @Test
    fun `未修改的文件字节完全一致`() {
        val original = buildOsz("a.osu" to osuA, "song.ogg" to "binary-audio-bytes")
        val editor = OszEditor.load(original)
        val exported = readZip(editor.export())
        assertEquals("binary-audio-bytes", exported["song.ogg"])
        assertEquals(osuA, exported["a.osu"])
    }

    @Test
    fun `保留 CRLF 换行`() {
        val crlf = osuA.replace("\n", "\r\n")
        val editor = OszEditor.load(buildOsz("a.osu" to crlf, "song.ogg" to "x"))
        editor.difficulties[0].setVersion("CRLF 难度")
        val text = readZip(editor.export())["a.osu"]!!
        assertTrue(text.contains("\r\n"), "应保留 CRLF")
        assertTrue(text.contains("Version:CRLF 难度"))
    }

    @Test
    fun `字段不存在时插入到 Metadata 段末尾`() {
        val noSource = osuA.lines().filterNot { it.startsWith("Source:") }.joinToString("\n")
        val editor = OszEditor.load(buildOsz("a.osu" to noSource, "song.ogg" to "x"))
        // 直接测 applyEdits：新增一个不存在的键
        val result = OszEditor.applyEdits(noSource, mapOf("Source" to "新来源"))
        val lines = result.lines()
        val sourceIdx = lines.indexOfFirst { it.startsWith("Source:") }
        val difficultyIdx = lines.indexOfFirst { it == "[Difficulty]" }
        assertTrue(sourceIdx in 0 until difficultyIdx, "Source 应插入在 [Difficulty] 之前")
    }

    @Test
    fun `拒绝路径穿越的条目`() {
        val editor = OszEditor.load(buildOsz("../evil.sh" to "bad", "a.osu" to osuA, "song.ogg" to "x"))
        val exported = readZip(editor.export())
        assertTrue(exported.keys.none { it.contains("..") })
        assertTrue(exported.containsKey("a.osu"))
    }

    @Test
    fun `多难度同名时各改各的`() {
        val editor = OszEditor.load(buildOsz("a.osu" to osuA, "b.osu" to osuB, "song.ogg" to "x"))
        editor.difficulties[0].setVersion("甲")
        editor.difficulties[1].setVersion("乙")
        val exported = readZip(editor.export())
        assertTrue(exported["a.osu"]!!.contains("Version:甲"))
        assertTrue(exported["b.osu"]!!.contains("Version:乙"))
    }
}
