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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import moe.hellowidget.R
import moe.hellowidget.SyncActionReceiver
import moe.hellowidget.SyncActivity

/**
 * WebDAV 同步的通知。
 *
 * 产品要求是「同步期间通知栏留一条进度条，结束后立刻收掉」：
 *  - 进度通知既是用户可见的反馈，也是前台服务能长期存活（不被系统回收）的依据；
 *  - 同步一结束就 `stopForeground(REMOVE)`，不留常驻通知、不留后台线程；
 *  - 只有「失败」与「待处理的冲突」会留下通知，因为它们需要用户做点什么。
 */
object SyncNotifier {

    const val CHANNEL_ID = "webdav_sync"
    const val ID_PROGRESS = 1001
    const val ID_CONFLICT = 1002
    const val ID_FAILURE = 1003

    const val ACTION_KEEP_LOCAL = "moe.hellowidget.action.SYNC_KEEP_LOCAL"
    const val ACTION_USE_REMOTE = "moe.hellowidget.action.SYNC_USE_REMOTE"

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

    /** 同步失败：留下通知，点开进入同步页看原因并重试 */
    fun postFailure(context: Context, error: WebDavError, detail: String) {
        val localized = SyncErrorText.of(context, error)
        val message = if (detail.isNotBlank() && detail != error.name) "$localized（$detail）" else localized
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(context.getString(R.string.sync_notif_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openSyncActivity(context))
            .build()
        notifyCompat(context, ID_FAILURE, notification)
    }

    fun cancelFailure(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_FAILURE) }
    }

    /**
     * 待处理的冲突：**绝不自动解决**，把决定权交给用户。
     * 两个动作按钮让用户不必打开应用就能处理，符合「后台同步时也要能询问」的场景。
     */
    fun postConflict(context: Context, fileName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync_notification)
            .setContentTitle(context.getString(R.string.sync_notif_conflict_title))
            .setContentText(context.getString(R.string.sync_notif_conflict_text, fileName))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.sync_notif_conflict_big_text, fileName))
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openSyncActivity(context))
            .addAction(
                0,
                context.getString(R.string.sync_conflict_keep_local),
                broadcast(context, ACTION_KEEP_LOCAL, 11)
            )
            .addAction(
                0,
                context.getString(R.string.sync_conflict_use_remote),
                broadcast(context, ACTION_USE_REMOTE, 12)
            )
            .build()
        notifyCompat(context, ID_CONFLICT, notification)
    }

    fun cancelConflict(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_CONFLICT) }
    }

    fun cancelProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_PROGRESS) }
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

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, SyncActionReceiver::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(context, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
}
