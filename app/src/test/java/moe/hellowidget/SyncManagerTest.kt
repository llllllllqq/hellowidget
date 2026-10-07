package moe.hellowidget

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncErrorText
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncRetry
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
        // setPersisted(true) 的前置权限（v7.9 的系统重试任务）：JobInfo.Builder.build() 会检查它
        shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_BOOT_COMPLETED)
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
            moe.hellowidget.sync.OkHttpWebDavClient(config, onUntrusted)
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
        assertTrue("文件名应形如 note<unix 毫秒>.txt，实际：$name", Regex("""note\d{13}\.txt""").matches(name))
        val stamp = name.removePrefix("note").removeSuffix(".txt").toLong()
        val nowMs = System.currentTimeMillis()
        assertTrue("时间戳应是当下（±120s），实际：$stamp", Math.abs(stamp - nowMs) < 120_000)
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
    fun uploadSuccess_cancelsTheScheduledSystemRetry() {
        // 「保存」时排入的系统重试任务（v7.9 的自愈通道）
        SyncRetry.schedule(context)
        assertTrue("前置条件：任务已排队", SyncRetry.isScheduled(context))

        val status = sync()

        assertTrue("第一次同步（本机从未上传过）必须上传，实际：$status", status is SyncStatus.Success)
        assertFalse(
            "上传成功后必须撤销系统重试任务，否则会留下一个永远醒来的后台任务",
            SyncRetry.isScheduled(context)
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
        // 唯一必须成立的保证是**单调递增**（否则新上传会盖掉上一份历史）。
        // 恰好同秒时严格 +1（这条边界由 SyncEngineTest.nextUploadTimestamp_sameSecondStillAdvances 固定）；
        // 这里不能用 == firstStamp + 1：两次上传本身可能跨过秒边界，那是同样正确的行为。
        assertTrue("时间戳必须递增：$firstStamp -> $secondStamp", secondStamp > firstStamp)
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

    // ------------------------------------------------------------ 没有节流（v8.1.0）

    @Test
    fun closeEditorTrigger_isNeverThrottled_twiceInARowMeansTwoUploads() {
        assertTrue(sync(SyncTrigger.CLOSE_EDITOR) is SyncStatus.Success)
        // 注意：每次同步都会**新建**一个客户端（clientFactory 每次调用都造一个 FakeClient），
        // 所以"有没有 PUT"只能按 clients[i] 分别看，不能只看最后那个。
        assertEquals("第一次：真的传上去", 1, clients[0].puts.size)
        assertTrue("真的发过请求 ⇒ 访问锚点必须被更新", SyncSettings.lastServerContactAt(context) > 1L)

        // 同一秒内改内容再保存一次。旧实现这里会返回 Skipped(THROTTLED)（1 分钟闸门），
        // 正是真机日志里「退出后 156 秒才上传」的成因。
        content = "内容 B"
        val second = sync(SyncTrigger.CLOSE_EDITOR)

        assertTrue("自动触发不再有任何节流，实际：$second", second is SyncStatus.Success)
        assertTrue((second as SyncStatus.Success).uploaded)
        assertEquals("两次保存 = 两次 PUT", 2, clients.sumOf { it.puts.size })
        assertEquals("内容 B", clients[1].puts[0].second)
    }

    /**
     * 没有节流之后，**防重复上传靠的是内容哈希**，不是闸门 —— 这条用例把两件事一起钉住：
     * 第二次保存仍然"尝试"（不再被静默跳过），但内容没变时一个请求都不发，
     * 而且**不更新访问锚点**（旧实现会把闸门锚点写下去，于是把下一次真实上传挡在门外 60 秒）。
     */
    @Test
    fun unchangedContent_stillTriesButSendsNothing_andDoesNotTouchTheContactAnchor() {
        assertTrue(sync(SyncTrigger.CLOSE_EDITOR) is SyncStatus.Success)
        assertEquals(1, clients[0].puts.size)
        // 哨兵值：1970 年，任何真实时间戳都不可能等于它 —— 只有"真的碰了云端"才会覆盖它
        SyncSettings.setLastServerContactAt(context, 1L)

        val again = sync(SyncTrigger.CLOSE_EDITOR)

        assertTrue("没变化也是一次成功的同步，实际：$again", again is SyncStatus.Success)
        assertFalse("没有改动 ⇒ 不该真的上传", (again as SyncStatus.Success).uploaded)
        assertEquals("第二次那个客户端一个 PUT 都没有", 0, clients[1].puts.size)
        assertEquals("总共只允许一次 PUT", 1, clients.sumOf { it.puts.size })
        assertEquals(
            "零请求的空跑不该更新访问锚点",
            1L,
            SyncSettings.lastServerContactAt(context)
        )
    }

    // ------------------------------------------------------------ 失败路径

    /**
     * v7.8：失败反馈改成一条 Toast（与成功提示对称），**不再往通知栏留东西**。
     * 完整原因仍然写进 [SyncSettings.lastResult] / `lastError`，同步设置页的状态行照常显示。
     */
    @Test
    fun failure_recordsTheErrorAndShowsAFailureToastButLeavesNoNotification() {
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

        shadowOf(Looper.getMainLooper()).idle()
        // 断言完整文案（含原因）而不是硬编码中文：Robolectric 默认用 en 资源，
        // 与真机语言无关的写法才是稳定的 —— 文案本身由 values/ 与 values-en/ 同时提供。
        val localized = SyncErrorText.of(context, WebDavError.UNAUTHORIZED)
        val expectedToast = context.getString(
            R.string.sync_failed_toast,
            "$localized（HTTP 401 Unauthorized）"
        )
        assertEquals(
            "失败必须弹一条带原因的 Toast，让用户知道这次没传上去",
            expectedToast,
            ShadowToast.getTextOfLatestToast()
        )
        assertEquals("失败之后不得留下任何通知", 0, notificationCount(context))
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
         * 而通知的标题在应用内唯一，足够精确。
         */
        fun notificationWithTitle(context: Context, title: String): Any? =
            shadowOf(notificationManager(context)).allNotifications.firstOrNull {
                it.extras?.getString(android.app.Notification.EXTRA_TITLE) == title
            }

        fun progressNotificationVisible(context: Context): Boolean =
            notificationWithTitle(context, context.getString(R.string.sync_notif_progress_title)) != null

        /** 当前还挂在通知栏上的通知数量（v7.8：失败之后必须是 0） */
        fun notificationCount(context: Context): Int =
            shadowOf(notificationManager(context)).allNotifications.size
    }
}
