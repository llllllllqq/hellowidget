package moe.hellowidget.sync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import moe.hellowidget.SyncRetryJobService

/**
 * v7.9：保存之后没能把内容传上去时，交给系统一个**一次性持久化重试任务**兜底。
 *
 * ## 为什么必须有它
 * v7.8 起自动上传只由「保存」触发，而这次触发可能**任何反馈都没有地消失**：
 * 网络客户端的阻塞 IO 被冻在半路、进程被系统冻结/回收、启动前台服务被系统静默拒绝……
 * 于是「本地有改动」这件事只剩下一个橙点提示，橙点自己永远变不绿 —— 用户只能清后台。
 * 现在写盘一成功就把这件事记到系统里：只要还有没传上去的内容，
 * 系统会在**有网络**时重新拉起进程执行一次同步，失败按指数退避重试，
 * 进程被杀、设备重启后依然存在（`setPersisted` + `RECEIVE_BOOT_COMPLETED`）。
 *
 * ## 三条硬约束（与 [SyncManager] 的不变量一致）
 *  - **绝不并发 PUT**：任务里的同步仍然走 [SyncManager] 的同一把互斥锁；文件名带时间戳，
 *    两个并发的上传会算出同一个名字，后一个覆盖前一个（云端丢一份历史）。
 *  - **绝不重复上传**：同步本身就按内容哈希短路，内容没变时一个请求都不发，
 *    所以"误排"一个任务最多只是白醒一次进程。
 *  - **不打扰用户**：任务不显示任何通知（进度通知只属于前台服务那条路径）。
 *
 * ## 只对"看起来是暂时性"的失败重试
 * 凭据错误、证书不受信、服务器不支持写入这类需要人介入的失败，自动重试一万次也没用，
 * 只会白白唤醒进程（这正是旧版把 `lastAttemptAt` 记成"尝试时间"要避免的重试风暴）。
 * 判断集中在 [shouldReschedule]，是可单测的纯函数。
 */
object SyncRetry {

    private const val TAG = "SyncRetry"

    /** 任务 id：固定且唯一。重复排入会**替换**掉上一个待执行的任务，不会堆积 */
    const val JOB_ID = 0x5A17

    /**
     * 首次重试的最短延迟。必须大于 1 分钟闸门（[SyncEngine.MIN_SYNC_INTERVAL_MS]），
     * 否则重试会正好撞进刚才那次尝试留下的节流窗口被静默跳过 —— 那正是本次要修掉的形态。
     */
    const val MIN_LATENCY_MS = 90 * 1000L

    /** 退避起点；JobScheduler 会按指数放大（系统上限 5 小时） */
    const val BACKOFF_MS = 60 * 1000L

    /**
     * 每次「保存」之后最多自动重试几次。
     *
     * 必须有上限：指数退避的系统上限是 5 小时，没有上限时"某个永远传不上去的内容"会让系统
     * **永远**每隔几小时唤醒一次进程 —— 那正是产品明确不要的"后台长期驻留"。
     * 用完预算就停：任务从系统里消失，橙点继续亮着，用户点「立即同步」或下次保存会重新排。
     */
    const val MAX_ATTEMPTS = 5

    /**
     * 这一次重试任务是否正在执行。
     *
     * 存在的理由是一个会变成死循环的坑：任务成功之后 [SyncManager] 会调 [cancel]，
     * 如果那次成功**正是这个任务自己**跑出来的，`cancel()` 就会把正在执行的它取消掉
     * → 系统回调 [moe.hellowidget.SyncRetryJobService.onStopJob]，而那个回调的语义是
     * 「让路，请按退避重排」→ 任务被排回来 → 又跑一次 → 又取消自己…… 永不结束。
     * 因此在执行期间忽略 [cancel]：正在跑的这一趟会自己用 `jobFinished(params, false)` 收尾。
     */
    @VisibleForTesting
    internal var running = false
        private set

