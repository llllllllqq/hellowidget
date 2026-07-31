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
import kotlinx.coroutines.runBlocking

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
         * Android 12+（API 31）：ScrollView 方案——TextView 填满整块可点击，原生滚动。
         * Android 5–11：ListView 集合方案——点击文字行打开应用，列表滚动。
         */
        private val useScrollLayout: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

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
                val views = if (useScrollLayout) {
                    buildScrollViews(context)
                } else {
                    buildListViews(context, id)
                }
                manager.updateAppWidget(id, views)
            }

            // 仅 ListView 方案需要通知数据变化（ScrollView 方案直接写文本）
            if (!useScrollLayout && ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }

        /**
         * Android 12+：ScrollView + 填满的 TextView。
         * 内容短 → TextView 填满整块 → 点击任何位置打开应用；
         * 内容长 → TextView 超高 → 原生滚动。二者兼得，无需叠加层。
         */
        private fun buildScrollViews(context: Context): RemoteViews {
            // DataStore 首次读取会读盘（几毫秒），此后为内存缓存，开销可忽略
            val savedText = runBlocking { ContentStore.read() }
            val displayText = savedText.ifBlank { context.getString(R.string.widget_empty_hint) }

            return RemoteViews(context.packageName, R.layout.widget_layout_scroll).apply {
                setInt(R.id.widget_scroll, "setBackgroundColor", WidgetSettings.effectiveBgColor(context))
                setTextViewText(R.id.widget_text, displayText)
                setFloat(R.id.widget_text, "setTextSize", WidgetSettings.fontSp(context))
                setInt(R.id.widget_text, "setTextColor", WidgetSettings.textColor(context))
                // 点击整块（TextView 填满 ScrollView）→ 打开主界面
                setOnClickPendingIntent(R.id.widget_text, openAppPendingIntent(context))
            }
        }

        /** Android 5–11：ListView 集合方案（可滚动；点击文字行打开应用） */
        private fun buildListViews(context: Context, id: Int): RemoteViews {
            return RemoteViews(context.packageName, R.layout.widget_layout).apply {
                setInt(R.id.widget_list, "setBackgroundColor", WidgetSettings.effectiveBgColor(context))

                // 把列表数据源绑定到 TextWidgetService（绑定式，按需启动）
                val serviceIntent = Intent(context, TextWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse("widget://${context.packageName}/$id")
                }
                setRemoteAdapter(R.id.widget_list, serviceIntent)

                // 列表项（文字）点击：template + fill-in（官方支持，安全）
                // ⚠ 绝不能对 ListView 本身调用 setOnClickPendingIntent（部分桌面含 MIUI 会导致加载失败）
                setPendingIntentTemplate(R.id.widget_list, openAppPendingIntent(context))
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
