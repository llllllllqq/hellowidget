package moe.hellowidget

import android.app.Application
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.OkHttpWebDavClient
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import moe.hellowidget.sync.WebDavClient
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * v8.1.0：删掉 1 分钟闸门之后，**单飞（同一时刻只有一个上传）**与**合并（突发保存不排队成 N 趟）**
 * 就成了新的地基 —— 这个文件专门钉住它们，以及"合并绝不能丢版本"。
 *
 * ## 为什么这四条值得单独一个文件
 * 删闸门把"防重复上传"的全部重量压在**内容哈希短路**上；而"每次保存都立刻尝试"意味着
 * 请求会比以前密得多（真机日志里 90 秒内就有 6 次保存）。于是两条新的失败形态必须被挡住：
 *  - **并发 PUT**：同一份内容被两个协程同时推上去（文件名由时间戳决定，会互相覆盖历史）；
 *  - **合并把最新内容吃掉**：上传进行中用户又存了一次，那一次内容必须由尾部那一趟补上去。
 *
 * 这里的"并发"是真的并发：请求跑在 [Dispatchers.Default] 上，假客户端在 PUT 里阻塞，
 * 所以如果互斥锁失效，`maxInFlight` 一定会 > 1（而不是"单线程碰巧不重叠"）。
 * 提示（Toast）由 [moe.hellowidget.sync.SyncNotifier] 自己 post 到主 Looper，因此
 * 在后台线程上跑同步不会碰到 Looper 的限制。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncManagerUploadTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    /**
     * 会记录"同时在飞几个 PUT"的假客户端。[onPut] 与 [putDelayMs] 让用例能把一次上传
     * **卡在中途**，从而稳定复现"上传进行中又来了新请求"的时序。
     */
    private class RecordingClient : WebDavClient {
        val puts = mutableListOf<String>()
        var putAttempts = 0
        var onPut: (() -> Unit)? = null
        var putDelayMs = 0L
        var failure: WebDavException? = null

        private val inFlight = AtomicInteger(0)
        private val peak = AtomicInteger(0)

        /** 历史上同时在飞的最大 PUT 数 —— 必须恒为 1 */
        val maxInFlight: Int get() = peak.get()

        override fun put(url: String, body: ByteArray) {
            putAttempts++
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            try {
                onPut?.invoke()
                if (putDelayMs > 0) Thread.sleep(putDelayMs)
                failure?.let { throw it }
                synchronized(this) { puts += String(body, Charsets.UTF_8) }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun mkcol(url: String) = Unit
        override fun close() = Unit
    }

    private lateinit var client: RecordingClient

    /** 当前磁盘上的内容；多线程下由 [Volatile] 保证可见性（顺序由 latch 固定） */
    @Volatile
    private var content = "内容"

    @Before
    fun setUp() {
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
        client = RecordingClient()
        SyncManager.clientFactory = { _, _ -> client }
        SyncManager.contentReader = { content }
    }

    @After
    fun tearDown() {
        SyncManager.contentReader = { ContentStore.read() }
        SyncManager.clientFactory = { config, onUntrusted -> OkHttpWebDavClient(config, onUntrusted) }
        SyncSettings.resetRuntimeState(context)
    }

    private fun request(trigger: SyncTrigger = SyncTrigger.CLOSE_EDITOR): SyncStatus = runBlocking {
        SyncManager.performSync(context, trigger)
    }

    // ------------------------------------------------------------ 单飞

    @Test
    fun manyRequestsAtOnce_neverRunTwoUploadsInParallel() {
        content = "并发测试内容"
        client.putDelayMs = 40 // 把并发窗口撑开：锁若失效，这里必然被观测到
        val coalescedBefore = SyncManager.coalescedRequests

        val results = runBlocking(Dispatchers.Default) {
            (1..8).map { async { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) } }.awaitAll()
        }

        assertEquals("8 个请求都必须拿到终态", 8, results.size)
        assertTrue("每个请求都必须是成功终态：$results", results.all { it is SyncStatus.Success })
        assertEquals(
            "同一条内容只允许写一次（其余请求要么并入、要么被哈希短路）",
            1,
            client.putAttempts
        )
        assertEquals("任何时刻都不得有两个 PUT 在飞", 1, client.maxInFlight)
        assertTrue(
            "同一批请求里必须真的发生合并（实际并入 ${SyncManager.coalescedRequests - coalescedBefore} 个）；" +
                "阈值为 3 而不是 7，是因为 CI 的 Default 线程池可能只有 2~4 条线程，" +
                "后到的请求会等前一趟跑完才登记 —— 那正是「合并粒度是一趟」的另一条路径",
            SyncManager.coalescedRequests - coalescedBefore >= 3
        )
    }

    // ------------------------------------------------------------ 合并不得丢版本

    @Test
    fun contentSavedWhileAnUploadIsInFlight_isUploadedByTheTrailingRun() {
        content = "v1"
        val inPut = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.onPut = {
            inPut.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        runBlocking(Dispatchers.Default) {
            val first = async { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }
            assertTrue("第一次 PUT 应已开始", inPut.await(5, TimeUnit.SECONDS))

            // 上传还没结束时，用户又存了一次（内容变了）
            content = "v2"
            val second = async { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }
            release.countDown()

            val statuses = listOf(first, second).awaitAll()
            assertTrue("两次都必须有终态：$statuses", statuses.all { it is SyncStatus.Success })
        }

        assertEquals(
            "v2 绝不能因为合并而丢失：必须是 v1 然后 v2",
            listOf("v1", "v2"),
            client.puts
        )
        assertEquals("这两批保存只允许两趟 PUT（在飞的一趟 + 尾部一趟）", 2, client.putAttempts)
        assertEquals("串行执行：任何时刻都只有一个 PUT 在飞", 1, client.maxInFlight)
    }

    // ------------------------------------------------------------ 用户按下的按钮不合并

    @Test
    fun manualTrigger_neverMergesIntoARunInFlight() {
        content = "手动内容"
        val inPut = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.onPut = {
            inPut.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val coalescedBefore = SyncManager.coalescedRequests

        runBlocking(Dispatchers.Default) {
            val auto = async { SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR) }
            assertTrue("自动那条已进入 PUT", inPut.await(5, TimeUnit.SECONDS))

            val manual = async { SyncManager.performSync(context, SyncTrigger.MANUAL) }
            release.countDown()

            val statuses = listOf(auto, manual).awaitAll()
            assertTrue("两次都必须有终态：$statuses", statuses.all { it is SyncStatus.Success })
        }

        assertEquals(
            "用户按下的按钮必须自己走一趟（当下意图不能被合并掉）",
            coalescedBefore,
            SyncManager.coalescedRequests
        )
        assertEquals("内容已被上一趟传上去，手动这趟零请求", 1, client.putAttempts)
        assertEquals(1, client.maxInFlight)
    }

    // ------------------------------------------------------------ 失败锚点

    @Test
    fun failure_updatesTheContactAnchor_soTheStatusPageCanTellTheTruth() {
        content = "失败的内容"
        client.failure = WebDavException(WebDavError.NETWORK, detail = "断网")

        val status = request()

        assertTrue("断网必须是失败终态，实际：$status", status is SyncStatus.Failed)
        assertEquals(WebDavError.NETWORK, (status as SyncStatus.Failed).error)
        assertTrue(
            "失败也意味着「尝试访问过云端」：锚点必须被更新（否则状态页会显示成从未访问）",
            SyncSettings.lastServerContactAt(context) > 1L
        )
    }
}
