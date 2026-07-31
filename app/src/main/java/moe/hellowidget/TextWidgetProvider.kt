package moe.hellowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews

class TextWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetIds)
    }

    /** 小组件被缩放时刷新，让工厂按新尺寸重新计算空白区填充 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateWidgets(context, intArrayOf(appWidgetId))
    }

    companion object {

        /**
         * 刷新所有已添加的桌面小组件。
         * 可从 [MainActivity.saveContent] 和 [onUpdate] 中调用。
         */
        fun updateWidgets(context: Context, appWidgetIds: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = appWidgetIds
                ?: manager.getAppWidgetIds(
                    ComponentName(context, TextWidgetProvider::class.java)
                )

            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_layout).apply {
                    // 应用背景颜色（含透明度）
                    setInt(R.id.widget_list, "setBackgroundColor", WidgetSettings.effectiveBgColor(context))

                    // 把列表数据源绑定到 TextWidgetService（绑定式，按需启动）
                    val serviceIntent = Intent(context, TextWidgetService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        data = Uri.parse("widget://${context.packageName}/$id")
                    }
                    setRemoteAdapter(R.id.widget_list, serviceIntent)

                    // 列表项（文字）点击：template + fill-in（官方支持，安全）
                    // ⚠ 绝不能对 ListView 本身调用 setOnClickPendingIntent
                    //   （部分桌面含 MIUI 会导致整个小部件加载失败）
                    setPendingIntentTemplate(R.id.widget_list, openAppPendingIntent(context))
                }
                manager.updateAppWidget(id, views)
            }

            // 通知列表数据已变化，让工厂重新读取（含按新尺寸计算空白区填充）
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }

        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
            return PendingIntent.getActivity(context, 0, intent, flags)
        }
    }
}
