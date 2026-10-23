package moe.hellowidget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.annotation.VisibleForTesting
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
 * - 内容短 → 列表只有文字那么高，下方空白由根容器承接点击（见 widget_layout.xml）
 *
 * v8.5.0：**工厂不再接收 widget id，也不再计算任何尺寸** —— 它只负责"把内容放进
 * 一条列表项"，高度完全交给文字自己决定。旧实现要读 `OPTION_APPWIDGET_MIN_HEIGHT`
 * 再算 `setMinimumHeight`，正是"短内容误滚动"与「防误触余量」的来源。
 */
class TextWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TextWidgetFactory(applicationContext)

    /** `internal` 是给 Robolectric 用例直接构造工厂用的（见 WidgetBehaviourTest） */
    @VisibleForTesting
    internal class TextWidgetFactory(private val context: Context) : RemoteViewsFactory {

        /** 待展示的完整内容（单条渲染） */
        private var content: String = ""

        override fun onCreate() {
            reload()
        }

        /** 数据变化（文本保存 / 外观设置变化）时由框架在后台线程调用 */
        override fun onDataSetChanged() {
            reload()
        }

        /**
         * 重新读取要展示的内容。
         *
         * v8.4.0：读盘失败不再当成"空便签"——那会让用户以为内容没了。现在失败时
         * 明确显示「内容读取失败」提示，等下次数据变化（应用里保存成功后
         * `notifyAppWidgetViewDataChanged`）再重试。
         */
        private fun reload() {
            content = when (val read = runBlocking { ContentStore.readResult() }) {
                is ContentRead.Success -> read.text.ifBlank { context.getString(R.string.widget_empty_hint) }
                is ContentRead.Failed -> context.getString(R.string.widget_read_failed)
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
