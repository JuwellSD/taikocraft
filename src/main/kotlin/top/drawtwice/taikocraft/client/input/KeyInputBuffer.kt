package top.drawtwice.taikocraft.client.input

import top.drawtwice.taikocraft.chart.NoteColor
import top.drawtwice.taikocraft.judge.Side

/** 一次按键击打（附 GLFW 回调级时间戳） */
data class TimedHit(
    val color: NoteColor,
    val side: Side,
    /** `System.nanoTime()` 时间戳 */
    val nanoTime: Long,
)

/**
 * 键盘输入缓冲：Mixin 在 GLFW 回调里写入，判定循环里消费。
 *
 * 线程安全：写入发生在渲染线程（GLFW 回调由 `glfwPollEvents` 派发），
 * 消费也在渲染线程，因此用 ArrayDeque 即可，无需加锁。
 */
object KeyInputBuffer {

    private val queue = ArrayDeque<TimedHit>(16)

    /** 单侧 40ms 冷却由判定引擎负责；这里只做时间戳入队 */
    fun push(hit: TimedHit) {
        if (queue.size > 64) queue.removeFirst() // 防止卡顿时无限堆积
        queue.addLast(hit)
    }

    /** 取出所有待处理事件 */
    fun drain(): List<TimedHit> {
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<TimedHit>(queue.size)
        while (queue.isNotEmpty()) out += queue.removeFirst()
        return out
    }

    fun clear() = queue.clear()
}
