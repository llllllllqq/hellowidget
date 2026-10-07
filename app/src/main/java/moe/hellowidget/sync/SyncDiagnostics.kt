package moe.hellowidget.sync

import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * v8.0.1：把「系统为什么没让自动上传跑起来」变成可读的证据。
 *
 * ## 为什么需要它
 * v7.9 起的自愈通道是「保存后向系统排一个一次性任务，由系统在有网络时补传」。
 * 但实测线索（HyperOS 3 / Android 16）显示它**偶尔不生效**，而应用代码里完全看不见原因。
 * 官方文档给出的机制是：**应用所处的「待机分桶」决定它的任务能以多高频率运行**，
 * 而且原文明确「每个厂商可以自定非活跃应用进入哪个桶的分桶标准」；
 * 更关键的是 **Rare / Restricted 桶下后台网络直接是 Disabled** ——
 * 我们那条任务带着 `NETWORK_TYPE_ANY` 约束，此时可能**永远等不到约束满足**。
 *
 * 这些状态只有问系统才知道，所以这里把三个问题读出来：
 *  1. 我当前在哪个待机分桶？
 *  2. 我那条待执行任务查得到吗？
 *  3. 系统说它为什么还没跑？（API 34+ 的 `getPendingJobReason`）
 *
 * ## 设计约束
 *  - **只读、零网络、零落盘**：不写任何状态，不申请任何权限
 *    （`getAppStandbyBucket()` 查自己的分桶不需要 `PACKAGE_USAGE_STATS`）；
 *  - **每个 API 都按版本门控**，拿不到就返回 null —— 诊断绝不能影响任何业务流程；
 *  - 返回值用 **ASCII token**（`RARE` / `PENDING_JOB_REASON_APP_STANDBY`），
 *    日志与界面共用同一个 token：用户截图和 logcat 能一一对上，不用翻译。
 */
object SyncDiagnostics {

    private const val TAG = "SyncDiag"

    /**
     * 应用当前所处的待机分桶；拿不到返回 `null`。
     *
     * API 28 起可用。常量是编译期内联的，所以这里引用 API 30 才加的
     * [UsageStatsManager.STANDBY_BUCKET_RESTRICTED] 在 28/29 上也是安全的
     * —— 那个分支永远不会被走到（旧系统根本不返回 45）。
     */
    @Suppress("InlinedApi")
    fun standbyBucket(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            manager?.appStandbyBucket
        } catch (e: Exception) {
            Log.w(TAG, "读取待机分桶失败", e)
            null
        }
    }

    /** 分桶的 ASCII 名称（日志与设置页共用同一 token） */
    @Suppress("InlinedApi")
    fun bucketName(bucket: Int?): String = when (bucket) {
        null -> "n/a"
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
        50 -> "NEVER"
        else -> "UNKNOWN($bucket)"
    }

    /**
     * 系统说我们那条兜底任务为什么还没执行；API 34 以下或查不到时返回 `null`。
     *
     * 用的是单值版 [JobScheduler.getPendingJobReason]（API 34）而不是
     * `getPendingJobReasons`（那是 API 36）—— minSdk 24，必须按最低的那条线走。
     */
    fun pendingJobReason(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return try {
            val scheduler = context.applicationContext
                .getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return null
            scheduler.getPendingJobReason(SyncRetry.JOB_ID)
        } catch (e: Exception) {
            // Robolectric 与个别 OEM 上这个方法可能没实现 —— 诊断失败不该有任何后果
            Log.w(TAG, "读取待执行任务原因失败", e)
            null
        }
    }

    /**
     * 待执行的重试任务是不是**以加急身份**排着的；API 31 以下或查不到时返回 `null`。
     *
     * v8.0.1 试过给兜底任务加 `setExpedited(true)`，但平台明确禁止（见
     * [SyncRetry.MIN_LATENCY_MS] 的记录：`An expedited job cannot have a time delay`），
     * 所以本应用**恒为常规任务**，这里正常应显示 `false`。
     * 保留这条诊断的意义是"反证"：如果某台设备/某个 OEM 上它显示 `true`，
     * 说明系统改变了我们排出去的任务，这正是排查"为什么行为和预期不一样"的第一手证据。
     */
    fun pendingJobExpedited(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val scheduler = context.applicationContext
                .getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
                ?: return null
            scheduler.getPendingJob(SyncRetry.JOB_ID)?.isExpedited
        } catch (e: Exception) {
            Log.w(TAG, "读取待执行任务加急状态失败", e)
            null
        }
    }

    /** 待执行任务原因的 ASCII 名称 */
    @Suppress("InlinedApi")
    fun pendingJobReasonName(reason: Int?): String = when (reason) {
        null -> "n/a"
        JobScheduler.PENDING_JOB_REASON_UNDEFINED -> "UNDEFINED"
        JobScheduler.PENDING_JOB_REASON_APP -> "APP"
        JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> "APP_STANDBY"
        JobScheduler.PENDING_JOB_REASON_QUOTA -> "QUOTA"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "CONSTRAINT_CONNECTIVITY"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY -> "CONSTRAINT_MINIMUM_LATENCY"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> "CONSTRAINT_CHARGING"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "CONSTRAINT_BATTERY_NOT_LOW"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "CONSTRAINT_STORAGE_NOT_LOW"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE -> "CONSTRAINT_DEVICE_IDLE"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER -> "CONSTRAINT_CONTENT_TRIGGER"
        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH -> "CONSTRAINT_PREFETCH"
        JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> "BACKGROUND_RESTRICTION"
        JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "DEVICE_STATE"
        JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION -> "JOB_SCHEDULER_OPTIMIZATION"
        JobScheduler.PENDING_JOB_REASON_EXECUTING -> "EXECUTING"
        JobScheduler.PENDING_JOB_REASON_USER -> "USER"
        JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID -> "INVALID_JOB_ID"
        else -> "UNKNOWN($reason)"
    }

    /**
     * 一行式快照，既写 logcat 也供设置页展示。
     *
     * 格式刻意固定成 `key=value` 的 ASCII，便于用户直接复制、也便于 grep：
     * `bucket=RARE pendingJob=APP_STANDBY scheduled=true`
     */
    fun snapshot(context: Context): String {
        val bucket = standbyBucket(context)
        val reason = pendingJobReason(context)
        return "bucket=${bucketName(bucket)} " +
            "pendingJobReason=${pendingJobReasonName(reason)} " +
            "pendingJobExpedited=${pendingJobExpedited(context)} " +
            "scheduled=${SyncRetry.isScheduled(context)} " +
            "retryAttempts=${SyncSettings.retryAttempts(context)}"
    }

    /** 把快照写进 logcat（打开应用、进入同步设置页时调用） */
    fun logSnapshot(context: Context, where: String) {
        Log.i(TAG, "诊断[$where] ${snapshot(context)}")
    }
}
