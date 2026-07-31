package moe.hellowidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking

/**
 * 为桌面小组件提供文本数据的服务。
 *
 * 这是"绑定式"服务（Bound Service）：只有桌面(Launcher)需要渲染小组件内容时
 * 才会被系统临时绑定启动，渲染/滑动结束后即停止。
 * 不需要常驻后台、没有定时轮询、不消耗电量。
 *
 * 渲染策略：整段内容作为【单条】列表项渲染（getCount() = 1）。
 * - TextView 按小组件宽度自动软换行，行高正常，原内容中的 \n 自然保留
 * - 内容长 → 单项自动超高 → ListView 原生上下滚动阅读
 * - 内容短 → 单项按小组件报告高度补高（setMinimumHeight）→ 整块可点击打开应用
 * - 填充只在渲染时计算，不影响存储内容和编辑器显示
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

        /** 待展示的完整内容（单条渲染） */
        private var content: String = ""

        /** 小组件高度（px）：短内容时作为列表项最小高度，保证整块可点击 */
        private var widgetHeightPx: Int = 0

        override fun onCreate() {
            reload()
        }

        /** 数据变化（文本保存 / 小组件缩放）时由框架在后台线程调用 */
        override fun onDataSetChanged() {
            reload()
        }

        private fun reload() {
            val savedText = runBlocking { ContentStore.read() }
            content = savedText.ifBlank { context.getString(R.string.widget_empty_hint) }
            widgetHeightPx = computeWidgetHeightPx()
        }

        /** 读取桌面报告的小组件高度（dp），减去防误触余量后换算成 px；任何异常返回 0（不填充） */
        private fun computeWidgetHeightPx(): Int {
            return try {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return 0
                val heightDp = AppWidgetManager.getInstance(context)
                    .getAppWidgetOptions(widgetId)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                if (heightDp <= 0) return 0
                val density = context.resources.displayMetrics.density
                // 扣除防误触余量（用户可在设置里调整）：
                // 余量不足 → 单项略高于可视区，产生误滚动；余量过大 → 底部留出不可点击窄条
                val usableDp = (heightDp - WidgetSettings.fillMarginDp(context)).coerceAtLeast(0)
                (usableDp * density).toInt()
            } catch (e: Exception) {
                // 个别桌面可能不报告尺寸或抛异常：优雅降级为不填充（文字行仍可点击）
                0
            }
        }

        override fun onDestroy() {
            content = ""
        }

        override fun getCount(): Int = 1

        override fun getViewAt(position: Int): RemoteViews {
            val item = RemoteViews(context.packageName, R.layout.widget_list_item)
            item.setTextViewText(R.id.widget_item_text, content)

            // 应用字体大小（sp）与字体颜色
            item.setFloat(R.id.widget_item_text, "setTextSize", WidgetSettings.fontSp(context))
            item.setInt(R.id.widget_item_text, "setTextColor", WidgetSettings.textColor(context))

            // 短内容：整条补高到小组件高度，任意位置都可点击打开应用
            if (widgetHeightPx > 0) {
                item.setInt(R.id.widget_item_text, "setMinimumHeight", widgetHeightPx)
            }

            // 点击 → 通过列表模板打开主界面
            item.setOnClickFillInIntent(R.id.widget_item_text, Intent())
            return item
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun hasStableIds(): Boolean = false

        override fun getItemId(position: Int): Long = position.toLong()
    }
}
