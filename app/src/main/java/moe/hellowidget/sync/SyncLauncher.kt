package moe.hellowidget.sync

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import moe.hellowidget.SyncService

/**
 * 同步的统一入口。
 *
 * 先用**前台服务**跑（通知栏进度条 + 进程不会被回收），这是产品明确要求的形态；
 * 若系统拒绝后台启动前台服务（Android 12+ 的 FGS 限制在个别时序下会拒绝，
 * 例如「切后台的瞬间触发」），降级为进程内协程同步：
 * 上传照做，进度通知与成功 Toast 由 [SyncManager] 自己发（见 `requestInProcess`），
 * 因此**两条路径都有用户可见的反馈**。
 */
object SyncLauncher {

    private const val TAG = "SyncLauncher"

    /**
     * 是否具备同步条件（已启用 + 配置合法）。调用方（例如编辑页顶部导航栏的「立即上传」）
     * 用它来区分「真的开始上传」与「什么都没发生」，从而给出明确提示。
     * [request] 内部走的是同一个判断，避免两处逻辑漂移。
     */
    fun isReady(context: Context): Boolean {
        val appContext = context.applicationContext
        return SyncSettings.enabled(appContext) && SyncSettings.config(appContext) != null
    }

    fun request(context: Context, trigger: SyncTrigger): Boolean {
        val appContext = context.applicationContext
        if (!isReady(appContext)) {
            Log.i(TAG, "同步条件不满足（未启用或配置不完整），本次触发忽略：trigger=$trigger")
            SyncSettings.setLaunchNote(appContext, "skipped:not-ready")
            return false
        }
        return try {
            // 这一步必须在应用仍处于"可见 → 后台"的转换窗口内完成：
            // 官方对后台启动前台服务的限制有豁免条款「app transitions from a user-visible state」，
            // 而豁免窗口会随时间流逝关闭 —— 所以调用方把它排在写盘之后、主线程跳转**之前**。
            ContextCompat.startForegroundService(
                appContext,
                SyncService.intent(appContext, trigger)
            )
            Log.i(TAG, "已请求系统启动同步前台服务：trigger=$trigger")
            SyncSettings.setLaunchNote(appContext, "fgs-requested:$trigger")
            true
        } catch (e: Exception) {
            // v8.0.1：把异常**类型**写进消息与落盘记录。
            // ForegroundServiceStartNotAllowedException（后台启动被拒）与
            // ForegroundServiceTypeNotAllowedException（类型不被允许）需要完全不同的对策，
            // 只看堆栈的 Log.w(..., e) 在用户转述时会被丢掉。
            val kind = e.javaClass.simpleName
            Log.w(
                TAG,
                "无法启动同步前台服务（$kind: ${e.message}），降级为进程内同步（通知由 SyncManager 补上）：trigger=$trigger",
                e
            )
            SyncSettings.setLaunchNote(appContext, "fgs-rejected:$kind")
            SyncManager.requestInProcess(appContext, trigger)
            false
        }
    }
}
