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
import moe.hellowidget.R
import moe.hellowidget.SyncActivity

/**
 * WebDAV 同步的通知与上传结果提示。
 *
 * ## v8.5.6：用户可见通知只在失败时发
 * 旧实现每次同步都发一条「正在同步」的进度通知，并保证它至少可见 1.5 秒（v8.5.4 曾试过 5 秒）。
 * 但真机反馈 5 秒也没有改善「有时看不到」，而且成功路径上的进度条本身就是打扰。
 * 现在：
 *  - **失败**才发一条 `HIGH` 渠道的通知（[CHANNEL_ID_FAILURE]），点开进入同步设置页；
 *  - 成功后不发任何用户可见的通知，并撤掉之前的失败通知；
 *  - Toast 逻辑保持不变（成功「已上传到云端」/ 失败原因）。
 *
 * ## 前台服务仍有一条通知，这是 Android 的硬要求
 * 同步跑在前台服务里，`startForeground()` **必须**关联一条通知；拒绝发通知也挡不住这条要求
 * （没有通知前台服务根本不成立）。它走单独的 `LOW` 渠道 [CHANNEL_ID]：静音、无横幅、
 * 不可手动划掉，只在同步执行的几百毫秒到几十秒内存在，结束立刻 `stopForeground(REMOVE)`。
 * 因此它不是「成功通知」，而是平台强制的前台服务凭据；用户可感知的失败提醒只有 HIGH 那条。
 *
 * ## 历史
 * v7.5 为「上传太快、进度条看不到」加过最短可见时长；v8.5.4 延长到 5 秒，真机反馈无效；
 * v8.5.5 撤回 1.5 秒；v8.5.6 直接取消成功路径的进度通知，只保留平台必需的前台服务通知。
 */
object SyncNotifier {

    /** 前台服务通知渠道（LOW、静音；平台强制，不是成功提醒） */
    const val CHANNEL_ID = "webdav_sync"

    /** 失败通知渠道（HIGH；只有失败时才会创建/使用） */
    const val CHANNEL_ID_FAILURE = "webdav_sync_failure"

    /** 前台服务通知 id（`startForeground` 必须与它绑定） */
    const val ID_PROGRESS = 1001

    /** 失败通知 id（独立于前台服务通知，`stopForeground(REMOVE)` 不会误删它） */
    const val ID_FAILURE = 1002

    /** 前台服务通知渠道懒创建：同步没启用时应用启动完全不受影响 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createRunningChannel(context)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRunningChannel(context: Context) {
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

    /** 失败通知渠道：只有真正失败时才会创建（首次失败时系统会按 HIGH 提醒一次） */
    fun ensureFailureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createFailureChannel(context)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createFailureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID_FAILURE) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID_FAILURE,
            context.getString(R.string.sync_notif_failure_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.sync_notif_failure_channel_description)
            setShowBadge(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** 前台服务通知（平台强制；内容只是「正在同步」/ 重试阶段） */
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
     * 更新前台服务通知的正文（同 id、同渠道）。
     *
     * 只应从前台服务路径调用：v8.0.3 起窗口内重试会用它显示「网络不稳，正在重试（1/2）…」，
     * 同一个 id 的 `notify` 不会撤销前台状态。进程内兜底路径**不再发进度通知**，
     * 因此也不要从那里调用它。
     */
    fun postProgress(context: Context, text: String? = null) {
        ensureChannel(context)
        notifyCompat(context, ID_PROGRESS, progressNotification(context, text))
    }

    /**
     * 窗口内重试时的进度正文（只用于前台服务通知）。
     */
    fun retryText(context: Context, attempt: Int, max: Int): String =
        context.getString(R.string.sync_notif_retry_text, attempt, max)

    /**
     * 失败通知（v8.5.6）。
     *
     * 只在**失败**时调用；成功路径不会留下它。`detail` 必须已经过
     * `SyncManager` 的 sanitize（`SyncStatus.Failed.detail` 即是），
     * 绝不能把 WebDAV 口令带进通知栏。
     *
     * 同一 id 更新而不是每次新建：多次失败只保留最新一条；`setOnlyAlertOnce(true)`
     * 避免后台重试连续失败时反复响铃，用户划掉后再失败仍会重新提醒。
     */
    fun notifyFailure(context: Context, error: WebDavError, detail: String) {
        ensureFailureChannel(context)
        notifyCompat(context, ID_FAILURE, failureNotification(context, error, detail))
    }

    /** 成功（或用户主动重试成功）后撤掉旧的失败通知，避免状态行说成功、通知栏还留着失败 */
    fun cancelFailure(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_FAILURE) }
    }

    private fun failureNotification(
        context: Context,
        error: WebDavError,
        detail: String
    ): Notification {
        val localized = SyncErrorText.of(context, error)
        val trimmed = detail.trim().take(FAILURE_DETAIL_MAX)
        val reason = if (trimmed.isNotEmpty() && trimmed != error.name) {
            "$localized（$trimmed）"
        } else {
            localized
        }
        return NotificationCompat.Builder(context, CHANNEL_ID_FAILURE)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(context.getString(R.string.sync_notif_failure_title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openSyncActivity(context))
            .build()
    }

    /**
     * 同步失败的 Toast（v7.8 起与成功提示对称）。
     *
     * v8.5.6 保持原样：Toast 只给「一句话原因 + 截断后的服务器细节」，
     * 完整细节在 WebDAV 设置页的「同步状态」里。失败通知是**额外**的持久提醒，
     * 不改变 Toast 的文案与触发条件。
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

    /**
     * 上传成功的 Toast（v8.5.6 不变）。
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

    /** 通知里可以多带一点细节，但仍要能一眼看到原因 */
    private const val FAILURE_DETAIL_MAX = 200
}
