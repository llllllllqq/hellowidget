package moe.hellowidget

import android.os.Looper
import android.widget.EditText
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
 * 推进主线程消息队列，直到 [condition] 成立或超时。
 *
 * 用于"等待读盘/写盘协程在主线程上的收尾"这类场景（例如读失败后的 Toast、
 * 写失败后返回键流程的终态）。注意它只 idle 不 sleep 主线程以外的调度。
 */
internal fun awaitMainIdle(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    fail("等待主线程异步结果超时")
}

/** 只是把主线程队列推进若干次（用于"确认某个异步动作没有发生"） */
internal fun idleMain(times: Int = 20) {
    repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }
}

