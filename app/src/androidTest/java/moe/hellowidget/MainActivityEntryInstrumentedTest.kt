package moe.hellowidget

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v7.1 新功能的**真机级**验证（CI 里跑在 Android 模拟器上）。
 *
 * 为什么需要这一层：JVM/Robolectric 里没有真实输入法，「输入法到底有没有弹出来」
 * 只能在真实 Android 运行环境里观察到。这里验证四件事：
 *  1. 进入应用后光标落在第一行行首、且编辑器持有焦点；
 *  2. **输入法真的变得可见**（WindowInsetsCompat.Type.ime()）；
 *  3. 输入法弹出后 Activity 仍持有窗口焦点
 *     —— 若输入法抢走窗口焦点，onWindowFocusChanged(false) 会误触发「已保存」Toast
 *     （AOSP 中 IME 窗口带 FLAG_NOT_FOCUSABLE，本用例把它固化成回归契约）；
 *  4. 底部按钮没有被键盘挡住（targetSdk 35 边到边下必须自行消费 ime insets）。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEntryInstrumentedTest {

    private val seed = "第一行内容\n第二行内容"

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

            assertTrue("进入应用后必须自动弹出输入法", awaitImeVisible(scenario))

            scenario.onActivity { activity ->
                assertTrue(
                    "输入法弹出后 Activity 必须仍持有窗口焦点（否则会误触发失焦保存）",
                    activity.hasWindowFocus()
                )
                val editor = activity.findViewById<EditText>(R.id.editor)
                assertTrue("输入法弹出后编辑器必须仍持有焦点", editor.hasFocus())
            }

            assertBottomButtonNotCoveredByIme(scenario)
        }
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
