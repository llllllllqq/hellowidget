package moe.hellowidget

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.hellowidget.sync.SyncSettings
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 同步设置页的**键盘遮挡**回归测试（真机级，跑在 CI 的模拟器上）。
 *
 * 背景：targetSdk 35 起应用强制边到边，窗口不再为输入法让位（这一条在 v7.1 的编辑页上
 * 已经实测确认）。同步设置页有 5 个输入框，如果只把系统栏 insets 消费掉、不处理 `ime()`，
 * 键盘就会盖住下半部分表单和「保存设置 / 立即同步」按钮 —— 而官方文档恰好把
 * **设置页**列为「即使自认为已适配也要单独检查的低流量页面」。
 *
 * 用例先把表单滚到底（表单比屏幕高，不滚到底断言就没有意义），再断言三件事：
 *  1. 输入法可见时，滚动容器的高度真的变矮了（`ScrollView.height <= 窗口高 - imeBottom`）
 *     —— 只有外面套一层 FrameLayout、把 ime 高度加在那一层上才成立；
 *  2. 滚到底时底部按钮停在键盘顶部之上；
 *  3. 聚焦的输入框也在键盘顶部之上。
 */
@RunWith(AndroidJUnit4::class)
class SyncActivityImeInstrumentedTest {

    /**
     * 清掉可能残留的待处理冲突与待确认指纹：它们会让同步页自动弹出对话框，
     * 抢走输入焦点、让本用例失去意义（仪器化测试共用同一份 prefs）。
     */
    @Before
    fun clearPendingState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SyncSettings.clearConflict(context)
        SyncSettings.setPendingTlsPin(context, null)
    }

    @Test
    fun focusingFormField_keepsFormAboveIme() {
        ActivityScenario.launch(SyncActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // 表单比屏幕高，先滚到底：这样断言的是「最底部的控件」是否被键盘盖住，
                // 而不是「碰巧没滚到底所以看得见」
                val scroll = activity.findViewById<ScrollView>(R.id.sync_scroll)
                scroll.fullScroll(View.FOCUS_DOWN)
                activity.findViewById<EditText>(R.id.sync_password).requestFocus()
                val root = activity.findViewById<View>(android.R.id.content)
                ViewCompat.getWindowInsetsController(root)?.show(WindowInsetsCompat.Type.ime())
            }

            assertTrue(
                "输入法必须真的弹出（JVM/Robolectric 里没有输入法，只能在这一层验证）",
                awaitImeVisible(scenario)
            )

            // 键盘动画 + 因 insets 变化触发的重新布局/滚动都要落定
            SystemClock.sleep(600)
            scenario.onActivity { activity ->
                activity.findViewById<ScrollView>(R.id.sync_scroll).fullScroll(View.FOCUS_DOWN)
            }
            SystemClock.sleep(300)

            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(android.R.id.content)
                val scroll = activity.findViewById<ScrollView>(R.id.sync_scroll)
                val field = activity.findViewById<View>(R.id.sync_password)
                val button = activity.findViewById<View>(R.id.sync_now)

                val insets = ViewCompat.getRootWindowInsets(root)
                assertNotNull("必须能读到窗口 insets", insets)
                val imeBottom = insets!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                assertTrue("输入法可见时其 inset 高度必须大于 0", imeBottom > 0)

                // 1) 机制：滚动容器的高度必须真的让出键盘那块空间。
                //    把 ime 高度加在 ScrollView 自己的 padding 上不会让视口变矮 —— 这条断言正是防那个坑。
                assertTrue(
                    "滚动容器必须被键盘顶矮：ScrollView 高=${scroll.height}，" +
                        "窗口高=${root.height}，imeBottom=$imeBottom",
                    scroll.height <= root.height - imeBottom + 16
                )

                val imeTop = activity.resources.displayMetrics.heightPixels - imeBottom

                // 2) 结果：滚到底时，底部按钮必须停在键盘之上
                val buttonLocation = IntArray(2)
                button.getLocationOnScreen(buttonLocation)
                val buttonBottom = buttonLocation[1] + button.height
                assertTrue(
                    "底部按钮不能被输入法遮挡：按钮底部=$buttonBottom，键盘顶部=$imeTop",
                    buttonBottom <= imeTop + 16
                )

                // 3) 结果：聚焦的输入框也不能被遮挡
                val fieldLocation = IntArray(2)
                field.getLocationOnScreen(fieldLocation)
                val fieldBottom = fieldLocation[1] + field.height
                assertTrue(
                    "聚焦的输入框不能被输入法遮挡：输入框底部=$fieldBottom，键盘顶部=$imeTop",
                    fieldBottom <= imeTop + 16
                )
            }
        }
    }

    private fun awaitImeVisible(scenario: ActivityScenario<SyncActivity>, timeoutMs: Long = 8_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var visible = false
        while (SystemClock.uptimeMillis() < deadline && !visible) {
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.findViewById<View>(android.R.id.content))
                visible = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
            }
            if (!visible) SystemClock.sleep(100)
        }
        return visible
    }
}
