package moe.hellowidget

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncLauncher
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncRetry
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebDAV 同步的**真机级**端到端验证（CI 里跑在 Android 模拟器上，打 runner 上真实运行的
 * Python WebDAV 服务器：`.github/scripts/webdav_stub_server.py`，模拟器通过 10.0.2.2 访问）。
 *
 * 为什么必须有这一层：
 *  1. JVM 单测跑的是桌面 JVM 的 Socket 栈，只有真实 Android 才能证明这台设备上的
 *     网络栈、明文策略（network_security_config）、前台服务与通知权限下流程成立；
 *  2. 服务器端有请求日志，可以断言**客户端究竟发了什么** —— v7.6 的语义是
 *     「往指定目录里放一个带 unix 时间戳的新文件」，日志里应只有 `PUT`（必要时一个 `MKCOL`），
 *     不得出现任何读取动作、临时文件或换名；
 *  3. 通知栏这种只有真实 NotificationManager 才有的行为，也只有在设备上才算数。
 *
 * 每个用例用**各自的远端目录**（目录本身由被测代码 `MKCOL` 出来），
 * 因此用例之间互不干扰，而目录内的文件名就是产品格式 `note<unix 秒>.txt`。
 *
 * 未传 `webdavUrl` 时整体跳过（本地或其它 CI 运行不会因此变红）。
 */
