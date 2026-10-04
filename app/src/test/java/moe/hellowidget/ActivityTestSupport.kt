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
 * 等待「待上传」橙点的状态稳定到 [expected]。
 *
 * v7.8 的检测是异步的（IO 线程算 SHA-256 → 回主线程改图标），PAUSED 模式下必须
 * idle 主线程消息队列才会推进，因此和 [awaitEditorEnabled] 同一套「idle + sleep」写法。
 */
internal fun awaitUploadPending(activity: MainActivity, expected: Boolean, timeoutMs: Long = 5_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        if (activity.uploadPending == expected) return
        Thread.sleep(10)
    }
    fail("等待「待上传」状态变为 $expected 超时（当前 ${activity.uploadPending}）")
}
