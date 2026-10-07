package moe.hellowidget

import android.os.Looper
import android.widget.EditText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/**
 * 编辑页（MainActivity）Robolectric 测试的公共工具。
 *
 * 主线程处于 `LooperMode.PAUSED` 时，协程里 `withContext(Dispatchers.IO)` 的续体必须由测试
 * 显式 idle 主线程消息队列才会执行，因此「idle + 等 IO 线程」交替进行。
 */
internal fun editorOf(activity: MainActivity): EditText = activity.findViewById(R.id.editor)

internal fun editorText(activity: MainActivity): String = editorOf(activity).text.toString()

internal fun awaitEditorEnabled(activity: MainActivity, timeoutMs: Long = 10_000) {
    val editor = editorOf(activity)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (editor.isEnabled) return
        Thread.sleep(10)
    }
    fail("等待异步加载完成超时（编辑器始终处于禁用状态）")
}

/**
 * 等待磁盘上的内容变成 [expected]（写盘跑在 IO 线程，主线程 PAUSED 时必须交替推进）。
 *
 * 走 [ContentStore.read] 而不是直接读文件：与生产代码同一条路径，且不受 DataStore
 * 内部文件名/目录布局变化影响。
 */
internal fun awaitDiskContent(expected: String, timeoutMs: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var actual = ""
    while (System.currentTimeMillis() < deadline) {
        actual = runBlocking { ContentStore.read() }
        if (actual == expected) return
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(20)
    }
    fail("等待写盘超时：期望「$expected」，实际「$actual」")
}
