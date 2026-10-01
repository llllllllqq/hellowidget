package moe.hellowidget

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SkipReason
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import moe.hellowidget.sync.WebDavClient
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * 同步编排（v7.5 单向覆盖语义）的单元测试。
 *
 * 这里把客户端的两个入口都换成假实现（[SyncManager.clientFactory]、[SyncManager.contentReader]），
 * 因此断言的是**编排本身**：有没有发请求、发的是什么、有没有给用户可见的反馈。
 * 真实的 HTTP 行为由 `HttpWebDavClientTest`（真 Socket）与 CI 的模拟器端到端测试覆盖。
 *
 * 两个直接对应线上报障的用例：
 *  - [upload_whenLocalChanged_hasVisibleProgressNotificationAndShowsToast]：
 *    「上传很快，通知栏进度根本看不到」——断言**上传进行中进度通知确实存在**，
 *    且上传结束后至少保留 [SyncNotifier.MIN_PROGRESS_VISIBLE_MS]，并弹出成功 Toast；
 *  - [noUpload_whenContentUnchanged_sendsNothingAndIsSilent]：
 *    「本地没改」时一个请求都不发、也不打扰用户。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncManagerTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** 记录所有请求的假客户端 */
    private class FakeClient(
        private val context: Context,
        private val firstPutFailure: WebDavException? = null
    ) : WebDavClient {
        val puts = mutableListOf<Pair<String, String>>()
        val mkcols = mutableListOf<String>()

        /** 尝试过的 PUT 次数（含被模拟失败挡下的那次） */
        var putAttempts = 0

        /** 上传进行中，进度通知是否可见（这正是「有没有通知栏进度」的证据） */
        var progressVisibleDuringPut = false

        private var failed = false

        override fun put(url: String, body: ByteArray) {
            putAttempts++
            if (firstPutFailure != null && !failed) {
                failed = true
                throw firstPutFailure
            }
            if (progressNotificationVisible(context)) progressVisibleDuringPut = true
            puts += url to String(body, Charsets.UTF_8)
        }

        override fun mkcol(url: String) {
            mkcols += url
        }

        override fun close() = Unit
    }

    private var lastClient: FakeClient? = null

    /** 每次同步都会新建一个客户端，这里把每次的都留着（断言「两次上传用了两个文件名」） */
    private val clients = mutableListOf<FakeClient>()
    private var clientBuilds = 0

    @Before
    fun setUp() {
        // 通知权限（API 33+ 需要）；不过权限时进度通知不会显示，测不到
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        SyncSettings.setEnabled(context, true)
        SyncSettings.saveConfig(
            context,
            SyncConfig(
                baseUrl = "http://127.0.0.1:1/dav/",
                fileName = "note.txt",
                username = "u",
                password = "p"
            )
        )
        SyncSettings.resetRuntimeState(context)
        clients.clear()
        SyncManager.contentReader = { content }
        installClient()
    }

    @After
    fun tearDown() {
        SyncManager.contentReader = { moe.hellowidget.ContentStore.read() }
        SyncManager.clientFactory = { config, onUntrusted ->
            moe.hellowidget.sync.HttpWebDavClient(config, onUntrusted)
        }
        SyncSettings.resetRuntimeState(context)
    }

    private var content = "内容 A"

    private fun installClient(firstPutFailure: WebDavException? = null) {
        SyncManager.clientFactory = { _, _ ->
            clientBuilds++
            FakeClient(context, firstPutFailure).also {
                lastClient = it
                clients += it
            }
        }
    }

    private fun sync(trigger: SyncTrigger = SyncTrigger.MANUAL): SyncStatus =
        runBlocking { SyncManager.performSync(context, trigger, progress = true) }

    // ------------------------------------------------------------ 上传

    @Test
    fun upload_whenLocalChanged_hasVisibleProgressNotificationAndShowsToast() {
        val startedAt = System.currentTimeMillis()

        val status = sync()

        assertTrue("第一次同步（本机从未上传过）必须上传，实际：$status", status is SyncStatus.Success)
        assertTrue("应确实上传了内容", (status as SyncStatus.Success).uploaded)

        val client = lastClient!!
        assertEquals("只应有一次 PUT（没有任何读取/条件请求）", 1, client.puts.size)
        assertEquals("内容 A", client.puts[0].second)
        assertTrue("目录存在时不应该多发 MKCOL：${client.mkcols}", client.mkcols.isEmpty())
        // 文件名必须是「前缀 + unix 秒时间戳 + 扩展名」：每次上传一个新文件，历史不会被覆盖
        val name = client.puts[0].first.removePrefix("http://127.0.0.1:1/dav/")
        assertTrue("文件名应形如 note<unix 秒>.txt，实际：$name", Regex("""note\d{10}\.txt""").matches(name))
        val stamp = name.removePrefix("note").removeSuffix(".txt").toLong()
        val nowSec = System.currentTimeMillis() / 1000
        assertTrue("时间戳应是当下（±120s），实际：$stamp", Math.abs(stamp - nowSec) < 120)
        assertEquals("时间戳要落盘，供下次保证文件名单调递增", stamp, SyncSettings.lastUploadedTs(context))

        // 报障点 1：上传很快也必须真的有通知栏进度
        assertTrue(
            "上传进行中进度通知必须可见（否则就是用户看到的「没有通知栏进度」）",
            client.progressVisibleDuringPut
        )
        // 报障点 2：通知至少可见 MIN_PROGRESS_VISIBLE_MS，不会「闪一下就没了」
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(
            "上传结束后进度通知应至少保留 ${SyncNotifier.MIN_PROGRESS_VISIBLE_MS}ms，实际 ${elapsed}ms",
            elapsed >= SyncNotifier.MIN_PROGRESS_VISIBLE_MS - 100
        )
        // 报障点 3：上传成功要有 Toast（即使通知一闪而过也能知道）
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(context.getString(R.string.sync_upload_succeeded), ShadowToast.getTextOfLatestToast())

        // 收尾：进度通知必须被收掉，不留常驻通知
        assertFalse("同步结束后不得留下进度通知", progressNotificationVisible(context))
        // 基线：哈希记录下来，供下次判断「本地有没有变」
        assertEquals(
            SyncEngine.sha256Hex("内容 A".toByteArray()),
            SyncSettings.lastUploadedHash(context)
        )
    }

    @Test
    fun noUpload_whenContentUnchanged_sendsNothingAndIsSilent() {
        SyncSettings.recordSuccess(context, SyncEngine.sha256Hex(content.toByteArray()), 1_735_689_600L)
        val startedAt = System.currentTimeMillis()

        val status = sync()

        assertTrue("内容没变应成功但不上传，实际：$status", status is SyncStatus.Success)
        assertFalse("不应上传", (status as SyncStatus.Success).uploaded)
        assertTrue("本地没变时一个请求都不该发", lastClient!!.puts.isEmpty())
        assertTrue("也不该创建目录", lastClient!!.mkcols.isEmpty())

        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("没有上传就不该占着通知（无需等待可见时长），实际 ${elapsed}ms", elapsed < 1_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("没有上传就不该弹成功提示", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun firstEverSync_uploadsTheLocalContentWithoutReadingTheCloud() {
        // 云端有什么、对不对，本应用一概不管：接口里根本没有读取方法，
        // 这里断言「第一次同步就是一次 PUT，且内容就是本地内容」
        assertNull("前置条件：本机从未上传过", SyncSettings.lastUploadedHash(context))
        content = "本地唯一的真相"

        val status = sync()

        assertTrue(status is SyncStatus.Success && (status as SyncStatus.Success).uploaded)
        assertEquals(1, lastClient!!.puts.size)
        assertEquals("本地唯一的真相", lastClient!!.puts[0].second)
    }

    @Test
    fun everyUploadGoesToANewTimestampedFile_soHistoryIsNeverOverwritten() {
        content = "第一版"
        assertTrue(sync() is SyncStatus.Success)
        val firstUrl = clients[0].puts[0].first

        // 同一秒内立刻再传一次：文件名必须递增，绝不能复用上一个名字（否则会覆盖历史）
        content = "第二版"
        assertTrue(sync() is SyncStatus.Success)
        val secondUrl = clients[1].puts[0].first

        assertTrue("两次上传必须是两个不同的文件：$firstUrl / $secondUrl", firstUrl != secondUrl)
        assertEquals("第二次的正文应是新内容", "第二版", clients[1].puts[0].second)
        val firstStamp = firstUrl.removePrefix("http://127.0.0.1:1/dav/note").removeSuffix(".txt").toLong()
        val secondStamp = secondUrl.removePrefix("http://127.0.0.1:1/dav/note").removeSuffix(".txt").toLong()
        assertEquals("同一秒内也必须递增 1 秒", firstStamp + 1, secondStamp)
    }

    @Test
    fun parentMissing_mkcolThenRetriesTheUploadOnce() {
        installClient(firstPutFailure = WebDavException(WebDavError.PARENT_NOT_FOUND, 409, "no parent"))

        val status = sync()

        assertTrue("先建目录再重试应能成功，实际：$status", status is SyncStatus.Success)
        val client = lastClient!!
        assertEquals("应只补一次 MKCOL", listOf("http://127.0.0.1:1/dav/"), client.mkcols)
        assertEquals("应重试一次 PUT（第一次被 409 挡下）", 2, client.putAttempts)
        assertEquals("成功的 PUT 只有一次", 1, client.puts.size)
        assertEquals("内容 A", client.puts[0].second)
    }

    // ------------------------------------------------------------ 闸门

    @Test
    fun throttled_autoTrigger_doesNotEvenBuildAClient() {
        SyncSettings.setLastAttemptAt(context, System.currentTimeMillis())

        val status = sync(SyncTrigger.CLOSE_EDITOR)

        assertEquals(SyncStatus.Skipped(SkipReason.THROTTLED), status)
        assertEquals("被节流时不得触碰网络", 0, clientBuilds)
    }

    @Test
    fun manualTrigger_bypassesTheThrottle() {
        SyncSettings.setLastAttemptAt(context, System.currentTimeMillis())

        val status = sync(SyncTrigger.MANUAL)

        assertTrue("手动同步不受 30 分钟限制，实际：$status", status is SyncStatus.Success)
        assertEquals(1, lastClient!!.puts.size)
    }

    // ------------------------------------------------------------ 失败路径

    @Test
    fun failure_recordsTheErrorAndLeavesANotificationButNoSuccessToast() {
        SyncManager.clientFactory = { _, _ ->
            clientBuilds++
            object : WebDavClient {
                override fun put(url: String, body: ByteArray) {
                    throw WebDavException(WebDavError.UNAUTHORIZED, 401, "HTTP 401 Unauthorized")
                }

                override fun mkcol(url: String) = Unit
                override fun close() = Unit
            }
        }

        val status = sync()

        assertTrue("应失败，实际：$status", status is SyncStatus.Failed)
        assertEquals(WebDavError.UNAUTHORIZED, (status as SyncStatus.Failed).error)
        assertEquals(SyncEngine.RESULT_FAILED, SyncSettings.lastResult(context))
        assertEquals(WebDavError.UNAUTHORIZED.name, SyncSettings.lastError(context))
        assertNull("失败的改动必须仍被视作「待上传」", SyncSettings.lastUploadedHash(context))
        assertNotNull("失败要留下通知", failureNotificationVisible(context))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("失败不该弹「已上传」提示", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun tlsUntrusted_recordsTheFingerprintForUserConfirmation() {
        SyncManager.clientFactory = { _, onUntrusted ->
            clientBuilds++
            object : WebDavClient {
                override fun put(url: String, body: ByteArray) {
                    onUntrusted("AB:CD:EF")
                    throw WebDavException(WebDavError.TLS_UNTRUSTED, detail = "AB:CD:EF")
                }

                override fun mkcol(url: String) = Unit
                override fun close() = Unit
            }
        }

        val status = sync()

        assertTrue(status is SyncStatus.Failed)
        assertEquals("证书必须由用户确认后才信任", "AB:CD:EF", SyncSettings.pendingTlsPin(context))
        assertNull("未经确认不得写入信任列表", SyncSettings.tlsPin(context))
    }

    // ------------------------------------------------------------ 工具

    private companion object {
        fun notificationManager(context: Context): NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        /**
         * 通知是否处于「已发出」状态。
         *
         * 用标题做匹配而不是 `getNotification(tag, id)`：进度通知由前台服务的
         * `startForeground` 发出（没有 tag），用无 tag 查询在 Robolectric 上不可靠；
         * 而两条通知的标题在应用内唯一，足够精确。
         */
        fun notificationWithTitle(context: Context, title: String): Any? =
            shadowOf(notificationManager(context)).allNotifications.firstOrNull {
                it.extras?.getString(android.app.Notification.EXTRA_TITLE) == title
            }

        fun progressNotificationVisible(context: Context): Boolean =
            notificationWithTitle(context, context.getString(R.string.sync_notif_progress_title)) != null

        fun failureNotificationVisible(context: Context): Any? =
            notificationWithTitle(context, context.getString(R.string.sync_notif_failed_title))
    }
}
