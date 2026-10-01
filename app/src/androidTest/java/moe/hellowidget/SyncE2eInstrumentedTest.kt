package moe.hellowidget

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SkipReason
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncLauncher
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
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

    // ------------------------------------------------------------ 节流

    @Test
    fun throttle_blocksAutomaticSyncButNotTheManualButton() {
        assertTrue(runBlocking { ContentStore.write("v1") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        val first = uploadedName()
        val countAfterFirst = requestCount()

        // 内容又变了，但距上次同步不足 30 分钟：自动触发必须被跳过，且**不发任何请求**
        assertTrue(runBlocking { ContentStore.write("v2") })
        val throttled = runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }
        assertEquals(SyncStatus.Skipped(SkipReason.THROTTLED), throttled)
        assertEquals("被节流时不能访问服务器", countAfterFirst, requestCount())
        assertEquals("被节流时不会产生新文件", "v1", cloud(first))

        // 手动按钮不受限
        val manual = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("手动同步应成功，实际：$manual", manual is SyncStatus.Success)
        assertTrue("手动同步应真的发出请求", requestCount() > countAfterFirst)
        val second = uploadedName()
        assertTrue("必须写成另一个新文件：$first / $second", first != second)
        assertEquals("v2", cloud(second))
        assertEquals("v1 的那份仍在", "v1", cloud(first))
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
            SyncSettings.setLastAttemptAt(context, 0)

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
            // 节流掉 MainActivity 自己可能触发的 APP_OPEN 同步，保证只观测这一次上传
            SyncSettings.setLastAttemptAt(context, System.currentTimeMillis())

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
        SyncSettings.setLastAttemptAt(context, 0)
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

    // ------------------------------------------------------------ 工具

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
