package moe.hellowidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SkipReason
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncManager
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * v7.9「保存后没能上传成功 → 交给系统重试」的单元测试。
 *
 * 这一层钉住三件事（都直接对应 v7.8.0 那个"只能清后台"的报障）：
 *  1. 任务本身排得对：有网络约束、退避、持久化（进程被杀/重启后还在），且 id 固定不堆积；
 *  2. 只对"看起来是暂时性"的失败重试 —— 凭据/证书这类需要人介入的失败不自动重试，
 *     否则就变成无意义的唤醒风暴；
 *  3. 同步成功（含"本地没改动"）之后任务必须被撤销，不能留下永不结束的后台唤醒源。
 *
 * `@SuppressLint("NewApi")`：本类整体跑在 [Config] 指定的 `sdk = [34]` 上，
 * 因此 API 31 的 `JobInfo.isExpedited` 在这里恒可用；lint 读不到 Robolectric 的 `@Config`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressLint("NewApi")
class SyncRetryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val scheduler: JobScheduler
        get() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    private var content = "内容 A"

    private var putFailure: Throwable? = null

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // setPersisted(true) 的前置权限：JobInfo.Builder.build() 会检查它
        shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        enableSync()
        SyncManager.contentReader = { content }
        SyncManager.clientFactory = { _, _ -> FakeClient() }
    }

    @After
    fun tearDown() {
        SyncManager.contentReader = { moe.hellowidget.ContentStore.read() }
        SyncManager.clientFactory = { config, onUntrusted ->
            moe.hellowidget.sync.OkHttpWebDavClient(config, onUntrusted)
        }
        SyncRetry.cancel(context)
        SyncSettings.resetRuntimeState(context)
    }

    private fun enableSync() {
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
    }

    private fun pendingRetryJob(): JobInfo? =
        scheduler.allPendingJobs.firstOrNull { it.id == SyncRetry.JOB_ID }

    private inner class FakeClient : WebDavClient {
        override fun put(url: String, body: ByteArray) {
            putFailure?.let { throw it }
        }

        override fun mkcol(url: String) = Unit
        override fun close() = Unit
    }

    // ------------------------------------------------------------ 任务本身

    @Test
    fun schedule_enqueuesOnePersistedJobWithNetworkConstraintAndExponentialBackoff() {
        assertTrue("系统必须收下这个任务（返回值要如实反映）", SyncRetry.schedule(context))

        val job = pendingRetryJob()
        assertNotNull("保存后必须留下一个系统重试任务", job)
        assertEquals("任务必须只在有网络时执行", JobInfo.NETWORK_TYPE_ANY, job!!.networkType)
        assertTrue("进程被杀/设备重启后任务必须还在", job.isPersisted)
        assertEquals(
            "必须按指数退避，避免失败后形成重试风暴",
            JobInfo.BACKOFF_POLICY_EXPONENTIAL,
            job.backoffPolicy
        )
        assertEquals(
            "首次重试延迟是 30 秒：不再有闸门要躲，只需避开前台窗口的最坏占用（约 24 秒）",
            moe.hellowidget.sync.SyncRetry.FIRST_RETRY_DELAY_MS,
            job.minLatencyMillis
        )
        // v8.0.1：记录一条被 CI 抓到的平台事实，别再踩 ——
        // 给这条任务加 setExpedited(true) 会直接抛
        //   IllegalArgumentException: An expedited job cannot have a time delay
        //     at android.app.job.JobInfo.enforceValidity(JobInfo.java:2279)
        // 即「加急 ⇒ 不允许延迟 ⇒ 必须立刻执行」，而立刻执行会在应用仍处前台时
        // 抢走 SyncManager 的互斥锁，把上传从"进度条 + 成功 Toast"这条唯一可见路径上夺走。
        // 所以这里**故意**保持常规 + 延迟；想拿加急的收益需要独立一轮改成两个任务 id。
        // （sdk 34 的 JobInfo.isExpedited 是 API 31，本类 @Config(sdk = [34]) 恒可用）
        assertFalse("兜底任务刻意不使用加急", job.isExpedited)
    }

    @Test
    fun schedule_twice_keepsASinglePendingJob() {
        SyncRetry.schedule(context)
        SyncRetry.schedule(context)

        assertEquals(
            "同一个 id 只会替换，不能堆积成一堆待执行任务",
            1,
            scheduler.allPendingJobs.count { it.id == SyncRetry.JOB_ID }
        )
    }

    /**
     * v8.0.1（D1）：已有待执行任务时**不许重排**。
     *
     * 官方 `schedule()` 会用新 JobInfo **替换**同 id 的任务，而 `setMinimumLatency` 的
     * "不早于"时刻是**从排入那一刻**算起的。旧实现每次保存都重排 ⇒ 截止时间被一次次推后 ⇒
     * 只要保存间隔小于该延迟，"兜底"就永远不会执行（恰好在最需要它时失效）。
     *
     * 断言用的是"待执行任务还是**同一个对象**"：一旦被重排，Robolectric 里存的就是新的 JobInfo。
     */
    @Test
    fun schedule_doesNotReplaceAnAlreadyPendingJob() {
        assertTrue(SyncRetry.schedule(context))
        val first = pendingRetryJob()
        assertNotNull("前置条件：第一次必须排上", first)

        SyncSettings.setRetryAttempts(context, 3) // 模拟"已经失败过 3 次"
        assertTrue("已有待执行任务时也应如实返回 true", SyncRetry.schedule(context))

        assertSame(
            "已排着的任务不能被替换 —— 替换会把兜底任务的截止时间往后推",
            first,
            pendingRetryJob()
        )
        assertEquals("用户又保存了一次，重试预算必须重新给满", 0, SyncSettings.retryAttempts(context))
        assertEquals(1, scheduler.allPendingJobs.count { it.id == SyncRetry.JOB_ID })
    }

    @Test
    fun schedule_isIgnoredWhenSyncIsNotConfigured() {
        SyncSettings.setEnabled(context, false)

        SyncRetry.schedule(context)

        assertFalse("未启用同步时不该排任务", SyncRetry.isScheduled(context))
    }

    @Test
    fun cancel_removesTheScheduledJob() {
        SyncRetry.schedule(context)
        assertTrue(SyncRetry.isScheduled(context))

        SyncRetry.cancel(context)

        assertFalse(SyncRetry.isScheduled(context))
    }

    /**
     * 防「自取消 → onStopJob 返回"请重排" → 又跑一次」这个死循环：
     * 任务自己执行期间，成功后的撤销必须被忽略（那一趟由 `jobFinished(params, false)` 收尾）。
     */
    @Test
    fun cancel_isIgnoredWhileTheRetryItselfIsRunning() = runBlocking {
        SyncRetry.schedule(context)
        SyncManager.contentReader = { awaitCancellation() } // 让这一趟重试停在半路

        val job = launch(Dispatchers.IO) { SyncRetry.runOnce(context) }
        val entered = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < entered && !SyncRetry.running) delay(5)
        assertTrue("重试任务应进入执行中状态", SyncRetry.running)

        SyncRetry.cancel(context)

        assertTrue(
            "执行期间的撤销必须被忽略，否则会把正在跑的任务重排回来形成死循环",
            SyncRetry.isScheduled(context)
        )
        job.cancel()
        job.join()
        assertFalse("收尾后必须回到非执行中状态", SyncRetry.running)
    }

    // ------------------------------------------------------------ 重试判定（纯函数）

    @Test
    fun shouldReschedule_retriesOnlyTransientFailures() {
        val transient = listOf(
            WebDavError.NETWORK,
            WebDavError.TIMEOUT,
            WebDavError.IO,
            WebDavError.SERVER_ERROR,
            WebDavError.NOT_FOUND,
            WebDavError.PARENT_NOT_FOUND,
            WebDavError.LOCKED,
            WebDavError.INSUFFICIENT_STORAGE
        )
        WebDavError.entries.forEach { error ->
            val status = SyncStatus.Failed(System.currentTimeMillis(), error, "d")
            assertEquals(
                "错误 $error 的重试判定不符",
                error in transient,
                SyncRetry.shouldReschedule(status)
            )
        }
    }

    @Test
    fun shouldReschedule_stopsAfterSuccess_andOnlyRetriesFailures() {
        assertFalse(
            "上传成功之后不该再唤醒进程",
            SyncRetry.shouldReschedule(SyncStatus.Success(System.currentTimeMillis(), uploaded = true))
        )
        assertFalse(
            "本地没有改动（一个请求都没发）同样算完成",
            SyncRetry.shouldReschedule(SyncStatus.Success(System.currentTimeMillis(), uploaded = false))
        )
        assertFalse(
            "同步被用户关掉后不必再试",
            SyncRetry.shouldReschedule(SyncStatus.Skipped(SkipReason.NOT_ENABLED))
        )
        assertFalse(
            "配置不完整时再试也没用",
            SyncRetry.shouldReschedule(SyncStatus.Skipped(SkipReason.NOT_CONFIGURED))
        )
        // v8.1.0：闸门删除后不再有"被节流跳过"这一种结果，因此只剩"失败（且暂时性）才重试"
        assertTrue(
            "暂时性失败必须重试",
            SyncRetry.shouldReschedule(
                SyncStatus.Failed(System.currentTimeMillis(), WebDavError.NETWORK, "断网")
            )
        )
        assertFalse(
            "凭据错误这类需要人介入的失败不重试",
            SyncRetry.shouldReschedule(
                SyncStatus.Failed(System.currentTimeMillis(), WebDavError.UNAUTHORIZED, "401")
            )
        )
    }

    // ------------------------------------------------------------ 任务真正执行的那一次同步

    @Test
    fun runOnce_afterASuccessfulUpload_asksForNoFurtherRetry() = runBlocking {
        val again = SyncRetry.runOnce(context)

        assertFalse("上传成功后任务应当结束", again)
        assertEquals(
            "内容必须真的传上去了（哈希落盘，设置页据此显示「上次成功上传」）",
            moe.hellowidget.sync.SyncEngine.sha256Hex(content.toByteArray()),
            SyncSettings.lastUploadedHash(context)
        )
    }

    @Test
    fun runOnce_whenNothingChanged_isAlsoFinished() = runBlocking {
        SyncSettings.recordSuccess(
            context,
            moe.hellowidget.sync.SyncEngine.sha256Hex(content.toByteArray()),
            1_735_689_600L
        )

        val again = SyncRetry.runOnce(context)

        assertFalse("没有改动时不该反复唤醒进程", again)
    }

    @Test
    fun runOnce_afterATransientFailure_asksForAnotherRetry() = runBlocking {
        putFailure = WebDavException(WebDavError.TIMEOUT)

        val again = SyncRetry.runOnce(context)

        assertTrue("超时属于暂时性失败，应当再试", again)
    }

    @Test
    fun runOnce_afterAPermanentFailure_stopsRetrying() = runBlocking {
        putFailure = WebDavException(WebDavError.UNAUTHORIZED, 401)

        val again = SyncRetry.runOnce(context)

        assertFalse("凭据错误自动重试一万次也没用，必须停下等人处理", again)
    }

    @Test
    fun runOnce_whenSyncWasDisabledMeanwhile_stopsWithoutTouchingTheServer() = runBlocking {
        SyncSettings.setEnabled(context, false)

        val again = SyncRetry.runOnce(context)

        assertFalse(again)
    }

    // ------------------------------------------------------------ 重试预算（不做长期后台驻留）

    /**
     * 必须有上限：指数的系统上限是 5 小时，没有上限时"永远传不上去的内容"
     * 会让系统永远每隔几小时唤醒一次进程。
     */
    @Test
    fun runOnce_stopsAfterTheAttemptBudget_soTheProcessIsNotWokenForever() = runBlocking {
        putFailure = WebDavException(WebDavError.TIMEOUT)

        repeat(SyncRetry.MAX_ATTEMPTS - 1) { index ->
            assertTrue("第 ${index + 1} 次仍在预算内，应当继续重试", SyncRetry.runOnce(context))
        }
        assertFalse("用满预算必须停下", SyncRetry.runOnce(context))
        assertFalse("停下之后即使系统再拉起也不该继续", SyncRetry.runOnce(context))
        assertEquals(
            "停止时的计数就是上限值",
            SyncRetry.MAX_ATTEMPTS,
            SyncSettings.retryAttempts(context)
        )
    }

    @Test
    fun savingAgain_givesAFreshRetryBudget() = runBlocking {
        putFailure = WebDavException(WebDavError.TIMEOUT)
        repeat(SyncRetry.MAX_ATTEMPTS) { SyncRetry.runOnce(context) }
        assertFalse("预算已用完", SyncRetry.runOnce(context))

        SyncRetry.schedule(context) // 用户又保存了一次

        assertTrue("再次保存必须重新给足预算（这是用户自救的路径）", SyncRetry.runOnce(context))
    }

    @Test
    fun successfulRetry_resetsTheAttemptBudget() = runBlocking {
        putFailure = WebDavException(WebDavError.TIMEOUT)
        assertTrue(SyncRetry.runOnce(context))
        assertEquals(1, SyncSettings.retryAttempts(context))

        putFailure = null
        // v8.1.0：没有任何节流，直接再来一次即可
        // （旧版必须先把 lastAttemptAt 拨回 2 分钟前，否则这次会被 1 分钟闸门静默跳过）
        assertFalse("成功后不该再重试", SyncRetry.runOnce(context))
        assertEquals("成功后预算归零", 0, SyncSettings.retryAttempts(context))
    }

    @Test
    fun resetRuntimeState_clearsTheRetryBudget() {
        SyncSettings.setRetryAttempts(context, 3)

        SyncSettings.resetRuntimeState(context)

        assertEquals(0, SyncSettings.retryAttempts(context))
    }

    // ------------------------------------------------------------ 声明层面

    @Test
    fun retryJobService_isDeclaredUnexportedAndProtectedByBindJobService() {
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, SyncRetryJobService::class.java),
            PackageManager.GET_META_DATA
        )

        assertNotNull("SyncRetryJobService 必须在 Manifest 里声明，否则系统无法拉起它", info)
        // 用字面量而不是 Manifest.permission 常量：BIND_JOB_SERVICE 是 @SystemApi，
        // 公共 SDK（单测编译用的就是它）里没有这个常量
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        assertFalse("只允许系统绑定，不能导出", info.exported)
    }

    /** 合并后的 Manifest 里声明了哪些权限（比 checkPermission 更直接：后者可能被测试自己授予） */
    private fun requestedPermissions(): List<String> =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

    @Test
    fun persistedJobPermission_isDeclaredInTheManifest() {
        assertTrue(
            "setPersisted(true) 需要 RECEIVE_BOOT_COMPLETED（见 AndroidManifest），" +
                "实际声明：${requestedPermissions()}",
            requestedPermissions().contains(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        )
    }

    /**
     * v7.9 真机日志抓到的坑：带连通性约束的任务要求调用方持有 `ACCESS_NETWORK_STATE`，
     * 否则 `JobScheduler.schedule()` 抛 `SecurityException`（"required for jobs with a
     * connectivity constraint"）—— 而它只会被 [SyncRetry.schedule] 的兜底 catch 吞掉，
     * 表现为「自愈通道静默失效」。这条用例把它钉在单测里。
     */
    @Test
    fun connectivityPermission_isDeclaredOrTheRetryJobWouldBeSilentlyRejected() {
        assertTrue(
            "JobScheduler 的连通性约束要求 ACCESS_NETWORK_STATE，否则真机上排任务会抛 SecurityException，" +
                "实际声明：${requestedPermissions()}",
            requestedPermissions().contains(Manifest.permission.ACCESS_NETWORK_STATE)
        )
    }

    /**
     * 「同步进行到一半协程被取消」（例如 SyncService 被系统销毁）也必须留下终态：
     * 旧实现里状态行会永远停在「正在同步…」，让人误以为上传还在进行。
     */
    @Test
    fun cancelledSync_stillLeavesATerminalStatus_andKeepsTheRetryJob() = runBlocking {
        SyncRetry.schedule(context)
        // contentReader 是同步里的第一个挂起点：在这里挂住 = 同步途中被取消
        SyncManager.contentReader = { awaitCancellation() }

        val job = launch(Dispatchers.IO) {
            SyncManager.performSync(context, SyncTrigger.CLOSE_EDITOR, progress = false)
        }
        // 必须等它真的进入同步（状态变成 Running）再取消，否则测不到「Running 之后被取消」
        val entered = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < entered &&
            SyncManager.status.value !is SyncStatus.Running
        ) {
            delay(5)
        }
        assertTrue("同步应进入 Running，实际：${SyncManager.status.value}", SyncManager.status.value is SyncStatus.Running)
        job.cancel()
        job.join()

        val status = SyncManager.status.value
        assertFalse("取消后不能让状态停在 Running", status is SyncStatus.Running)
        assertTrue("取消应留下明确的失败终态，实际：$status", status is SyncStatus.Failed)
        assertTrue("内容还没传上去，系统重试任务必须继续留着", SyncRetry.isScheduled(context))
    }
}
