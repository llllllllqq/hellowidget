package moe.hellowidget

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
        @Suppress("UNUSED_PARAMETER") intent: Intent
    ) : RemoteViewsFactory {

        /** 当前要展示的所有文本行（按 \n 拆分） */
        private val lines = mutableListOf<String>()

        /**
         * 短内容模式（≤ [SHORT_CONTENT_LINES] 行）：合并为单条、
         * 高度填满整个小组件，使整块区域都可点击打开应用。
         */
        private var shortMode = false

        override fun onCreate() {
            // 首次创建时加载一次
            reload()
        }

        /**
         * 数据变化时由框架调用（在后台线程执行），
         * 重新从 DataStore 读取最新保存的文本。
         */
        override fun onDataSetChanged() {
            reload()
        }

        private fun reload() {
            lines.clear()
            // 从 DataStore 读取（工厂运行在后台线程，runBlocking 短暂阻塞可接受）
            val savedText = runBlocking { ContentStore.read() }
            val split = if (savedText.isBlank()) {
                listOf(context.getString(R.string.widget_empty_hint))
            } else {
                savedText.split("\n")
            }
            lines.addAll(split)
            shortMode = split.size <= SHORT_CONTENT_LINES
        }

        override fun onDestroy() {
            lines.clear()
        }

        override fun getCount(): Int = if (shortMode) 1 else lines.size

        override fun getViewAt(position: Int): RemoteViews {
            // 短内容：单条填满整块（match_parent）；长内容：逐行普通高度（可滚动）
            val layout = if (shortMode) R.layout.widget_list_item_fill else R.layout.widget_list_item
            val item = RemoteViews(context.packageName, layout)

            // 短内容模式把所有行合并为一条文本
            val text = if (shortMode) lines.joinToString("\n") else lines[position]
            item.setTextViewText(R.id.widget_item_text, text)

            // 应用字体大小（sp）与字体颜色
            item.setFloat(R.id.widget_item_text, "setTextSize", WidgetSettings.fontSp(context))
            item.setInt(R.id.widget_item_text, "setTextColor", WidgetSettings.textColor(context))

            // 点击这一行 → 通过列表模板打开主界面（官方 fill-in 机制，不影响加载）
            item.setOnClickFillInIntent(R.id.widget_item_text, Intent())
            return item
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 2

        override fun hasStableIds(): Boolean = false

        override fun getItemId(position: Int): Long = position.toLong()

        companion object {
            /** 内容行数不超过该值时进入「单条填满」模式 */
            private const val SHORT_CONTENT_LINES = 3
        }
    }
}
