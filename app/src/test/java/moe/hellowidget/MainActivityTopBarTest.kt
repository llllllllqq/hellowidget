package moe.hellowidget

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncRetry
import moe.hellowidget.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * v7.7.1 追加回归：顶部系统栏再高也不得挤压导航栏内容区（线上事故的根因用例）。
 *
 * 说明：菜单项 id 与顺序由 `main_menu.xml` 定义、由 `onCreateOptionsMenu` 装载；
 * JVM 侧用 `RoboMenuItem` 直接驱动 `onOptionsItemSelected`（验证 4 个 id 的路由），
 * 「工具栏在真机上真的装载了 4 个入口、且标题与图标真的被画出来」由
 * `MainActivityEntryInstrumentedTest` 验证。
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

        // 根布局只剩「系统栏占位条 + 顶部导航栏 + 编辑区」：
        // 原先屏幕底部的两个按钮已经删除，编辑区下面不再有任何东西
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        assertEquals("根布局应当只剩三个子视图（占位条 + 导航栏 + 编辑区）", 3, root.childCount)
        assertSame(
            "第一个子视图必须是系统栏占位条",
            activity.findViewById<View>(R.id.status_bar_spacer),
            root.getChildAt(0)
        )
        assertSame("第二个子视图必须是顶部导航栏", toolbar, root.getChildAt(1))
        assertSame(
            "最后一个子视图必须是编辑区（其下不得再有任何按钮）",
            activity.findViewById<View>(R.id.editor_scroll),
            root.getChildAt(2)
        )
    }

    /**
     * v7.7.1 回归：**顶部系统栏再高，也不能挤压导航栏的内容区**。
     *
     * v7.7 的线上事故：把系统栏 inset 当成 Toolbar 的 paddingTop，而 Toolbar 高度固定 56dp。
     * 用户手机上 `systemBars() ∪ displayCutout()` 的 top ≈ 53dp，内容只剩约 3dp ——
     * 标题被 AppCompat 贴底裁成一条缝（截图实测可见高度仅 6~7px）、4 个按钮完全看不见。
     *
     * 本用例把那个真实高度灌进窗口 insets，断言：
     *  1. 这条高度由独立占位视图承担；
     *  2. 导航栏自己的 padding 不受影响；
     *  3. 真正测量一遍后，导航栏内容可用高度仍是一整条导航栏。
     */
    @Test
    fun aTallTopInset_neverSqueezesTheTopBarContent() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        val strip = activity.findViewById<View>(R.id.status_bar_spacer)
        val barHeight = activity.resources.getDimensionPixelSize(R.dimen.top_bar_height)

        // 由用户截图反推出的系统栏高度：几乎等于整条导航栏（53 / 56）
        val hugeInset = barHeight * 53 / 56
        ViewCompat.dispatchApplyWindowInsets(
            root,
            WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, hugeInset, 0, 0))
                .build()
        )

        assertEquals("顶部系统栏高度必须由占位视图承担", hugeInset, strip.layoutParams.height)
        assertEquals("导航栏自己不得再被系统栏 inset 挤压", 0, toolbar.paddingTop)
        assertEquals("导航栏高度必须是固定值", barHeight, toolbar.layoutParams.height)

        toolbar.measure(
            View.MeasureSpec.makeMeasureSpec(
                activity.resources.displayMetrics.widthPixels,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(barHeight, View.MeasureSpec.EXACTLY)
        )
        assertEquals("导航栏必须量到完整高度", barHeight, toolbar.measuredHeight)
        val contentHeight = toolbar.measuredHeight - toolbar.paddingTop - toolbar.paddingBottom
        assertTrue(
            "导航栏内容可用高度必须仍是完整一条（旧实现这里只剩约 3dp）：${contentHeight}px",
            contentHeight >= barHeight - 2
        )
    }

    /** 需求 1 续：两个设置入口依然打开原来的两个设置页 */
    @Test
    fun appearanceAndWebdavEntries_stillOpenTheirScreens() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_appearance)))
        assertEquals(
            "「设置」必须打开设置页（外观 + 备份）",
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

    /**
     * 需求 2：点「立即上传」时，编辑器里的**当前**内容必须先落盘 ——
     * 同步读的是磁盘内容，不先落盘就会把旧内容推上云端（而且看起来「同步成功」）。
     *
     * 说明：「真的启动了前台服务、真的以 MANUAL 绕过节流把文件写进云端」由
     * `SyncE2eInstrumentedTest.topBarUploadButton_uploadsTheCurrentEditorContent_ignoringThrottle`
     * 在模拟器上打真实 WebDAV 服务器验证（Robolectric 不记录 `startForegroundService`，
     * 这里不做无法观测的断言）。
     */
    @Test
    fun uploadEntry_savesTheEditorContentFirst_soTheUploadCanNeverBeStale() {
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

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_upload)))

        awaitContent("云端要看到的最新内容")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(
            "配置齐全时不该走「请先去配置同步」的提示",
            ShadowToast.getTextOfLatestToast()
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
        assertEquals(
            "未配置时不得发起任何同步尝试（一次请求都没发）",
            0L,
            SyncSettings.lastServerContactAt(app)
        )
    }

    // ------------------------------------------------------------ v7.8 语义：打开应用只读盘、不上传

    private val uploadedAt = 1_735_689_600L

    private fun configureSync() {
        SyncSettings.saveConfig(
            app,
            SyncConfig("https://dav.example.com/dav/", "note.txt", "user", "pass")
        )
        SyncSettings.setEnabled(app, true)
    }

    private fun hashOf(text: String): String = SyncEngine.sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * v7.8 语义中真正重要的那一条：**打开应用只读盘，绝不上传**。
     *
     * v8.2.0 删掉了「待上传」橙点（连同它那条"进应用顺手检测一次哈希"的支路），
     * 因此这里不再断言角标，只留下不会因为界面变化而失效的那部分：
     * 构造「磁盘内容 ≠ 上次成功上传的内容」，进应用后
     *  - 磁盘内容必须真的显示出来；
     *  - 不得发起任何同步尝试（`lastServerContactAt` 必须仍是 0 —— 它只在**真的发过请求**时才写，
     *    旧字段 `lastAttemptAt` 是 1 分钟闸门的锚点，已随闸门删除）；
     *  - 不得启动任何同步服务。
     */
    @Test
    fun openingTheApp_onlyReadsTheDiskContent_andNeverUploads() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("磁盘上的最新内容") })
        configureSync()
        // 「上次成功上传的是别的内容」→ 磁盘上确实存在"没传上去"的改动
        SyncSettings.recordSuccess(app, hashOf("上一次传上去的内容"), uploadedAt)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)

        assertEquals("打开应用必须把磁盘内容显示到编辑器里", "磁盘上的最新内容", editorText(activity))
        assertEquals(
            "打开应用不得发起任何同步尝试（一次请求都没发）",
            0L,
            SyncSettings.lastServerContactAt(app)
        )
        assertNull("打开应用不得启动任何同步服务", shadowOf(app).nextStartedService)
    }

    /**
     * v7.8 需求 2：**任何保存操作都触发自动上传** —— 包括「应用内跳设置页」这种
     * 以前明确不上传的静默保存（旧实现传 `syncTrigger = null`）。
     * 密集触发由 SyncManager 的单飞合并吸收（v8.1.0 起没有任何节流，
     * 见 SyncManagerUploadTest 的"同一时刻最多一次上传"用例）。
     */
    @Test
    fun everySave_startsTheAutomaticUpload_includingSilentSaves() {
        configureSync()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)
        editorOf(activity).setText("跳设置页前的内容")

        // 打开设置页（openingSettings = true）→ 随后的 onStop 属于「静默保存」
        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_webdav)))
        controller.pause().stop()
        awaitContent("跳设置页前的内容")

        // 写盘在 IO 线程完成，触发同步的续体在主线程 —— 交替 idle 直到服务真的被拉起
        var started: android.content.Intent? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (started == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            started = shadowOf(app).nextStartedService
            if (started == null) Thread.sleep(20)
        }
        assertNotNull("保存（含应用内跳转的静默保存）必须触发自动上传", started)
        assertEquals(SyncService::class.java.name, started!!.component?.className)
    }

    /**
     * v7.9：保存时必须**同时**把「这次改动还没传上去」交给系统（一次性持久化重试任务）。
     *
     * 这是"上传失败不至于永久丢失"的唯一兜底：即使紧接着的触发被系统吞掉、
     * 进程被冻结或回收，系统仍然记着这件事，会在有网络时重新拉起进程补传。
     * 真机侧的端到端证据由 `SyncE2eInstrumentedTest` 覆盖，这里钉住「保存路径确实排了」。
     */
    @Test
    fun everySave_alsoHandsThePendingUploadToTheSystemRetry() {
        configureSync()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)
        assertFalse("前置条件：还没有排过重试任务", SyncRetry.isScheduled(app))
        editorOf(activity).setText("保存时应当把它交给系统重试")

        // 打开设置页（openingSettings = true）→ 随后的 onStop 属于「静默保存」
        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_webdav)))
        controller.pause().stop()
        awaitContent("保存时应当把它交给系统重试")

        // 写盘在 IO 线程、排任务在写盘之后：交替 idle 直到它真的排上
        val deadline = System.currentTimeMillis() + 5_000
        while (!SyncRetry.isScheduled(app) && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue(
            "保存必须把待上传内容交给系统重试任务，否则上传失败后又变回「只能清后台」",
            SyncRetry.isScheduled(app)
        )
    }
}
