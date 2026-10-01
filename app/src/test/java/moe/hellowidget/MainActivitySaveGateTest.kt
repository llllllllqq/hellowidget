package moe.hellowidget

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
 * 投产 QA：MainActivity「保存门禁」的确定性回归测试（Robolectric，全云端运行）。
 *
 * 被测缺陷（QA 审计 MAJOR/CRITICAL）：
 *   三条保存路径（shouldSaveOnLeave / onStop / 返回键）全部以 `loadCompleted` 作为门禁，
 *   而 `loadCompleted` 只有在 `ContentStore.read()` 这个异步读盘返回后才为 true。
 *   代码注释断言「loadCompleted==false 时磁盘上已是完整内容」——该断言在用户已经开始
 *   输入后不再成立：磁盘是旧内容，编辑器是新内容。
 *
 * 本测试用一个方法内的 A/B 两阶段证明「唯一的差别就是这道门禁」：
 *   阶段 A（loadCompleted=false）→ 离开时用户输入不会写盘（缺陷）
 *   阶段 B（loadCompleted=true） → 同样的输入被正确写盘（对照组）
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

    @Test
    fun saveGate_dependsOnLoadCompleted_notOnUserInput() {
        // ---------- 前置：磁盘上已有旧内容 ----------
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("OLD_CONTENT") })
        assertEquals("前置条件：旧内容可读", "OLD_CONTENT", runBlocking { ContentStore.read() })

        // ---------- 启动 Activity（onCreate 会发起异步加载） ----------
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor: EditText = activity.findViewById(R.id.editor)

        // 真实设备上「onCreate 已发出异步读盘、读盘尚未返回」的窗口客观存在，
        // 但用测试时钟无法稳定命中；若此处加载恰好已完成，则显式回到该状态，
        // 以确定性验证门禁行为（否则本测试会因竞态而时好时坏）。
        if (getBooleanField(activity, "loadCompleted")) {
            setBooleanField(activity, "loadCompleted", false)
        }

        // ================= 阶段 A：加载未完成时离开 =================
        editor.setText("NEW_TYPED_TEXT")
        assertEquals("用户输入已进入编辑器", "NEW_TYPED_TEXT", editor.text.toString())

        activity.onWindowFocusChanged(false)   // Home / 多任务键 / 下拉通知栏
        controller.pause().stop()              // 切后台

        assertEquals(
            "缺陷：加载未完成时用户输入被静默丢弃（应当保存 NEW_TYPED_TEXT，实际磁盘仍是旧内容）",
            "OLD_CONTENT",
            runBlocking { ContentStore.read() }
        )

        // ================= 阶段 B：同一输入，仅把门禁置为已加载 =================
        setBooleanField(activity, "loadCompleted", true)
        setBooleanField(activity, "savedOnLeave", false)

        activity.onWindowFocusChanged(false)

        assertEquals(
            "对照组：门禁满足时同一输入必须被写盘（证明阶段 A 的差异只来自这道门禁）",
            "NEW_TYPED_TEXT",
            runBlocking { ContentStore.read() }
        )
    }
}
