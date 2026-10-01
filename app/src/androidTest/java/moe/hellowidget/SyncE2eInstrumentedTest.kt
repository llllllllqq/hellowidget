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
 *  2. 服务器端有请求日志，可以断言**客户端究竟发了什么** —— v7.5 的单向语义要求
 *     日志里只有 `PUT`（必要时一个 `MKCOL`），不得出现任何读取动作或临时文件/换名；
 *  3. 通知栏这种只有真实 NotificationManager 才有的行为，也只有在设备上才算数
 *     （见 [fastUpload_stillShowsProgressNotification]）。
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

    /**
     * 每个用例用**各自独立的远端文件名**，而不是靠服务器 reset 来隔离。
     *
     * 原因：服务器 reset 会连请求日志一起清空，那么 CI 最后打印出来的「WebDAV 动词实证」
     * 就只剩下最后执行的那个用例；用独立文件名后，日志天然累积，
     * 一次运行里所有场景的请求序列都留在证据里。
     */
    private val fileName: String get() = "note-${testName.methodName}.txt"

    /** 通知自检结果：本环境下能否「发出并查询到」本应用的通知 */
    private var notificationQueryReliable = false

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
                baseUrl = davUrl!!,
                fileName = fileName,
                username = "test",
                password = "test"
            )
        )
        SyncSettings.setTlsPin(context, null)
        SyncSettings.setPendingTlsPin(context, null)
        SyncSettings.resetRuntimeState(context)
    }

    /**
     * API 33+ 没授予通知权限时，前台服务通知不会进通知栏、`notify()` 也会被丢弃，
     * 「进度通知是否可见」这条断言就会变成假失败 —— 所以先确保权限真的到手。
     *
     * 注意 `executeShellCommand` 得到的是异步管道：必须读到 EOF 才算命令执行完，
     * 直接 close 可能把 `pm grant` 掐掉（这正是第一版里权限没生效的原因）。
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

    // ------------------------------------------------------------ 单向覆盖上传

    @Test
    fun upload_pushesLatestContent_withASingleUnconditionalPut() {
        val content = "第一行内容\n第二行 with emoji 📝\n第三行"
        assertTrue("前置条件：本地写入成功", runBlocking { ContentStore.write(content) })

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("同步应成功，实际：$status", status is SyncStatus.Success)
        assertTrue("应确实上传了内容", (status as SyncStatus.Success).uploaded)

        assertEquals("服务器端字节必须与编辑器内容一致", content, cloud(fileName))

        val log = control("log")
        assertTrue("应直接 PUT 正式文件：$log", log.contains("PUT /dav/$fileName -> 201"))
        // v7.5 的单向语义：一个请求搞定，不读云端、不写临时文件、不换名
        assertFalse("不得再出现临时文件：$log", log.contains(".uploading"))
        assertFalse("不得再出现 MOVE/COPY：$log", log.contains("MOVE") || log.contains("COPY"))
        assertFalse("正常路径不该读到云端（HEAD/PROPFIND）：$log", log.contains("HEAD /dav/$fileName") || log.contains("PROPFIND"))
    }

    @Test
    fun secondSync_withoutChanges_doesNotUploadAgain() {
        assertTrue(runBlocking { ContentStore.write("内容 A") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        val countAfterFirst = requestCount()

        val second = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("第二次应识别为已是最新，实际：$second", second is SyncStatus.Success)
        assertFalse("不应重复上传", (second as SyncStatus.Success).uploaded)
        assertEquals("本地没变时**一个请求都不该发**", countAfterFirst, requestCount())
        assertEquals("内容 A", cloud(fileName))
    }

    @Test
    fun forceOverwrite_replacesCloudContentWithoutAnyComparisonOrConflict() {
        // 第一次上传建立基线
        assertTrue(runBlocking { ContentStore.write("本机第一版") })
        assertTrue(
            "第一次同步应成功",
            runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) } is SyncStatus.Success
        )

        // 模拟「电脑端在云端改了内容」——v7.5 不再关心，也不该弹冲突
        controlPost("file?path=/dav/$fileName", "电脑上改过的云端内容")

        // 本机再改 → 直接整份覆盖云端
        assertTrue(runBlocking { ContentStore.write("本机第二版") })
        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("应成功覆盖，实际：$status", status is SyncStatus.Success)
        assertTrue("应确实上传", (status as SyncStatus.Success).uploaded)

        assertEquals("云端必须被本机内容覆盖", "本机第二版", cloud(fileName))
        val log = control("log")
        assertTrue("覆盖应是单次 PUT（已存在 → 204）：$log", log.contains("PUT /dav/$fileName -> 204"))
        assertFalse("不得产生冲突副本：$log", log.contains(".conflict-"))
    }

    @Test
    fun sync_neverReadsTheCloud_evenWhenTheCloudWasChanged() {
        controlPost("file?path=/dav/$fileName", "云端已有内容")
        assertTrue(runBlocking { ContentStore.write("本机内容") })
        val before = requestCount()

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue(status is SyncStatus.Success)

        val newLines = control("log").lines().filter { it.isNotBlank() }
        val relevant = newLines.drop(before)
        assertTrue(
            "云端被外部改过时也只应有写入请求：${relevant.joinToString("\n")}",
            relevant.isNotEmpty() && relevant.none { it.contains("HEAD") || it.contains("PROPFIND") || it.contains("GET") }
        )
    }

    // ------------------------------------------------------------ 节流

    @Test
    fun throttle_blocksAutomaticSyncButNotTheManualButton() {
        assertTrue(runBlocking { ContentStore.write("v1") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        val countAfterFirst = requestCount()

        // 内容又变了，但距上次同步不足 30 分钟：自动触发必须被跳过，且**不发任何请求**
        assertTrue(runBlocking { ContentStore.write("v2") })
        val throttled = runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }
        assertEquals(SyncStatus.Skipped(SkipReason.THROTTLED), throttled)
        assertEquals("被节流时不能访问服务器", countAfterFirst, requestCount())
        assertEquals("被节流时云端保持旧内容", "v1", cloud(fileName))

        // 手动按钮不受限
        val manual = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("手动同步应成功，实际：$manual", manual is SyncStatus.Success)
        assertTrue("手动同步应真的发出请求", requestCount() > countAfterFirst)
        assertEquals("v2", cloud(fileName))
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
            assertEquals("通过前台服务上传", cloud(fileName))

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
     * 本地桩服务器上的上传通常几十毫秒就结束；这条用例能在通知栏里**观测到**进度通知，
     * 正是因为 v7.5 为「确实发生了上传」的同步保证了最短可见时长
     * （[SyncNotifier.MIN_PROGRESS_VISIBLE_MS]）。上传成功还会额外弹一个 Toast
     * （Toast 无法在仪器化测试里断言，其行为由 JVM 的 SyncManagerTest 覆盖）。
     */
    /**
     * 用户报障的复现与固化：「上传很快，进度条根本看不到」。
     *
     * 本地桩服务器上的上传通常几十毫秒就结束；这条用例能在通知栏里**观测到**进度通知，
     * 正是因为 v7.5 为「确实发生了上传」的同步保证了最短可见时长
     * （[SyncNotifier.MIN_PROGRESS_VISIBLE_MS]）。上传成功还会额外弹一个 Toast
     * （Toast 无法在仪器化测试里断言，其行为由 JVM 的 SyncManagerTest 覆盖）。
     */
    @Test
    fun fastUpload_stillShowsProgressNotification() {
        assumeTrue(
            "此环境无法发出/查询本应用的通知（自检失败），跳过通知可见性断言",
            notificationQueryReliable
        )
        assertTrue(runBlocking { ContentStore.write("通知可见性验证") })
        SyncSettings.setEnabled(context, false)

        ActivityScenario.launch(MainActivity::class.java).use {
            SyncSettings.setEnabled(context, true)
            // 节流掉 MainActivity 自己可能触发的 APP_OPEN 同步：否则「另一个同步无事可做」
            // 会立刻收掉进度通知，干扰观测（用户手点的同步不受节流限制）
            SyncSettings.setLastAttemptAt(context, System.currentTimeMillis())

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            assertTrue("应启动前台服务", SyncLauncher.request(context, SyncTrigger.MANUAL))

            val observedIds = linkedSetOf<Int>()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            var serviceSeen = false
            var observed = false
            val observeDeadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < observeDeadline) {
                manager.activeNotifications.forEach { observedIds += it.id }
                if (manager.activeNotifications.any { it.id == SyncNotifier.ID_PROGRESS }) {
                    observed = true
                }
                if (activityManager.getRunningServices(Int.MAX_VALUE)
                        .any { it.service.className == SyncService::class.java.name }
                ) {
                    serviceSeen = true
                }
                if (observed) break
                SystemClock.sleep(20)
            }
            assertTrue(
                "上传期间通知栏里必须真的存在进度通知（用户报障点）" +
                    "（observedIds=$observedIds, serviceSeen=$serviceSeen, " +
                    "lastResult=${SyncSettings.lastResult(context)}, " +
                    "lastError=${SyncSettings.lastError(context)}）",
                observed
            )

            val doneDeadline = SystemClock.uptimeMillis() + 90_000
            while (SystemClock.uptimeMillis() < doneDeadline &&
                SyncSettings.lastResult(context) != SyncEngine.RESULT_SUCCESS
            ) {
                SystemClock.sleep(100)
            }
            assertEquals(
                "上传应成功（lastError=${SyncSettings.lastError(context)}）",
                SyncEngine.RESULT_SUCCESS,
                SyncSettings.lastResult(context)
            )
            assertEquals("通知可见性验证", cloud(fileName))

            val goneDeadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < goneDeadline &&
                manager.activeNotifications.any { it.id == SyncNotifier.ID_PROGRESS }
            ) {
                SystemClock.sleep(100)
            }
            assertFalse(
                "同步结束后不得留下常驻进度通知",
                manager.activeNotifications.any { it.id == SyncNotifier.ID_PROGRESS }
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
        assertTrue(runBlocking { ContentStore.write("进程内兜底验证") })
        SyncSettings.setLastAttemptAt(context, 0)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        assumeTrue(
            "此环境无法发出/查询本应用的通知（自检失败），跳过通知可见性断言",
            notificationQueryReliable
        )

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
        assertEquals("进程内兜底验证", cloud(fileName))
    }

    // ------------------------------------------------------------ 工具

    /**
     * 自检：本环境下「发出一条通知并能查询回来」是否可用。
     *
     * 必须重试：通知权限刚授予、或系统刚完成启动时，第一次 `notify` 可能被丢弃，
     * 查询也可能跑在系统落库之前 —— 一次性判定会把用例误跳过（第三轮 CI 就发生了）。
     *
     * 真正不可用时（受限环境）跳过可见性断言，而不是给出误导性的失败：
     * 「通知有没有发出去」由 JVM 的 SyncManagerTest 用假客户端直接断言。
     */
    private fun notificationQueryWorks(manager: NotificationManager): Boolean {
        val probeId = 4099
        val composer = NotificationManagerCompat.from(context)
        // 最多等 10s：权限刚授予时系统的「通知已启用」状态是异步生效的，
        // 这段窗口内发出的通知会被静默丢弃（早于本修复时用例被误跳过就是这个原因）
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

    /** 云端文件内容（经过 control 面读取，不经过被测客户端） */
    private fun cloud(name: String): String = control("file?path=/dav/$name")

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
}
