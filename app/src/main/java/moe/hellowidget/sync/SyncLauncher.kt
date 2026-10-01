package moe.hellowidget.sync

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import moe.hellowidget.SyncService

/**
 * 同步的统一入口。
 *
 * 先用**前台服务**跑（通知栏进度条 + 进程不会被回收），这是产品明确要求的形态；
 * 若系统拒绝后台启动前台服务（Android 12+ 的 FGS 限制在个别时序下会拒绝），
 * 降级为进程内协程同步：功能不丢，只是没有通知栏进度。
 */
object SyncLauncher {

    private const val TAG = "SyncLauncher"

    fun request(context: Context, trigger: SyncTrigger, resolution: ConflictChoice? = null): Boolean {
        val appContext = context.applicationContext
        if (!SyncSettings.enabled(appContext)) return false
        if (SyncSettings.config(appContext) == null) return false
        return try {
            ContextCompat.startForegroundService(
                appContext,
                SyncService.intent(appContext, trigger, resolution)
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "无法启动同步前台服务，降级为进程内同步", e)
            SyncManager.requestInProcess(appContext, trigger, resolution)
            false
        }
    }
}
