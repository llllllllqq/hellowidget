package moe.hellowidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking

/**
 * 为桌面小组件提供可滚动文本数据的服务。
 *
 * 这是"绑定式"服务（Bound Service）：只有桌面(Launcher)需要渲染小组件内容时
 * 才会被系统临时绑定启动，渲染/滑动结束后即停止。
 * 不需要常驻后台、没有定时轮询、不消耗电量。
 */
class TextWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TextWidgetFactory(applicationContext, intent)

    private class TextWidgetFactory(
        private val context: Context,
        intent: Intent
    ) : RemoteViewsFactory {

        private val widgetId: Int = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        /** 当前要展示的所有文本行（按 \n 拆分） */
        private val lines = mutableListOf<String>()

        /** 最后一个列表项需补齐的高度（px）：内容不满一页时填满空白区，使整块可点击 */
        private var fillPx = 0

        override fun onCreate() {
            // 首次创建时加载一次
            reload()
        }

        /**
         * 数据变化时由框架调用（在后台线程执行），
         * 重新从 DataStore 读取最新保存的文本，并按小组件尺寸计算空白区填充。
         */
        override fun onDataSetChanged() {
            reload()
        }

        private fun reload() {
            lines.clear()
            // 从 DataStore 读取（工厂运行在后台线程，runBlocking 短暂阻塞可接受）
            val savedText = runBlocking { ContentStore.read() }
            if (savedText.isBlank()) {
                lines.add(context.getString(R.string.widget_empty_hint))
            } else {
                lines.addAll(savedText.split("\n"))
            }
            fillPx = computeFillPx()
        }

        /**
         * 内容不满一页时，计算最后一行需要补齐的高度（px）。
         * 高度取自 getAppWidgetOptions（桌面报告的小组件尺寸，dp）。
         * 行高按 字号(sp)×1.2 + 行距(2dp) 估算，并额外补一行余量避免缝隙。
         * 补齐后最后一行覆盖整个空白区：ListView 行与行之间无缝隙（透明 0dp 分隔线），
         * 点击不会漏。填充仅在渲染时计算，不影响存储内容和编辑器显示。
         */
        private fun computeFillPx(): Int {
            if (lines.isEmpty()) return 0
            val heightDp = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                AppWidgetManager.getInstance(context)
                    .getAppWidgetOptions(context, widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            } else {
                0
            }
            if (heightDp <= 0) return 0

            val density = context.resources.displayMetrics.density
            val lineHeightPx = (WidgetSettings.fontSp(context) * 1.2f + 2f) * density
            val contentHeightPx = lines.size * lineHeightPx
            val widgetHeightPx = heightDp * density
            val gapPx = widgetHeightPx - contentHeightPx
            if (gapPx <= 0) return 0
            // 额外补一行余量，避免测量误差留下不可点击缝隙
            return (gapPx + lineHeightPx).toInt()
        }

        override fun onDestroy() {
            lines.clear()
        }

        override fun getCount(): Int = lines.size

        override fun getViewAt(position: Int): RemoteViews {
            val item = RemoteViews(context.packageName, R.layout.widget_list_item)
            item.setTextViewText(R.id.widget_item_text, lines[position])

            // 应用字体大小（sp）与字体颜色
            item.setFloat(R.id.widget_item_text, "setTextSize", WidgetSettings.fontSp(context))
            item.setInt(R.id.widget_item_text, "setTextColor", WidgetSettings.textColor(context))

            // 最后一项：补齐空白区高度，使整块区域都可点击打开应用
            if (position == lines.size - 1 && fillPx > 0) {
                item.setInt(R.id.widget_item_text, "setMinimumHeight", fillPx)
            }

            // 点击这一行 → 通过列表模板打开主界面
            item.setOnClickFillInIntent(R.id.widget_item_text, Intent())
            return item
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun hasStableIds(): Boolean = false

        override fun getItemId(position: Int): Long = position.toLong()
    }
}
