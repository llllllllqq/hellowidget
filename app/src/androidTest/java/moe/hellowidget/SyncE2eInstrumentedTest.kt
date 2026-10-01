package moe.hellowidget

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.ConflictChoice
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
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebDAV 同步的**真机级**端到端验证（CI 里跑在 Android 模拟器上，打 runner 上真实运行的
 * Python WebDAV 服务器：`.github/scripts/webdav_stub_server.py`，模拟器通过 10.0.2.2 访问）。
 *
 * 为什么必须有这一层：
 *  1. JVM 单测跑的是桌面 JVM 的 Socket 栈，只有真实 Android 才能证明这台设备上的
 *     网络栈、明文策略（network_security_config）与前台服务限制下流程成立；
 *  2. 服务器端有请求日志，可以断言**真的用了 WebDAV 动词**（HEAD/PROPFIND、MKCOL、PUT、
 *     MOVE、COPY），而不是被降级成某种 REST 变通 —— 这是「支持 WebDAV」的硬证据；
 *  3. 冲突的真实语义（云端不被静默覆盖、两边版本都留副本）只有对着真实服务器跑一遍才算数。
 *
 * 未传 `webdavUrl` 时整体跳过（本地或其它 CI 运行不会因此变红）。
 */
@RunWith(AndroidJUnit4::class)
class SyncE2eInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    private val davUrl: String? get() = arguments.getString("webdavUrl")
    private val controlUrl: String? get() = arguments.getString("webdavControlUrl")
    private val fileName = "note.txt"

    @Before
    fun setUp() {
        assumeTrue(
            "未提供 webdavUrl/webdavControlUrl，跳过 WebDAV 端到端测试",
            !davUrl.isNullOrBlank() && !controlUrl.isNullOrBlank()
        )
        control("reset")
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

    // ------------------------------------------------------------ 正常上传

    @Test
    fun upload_pushesLatestContent_andUsesRealWebdavVerbs() {
        val content = "第一行内容\n第二行 with emoji 📝\n第三行"
        assertTrue("前置条件：本地写入成功", runBlocking { ContentStore.write(content) })

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("同步应成功，实际：$status", status is SyncStatus.Success)
        assertTrue("应确实上传了内容", (status as SyncStatus.Success).uploaded)

        assertEquals("服务器端字节必须与编辑器内容一致", content, cloud(fileName))

        val log = control("log")
        assertTrue("应先取云端元数据（HEAD 或 PROPFIND）：$log", log.contains("HEAD /dav/$fileName") || log.contains("PROPFIND"))
        assertTrue("应创建目标目录：$log", log.contains("MKCOL"))
        assertTrue("应先把内容写到临时文件：$log", log.contains("PUT /dav/$fileName.uploading"))
        assertTrue("应用 MOVE 原子换名（否则会留下被截断的风险）：$log", log.contains("MOVE /dav/$fileName.uploading"))
    }

    @Test
    fun secondSync_withoutChanges_doesNotUploadAgain() {
        assertTrue(runBlocking { ContentStore.write("内容 A") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        val writesAfterFirst = writeRequestCount()

        val second = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("第二次应识别为已是最新，实际：$second", second is SyncStatus.Success)
        assertFalse("不应重复上传", (second as SyncStatus.Success).uploaded)
        assertEquals("不应产生任何写请求（PUT/MOVE/COPY/DELETE）", writesAfterFirst, writeRequestCount())
        assertEquals("内容 A", cloud(fileName))
    }

    // ------------------------------------------------------------ 冲突

    @Test
    fun conflict_isNeverAutoResolved_andKeepingLocalPreservesTheCloudVersion() {
        assertTrue(runBlocking { ContentStore.write("本地第一版") })
        assertTrue(runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) } is SyncStatus.Success)

        // 模拟「电脑网页端改了云端」
        controlPost("file?path=/dav/$fileName", "云端被电脑改过")
        // 本地也改了
        assertTrue(runBlocking { ContentStore.write("本地第二版") })
        SyncSettings.setLastAttemptAt(context, 0)

        val conflict = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("应识别为冲突，实际：$conflict", conflict is SyncStatus.Conflict)
        assertEquals("冲突时绝不能静默覆盖云端", "云端被电脑改过", cloud(fileName))
        assertTrue("应留下待处理冲突（自动同步暂停）", SyncSettings.pendingConflict(context))

        // 待处理冲突期间，自动触发必须被暂停
        SyncSettings.setLastAttemptAt(context, 0)
        val whilePending = runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR, null) }
        assertEquals(SyncStatus.Skipped(SkipReason.PENDING_CONFLICT), whilePending)
        assertEquals("云端仍然不能被覆盖", "云端被电脑改过", cloud(fileName))

        // 选择保留本地：云端旧版本先被另存为冲突副本，再被本地覆盖
        SyncSettings.setLastAttemptAt(context, 0)
        val resolved = runBlocking {
            SyncManager.performSync(context, SyncTrigger.CONFLICT_RESOLVE, ConflictChoice.KEEP_LOCAL)
        }
        assertTrue("解冲突应成功，实际：$resolved", resolved is SyncStatus.Success)
        assertEquals("本地第二版", cloud(fileName))
        assertFalse(SyncSettings.pendingConflict(context))

        val copyName = conflictCopyNameFromLog()
        assertEquals("冲突副本必须保留被覆盖掉的云端版本", "云端被电脑改过", cloud(copyName))
        assertTrue("冲突副本应通过 COPY 或 GET+PUT 生成", control("log").contains(".conflict-"))
    }

    @Test
    fun useRemote_replacesLocalContent_andPreservesTheLocalVersionOnTheServer() {
        controlPost("file?path=/dav/$fileName", "云端版本")
        assertTrue(runBlocking { ContentStore.write("本地版本") })

        // 本机从未上传过、云端已有不同内容 → 首次同步即为冲突（绝不默认覆盖任何一边）
        val conflict = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("首次遇到已有云端文件应产生冲突，实际：$conflict", conflict is SyncStatus.Conflict)

        val resolved = runBlocking {
            SyncManager.performSync(context, SyncTrigger.CONFLICT_RESOLVE, ConflictChoice.USE_REMOTE)
        }
        assertTrue("解冲突应成功，实际：$resolved", resolved is SyncStatus.Success)

        assertEquals("本地内容应被云端版本替换", "云端版本", runBlocking { ContentStore.read() })
        assertEquals("云端主文件不应被改动", "云端版本", cloud(fileName))
        val copyName = conflictCopyNameFromLog()
        assertEquals("本地版本必须被保留为云端冲突副本", "本地版本", cloud(copyName))
        assertTrue("编辑页需要知道内容已被替换", SyncSettings.contentReplacedAt(context) > 0)

        // 替换后本地与云端一致，下一次同步应无事可做
        SyncSettings.setLastAttemptAt(context, 0)
        val after = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue(after is SyncStatus.Success && !(after as SyncStatus.Success).uploaded)
    }

    // ------------------------------------------------------------ 节流

    @Test
    fun throttle_blocksAutomaticSyncButNotTheManualButton() {
        assertTrue(runBlocking { ContentStore.write("v1") })
        runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        val countAfterFirst = requestCount()

        // 内容又变了，但距上次同步不足 30 分钟：自动触发必须被跳过，且**不发任何请求**
        assertTrue(runBlocking { ContentStore.write("v2") })
        val throttled = runBlocking { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR, null) }
        assertEquals(SyncStatus.Skipped(SkipReason.THROTTLED), throttled)
        assertEquals("被节流时不能访问服务器", countAfterFirst, requestCount())
        assertEquals("被节流时云端保持旧内容", "v1", cloud(fileName))

        // 手动按钮不受限
        val manual = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("手动同步应成功，实际：$manual", manual is SyncStatus.Success)
        assertTrue("手动同步应真的发出请求", requestCount() > countAfterFirst)
        assertEquals("v2", cloud(fileName))
    }

    // ------------------------------------------------------------ 前台服务

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

    // ------------------------------------------------------------ 工具

    /** 云端文件内容（经过 control 面读取，不经过被测客户端） */
    private fun cloud(name: String): String = control("file?path=/dav/$name")

    private fun conflictCopyNameFromLog(): String {
        val match = Regex("note\\.conflict-[0-9]{8}-[0-9]{6}(-[0-9]+)?\\.txt").find(control("log"))
        assertNotNull("服务器日志里应出现冲突副本文件名：${control("log")}", match)
        return match!!.value
    }

    private fun requestCount(): Int = control("count").trim().toInt()

    /** 写请求次数：判断「有没有真的改动云端」比总请求数更准确（读元数据的 HEAD 不算） */
    private fun writeRequestCount(): Int {
        val log = control("log")
        return Regex("(PUT|MOVE|COPY|DELETE) ").findAll(log).count()
    }

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
