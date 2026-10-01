package moe.hellowidget

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v7.1 新功能的**真机级**验证（CI 里跑在 Android 模拟器上）。
 *
 * 为什么需要这一层：JVM/Robolectric 里没有真实输入法，「输入法到底有没有弹出来」
 * 只能在真实 Android 运行环境里观察到。这里验证：
 *  1. 进入应用后光标落在第一行行首、编辑器持有焦点；
 *  2. **输入法真的变得可见**（WindowInsetsCompat.Type.ime()）；
 *  3. 输入法弹出后编辑器最终仍持有焦点，且**敲键真的能打进编辑器、插在行首**
 *     —— 这才是「打开即输入」真正可用的证据；
 *  4. 输入法弹出后 Activity 仍持有窗口焦点，且**没有触发失焦保存**
 *     （AOSP 中 IME 窗口带 FLAG_NOT_FOCUSABLE，不会夺走 Activity 的窗口焦点）；
 *  5. 底部按钮没有被键盘挡住（targetSdk 35 边到边下必须自行消费 ime insets）。
 *
 * 关于「最终」：实测输入法首帧可见时焦点可能短暂不在编辑器上，
 * 因此第 3 条允许过渡重试（并把焦点时间线写进失败信息与 logcat，便于定位）。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEntryInstrumentedTest {

    private val seed = "第一行内容\n第二行内容"
    private val tag = "HelloWidgetEntryTest"

    /** 与 ContentStore 内部文件名一致；用于检测「不该发生的写盘」 */
    private val contentFileName = "user_content.dat"

    @Before
    fun seedContent() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write(seed) })
    }

    @Test
    fun enteringApp_showsIme_withCursorAtStartOfFirstLine() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitEditorEnabled(scenario)

            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.editor)
                assertEquals("必须先恢复已保存的内容", seed, editor.text.toString())
                assertTrue("编辑器必须获得焦点", editor.hasFocus())
                assertEquals("光标必须落在第一行行首", 0, editor.selectionStart)
                assertEquals("不能存在选区", 0, editor.selectionEnd)
            }

            val contentModifiedBeforeIme = contentFileLastModified()
            assertTrue("进入应用后必须自动弹出输入法", awaitImeVisible(scenario))

            val timeline = awaitEditorFocused(scenario, timeoutMs = 5_000)
            Log.i(tag, "输入法弹出后的焦点时间线: ${timeline.description}")
            assertTrue(
                "输入法弹出后编辑器必须持有焦点。焦点时间线：${timeline.description}",
                timeline.editorFocused
            )

            assertEquals("输入法弹出后光标必须仍在第一行行首", 0, editorSelectionStart(scenario))

            // 功能性断言：键盘已弹出的情况下，输入必须真的进入编辑器，且插在光标处（行首）
            sendKey(KeyEvent.KEYCODE_X)
            val typed = awaitText(scenario) { it == "x$seed" }
            assertEquals("键盘弹出后输入必须真的落进编辑器（插在行首）", "x$seed", typed)

            scenario.onActivity { activity ->
                assertTrue("输入法弹出后 Activity 必须仍持有窗口焦点", activity.hasWindowFocus())
            }

            assertEquals(
                "弹出输入法不得触发失焦保存（内容文件不应被重写）",
                contentModifiedBeforeIme,
                contentFileLastModified()
            )

            assertBottomButtonNotCoveredByIme(scenario)
        }
    }

    // ---------- 断言辅助 ----------

    private class FocusTimeline(val editorFocused: Boolean, val description: String)

    /** 记录焦点归属时间线，直到编辑器拿到焦点或超时 */
    private fun awaitEditorFocused(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long
    ): FocusTimeline {
        val samples = mutableListOf<String>()
        var lastSample: String? = null
        var focused = false
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            var sample = ""
            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.editor)
                focused = editor.hasFocus()
                val current = activity.currentFocus
                sample = "编辑器有焦点=$focused" +
                    ", 当前焦点视图=${current?.let { "${it.javaClass.simpleName}#${it.id}" } ?: "无"}" +
                    ", Activity有窗口焦点=${activity.hasWindowFocus()}" +
                    ", 编辑器可见=${editor.isShown}" +
                    ", 触摸模式可聚焦=${editor.isFocusableInTouchMode}" +
                    ", 处于触摸模式=${editor.isInTouchMode}"
            }
            if (sample != lastSample) {
                samples += "[${SystemClock.uptimeMillis()}ms] $sample"
                lastSample = sample
            }
            if (focused || SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(100)
        }
        return FocusTimeline(focused, samples.joinToString(" → "))
    }

    private fun awaitEditorEnabled(scenario: ActivityScenario<MainActivity>, timeoutMs: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var enabled = false
        while (SystemClock.uptimeMillis() < deadline && !enabled) {
            scenario.onActivity { activity ->
                enabled = activity.findViewById<EditText>(R.id.editor).isEnabled
            }
            if (!enabled) SystemClock.sleep(50)
        }
        assertTrue("等待内容加载完成（编辑器解禁）超时", enabled)
    }

    private fun awaitImeVisible(scenario: ActivityScenario<MainActivity>, timeoutMs: Long = 8_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var visible = false
        while (SystemClock.uptimeMillis() < deadline && !visible) {
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.findViewById<View>(R.id.editor))
                visible = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
            }
            if (!visible) SystemClock.sleep(100)
        }
        return visible
    }

    private fun awaitText(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 3_000,
        predicate: (String) -> Boolean
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var text = editorText(scenario)
        while (!predicate(text) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            text = editorText(scenario)
        }
        return text
    }

    private fun sendKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun editorText(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { activity -> text = activity.findViewById<EditText>(R.id.editor).text.toString() }
        return text
    }

    private fun editorSelectionStart(scenario: ActivityScenario<MainActivity>): Int {
        var start = -1
        scenario.onActivity { activity -> start = activity.findViewById<EditText>(R.id.editor).selectionStart }
        return start
    }

    private fun contentFileLastModified(): Long = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        contentFileName
    ).lastModified()

    private fun assertBottomButtonNotCoveredByIme(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            val button = activity.findViewById<View>(R.id.btn_settings)
            val insets = ViewCompat.getRootWindowInsets(root)
            assertNotNull("必须能读到窗口 insets", insets)

            val imeBottom = insets!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            assertTrue("输入法可见时其 inset 高度必须大于 0", imeBottom > 0)

            val buttonLocation = IntArray(2)
            button.getLocationOnScreen(buttonLocation)
            val buttonBottom = buttonLocation[1] + button.height
            val imeTop = activity.resources.displayMetrics.heightPixels - imeBottom
            assertTrue(
                "底部按钮不能被输入法遮挡：按钮底部=$buttonBottom，键盘顶部=$imeTop",
                buttonBottom <= imeTop + 16 // 16px 容差，避免亚像素/取整差异
            )
        }
    }
}
