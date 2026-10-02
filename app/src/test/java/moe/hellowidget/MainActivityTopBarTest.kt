package moe.hellowidget

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowToast

/**
 * v7.7 顶部导航栏测试：入口位置（全部在顶部、底部不再留按钮）、四个入口的行为，
 * 以及「立即上传到云端」的语义（先落盘 + MANUAL 触发 + 未配置时明确提示）。
 *
 * 说明：菜单项 id 与顺序由 `main_menu.xml` 定义、由 `onCreateOptionsMenu` 装载；
 * JVM 侧用 `RoboMenuItem` 直接驱动 `onOptionsItemSelected`（验证 4 个 id 的路由），
 * 「工具栏在真机上真的装载了 4 个入口」由 `MainActivityEntryInstrumentedTest` 验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTopBarTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

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

    /**
     * 需求 1：两个设置入口都上移到顶部导航栏，底部不再有任何按钮。
     *
     * 这里只断言布局结构（**不**在 JVM 里断言菜单项：Robolectric 的动作菜单装载路径与真机不同，
     * 「工具栏真的挂上了 4 个入口」由 `MainActivityEntryInstrumentedTest` 在模拟器上
     * 直接读 `toolbar.menu` 验证）；四个入口的**行为**则由本文件另外三个用例覆盖。
     */
    @Test
    fun theTopBarHostsTheEntries_andNothingIsLeftAtTheBottom() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val toolbar: Toolbar? = activity.findViewById(R.id.toolbar)
        assertNotNull("顶部导航栏（Toolbar）必须存在", toolbar)

        // 根布局只剩「顶部导航栏 + 编辑区」：原先屏幕底部的两个按钮已经删除
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        assertEquals("根布局应当只剩两个子视图（导航栏 + 编辑区）", 2, root.childCount)
        assertSame("第一个子视图必须是顶部导航栏", toolbar, root.getChildAt(0))
        assertSame(
            "第二个子视图必须是编辑区",
            activity.findViewById<View>(R.id.editor_scroll),
            root.getChildAt(1)
        )
    }

    /** 需求 1 续：两个设置入口依然打开原来的两个设置页 */
    @Test
    fun appearanceAndWebdavEntries_stillOpenTheirScreens() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_appearance)))
        assertEquals(
            "「外观设置」必须打开小组件外观设置页",
            SettingsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className
        )

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_webdav)))
        assertEquals(
            "「WebDAV 同步」必须打开 WebDAV 设置页",
            SyncActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className
        )
    }

    /** 需求 2：立即上传 = 先把编辑器当前内容落盘，再以 MANUAL 触发同步 */
    @Test
    fun uploadEntry_savesTheEditorContentFirst_thenSyncsWithTheManualTrigger() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("旧内容") })
        SyncSettings.saveConfig(
            app,
            SyncConfig("https://dav.example.com/dav/", "note.txt", "user", "pass")
        )
        SyncSettings.setEnabled(app, true)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val editor: EditText = editorOf(activity)
        awaitEditorEnabled(activity)
        editor.setText("云端要看到的最新内容")

        // 打开应用时可能已经补过一次自动同步（APP_OPEN），先把那次从队列里取走
        shadowOf(app).nextStartedService

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_upload)))

        // 同步读的是磁盘内容：不先落盘就会把旧内容推上云端
        awaitContent("云端要看到的最新内容")

        val started = shadowOf(app).nextStartedService
        assertNotNull("「立即上传」必须启动同步服务", started)
        assertEquals(SyncService::class.java.name, started!!.component?.className)
        assertEquals(
            "「立即上传」必须走 MANUAL 触发（不受 30 分钟节流限制）",
            SyncTrigger.MANUAL.name,
            started.getStringExtra(SyncService.EXTRA_TRIGGER)
        )
    }

    /** 需求 2 续：没配置同步时不能「点了没反应」，必须明确告知 */
    @Test
    fun uploadEntry_withoutConfiguration_tellsTheUserInsteadOfFailingSilently() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_upload)))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "未启用/未配置同步时必须给出明确提示",
            activity.getString(R.string.sync_upload_not_ready),
            ShadowToast.getTextOfLatestToast()
        )
        assertNull("未配置时不得启动同步服务", shadowOf(app).nextStartedService)
    }
}
