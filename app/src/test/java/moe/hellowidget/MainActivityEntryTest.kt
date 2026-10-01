package moe.hellowidget

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * v7.1 新功能回归测试：进入应用后 **光标落在第一行行首** 且 **自动请求弹出输入法**。
 *
 * 分层验证说明（这里能测什么、不能测什么）：
 *  - 能测：焦点归属、选区位置、内容恢复、输入法请求的**时机与次数**（确定性，无需设备）
 *  - 不能测：输入法是否真的在屏幕上展开（JVM 里没有输入法），
 *    这一条由 `app/src/androidTest` 的模拟器仪器化测试负责
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityEntryTest {

    private fun editorOf(activity: MainActivity): EditText = activity.findViewById(R.id.editor)

    /**
     * 等待异步读盘完成（编辑器解禁）。
     *
     * 主线程处于 PAUSED 模式：协程 `withContext(Dispatchers.IO)` 的续体必须由测试显式 idle
     * 主线程消息队列才会执行，所以这里「idle + 等待 IO 线程」交替进行。
     */
    private fun awaitEditorEnabled(activity: MainActivity, timeoutMs: Long = 10_000) {
        val editor = editorOf(activity)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (editor.isEnabled) return
            Thread.sleep(10)
        }
        fail("等待异步加载完成超时（编辑器始终处于禁用状态）")
    }

    /** 需求 1：进入应用后光标自动聚焦在第一行行首（已有内容时同样从行首开始） */
    @Test
    fun enteringApp_focusesEditorAtStartOfFirstLine() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("第一行内容\n第二行内容") })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor = editorOf(activity)
        awaitEditorEnabled(activity)

        assertEquals("必须先恢复磁盘上的内容", "第一行内容\n第二行内容", editor.text.toString())
        assertTrue("编辑器必须获得焦点（否则输入法不会弹出）", editor.hasFocus())
        assertEquals("光标必须落在第一行行首", 0, editor.selectionStart)
        assertEquals("不能存在选区（光标是折叠的）", 0, editor.selectionEnd)
    }

    /** 需求 2：请求弹出输入法——必须在读盘完成（编辑器解禁）之后，且只请求一次 */
    @Test
    fun imeIsRequestedOnce_andOnlyAfterContentLoaded() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor = editorOf(activity)

        // 若此刻读盘尚未完成（编辑器仍禁用），则绝不允许已经发出输入法请求：
        // 禁用状态下请求会被系统忽略，而且会暴露「先弹键盘后填内容」的时序错误
        if (!editor.isEnabled) {
            assertEquals("编辑器解禁前不得请求弹出输入法", 0, activity.imeRequestCount)
        }

        awaitEditorEnabled(activity)
        assertTrue("解禁与请求输入法必须发生在同一次主线程执行块内", editor.isEnabled)
        assertEquals("加载完成后必须请求弹出输入法（且只请求一次）", 1, activity.imeRequestCount)

        // 再空转几轮主线程任务：不得重复请求（重复请求会让输入法反复抢焦点）
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("输入法请求不得重复", 1, activity.imeRequestCount)
    }

    /** 需求 3：系统重建（旋转 / 深色模式 recreate / 进程恢复）不覆盖用户光标，但仍要自动弹输入法 */
    @Test
    fun recreation_keepsRestoredCursorPosition_andStillRequestsIme() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("0123456789") })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitEditorEnabled(controller.get())
        editorOf(controller.get()).setSelection(5)

        controller.recreate()

        val activity = controller.get()
        val editor = editorOf(activity)
        awaitEditorEnabled(activity)

        assertEquals("系统重建必须保留已恢复的内容", "0123456789", editor.text.toString())
        assertEquals("系统重建必须保留用户原有光标位置（不能跳回行首）", 5, editor.selectionStart)
        assertEquals("系统重建后同样要自动弹出输入法", 1, activity.imeRequestCount)
    }

    /**
     * 需求 4（兜底）：输入法可见时如果焦点已从编辑器脱落，必须自动拉回编辑器。
     *
     * 这条不是假想：CI 的 API 34 模拟器上实测到「输入法首帧可见时 Activity 仍有窗口焦点、
     * 但没有任何 View 持有焦点」的时序，此时光标不闪烁、按键无处可去。
     */
    @Test
    fun imeVisible_withoutViewFocus_pullsFocusBackToEditor() {
        // 空内容：让「重新获得焦点会不会把光标移到文末」不干扰行首断言
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("") })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor = editorOf(activity)
        awaitEditorEnabled(activity)

        // 构造「键盘可见，但焦点已脱落」的状态
        editor.clearFocus()
        assertFalse("前置条件：焦点已从编辑器脱落", editor.hasFocus())

        val imeInsets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 800))
            .build()
        ViewCompat.dispatchApplyWindowInsets(rootOf(activity), imeInsets)

        assertTrue("输入法可见时编辑器必须重新获得焦点", editor.hasFocus())
        assertEquals("重新获得焦点后光标仍应在第一行行首", 0, editor.selectionStart)
    }

    /** setContentView 传进去的那个根布局（insets 监听器装在它身上） */
    private fun rootOf(activity: MainActivity): View =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
}
