package moe.hellowidget

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import moe.hellowidget.sync.WebDavError

/**
 * 承载一次 WebDAV 同步的前台服务。
 *
 * 为什么用前台服务而不是普通后台协程：
 *  - 产品要求「通知栏留一条进度条直到同步结束」，这正好是前台服务的语义；
 *  - 前台服务期间进程不会被系统回收，上传不会做到一半被打断；
 *  - 同步一结束立即 [ServiceCompat.stopForeground] + `stopSelf()` —— 不留常驻通知、
 *    不留后台线程、不做任何周期性任务（应用其余部分也刻意保持「无后台轮询」的设计）。
 *
 * ## v7.5：进度条必须真的被看见
 * 上传通常几百毫秒就结束，旧实现在 `startForeground` 之后立刻 `stopForeground(REMOVE)`，
 * SystemUI 甚至来不及渲染，用户看到的就是「明明上传了却没有通知栏进度」。
 * 现在只要**确实发生了上传**，进度通知就至少保留
 * [SyncNotifier.MIN_PROGRESS_VISIBLE_MS]；而「本地没变、什么都没做」的跳过立刻收掉，
 * 不产生无意义的闪烁。
 *
 * 启动点全部在用户可见的转换处（返回键退出、失焦、打开应用、手动按钮），
 * 因此不违反 Android 12+ 对后台启动前台服务的限制；万一仍被拒绝，
 * [moe.hellowidget.sync.SyncLauncher] 会降级为进程内同步（通知由 SyncManager 自己发）。
 */
class SyncService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SyncNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 startForegroundService 后的 5 秒内进入前台，否则系统直接 ANR/崩溃
        SyncNotifier.ensureChannel(this)
        // dataSync 类型是 API 29+ 的概念；ServiceCompat 在更低版本会忽略该参数
        // （显式分支而不是直接引用常量，既避免 InlinedApi 警告，也让意图一目了然）
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        val startedAt = System.currentTimeMillis()
        ServiceCompat.startForeground(
            this,
            SyncNotifier.ID_PROGRESS,
            SyncNotifier.progressNotification(this),
            foregroundServiceType
        )

        val trigger = parseTrigger(intent)

        scope.launch {
            val status = try {
                SyncManager.performSync(applicationContext, trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "同步服务执行异常", e)
                SyncStatus.Failed(System.currentTimeMillis(), WebDavError.IO, e.message ?: "未知错误")
            }
            // 失败/成功提示由 SyncManager 负责（进程内降级时同样会发），这里只管进度条
            if (status is SyncStatus.Skipped) {
                Log.i(TAG, "本次同步被跳过：${status.reason}")
            }
            if (status is SyncStatus.Success && status.uploaded) {
                // 上传已经完成，但通知刚发出去可能还没被渲染出来：补足最短可见时长
                SyncNotifier.awaitProgressVisibleFor(startedAt)
            }
            ServiceCompat.stopForeground(this@SyncService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun parseTrigger(intent: Intent?): SyncTrigger {
        val name = intent?.getStringExtra(EXTRA_TRIGGER) ?: return SyncTrigger.MANUAL
        return runCatching { SyncTrigger.valueOf(name) }.getOrDefault(SyncTrigger.MANUAL)
    }

    companion object {
        private const val TAG = "SyncService"
        private const val EXTRA_TRIGGER = "moe.hellowidget.extra.SYNC_TRIGGER"

        fun intent(context: Context, trigger: SyncTrigger): Intent =
            Intent(context, SyncService::class.java).apply {
                putExtra(EXTRA_TRIGGER, trigger.name)
            }
    }
}
