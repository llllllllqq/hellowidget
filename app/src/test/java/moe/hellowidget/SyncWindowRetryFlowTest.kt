package moe.hellowidget

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.OkHttpWebDavClient
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import moe.hellowidget.sync.SyncWindowRetry
import moe.hellowidget.sync.WebDavClient
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * v8.0.3：前台窗口内的有限次快速重试 —— 编排层的端到端（Robolectric）测试。
 *
 * 这个文件只回答一个问题：**退出瞬间那次网络抖动，会不会被窗口本身吃掉**。
 * 旧行为：一次 PUT 失败 → 当场放弃 → 65 秒后由系统兜底任务补传（用户看到"没传上去"）。
 * 新行为：只要失败得早且是暂时性的，就在**已经亮着通知**的前台窗口里退避重试最多
 * [SyncWindowRetry.MAX_RETRIES] 次；仍失败才落回兜底任务（原有行为一字不改）。
 *
 * 三条必须同时成立的边界：
 *  - 凭据/证书这类"需要人先解决"的失败**一次都不重试**；
 *  - 慢失败（本次尝试耗掉 20 秒以上）**不重试** —— 把时间让给兜底任务，也不逼近服务上限；
 *  - **系统兜底任务路径不做窗口内重试**（它没有前台服务、不显示通知，且自带系统退避与预算）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncWindowRetryFlowTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    /**
     * 按脚本失败的假客户端：`failures` 里每个元素表示"这一趟 PUT 抛出的异常"，用完即成功。
     * [onPut] 让用例能在"请求进行中"推进假时钟，模拟一次很慢才失败的尝试。
     */
    private class ScriptedClient(private val failures: MutableList<WebDavException>) : WebDavClient {
        var putAttempts = 0
        var onPut: (() -> Unit)? = null

        override fun put(url: String, body: ByteArray) {
            putAttempts++
            onPut?.invoke()
            if (failures.isNotEmpty()) throw failures.removeAt(0)
        }

        override fun mkcol(url: String) = Unit
        override fun close() = Unit
    }

    private lateinit var client: ScriptedClient

    /** 每一次阶段文案回调（null = 恢复默认文案） */
    private val phases = mutableListOf<String?>()

    /** 每次回调之后，通知栏上那条进度通知的正文（证明用户真的看得见"正在重试"） */
    private val notificationTexts = mutableListOf<String?>()

    private val content = "窗口重试的内容"

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
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
        SyncManager.contentReader = { content }
        SyncManager.elapsedClock = System::currentTimeMillis
        phases.clear()
        notificationTexts.clear()
    }

    @After
    fun tearDown() {
        SyncManager.contentReader = { ContentStore.read() }
        SyncManager.clientFactory = { config, onUntrusted -> OkHttpWebDavClient(config, onUntrusted) }
        SyncManager.elapsedClock = System::currentTimeMillis
        SyncSettings.resetRuntimeState(context)
    }

    private fun install(vararg failures: WebDavException) {
        client = ScriptedClient(failures.toMutableList())
        SyncManager.clientFactory = { _, _ -> client }
    }

    /**
     * 前台服务路径：有可见通知 ⇒ 允许窗口内重试，阶段文案交回调用方
     * （与 [SyncService] 里的 lambda 完全同款：更新的是同一条前台通知）。
     */
    private fun foregroundSync(): SyncStatus = runBlocking {
        SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) { text ->
            SyncNotifier.postProgress(context, text)
            phases += text
            notificationTexts += progressNotificationText()
        }
    }

    /** 系统兜底任务路径：没有前台窗口（[SyncRetry.runOnce] 就是这么调的） */
    private fun fallbackJobSync(): SyncStatus = runBlocking {
        SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR)
    }

    private fun retryText(attempt: Int): String =
        context.getString(R.string.sync_notif_retry_text, attempt, SyncWindowRetry.MAX_RETRIES)

    // ------------------------------------------------------------ 抖动被窗口吃掉

    @Test
    fun transientFailure_isRetriedInsideTheWindow_andTheUserEventuallySeesSuccess() {
        install(WebDavException(WebDavError.NETWORK, detail = "连接被拒绝"))

        val status = foregroundSync()

        assertTrue("第一次失败后应在窗口内重试，实际：$status", status is SyncStatus.Success)
        assertTrue("重试成功后要真的算上传成功", (status as SyncStatus.Success).uploaded)
        assertEquals("一次失败 + 一次成功 = 两次 PUT", 2, client.putAttempts)
        assertEquals(
            "阶段文案必须是「正在重试（1/2）」然后恢复默认",
            listOf(retryText(1), null),
            phases
        )
        assertEquals(
            "用户真的会在通知栏看到重试文案，而不是只有日志里有",
            listOf(retryText(1), context.getString(R.string.sync_notif_progress_text)),
            notificationTexts
        )
        assertEquals("重试成功就是一次正常成功：终态与哈希都要落盘", SyncEngine.RESULT_SUCCESS, SyncSettings.lastResult(context))
        assertEquals(
            SyncEngine.sha256Hex(content.toByteArray()),
            SyncSettings.lastUploadedHash(context)
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "中间那次失败**不该**弹失败提示：最终是成功的",
            context.getString(R.string.sync_upload_succeeded),
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun retriesExhausted_thenItFallsBackToTheJobAndTellsTheUserExactlyOnce() {
        // 三次都失败 = 第一次 + 窗口内两次（1 秒、3 秒退避）
        install(
            WebDavException(WebDavError.NETWORK, detail = "第一次"),
            WebDavException(WebDavError.NETWORK, detail = "第二次"),
            WebDavException(WebDavError.NETWORK, detail = "第三次")
        )

        val status = foregroundSync()

        assertTrue("三次都失败就必须是失败终态，实际：$status", status is SyncStatus.Failed)
        assertEquals(WebDavError.NETWORK, (status as SyncStatus.Failed).error)
        assertEquals("第一次 + 窗口内 ${SyncWindowRetry.MAX_RETRIES} 次", 3, client.putAttempts)
        assertEquals(
            "两次重试各更新一次文案；没有成功，所以不该出现「恢复默认」",
            listOf(retryText(1), retryText(2)),
            phases
        )
        assertEquals(
            "通知的收尾由前台服务负责（stopForeground + stopSelf），编排层只更新它的正文",
            retryText(2),
            progressNotificationText()
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "失败提示只允许出现一次（中间那两次失败不该各弹一条）",
            1,
            ShadowToast.shownToastCount()
        )
        assertEquals("失败要落盘，供设置页显示", WebDavError.NETWORK.name, SyncSettings.lastError(context))
    }

    // ------------------------------------------------------------ 三条边界

    @Test
    fun credentialsFailure_isNotRetriedEvenOnce() {
        install(WebDavException(WebDavError.UNAUTHORIZED, 401, "HTTP 401 Unauthorized"))

        val status = foregroundSync()

        assertTrue(status is SyncStatus.Failed)
        assertEquals(WebDavError.UNAUTHORIZED, (status as SyncStatus.Failed).error)
        assertEquals("口令错了，重试一万次也没用：只允许发一次请求", 1, client.putAttempts)
        assertTrue("不该出现任何重试文案，实际：$phases", phases.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("但失败提示必须照旧给用户", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun slowFailure_isLeftToTheFallbackJob() {
        install(WebDavException(WebDavError.TIMEOUT, detail = "读超时"))
        // 模拟"这一次尝试本身耗了 25 秒"（超过窗口内重试的时限）
        var fakeNow = 1_000_000L
        SyncManager.elapsedClock = { fakeNow }
        client.onPut = { fakeNow += 25_000L }

        val status = foregroundSync()

        assertTrue(status is SyncStatus.Failed)
        assertEquals(
            "慢失败说明链路确实慢或不通：不占用前台窗口，交给兜底任务",
            1,
            client.putAttempts
        )
        assertTrue("不该出现重试文案，实际：$phases", phases.isEmpty())
    }

    @Test
    fun fallbackJobPath_neverRetriesInsideItsOwnRun() {
        install(WebDavException(WebDavError.NETWORK, detail = "连接被拒绝"))

        val status = fallbackJobSync()

        assertTrue(status is SyncStatus.Failed)
        assertEquals(
            "系统兜底任务有自己的指数退避与重试预算，且不显示通知：不该在这里放大请求次数",
            1,
            client.putAttempts
        )
        assertTrue("兜底任务路径不做窗口内重试，因此也没有阶段文案", phases.isEmpty())
    }

    // ------------------------------------------------------------ 工具

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 进度通知当前的正文（按标题唯一定位，见 SyncManagerTest 的同款说明） */
    private fun progressNotificationText(): String? =
        shadowOf(notificationManager()).allNotifications
            .firstOrNull {
                it.extras?.getString(Notification.EXTRA_TITLE) ==
                    context.getString(R.string.sync_notif_progress_title)
            }
            ?.extras
            ?.getString(Notification.EXTRA_TEXT)
}
