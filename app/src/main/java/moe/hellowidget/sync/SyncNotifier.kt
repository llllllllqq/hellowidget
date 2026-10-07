package moe.hellowidget.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import moe.hellowidget.R
import moe.hellowidget.SyncActivity

/**
 * WebDAV 同步的通知与上传结果提示。
 *
 * 产品要求是「同步期间通知栏留一条进度条，结束后立刻收掉」：
 *  - 进度通知既是用户可见的反馈，也是前台服务能长期存活（不被系统回收）的依据；
 *  - 同步一结束就收掉，不留常驻通知、不留后台线程。
 *
 * ## v7.8：失败只用 Toast 报错
 * 旧实现把失败**留在通知栏**里（需要用户手动点掉）；v7.8 起改为与成功对称的一条 Toast，
 * 通知栏只在同步进行中出现进度条，结束后干干净净。失败的完整原因仍会写进
 * `SyncSettings.lastResult`，WebDAV 设置页的「同步状态」照样能看到。
 *
 * ## v7.5 起新增的两条保证
 *  1. **每次上传都有可见的进度条**：上传往往几百毫秒就结束，`startForeground` 之后
 *     立刻收掉用户根本看不到（这正是「明明上传了却没有通知栏进度」的根因）。
 *     为此引入 [awaitProgressVisibleFor]：真的发生了上传时，进度通知至少可见
 *     [MIN_PROGRESS_VISIBLE_MS]；而「本地没变、什么都没做」仍旧不打扰用户。
 *  2. **上传成功弹 Toast**：通知可能一闪而过（或用户没拉下通知栏），
 *     Toast 直接把「已上传到云端」送到眼前。用 `Toast.makeText`（纯文本 Toast）——
 *     官方文档明确：Android 11 起只禁止后台的**自定义视图** Toast，文本 Toast 仍然允许；
 *     何况本应用的同步始终在前台服务或可见界面中执行。
 */
object SyncNotifier {

    const val CHANNEL_ID = "webdav_sync"
    const val ID_PROGRESS = 1001

    /**
     * 进度通知的最短可见时长。上传通常只要几百毫秒：通知刚发出去就被收掉，
     * SystemUI 甚至来不及渲染，用户看到的是「完全没有通知」。
     */
    const val MIN_PROGRESS_VISIBLE_MS = 1500L

    /** 通知渠道懒创建：同步没启用时应用启动完全不受影响 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannel(context)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.sync_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.sync_notif_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** 进度通知（前台服务必须持有的那条） */
    fun progressNotification(context: Context, text: String? = null): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(context.getString(R.string.sync_notif_progress_title))
            .setContentText(text ?: context.getString(R.string.sync_notif_progress_text))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openSyncActivity(context))
            .build()

    /**
     * 进程内兜底路径的进度通知（没有前台服务，用普通通知顶上）。
     * 失败/成功的收尾与前台服务路径共用同一套回调。
     *
     * v8.0.3：也可用来**更新已有那条通知的正文** —— 前台服务路径用它显示
     * 「网络不稳，正在重试（1/2）…」（同一个通知 id，`startForeground` 之后
     * 用 `NotificationManager.notify` 更新前台通知正是官方做法，前台状态不受影响）。
     */
    fun postProgress(context: Context, text: String? = null) {
        // 进程内兜底路径可能从没启动过 SyncService，渠道要在这里补齐（幂等）
        ensureChannel(context)
        notifyCompat(context, ID_PROGRESS, progressNotification(context, text))
    }

    /**
     * 窗口内重试时的进度正文。
     *
     * 这条文案是"窗口内快速重试"对用户的全部可见代价：只在**真的在重试**时出现，
     * 且明确说清"正在重试第几次"—— 而不是像"挂一条常驻通知"那样，
     * 让用户在什么都没发生的时候也看到"正在上传"。
     */
    fun retryText(context: Context, attempt: Int, max: Int): String =
        context.getString(R.string.sync_notif_retry_text, attempt, max)

    /**
     * 同步失败的 Toast（v7.8：与成功提示对称，不再往通知栏留东西）。
     *
     * 只给「一句话原因 + 截断后的服务器细节」：完整细节在 WebDAV 设置页的「同步状态」里，
     * Toast 只是让用户立刻知道「这次没传上去」——否则失败是静默的，用户会以为已经同步了。
     * 和成功 Toast 一样切到主线程 Looper，并吞掉异常（提示不该影响同步流程本身）。
     */
    fun showUploadFailedToast(context: Context, error: WebDavError, detail: String) {
        val appContext = context.applicationContext
        val localized = SyncErrorText.of(appContext, error)
        val trimmed = detail.trim().take(TOAST_DETAIL_MAX)
        val reason = if (trimmed.isNotEmpty() && trimmed != error.name) "$localized（$trimmed）" else localized
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.sync_failed_toast, reason),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun cancelProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_PROGRESS) }
    }

    /**
     * 让进度通知至少显示到 [MIN_PROGRESS_VISIBLE_MS]（只在**真的上传了**之后调用）。
     * 调用方随后照常 [cancelProgress]。
     */
    suspend fun awaitProgressVisibleFor(startedAt: Long) {
        val elapsed = System.currentTimeMillis() - startedAt
        val remaining = MIN_PROGRESS_VISIBLE_MS - elapsed
        if (remaining > 0) delay(remaining)
    }

    /**
     * 上传成功的 Toast。
     *
     * 必须切到主线程 Looper：同步跑在 IO 协程上，而 Toast 需要一个有 Looper 的线程。
     * 失败时吞掉异常 —— 提示不该把一次成功的上传变成失败。
     */
    fun showUploadSucceededToast(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(appContext, R.string.sync_upload_succeeded, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 只有拿到通知权限（API 33+）才会真正显示；没权限时同步照跑，只是没有通知栏反馈 */
    private fun notifyCompat(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openSyncActivity(context: Context): PendingIntent {
        val intent = Intent(context, SyncActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(context, 10, intent, pendingIntentFlags())
    }

    // minSdk 24 起 FLAG_IMMUTABLE（API 23+）恒可用 —— 不再需要按版本拼 flags
    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Toast 里最多带出的服务器细节长度：一句话能读完，完整细节看同步设置页的状态行 */
    private const val TOAST_DETAIL_MAX = 80
}
