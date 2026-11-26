package top.drawtwice.taikocraft.chart

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsuParserTest {

    private fun osuText(
        version: String = "Oni",
        od: String = "5",
        audio: String = "song.ogg",
        mode: String = "1",
        beatLength: String = "500",
        hitObjects: List<String>,
    ): String = buildString {
        appendLine("osu file format v14")
        appendLine()
        appendLine("[General]")
        appendLine("AudioFilename: $audio")
        appendLine("Mode: $mode")
        appendLine()
        appendLine("[Metadata]")
        appendLine("Title:测试曲")
        appendLine("TitleUnicode:テスト曲")
        appendLine("Artist:测试歌手")
        appendLine("Version:$version")
        appendLine("Creator:TestMapper")
        appendLine()
        appendLine("[Difficulty]")
        appendLine("OverallDifficulty:$od")
        appendLine()
        appendLine("[TimingPoints]")
        appendLine("0,$beatLength,4,2,1,60,1,0")
        appendLine()
        appendLine("[HitObjects]")
        hitObjects.forEach { appendLine(it) }
    }

    @Test
    fun `圆圈 + Normal → 小咚`() {
        val bm = OsuParser.parseOsu(osuText(hitObjects = listOf("256,192,1000,1,1,0:0:0:0:")))
        assertEquals(1, bm.chart.notes.size)
        assertEquals(NoteColor.DON, bm.chart.notes[0].color)
        assertEquals(false, bm.chart.notes[0].big)
        assertEquals(1000.0, bm.chart.notes[0].timeMs)
    }

    @Test
    fun `Whistle 与 Clap → 小咔`() {
        val bm = OsuParser.parseOsu(
            osuText(
                hitObjects = listOf(
                    "256,192,1000,1,2,0:0:0:0:",  // whistle → ka
                    "256,192,2000,1,8,0:0:0:0:",  // clap → ka
                ),
            ),
        )
        assertEquals(NoteColor.KA, bm.chart.notes[0].color)
        assertEquals(NoteColor.KA, bm.chart.notes[1].color)
    }

    @Test
    fun `Finish → 大音符`() {
        val bm = OsuParser.parseOsu(
            osuText(
                hitObjects = listOf(
                    "256,192,1000,1,5,0:0:0:0:",  // normal + finish
                    "256,192,2000,1,6,0:0:0:0:",  // whistle + finish
                ),
            ),
        )
        assertEquals(true, bm.chart.notes[0].big)
        assertEquals(NoteColor.DON, bm.chart.notes[0].color)
        assertEquals(true, bm.chart.notes[1].big)
        assertEquals(NoteColor.KA, bm.chart.notes[1].color)
    }

    @Test
    fun `Slider → 连打`() {
        val bm = OsuParser.parseOsu(
            osuText(hitObjects = listOf("256,192,1000,2,0,L|400:192,1,100,0:0:0:0:")),
        )
        assertEquals(1, bm.chart.rolls.size)
        assertEquals(1000.0, bm.chart.rolls[0].startMs)
        assertTrue(bm.chart.rolls[0].endMs > 1000.0)
        assertTrue(bm.chart.notes.isEmpty())
    }

    @Test
    fun `Spinner 拨浪鼓 → 气球`() {
        // Spinner 行：x,y,time,type,hitSound,endTime,...
        val bm = OsuParser.parseOsu(
            osuText(hitObjects = listOf("256,192,1000,8,0,2000,0:0:0:0:")),
        )
        assertEquals(1, bm.chart.balloons.size)
        val balloon = bm.chart.balloons[0]
        assertEquals(1000.0, balloon.startMs)
        assertEquals(2000.0, balloon.endMs)
        assertTrue(balloon.requiredHits > 0)
        assertTrue(bm.chart.notes.isEmpty(), "气球不应产生普通音符")
        assertTrue(bm.chart.rolls.isEmpty(), "气球不应产生连打")
    }

    @Test
    fun `Spinner 需要击打数随 OD 升高而增加`() {
        val low = OsuParser.parseOsu(
            osuText(od = "2", hitObjects = listOf("256,192,1000,8,0,3000,0:0:0:0:")),
        )
        val high = OsuParser.parseOsu(
            osuText(od = "9", hitObjects = listOf("256,192,1000,8,0,3000,0:0:0:0:")),
        )
        assertTrue(high.chart.balloons[0].requiredHits > low.chart.balloons[0].requiredHits)
    }

    @Test
    fun `BPM 由 TimingPoint 推算 - 500ms beat = 120bpm`() {
        val bm = OsuParser.parseOsu(
            osuText(beatLength = "500", hitObjects = listOf("256,192,1000,1,0,0:0:0:0:")),
        )
        assertEquals(120.0, bm.chart.bpm, 0.001)
    }

    @Test
    fun `元数据与难度名`() {
        val bm = OsuParser.parseOsu(osuText(version = "Inner Oni", hitObjects = emptyList()))
        assertEquals("テスト曲", bm.title)
        assertEquals("测试歌手", bm.artist)
        assertEquals("Inner Oni", bm.version)
        assertEquals("TestMapper", bm.creator)
        assertEquals(3, bm.chart.course)
        assertEquals(1, bm.mode)
    }

    @Test
    fun `osz 解析 - 安全路径与白名单`() {
        val osu = osuText(hitObjects = listOf("256,192,1000,1,1,0:0:0:0:"))
        val osz = buildZip(
            mapOf(
                "map/song.osu" to osu.toByteArray(),
                "map/song.ogg" to ByteArray(64),
                "../evil.sh" to ByteArray(10),        // 路径穿越，应被拒绝
                "map/evil.exe" to ByteArray(10),      // 非白名单，应被拒绝
            ),
        )
        val contents = OsuParser.parseOsz(osz)
        assertEquals(1, contents.beatmaps.size)
        assertEquals("map/song.ogg", contents.beatmaps[0].audioFileName)
        assertTrue(contents.files.keys.none { it.contains("..") })
        assertTrue(contents.files.keys.none { it.endsWith(".exe") })
        assertTrue(contents.files.containsKey("map/song.ogg"))
    }

    @Test
    fun `osz 里 osu 未写 AudioFilename 时回退到包内音频`() {
        val osu = osuText(audio = "", hitObjects = emptyList())
        val osz = buildZip(
            mapOf(
                "chart.osu" to osu.toByteArray(),
                "audio.ogg" to ByteArray(32),
            ),
        )
        val contents = OsuParser.parseOsz(osz)
        assertEquals(1, contents.beatmaps.size)
        assertEquals("audio.ogg", contents.beatmaps[0].audioFileName)
    }

    @Test
    fun `osz 可含多难度`() {
        val easy = osuText(version = "Easy", hitObjects = listOf("256,192,500,1,1,0:0:0:0:"))
        val oni = osuText(version = "Oni", hitObjects = listOf("256,192,500,1,1,0:0:0:0:", "256,192,600,1,1,0:0:0:0:"))
        val osz = buildZip(
            mapOf(
                "a.osu" to easy.toByteArray(),
                "b.osu" to oni.toByteArray(),
                "song.ogg" to ByteArray(32),
            ),
        )
        val contents = OsuParser.parseOsz(osz)
        assertEquals(2, contents.beatmaps.size)
        assertEquals(0, contents.beatmaps[0].chart.course)
        assertEquals(3, contents.beatmaps[1].chart.course)
    }

    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
