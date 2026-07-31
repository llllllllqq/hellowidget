package moe.hellowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews

class TextWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetIds)
    }

    companion object {

        /**
         * 刷新所有已添加的桌面小组件。
         * 可从 [MainActivity.saveText] 和 [onUpdate] 中调用。
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
                        // 每个小组件独立的 data uri，避免多个小组件共享数据
                        data = Uri.parse("widget://${context.packageName}/$id")
                    }
                    setRemoteAdapter(R.id.widget_list, serviceIntent)

                    // 点击列表任意一行 → 打开主界面
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                PendingIntent.FLAG_IMMUTABLE
                            } else {
                                0
                            }
                    val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

                    // 列表项（文字）点击：template + fill-in
                    setPendingIntentTemplate(R.id.widget_list, pendingIntent)

                    // 空白区域点击（ListView 自身）：官方文档注明 collection 的列表项
                    // 不走 setOnClickPendingIntent，但空白区属于 ListView 自身事件，可正常触发
                    setOnClickPendingIntent(R.id.widget_list, pendingIntent)
                }
                manager.updateAppWidget(id, views)
            }

            // 通知列表数据已变化，让工厂重新从 SharedPreferences 读取
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }
    }
}
