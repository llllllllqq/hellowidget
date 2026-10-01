package moe.hellowidget

import android.text.SpannableStringBuilder
import android.widget.EditText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 投产 QA：MainActivity 保存语义的确定性回归测试（Robolectric，全云端运行）。
 *
 * 这里固化两条**修复后必须成立**的契约：
 *  A. 加载尚未完成、但用户已经输入时离开 → **输入必须落盘**（旧版本会静默丢弃）
 *  B. 加载尚未完成且用户没输入时离开 → **不得用空编辑器覆盖磁盘上的旧内容**
 *  C. 编辑器必须安装长度上限过滤器（防止超大文本导致 OOM）
 *
 * 说明：真实设备上「onCreate 已发出异步读盘、读盘尚未返回」的窗口客观存在，
 * 但用测试时钟无法稳定命中，因此这里显式构造该状态，以确定性地验证门禁逻辑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivitySaveGateTest {

    private fun getBooleanField(target: Any, name: String): Boolean =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(target)

    private fun setBooleanField(target: Any, name: String, value: Boolean) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setBoolean(target, value)
    }

    /** 保存是异步的，轮询等待落盘结果 */
    private fun awaitContent(expected: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var actual = ""
        while (System.currentTimeMillis() < deadline) {
            actual = runBlocking { ContentStore.read() }
            if (actual == expected) return
            Thread.sleep(20)
        }
        assertEquals("等待写盘超时", expected, actual)
    }

    @Test
    fun typedTextIsPersisted_evenIfLoadHasNotCompleted() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("OLD_CONTENT") })
        assertEquals("OLD_CONTENT", runBlocking { ContentStore.read() })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor: EditText = activity.findViewById(R.id.editor)

        // 构造「异步加载尚未完成」的状态
        setBooleanField(activity, "loadCompleted", false)

        // 用户此刻输入了新内容
        editor.setText("NEW_TYPED_TEXT")
        assertEquals("NEW_TYPED_TEXT", editor.text.toString())

        // 用户按 Home / 切后台
        activity.onWindowFocusChanged(false)
        controller.pause().stop()

        // 修复后：即使用户输入时加载还没完成，也必须保存
        awaitContent("NEW_TYPED_TEXT")
    }

    @Test
    fun leavingBeforeLoad_doesNotWipeStoredContent() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("OLD_CONTENT") })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        // 加载未完成，且用户没有任何输入（编辑器此时是空的）
        setBooleanField(activity, "loadCompleted", false)
        setBooleanField(activity, "editorTouched", false)

        activity.onWindowFocusChanged(false)
        controller.pause().stop()

        // 必须保留磁盘上的旧内容，不能被空编辑器覆盖
        assertEquals("OLD_CONTENT", runBlocking { ContentStore.read() })
    }

    @Test
    fun editorHasMaxLengthFilter_thatRejectsOverLimitInput() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor: EditText = activity.findViewById(R.id.editor)

        val filters = editor.filters
        assertTrue("编辑器必须安装长度上限过滤器", filters.isNotEmpty())

        // 目标文本已达上限，再输入必须被完全拒绝
        val dest = SpannableStringBuilder("a".repeat(MainActivity.MAX_CONTENT_CHARS))
        var incoming: CharSequence = "bcdef"
        for (filter in filters) {
            val result = filter.filter(incoming, 0, incoming.length, dest, dest.length, dest.length)
            if (result != null) incoming = result
            if (incoming.isEmpty()) break
        }
        assertTrue("超出长度上限的输入必须被拒绝", incoming.isEmpty())
    }
}
