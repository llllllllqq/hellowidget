package moe.hellowidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting

/**
 * 小组件入口：把「背景 + 点击 + 列表数据源」组装成 RemoteViews 交给桌面。
 *
 * v8.5.0 的点击模型（见 [buildWidgetViews]）：
 *  - **根容器**（`widget_root`，FrameLayout）承接点击 → 短内容时文字下方那片空白也可点；
 *  - **列表项**走 `setPendingIntentTemplate` + 工厂里的 `setOnClickFillInIntent`
 *    （官方唯一受支持的做法），所以点文字同样是打开应用。
 *
 * 绝不能对 `ListView` 本身调用 `setOnClickPendingIntent`：部分桌面（含 MIUI）会因此
 * 让整个小组件加载失败。根容器是普通 ViewGroup，不受这条限制。
 */
class TextWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetIds)
    }

    // 不再重写 onAppWidgetOptionsChanged：v8.5.0 起小组件内容的高度与宿主报告尺寸无关，
    // 缩放由桌面重新布局（ListView wrap_content 自动重排），不需要我们再重建 RemoteViews。

    companion object {

        /** 根容器点击（打开应用）与列表项模板点击用不同 requestCode，避免两个 PendingIntent 互相复用 */
        private const val REQUEST_OPEN_APP_ROOT = 0
        private const val REQUEST_OPEN_APP_ITEM = 1

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
                manager.updateAppWidget(id, buildWidgetViews(context, id))
            }

            // 通知列表数据已变化，让工厂重新读取内容（保存成功 / 外观设置变化后都走这里）
            if (ids.isNotEmpty()) {
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }

        /**
         * 组装一个小组件的 RemoteViews。
         *
         * `internal` 是给 Robolectric 用例用的：CI 里没有能力创建真正的桌面宿主
         * （`AppWidgetHost.allocateAppWidgetId` 需要系统级签名权限），所以「根容器可点」
         * 这条只能通过 apply 后的视图树来钉住，见 `WidgetBehaviourTest`。
         */
        @VisibleForTesting
        internal fun buildWidgetViews(context: Context, appWidgetId: Int): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_layout).apply {
                // 背景画在根容器上：列表只有内容那么高，其余面积由根绘制（颜色含透明度）
                setInt(
                    R.id.widget_root,
                    "setBackgroundColor",
                    WidgetSettings.effectiveBgColor(context)
                )

                // 把列表数据源绑定到 TextWidgetService（绑定式，按需启动）
                val serviceIntent = Intent(context, TextWidgetService::class.java).apply {
                    data = Uri.parse("widget://${context.packageName}/$appWidgetId")
                }
                setRemoteAdapter(R.id.widget_list, serviceIntent)

                // 短内容时列表下方的空白属于根容器 —— 点它同样打开应用
                setOnClickPendingIntent(
                    R.id.widget_root,
                    openAppPendingIntent(context, REQUEST_OPEN_APP_ROOT)
                )

                // 列表项（文字）点击：template + fill-in（官方支持，安全）
                setPendingIntentTemplate(
                    R.id.widget_list,
                    openAppPendingIntent(context, REQUEST_OPEN_APP_ITEM)
                )
            }

        private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            // minSdk 24 起 FLAG_IMMUTABLE（API 23+）恒可用
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getActivity(context, requestCode, intent, flags)
        }
    }
}
