package top.drawtwice.taikocraft.client.input

import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Side

/** 一次按键击打（含 GLFW 回调级时间戳） */
data class TimedHit(val color: NoteColor, val side: Side, val nanoTime: Long)

/** 键盘输入缓冲：Mixin 写入，判定循环消费 */
object KeyInputBuffer {
    private val queue = ArrayDeque<TimedHit>(16)

    fun push(hit: TimedHit) {
        if (queue.size > 64) queue.removeFirst()
        queue.addLast(hit)
    }

    fun drain(): List<TimedHit> {
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<TimedHit>(queue.size)
        while (queue.isNotEmpty()) out += queue.removeFirst()
        return out
    }

    fun clear() = queue.clear()
}