@RunWith(AndroidJUnit4::class)
class SyncE2eInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    private val davUrl: String? get() = arguments.getString("webdavUrl")
    private val controlUrl: String? get() = arguments.getString("webdavControlUrl")

    @get:Rule
    val testName = TestName()

    private val dirName: String get() = "note-${testName.methodName}"

    private val dirPath: String get() = "/dav/$dirName"

    @Before
    fun setUp() {
        assumeTrue(
            "未提供 webdavUrl/webdavControlUrl，跳过 WebDAV 端到端测试",
            !davUrl.isNullOrBlank() && !controlUrl.isNullOrBlank()
        )
        ensureNotificationPermission()
        // 渠道必须显式创建：API 26+ 往不存在的渠道发通知会被系统静默丢弃
        // （生产代码里由 SyncService.onCreate / SyncNotifier.postProgress 负责创建）
        SyncNotifier.ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationQueryReliable = notificationQueryWorks(manager)

        SyncSettings.setEnabled(context, true)
        SyncSettings.saveConfig(
            context,
            SyncConfig(
                baseUrl = davUrl!! + dirName + "/",
                fileName = "note.txt",
                username = "test",
                password = "test"
            )
        )
        SyncSettings.setTlsPin(context, null)
        SyncSettings.setPendingTlsPin(context, null)
        SyncSettings.resetRuntimeState(context)
    }

    /** 通知自检结果：本环境下能否「发出并查询到」本应用的通知 */
    private var notificationQueryReliable = false

    /**
     * API 33+ 没授予通知权限时，前台服务通知不会进通知栏、`notify()` 也会被丢弃，
     * 「进度通知是否可见」这条断言就会变成假失败 —— 所以先确保权限真的到手。
     *
     * 注意 `executeShellCommand` 得到的是异步管道：必须读到 EOF 才算命令执行完，
     * 直接 close 可能把 `pm grant` 掐掉。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (SyncNotifier.hasNotificationPermission(context)) return
        val permission = "android.permission.POST_NOTIFICATIONS"
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching { ui.grantRuntimePermission(context.packageName, permission) }
            .onFailure {
                runCatching {
                    val descriptor = ui.executeShellCommand("pm grant ${context.packageName} $permission")
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
        assertTrue(
            "无法授予通知权限，通知可见性用例无法进行",
            SyncNotifier.hasNotificationPermission(context)
        )
    }

    // ------------------------------------------------------------ 单向增量上传

    @Test
    fun upload_writesANewTimestampedFile_withASingleUnconditionalPut() {
        val content = "第一行内容\n第二行 with emoji 📝\n第三行"
        assertTrue("前置条件：本地写入成功", runBlocking { ContentStore.write(content) })

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("同步应成功，实际：$status", status is SyncStatus.Success)
        assertTrue("应确实上传了内容", (status as SyncStatus.Success).uploaded)

        val name = uploadedName()
        assertTrue("文件名必须是 note<unix 秒>.txt，实际：$name", NAME_PATTERN.matches(name))
        assertEquals("服务器端字节必须与编辑器内容一致", content, cloud(name))

        val log = control("log")
        assertTrue("应直接 PUT 一个带时间戳的新文件：$log", log.contains("PUT $dirPath/$name -> 201"))
        assertFalse("不得出现临时文件：$log", log.contains(".uploading"))
        assertFalse("不得出现 MOVE/COPY：$log", log.contains("MOVE") || log.contains("COPY"))
        assertFalse(
            "不得读取云端（HEAD/PROPFIND/GET）：$log",
            log.contains("HEAD $dirPath") || log.contains("PROPFIND") || log.contains("GET $dirPath")
        )
    }

    @Test
    fun secondSync_withoutChanges_doesNotUploadAgain() {
        assertTrue(runBlocking { ContentStore.write("内容 A") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        val name = uploadedName()
        val countAfterFirst = requestCount()

        val second = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("第二次应识别为已是最新，实际：$second", second is SyncStatus.Success)
        assertFalse("不应重复上传", (second as SyncStatus.Success).uploaded)
        assertEquals("本地没变时**一个请求都不该发**", countAfterFirst, requestCount())
        assertEquals("内容 A", cloud(name))
    }

    @Test
    fun everyUploadCreatesANewFile_andOlderFilesStayUntouched() {
        assertTrue(runBlocking { ContentStore.write("第一版") })
        assertTrue(runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) } is SyncStatus.Success)
        val first = uploadedName()

        assertTrue(runBlocking { ContentStore.write("第二版") })
        assertTrue(runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) } is SyncStatus.Success)
        val second = uploadedName()

        assertTrue("两次上传必须是两个不同的文件：$first / $second", first != second)
        assertEquals("旧文件必须原样保留（历史不丢）", "第一版", cloud(first))
        assertEquals("新文件是本次内容", "第二版", cloud(second))
        val log = control("log")
        assertTrue("新上传应创建一个新文件：$log", log.contains("PUT $dirPath/$second -> 201"))
        assertFalse("旧文件不得被改写：$log", log.contains("PUT $dirPath/$first -> 204"))
        assertFalse("不允许出现删除动作：$log", log.contains("DELETE $dirPath/$first"))
    }

    @Test
    fun sync_neverReadsTheCloud_evenWhenTheFolderHasOldFiles() {
        // 目录里预置一份「历史文件」（模拟以前同步上去的内容）
        controlPost("file?path=$dirPath/legacy-note.txt", "旧的历史内容")
        assertTrue(runBlocking { ContentStore.write("本机内容") })
        val before = requestCount()

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue(status is SyncStatus.Success)

        val newLines = control("log").lines().filter { it.isNotBlank() }.drop(before)
        assertTrue(
            "云端有旧文件时也只应有写入请求：${newLines.joinToString("\n")}",
            newLines.isNotEmpty() && newLines.none {
                it.contains("HEAD") || it.contains("PROPFIND") || it.contains("GET")
            }
        )
        assertEquals("旧文件必须原样保留", "旧的历史内容", cloud("legacy-note.txt"))
    }

    // ------------------------------------------------------------ 没有节流（v8.1.0）

    /**
     * v8.1.0：**两次自动触发之间没有任何节流** —— 这条用例就是旧行为的反证。
     *
     * 旧实现（v7.7.2 ~ v8.0.3）在这里会返回 `Skipped(THROTTLED)`、一个请求都不发，
     * 真机日志里"退出后 156 秒才上传"正是它造成的（10:13:23 那次零请求的空跑把闸门
     * 关到 10:14:23，于是 10:13:26 那次有内容的退出被静默跳过）。
     * 现在第二次保存（内容已变）必须**立刻**写出第二个文件，且旧文件原样保留。
     */
    @Test
    fun noThrottle_secondSaveWithChangedContentUploadsImmediately() {
        assertTrue(runBlocking { ContentStore.write("v1") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }
        val first = uploadedName()
        val countAfterFirst = requestCount()
        assertEquals("v1", cloud(first))

        // 紧接着（同一秒内）内容又变了：必须立刻再传一次，不能再被"距上次不足 1 分钟"挡下
        assertTrue(runBlocking { ContentStore.write("v2") })
        val second = runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }

        assertTrue("自动触发不再有任何节流，实际：$second", second is SyncStatus.Success)
        assertTrue("内容变了就必须真的传上去", (second as SyncStatus.Success).uploaded)
        assertTrue("必须真的发出了请求", requestCount() > countAfterFirst)
        val secondName = uploadedName()
        assertTrue("必须写成另一个新文件：$first / $secondName", first != secondName)
        assertEquals("v2", cloud(secondName))
        assertEquals("v1 的那份仍在", "v1", cloud(first))
    }

    /**
     * 没有节流之后，"内容没变就一个请求都不发"这条不变量反而更重要了 ——
     * 它现在是**防重复上传的唯一机制**（旧版还叠加了 1 分钟闸门）。
     */
    @Test
    fun unchangedContent_sendsNoRequest_evenRightAfterAnUpload() {
        assertTrue(runBlocking { ContentStore.write("没有变化的内容") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }
        val countAfterFirst = requestCount()
        assertTrue("第一次必须真的传上去", countAfterFirst > 0)

        val again = runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }

        assertTrue("没变化也算同步成功，实际：$again", again is SyncStatus.Success)
        assertFalse("没变化 ⇒ 不该真的上传", (again as SyncStatus.Success).uploaded)
        assertEquals("内容没变时一个请求都不许发（防重复靠哈希，不靠节流）", countAfterFirst, requestCount())
    }

    // ------------------------------------------------------------ 前台服务与通知

    @Test
    fun foregroundService_uploadsWithProgressNotification_thenStopsItself() {
        assertTrue(runBlocking { ContentStore.write("通过前台服务上传") })
        // 先关掉自动同步，保证这次上传只可能由下面的手动触发产生（测试确定性）
        SyncSettings.setEnabled(context, false)

        // 先让应用处于可见状态：Android 12+ 对「后台启动前台服务」有限制，
        // 真实产品里同步也正是由用户可见的操作触发的
        ActivityScenario.launch(MainActivity::class.java).use {
            SyncSettings.setEnabled(context, true)

            SyncNotifier.ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertNotNull("同步通知渠道应已创建", manager.getNotificationChannel(SyncNotifier.CHANNEL_ID))

            assertTrue("应在数据已配置时启动同步", SyncLauncher.request(context, SyncTrigger.MANUAL))

            val deadline = SystemClock.uptimeMillis() + 90_000
            while (SystemClock.uptimeMillis() < deadline &&
                SyncSettings.lastResult(context) != SyncEngine.RESULT_SUCCESS
            ) {
                SystemClock.sleep(300)
            }
            assertEquals(
                "前台服务路径应完成上传（lastError=${SyncSettings.lastError(context)}）",
                SyncEngine.RESULT_SUCCESS,
                SyncSettings.lastResult(context)
            )
            assertEquals("通过前台服务上传", cloud(uploadedName()))

            // 同步结束必须收掉服务：不留常驻后台
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val stopDeadline = SystemClock.uptimeMillis() + 15_000
            var running = true
            while (SystemClock.uptimeMillis() < stopDeadline) {
                running = activityManager.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == SyncService::class.java.name }
                if (!running) break
                SystemClock.sleep(300)
            }
            assertFalse("同步结束后前台服务必须停止（省电要求）", running)
        }
    }

    /**
     * 用户报障的复现与固化：「上传很快，进度条根本看不到」。
     *
     * 为什么这里断言的是**服务存活时长**而不是「查询到通知」：
     * 实测（CI 的 API 34 / API 35 模拟器）`NotificationManager.getActiveNotifications()`
     * **不会返回前台服务的通知** —— 上传成功、服务确实在跑，但查询结果始终为空；
     * 而本应用自己 `notify()` 发出的通知（见 [inProcessFallback_alsoShowsAProgressNotification]）
     * 能被正常查到。因此前台服务路径改为断言它的**效果**：进度通知的宿主（前台服务）
     * 必须存活足够久，让通知真的有机会被渲染出来 —— 这正是 v7.5 修的那个点
     * （[SyncNotifier.MIN_PROGRESS_VISIBLE_MS]）；修好之前上传一结束就收通知，
     * 服务只活几十毫秒。
     */
    @Test
    fun fastUpload_keepsTheProgressNotificationAliveLongEnough() {
        assertTrue(runBlocking { ContentStore.write("通知可见性验证") })
        SyncSettings.setEnabled(context, false)

        ActivityScenario.launch(MainActivity::class.java).use {
            SyncSettings.setEnabled(context, true)
            // v8.1.0：没有闸门可设，也不需要设 —— 打开应用本身不触发任何上传（v7.8），
            // 因此下面这次上传只可能来自那次显式请求
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            assertTrue("应启动前台服务", SyncLauncher.request(context, SyncTrigger.MANUAL))

            var firstSeenAt = 0L
            var lifetimeMs = -1L
            val observeDeadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < observeDeadline) {
                val running = activityManager.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == SyncService::class.java.name }
                val now = SystemClock.uptimeMillis()
                if (running) {
                    if (firstSeenAt == 0L) firstSeenAt = now
                } else if (firstSeenAt != 0L) {
                    lifetimeMs = now - firstSeenAt
                    break
                }
                SystemClock.sleep(20)
            }

            assertEquals(
                "上传应成功（lastError=${SyncSettings.lastError(context)}）",
                SyncEngine.RESULT_SUCCESS,
                SyncSettings.lastResult(context)
            )
            assertEquals("通知可见性验证", cloud(uploadedName()))
            assertTrue("应观测到前台服务的整个生命周期", lifetimeMs >= 0)
            assertTrue(
                "上传极快时进度通知也必须保留 ${SyncNotifier.MIN_PROGRESS_VISIBLE_MS}ms，" +
                    "否则用户根本看不到（实测服务存活 ${lifetimeMs}ms）",
                lifetimeMs >= SyncNotifier.MIN_PROGRESS_VISIBLE_MS - 200
            )
        }
    }

    /**
     * 前台服务**无法启动**时的进程内兜底路径，同样必须有进度通知。
     *
     * 这是用户报障的第二种成因：Android 12+ 在个别时序下会拒绝后台启动前台服务，
     * 旧实现在那条路径上静默同步 —— 完全没有用户可见反馈。这里直接调用
     * [SyncManager.requestInProcess]，断言通知确实发出来了。
     */
    @Test
    fun inProcessFallback_alsoShowsAProgressNotification() {
        assumeTrue(
            "此环境无法发出/查询本应用的通知（自检失败），跳过通知可见性断言",
            notificationQueryReliable
        )
        assertTrue(runBlocking { ContentStore.write("进程内兜底验证") })
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        SyncManager.requestInProcess(context, SyncTrigger.MANUAL)

        var observed = false
        val observeDeadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < observeDeadline) {
            if (manager.activeNotifications.any { it.id == SyncNotifier.ID_PROGRESS }) {
                observed = true
                break
            }
            SystemClock.sleep(20)
        }
        assertTrue(
            "进程内兜底路径同样必须发进度通知（旧实现这条路径完全没有反馈）" +
                "（lastResult=${SyncSettings.lastResult(context)}, " +
                "lastError=${SyncSettings.lastError(context)}）",
            observed
        )

        val doneDeadline = SystemClock.uptimeMillis() + 90_000
        while (SystemClock.uptimeMillis() < doneDeadline &&
            SyncSettings.lastResult(context) != SyncEngine.RESULT_SUCCESS
        ) {
            SystemClock.sleep(100)
        }
        assertEquals(SyncEngine.RESULT_SUCCESS, SyncSettings.lastResult(context))
        assertEquals("进程内兜底验证", cloud(uploadedName()))
    }

    // ------------------------------------------------------------ 顶部导航栏「立即上传」（v7.7）

    /**
     * v7.7 需求：编辑页顶部导航栏的「立即上传」按钮 —— 真的点它一下，编辑器里的**当前**内容
     * 必须立刻出现在云端。
     *
     * 为什么必须在真机上验证：
     *  - 菜单项由 AppCompat 装到 Toolbar 上，只有真实 AppCompat 环境才拿得到它；
     *  - 上传走前台服务，JVM/Robolectric 不记录 `startForegroundService`，那里断言不了。
     *
     * v8.1.0：这条用例原本还兼着"证明按钮绕过了 1 分钟节流"，闸门删除后那半边没了 ——
     * 现在它的职责只剩"点按钮确实把**编辑器里的当前内容**先落盘再传上去"（落盘顺序）。
     */
    @Test
    fun topBarUploadButton_uploadsTheCurrentEditorContent_ignoringThrottle() {
        val countBefore = requestCount()
        assertTrue(runBlocking { ContentStore.write("打开应用时的旧内容") })
        // 先关掉同步：保证这次上传只可能由「点按钮」产生（否则打开应用时的补同步会抢先上传）
        SyncSettings.setEnabled(context, false)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitEditorReady(scenario)

            val typed = "来自顶部导航栏的内容 ${System.currentTimeMillis()}"
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.editor).setText(typed)
            }

            // 打开同步。v8.1.0：这里不再需要"把闸门置为刚刚尝试过"——
            // 没有闸门，而打开应用本身不触发上传，因此下面这次上传只可能来自点按钮。
            SyncSettings.setEnabled(context, true)

            // 动作菜单在首次布局时装载，给它一点时间（不能在 onActivity 里 sleep：那是主线程）
            var found: MenuItem? = null
            val menuDeadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < menuDeadline && found == null) {
                scenario.onActivity { activity ->
                    found = activity.findViewById<Toolbar>(R.id.toolbar)
                        .menu.findItem(R.id.action_upload)
                }
                if (found == null) SystemClock.sleep(50)
            }
            val uploadItem = found
            assertNotNull("顶部导航栏必须有「立即上传」入口", uploadItem)

            scenario.onActivity { activity ->
                assertTrue("「立即上传」必须被处理", activity.onOptionsItemSelected(uploadItem!!))
            }

            val deadline = SystemClock.uptimeMillis() + 60_000
            while (SystemClock.uptimeMillis() < deadline &&
                SyncSettings.lastResult(context) != SyncEngine.RESULT_SUCCESS
            ) {
                SystemClock.sleep(200)
            }
            assertEquals(
                "点「立即上传」后必须同步成功（lastError=${SyncSettings.lastError(context)}）",
                SyncEngine.RESULT_SUCCESS,
                SyncSettings.lastResult(context)
            )

            val name = uploadedName()
            assertEquals(
                "云端文件必须就是编辑器里的当前内容（证明先落盘、再上传）",
                typed,
                cloud(name)
            )
            assertTrue("必须真的发出了请求", requestCount() > countBefore)
            assertTrue(
                "应当往新文件里写：${control("log")}",
                control("log").contains("PUT $dirPath/$name -> 201")
            )
        }
    }

    // ------------------------------------------------------------ v7.8：打开应用只检测不上传

    /**
     * v7.8 需求 1：**打开应用只检测、不上传**。
     *
     * 构造「磁盘内容 != 上次成功上传的内容」（= 有待上传改动），然后进编辑页：
     *  - 服务器在整段时间里**一个请求都不许收到**（连 MKCOL 都不许）—— 旧实现在这里走 `APP_OPEN`；
     *  - 也不许写 `lastServerContactAt`（那是"真的访问过云端"的证据；v8.1.0 起它取代了
     *    1 分钟闸门的锚点 `lastAttemptAt`，证明的含义反而更强：一次请求都没发）；
     *  - 设置页的状态行必须能看到这次成功（真机断言见 SyncActivity 相关用例）。
     */
    @Test
    fun openingTheAppWithPendingChanges_onlyDetects_andNeverTouchesTheServer() {
        assertTrue(runBlocking { ContentStore.write("本地比较新的内容") })
        SyncSettings.setEnabled(context, true)
        SyncSettings.recordSuccess(
            context,
            SyncEngine.sha256Hex("上次成功上传的内容".toByteArray()),
            1_735_689_600L
        )
        val countBefore = requestCount()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitEditorReady(scenario)
            // 给任何潜在的自动同步足够的时间露头（旧实现在启动瞬间就会上传）
            SystemClock.sleep(2000)

            assertEquals("打开应用不得访问服务器", countBefore, requestCount())
            assertEquals(
                "打开应用不得记录任何「访问过云端」的痕迹",
                0L,
                SyncSettings.lastServerContactAt(context)
            )
        }
    }

    // ------------------------------------------------------------ v7.9：上传失败后的系统级自愈

    /**
     * v7.9 的自愈通道，正对「保存了却没上传，只能清后台」那个报障：
     *
     *  1. 上传失败之后，系统里**必须还留着一个待执行的重试任务** —— 那是唯一会自己把
     *     欠下的上传补上的通道；
     *  2. 那个任务真的能在网络恢复后把欠的内容补上去，成功之后自己撤销，不留常驻唤醒源。
     *
     * 场景：把地址指向一定拒绝连接的端口（`10.0.2.2:1`）→ 按返回键保存（用户报障的原路径）
     * → 同步快速失败；随后把地址改回 CI 上的 WebDAV 桩服务器，并直接执行一次重试任务
     * （真机上那一步由系统在有网络时完成，测试里等不了 30 秒的最短延迟）。
     */
    @Test
    fun failedUpload_leavesASystemRetryQueued_andThatRetryFinishesTheUpload() {
        val goodConfig = SyncSettings.config(context)!!
        SyncRetry.cancel(context)
        SyncSettings.saveConfig(context, goodConfig.copy(baseUrl = "http://10.0.2.2:1/dav/"))
        SyncSettings.setEnabled(context, true)
        SyncSettings.resetRuntimeState(context)

        // 设备级前提自检：「排入系统任务」这件事本身必须先能在这台设备上用。
        // 同时记录"系统收下了没有"（schedule 的返回值）与"查得到没有"（isScheduled）——
        // 个别系统上刚排的任务查不回来，这两者必须分开看，否则分不清是系统拒绝还是查询不可靠。
        val acceptedDirect = SyncRetry.schedule(context)
        val visibleDirect = SyncRetry.isScheduled(context)
        val directPendingIds = pendingJobIds()
        SyncRetry.cancel(context)

        val typed = "网络恢复后应当补传的内容"
        assertTrue(runBlocking { ContentStore.write(typed) })

        // 先把失败这一次的现场全部记下来，再去断言（断言会中断测试，后面还想跑"补传成功"那一段）
        var resultAfterFailure = ""
        var visibleAfterFailure = false
        var pendingIdsAfterFailure = ""
        var statusAfterFailure = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitEditorReady(scenario)
            // 返回键 = 用户报障时的那条路径：先落盘、再触发上传
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }

            val failDeadline = SystemClock.uptimeMillis() + 60_000
            while (SystemClock.uptimeMillis() < failDeadline &&
                SyncSettings.lastResult(context) != SyncEngine.RESULT_FAILED
            ) {
                SystemClock.sleep(200)
            }
            resultAfterFailure = SyncSettings.lastResult(context)
            visibleAfterFailure = SyncRetry.isScheduled(context)
            pendingIdsAfterFailure = pendingJobIds()
            statusAfterFailure = SyncManager.status.value.toString()
        }

        // 网络恢复：模拟系统在合适时机拉起那个任务（真机上还要过 30 秒最短延迟）
        SyncSettings.saveConfig(context, goodConfig)
        val needsAnotherRetry = runBlocking { SyncRetry.runOnce(context) }
        val recovered = cloud(uploadedName())

        SyncRetry.cancel(context)
        val visibleAfterCancel = SyncRetry.isScheduled(context)

        // 一次把全部事实报出来：断言中断测试，分成多条只会一次只看到一个结论
        val scene = "result=$resultAfterFailure acceptedDirect=$acceptedDirect " +
            "visibleDirect=$visibleDirect visibleAfterFailure=$visibleAfterFailure " +
            "pendingIds=$pendingIdsAfterFailure status=$statusAfterFailure " +
            "directPendingIds=$directPendingIds visibleAfterCancel=$visibleAfterCancel"
        val failures = buildList {
            if (resultAfterFailure != SyncEngine.RESULT_FAILED) {
                add("指向不可达端口时同步必须明确失败")
            }
            if (!acceptedDirect) {
                add("系统必须收下这个重试任务（否则自愈通道在这台设备上根本不存在）")
            }
            if (needsAnotherRetry) add("内容已经补传成功，不该再重试")
            if (recovered != typed) add("重试必须真的把内容传上去，实际[$recovered]")
            if (visibleAfterCancel) add("兜底任务不能留在系统里成为常驻唤醒源")
        }
        // 注意：这里刻意**不**断言 visibleAfterFailure / pendingIdsAfterFailure ——
        // 实测模拟器上刚排入的任务用 getAllPendingJobs/getPendingJob 都查不回来
        // （app 侧与直接调用都一样），查得到与否是系统行为，不是本功能的正确性；
        // 「保存路径确实排了任务、成功后会撤销」由 Robolectric 单测钉住。
        // app 侧到底收下没有，看本次 CI 日志里 run_webdav_e2e.sh 导出的 SyncRetry logcat。
        assertTrue("v7.9 自愈通道断言失败：${failures.joinToString("；")}（$scene）", failures.isEmpty())
    }

    // ------------------------------------------------------------ 工具

    /** 当前待执行的系统任务 id；查询失败时把异常原样带出来（诊断信息比空列表有用得多） */
    private fun pendingJobIds(): String = try {
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler)
            .allPendingJobs.map { it.id }.toString()
    } catch (e: Exception) {
        "查询失败 ${e.javaClass.simpleName}: ${e.message}"
    }

    /** 等编辑器异步读盘完成（解禁）——只有解禁后才能编辑与保存 */
    private fun awaitEditorReady(scenario: ActivityScenario<MainActivity>, timeoutMs: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var enabled = false
        while (SystemClock.uptimeMillis() < deadline && !enabled) {
            scenario.onActivity { activity ->
                enabled = activity.findViewById<EditText>(R.id.editor).isEnabled
            }
            if (!enabled) SystemClock.sleep(50)
        }
        assertTrue("等待编辑器加载完成超时", enabled)
    }

    /**
     * 自检：本环境下「发出一条通知并能查询回来」是否可用。
     *
     * 必须重试：通知权限刚授予、或系统刚完成启动时，第一次 `notify` 可能被丢弃，
     * 查询也可能跑在系统落库之前 —— 一次性判定会把用例误跳过。
     *
     * 真正不可用时（受限环境）跳过可见性断言，而不是给出误导性的失败：
     * 「通知有没有发出去」由 JVM 的 SyncManagerTest 用假客户端直接断言。
     */
    private fun notificationQueryWorks(manager: NotificationManager): Boolean {
        val probeId = 4099
        val composer = NotificationManagerCompat.from(context)
        repeat(12) { attempt ->
            composer.notify(probeId, SyncNotifier.progressNotification(context, "自检"))
            val deadline = SystemClock.uptimeMillis() + 800
            while (SystemClock.uptimeMillis() < deadline) {
                if (manager.activeNotifications.any { it.id == probeId }) {
                    composer.cancel(probeId)
                    return true
                }
                SystemClock.sleep(20)
            }
            composer.cancel(probeId)
            if (attempt < 11) SystemClock.sleep(200)
        }
        return false
    }

    /** 本次实际上传用的文件名（由落盘的时间戳还原，不依赖任何测试侧约定） */
    private fun uploadedName(): String {
        val config = SyncSettings.config(context)
        assertNotNull("应有可用配置", config)
        return config!!.historyFileName(SyncSettings.lastUploadedTs(context))
    }

    /** 云端文件内容（经过 control 面读取，不经过被测客户端） */
    private fun cloud(name: String): String = control("file?path=$dirPath/$name")

    private fun requestCount(): Int = control("count").trim().toInt()

    private fun control(path: String): String {
        val connection = URL(controlUrl + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return try {
            String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun controlPost(path: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = URL(controlUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return try {
            connection.outputStream.use { it.write(bytes) }
            String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** `note1735689600123.txt`：前缀 + unix 毫秒时间戳 + 扩展名 */
        val NAME_PATTERN = Regex("""note\d{13}\.txt""")
    }
}