    /**
     * 排入（或刷新）重试任务。**写盘成功后立刻调用** —— 放在"触发上传之前"是有意的：
     * 接下来的触发可能根本没跑起来，那样就没有任何东西记得"还有内容没传上去"。
     * 上传成功后 [SyncManager] 会调用 [cancel] 把它撤销。
     *
     * 未启用同步 / 配置不完整时不排（那种情况橙点也不会亮）。任何异常都不能影响
     * 保存与上传本身，因此这里全部吞掉并记日志。
     *
     * @return 系统是否**收下**了这个任务（`JobScheduler.RESULT_SUCCESS`）。
     *   注意"收下"不等于"查得到"：`getAllPendingJobs()` 在个别系统上查不到刚排的任务，
     *   因此返回值只用于诊断与测试，调用方不必据此改变行为。
     */
    fun schedule(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!SyncLauncher.isReady(appContext)) {
            Log.i(TAG, "同步未启用或配置不完整，不排重试任务")
            return false
        }
        val scheduler = scheduler(appContext) ?: return false
        return try {
            // 用户又保存了一次：重试预算重新给满（这正是"用户动一下就能救回来"的路径）
            SyncSettings.setRetryAttempts(appContext, 0)
            // build() 自己也会抛（缺 RECEIVE_BOOT_COMPLETED 权限、一个约束都没有等），
            // 所以它必须和 schedule() 一起被接住：这里抛出去会顺着调用方（保存路径）冒上去，
            // 让「刷新小组件 / 触发上传 / finish()」全都做不成 —— 兜底功能绝不允许拖垮主流程。
            val job = JobInfo.Builder(JOB_ID, ComponentName(appContext, SyncRetryJobService::class.java))
                // 「有网络才执行」。注意：带连通性约束的任务要求调用方持有 ACCESS_NETWORK_STATE，
                // 否则 JobSchedulerService.enforceValidJobRequest 会直接抛 SecurityException
                // （真机实测："ACCESS_NETWORK_STATE required for jobs with a connectivity constraint"）。
                // Manifest 里已经声明了它 —— 少了这个权限，整个自愈通道会静默失效。
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(MIN_LATENCY_MS)
                // 注意参数顺序：官方签名是 (long initialBackoffMillis, @BackoffPolicy int backoffPolicy)
                // —— 毫秒在前、策略在后（与直觉相反，写反了 build() 会抛 IllegalArgumentException）。
                // 不设的话系统默认是 {30 秒, 指数退避}，这里显式写出来是为了"60 秒起步"可读可测
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                // 进程被杀、设备重启后任务仍在（需要 RECEIVE_BOOT_COMPLETED，见 AndroidManifest）
                .setPersisted(true)
                .build()
            val result = scheduler.schedule(job)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "已排入系统重试任务（jobId=$JOB_ID，最短延迟 ${MIN_LATENCY_MS / 1000}s）")
                true
            } else {
                // 系统可以"收下但不排"（返回 RESULT_FAILURE，不抛异常）：绝不能把它当成功
                Log.w(TAG, "系统拒绝了重试任务（result=$result），本次不会自动补传")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "排入系统重试任务失败（不影响本次上传）", e)
            false
        }
    }

    /** 撤销待执行的重试任务：内容已经在云端（或本来就没改动），不必再唤醒进程 */
    fun cancel(context: Context) {
        val appContext = context.applicationContext
        // 内容已经安全抵达云端：本次的重试预算归零
        SyncSettings.setRetryAttempts(appContext, 0)
        if (running) {
            // 正在执行的这一趟自己会收尾，见 [running] 的说明（直接 cancel 会把它重排回来）
            Log.i(TAG, "重试任务正在执行，撤销请求忽略（它自己会用 jobFinished 结束）")
            return
        }
        val scheduler = scheduler(appContext) ?: return
        try {
            scheduler.cancel(JOB_ID)
        } catch (e: Exception) {
            Log.w(TAG, "撤销系统重试任务失败", e)
        }
    }

    /**
     * 是否还有待执行的重试任务。同步设置页把它显示出来 ——
     * 用户拍一张截图就能说明"系统还记着这次没传上去"，不用再靠猜。
     */
    fun isScheduled(context: Context): Boolean {
        val scheduler = scheduler(context.applicationContext) ?: return false
        return try {
            // minSdk 24 起 getPendingJob（API 24）恒可用，不再需要 getAllPendingJobs 回退分支。
            // 查不到时返回 false —— 注意个别系统上"刚排的任务查不到"确实存在，
            // 因此这个值只用于界面提示与诊断，不能当成"一定没排上"的判据（见 schedule 的 @return）。
            scheduler.getPendingJob(JOB_ID) != null
        } catch (e: Exception) {
            Log.w(TAG, "查询系统重试任务失败", e)
            false
        }
    }

    /**
     * 这次同步结果还需不需要系统再试一次。
     *
     * - 成功（含"本地没改动、一个请求都没发"）→ 不需要，任务结束；
     * - 被 1 分钟闸门跳过 → 需要（内容还在，只是这次撞上了节流窗口）；
     * - 失败 → 只有[暂时性][isTransient]的才重试。
     */
    fun shouldReschedule(status: SyncStatus): Boolean = when (status) {
        is SyncStatus.Success -> false
        is SyncStatus.Skipped -> status.reason == SkipReason.THROTTLED
        is SyncStatus.Failed -> isTransient(status.error)
        else -> false
    }

    /**
     * 重试任务真正执行的那一次同步；返回 `true` = 请系统按退避再试一次。
     * [SyncStatus] 是同步的终态（[SyncManager] 保证不会停在 Running）。
     *
     * 正常由系统通过 [moe.hellowidget.SyncRetryJobService] 调用；公开出来也让
     * 仪器化测试可以在"网络恢复"的那一刻直接触发一次，而不必等 90 秒最短延迟。
     */
    suspend fun runOnce(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!SyncLauncher.isReady(appContext)) {
            // 用户关掉同步或改坏了配置：再唤醒进程也没有意义
            Log.i(TAG, "同步已被关闭或配置失效，重试任务结束")
            return false
        }
        val attempts = SyncSettings.retryAttempts(appContext)
        if (attempts >= MAX_ATTEMPTS) {
            // 预算用完就彻底停下：不做长期后台驻留，剩下的交给橙点与用户
            Log.i(TAG, "自动重试已用完 $MAX_ATTEMPTS 次，停止唤醒进程（下次保存或手动上传会重新排）")
            return false
        }
        // running 期间忽略 SyncManager 的成功撤销：否则会把正在跑的这一次取消 → onStopJob
        // 返回"请重排"→ 死循环（详见 [running]）
        running = true
        val again = try {
            // 依旧走 SyncManager 的互斥锁：与前台服务路径串行，绝不并发 PUT
            val status = SyncManager.performSync(appContext, SyncTrigger.CLOSE_EDITOR)
            val retry = shouldReschedule(status)
            if (retry) {
                val used = attempts + 1
                SyncSettings.setRetryAttempts(appContext, used)
                Log.i(
                    TAG,
                    "系统重试任务执行结果：$status（已用 $used/$MAX_ATTEMPTS 次，${
                        if (used < MAX_ATTEMPTS) "将继续重试" else "已达上限，停止自动重试"
                    }）"
                )
                used < MAX_ATTEMPTS
            } else {
                // 传上去了（或本来就没改动）：预算归零
                SyncSettings.setRetryAttempts(appContext, 0)
                Log.i(TAG, "系统重试任务执行结果：$status（结束）")
                false
            }
        } finally {
            running = false
        }
        return again
    }

    /**
     * 「暂时性失败」：再试一次真的可能成功。凭据/权限/证书/服务器不支持这类
     * 必须由人先动手，自动重试只会无意义地唤醒进程（因此一次性失败就收工，
     * 用户下次保存会重新排一个任务，橙点也一直亮着）。
     */
    private fun isTransient(error: WebDavError): Boolean = when (error) {
        WebDavError.NETWORK,
        WebDavError.TIMEOUT,
        WebDavError.IO,
        WebDavError.SERVER_ERROR,
        WebDavError.NOT_FOUND,
        WebDavError.PARENT_NOT_FOUND,
        WebDavError.LOCKED,
        WebDavError.INSUFFICIENT_STORAGE -> true
        else -> false
    }

    private fun scheduler(context: Context): JobScheduler? = try {
        context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
    } catch (e: Exception) {
        Log.w(TAG, "取不到 JobScheduler", e)
        null
    }
}
