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
import moe.hellowidget.sync.SyncSettings
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
 * 因此落在 Android 12+ 后台启动限制的豁免条款「app transitions from a user-visible state」里。
 * 但那个豁免**随时间关闭**，所以 v8.0.1 起调用方（[moe.hellowidget.MainActivity.saveContent]）
 * 把 `startForegroundService` 排在写盘之后、**主线程跳转之前** ——
 * 豁免窗口应该花在启动前台服务上，而不是花在一次线程跳转上。
 * 万一仍被拒绝，[moe.hellowidget.sync.SyncLauncher] 会降级为进程内同步
 * （通知由 SyncManager 自己发），并且把异常类型落盘到
 * [moe.hellowidget.sync.SyncSettings.launchNote]，让"为什么这次没传上去"有据可查。
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
        val foregroundServiceType = foregroundServiceType()
        val startedAt = System.currentTimeMillis()
        val trigger = parseTrigger(intent)
        val foreground = try {
            ServiceCompat.startForeground(
                this,
                SyncNotifier.ID_PROGRESS,
                SyncNotifier.progressNotification(this),
                foregroundServiceType
            )
            true
        } catch (e: Exception) {
            // v7.9：系统可能拒绝前台服务（后台启动限制 / 额度用尽 / 类型不允许）。
            // 这里**必须**接住 —— 让它冒泡就是未捕获异常，系统会直接杀掉进程
            // （RemoteServiceException），而用户只会看到「保存了却没上传」。
            // 降级为进程内同步：上传照做，进度通知与结果提示由 SyncManager 自己发。
            // v8.0.1：异常**类型**进消息与落盘记录，见 SyncLauncher 的同款说明。
            val kind = e.javaClass.simpleName
            Log.w(TAG, "前台服务启动被系统拒绝（$kind: ${e.message}），降级为进程内同步：trigger=$trigger", e)
            SyncSettings.setLaunchNote(applicationContext, "fgs-refused:$kind")
            false
        }
        if (!foreground) {
            SyncManager.requestInProcess(applicationContext, trigger)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        SyncSettings.setLaunchNote(applicationContext, "fgs-foreground:${typeToken(foregroundServiceType)}")
        Log.i(TAG, "同步服务启动：trigger=$trigger type=${typeToken(foregroundServiceType)}")

        scope.launch {
            val status = try {
                // v8.0.3：把"这条通知归我们管"告诉 SyncManager —— 它据此允许在窗口内
                // 对暂时性失败做有限次快速重试，并把阶段文案交回这里更新同一条前台通知。
                // 传 null 的路径（系统兜底任务）没有前台窗口，因此不做窗口内重试。
                SyncManager.performSync(applicationContext, trigger) { text ->
                    SyncNotifier.postProgress(applicationContext, text)
                }
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
            Log.i(TAG, "同步服务结束：$status（耗时 ${System.currentTimeMillis() - startedAt}ms）")
            ServiceCompat.stopForeground(this@SyncService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * v8.0.1：前台服务类型从 `dataSync` 换成 **`shortService`**（API 34+）。
     *
     * 依据是官方《Background Data Transfer Options》里对本场景的原文定义：
     * 「**shortService**：用户发起一个动作（*例如把数据同步到服务器*），
     * 而你希望**即使用户立刻把应用切到后台，这个操作也能完成**」—— 这正是本服务的形态：
     * 由保存/退出触发、几百毫秒到几十秒结束、必须跑完。
     *
     * 而 `dataSync` 恰好是官方建议「改用别的 API」的那一类，还额外背着两件事：
     *  1. Android 15 起 `dataSync` 有「24 小时内累计最多 6 小时」的系统上限，
     *     到点回调 [onTimeout] 并要求几秒内 `stopSelf()`，否则 RemoteServiceException 崩溃；
     *  2. 它需要 `FOREGROUND_SERVICE_DATA_SYNC` 权限，并且是 OEM（小米/MIUI 系）后台策略
     *     重点关照的类型。
     *
     * `shortService` 的代价我们都能接受：约 3 分钟上限（本服务的网络调用被
     * `callTimeout 60s` 封顶，够）、不能启动别的前台服务（我们不启动）。
     *
     * 版本分支保留 `dataSync` 给 API 29~33：那个区间没有 `shortService` 类型，
     * 而 `dataSync` 是当时唯一贴切的类型，换掉会带来无谓的回归风险。
     * Manifest 里两种类型都声明了，所以每一条分支传的类型都是"已声明"的。
     */
    private fun foregroundServiceType(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /** 类型对应的 ASCII token（与 [SyncSettings.launchNote] / logcat 共用） */
    @Suppress("InlinedApi")
    private fun typeToken(type: Int): String = when (type) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE -> "shortService"
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> "dataSync"
        else -> "legacy"
    }

    /**
     * API 34 起：`shortService` 到达约 3 分钟上限时系统回调**单参数**版本
     * （`Service.onTimeout(int)`，API 34；双参数的 `onTimeout(int, int)` 是 API 35 起
     * 给 `dataSync` / `mediaProcessing` 用的）。
     *
     * 官方 troubleshooting 明确：`shortService` 超时不 `stopSelf()` 会直接 ANR
     * （"A foreground service of type FOREGROUND_SERVICE_TYPE_SHORT_SERVICE did not
     * stop within its timeout"），而**实现 `onTimeout()` 是最佳实践**。
     * 本服务的同步被 `callTimeout` 封顶，正常永远走不到这里 —— 实现它才算真的合规。
     */
    override fun onTimeout(startId: Int) {
        Log.e(TAG, "shortService 前台服务到达系统时限（约 3 分钟），立即停止（startId=$startId）")
        stopSelf(startId)
    }

    /**
     * API 35 起：`dataSync` / `mediaProcessing` 到达「24 小时内 6 小时」上限时的回调。
     * v8.0.1 之后 API 35+ 已改用 `shortService`（走上面那个单参数版本），
     * 这个重载保留为兜底：万一系统按 dataSync 语义回调，也必须立刻停。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "前台服务到达系统时限，立即停止（startId=$startId, type=$fgsType）")
        stopSelf(startId)
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
