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

    fun request(context: Context, trigger: SyncTrigger): Boolean {
        val appContext = context.applicationContext
        if (!SyncSettings.enabled(appContext)) return false
        if (SyncSettings.config(appContext) == null) return false
        return try {
            ContextCompat.startForegroundService(
                appContext,
                SyncService.intent(appContext, trigger)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "无法启动同步前台服务，降级为进程内同步（通知由 SyncManager 补上）", e)
            SyncManager.requestInProcess(appContext, trigger)
            false
        }
    }
}
