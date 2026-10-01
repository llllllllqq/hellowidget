package moe.hellowidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.hellowidget.sync.ConflictChoice
import moe.hellowidget.sync.SyncLauncher
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncTrigger

/**
 * 通知栏动作的落点。
 *
 * 用户在通知上点「覆盖云端」/「用云端覆盖本地」属于明确的用户操作，
 * 因此这里启动前台服务是被允许的（用户与通知交互属于系统豁免场景）。
 * 解冲突时同步跳过 30 分钟节流 —— 那本来就是用户此刻的意图。
 */
class SyncActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SyncNotifier.ACTION_KEEP_LOCAL -> {
                SyncNotifier.cancelConflict(context)
                SyncLauncher.request(context, SyncTrigger.CONFLICT_RESOLVE, ConflictChoice.KEEP_LOCAL)
            }

            SyncNotifier.ACTION_USE_REMOTE -> {
                SyncNotifier.cancelConflict(context)
                SyncLauncher.request(context, SyncTrigger.CONFLICT_RESOLVE, ConflictChoice.USE_REMOTE)
            }

            else -> Unit
        }
    }
}
