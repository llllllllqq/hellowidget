package com.helloworld

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.helloworld.MainActivity.Companion.KEY_SAVED_TEXT
import com.helloworld.MainActivity.Companion.prefs

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

        override fun onCreate() {
            // 首次创建时加载一次
            reload()
        }

        /**
         * 数据变化时由框架调用（在后台线程执行），
         * 重新从 SharedPreferences 读取最新保存的文本。
         */
        override fun onDataSetChanged() {
            reload()
        }

        private fun reload() {
            lines.clear()
            val savedText = context.prefs.getString(KEY_SAVED_TEXT, "") ?: ""
            if (savedText.isBlank()) {
                lines.add(context.getString(R.string.widget_empty_hint))
            } else {
                lines.addAll(savedText.split("\n"))
            }
        }

        override fun onDestroy() {
            lines.clear()
        }

        override fun getCount(): Int = lines.size

        override fun getViewAt(position: Int): RemoteViews {
            val item = RemoteViews(context.packageName, R.layout.widget_list_item)
            item.setTextViewText(R.id.widget_item_text, lines[position])

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
