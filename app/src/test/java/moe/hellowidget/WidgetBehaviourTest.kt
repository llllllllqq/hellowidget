package moe.hellowidget

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import moe.hellowidget.MainActivity.Companion.prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException

/**
 * v8.5.0：小组件「整块可点」的新契约。
 *
 * 旧实现（v5.6~v8.4.0）把唯一的列表项撑到「宿主报告的小组件高度 − 防误触余量」，
 * 于是 item 高度是估算值的函数：估算值大于实际可视高度时短内容会多出滚动余量，
 * 点击被吞掉（v5.8「短内容误滚动半行」）。新实现把点击交给**根容器**、列表按内容高度，
 * 估算与余量全部删除。
 *
 * CI 里没有能力创建真正的桌面宿主（`AppWidgetHost.allocateAppWidgetId` 需要系统级签名
 * 权限），所以「点文字下方空白能打开应用」这条只能拆成两半来钉：
 *  1. 布局几何：短内容时列表只占文字那么高，且文字下方那片区域由根容器接收触摸；
 *  2. 组装结果：Provider 产出的 RemoteViews 里，点击确实挂在根容器上、背景也画在根上。
 * 真正的桌面行为（系统把点击投递给 PendingIntent）仍由用户真机验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetBehaviourTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ContentStore.readOverride = null
        ContentStore.writeOverride = null
        app.prefs.edit().clear().commit()
    }

    // ---------- 布局几何 ----------

    @Test
    fun widgetLayout_listIsContentHeight_soBlankAreaBelongsToTheRoot() {
        val (root, list) = inflateWidget()

        assertTrue(
            "根布局必须是普通容器（可挂 PendingIntent），不能是 ListView 本身",
            root is FrameLayout
        )
        assertEquals(
            "列表必须 wrap_content：写成 match_parent 会把文字下方的空白也圈进列表，" +
                "根容器就收不到那片区域的点击（旧方案由此才需要把列表项撑高）",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            list.layoutParams.height
        )

        list.adapter = linesAdapter("只有一行短内容")
        layoutWidget(root)
        assertTrue("短内容时列表只应占文字那么高，下方必须留出空白", list.height < root.height)
    }

    @Test
    fun tapOnBlankAreaBelowText_isReceivedByTheRoot() {
        val (root, list) = inflateWidget()
        list.adapter = linesAdapter("一行")

        // 必须挂到真实的窗口上再点：View 的点击是在 ACTION_UP 时把 PerformClick **post 到主线程**的，
        // 未 attach 的 View 会把它排进 RunQueue 且永远不执行 —— 第一版用例因此误判成"空白没接住点击"。
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle() // 让窗口 attach 与首帧布局跑完
        layoutWidget(root)                     // 再按"桌面给的小组件尺寸"强制布局一次

        assertTrue("前置条件：短内容下方必须有空白", list.bottom < root.height)

        var rootClicked = false
        root.setOnClickListener { rootClicked = true }

        val blankY = (list.bottom + (root.height - list.bottom) / 2).toFloat()
        dispatchTap(root, x = 20f, y = blankY)
        shadowOf(Looper.getMainLooper()).idle() // 执行 ACTION_UP 时 post 出来的 PerformClick

        assertTrue("文字下方的空白必须由根容器接住点击（短内容也能点开应用）", rootClicked)
    }

    @Test
    fun longContent_scrollsInsideTheWidget_andNeverOverflowsTheRoot() {
        val (root, list) = inflateWidget()
        val manyLines = (1..200).map {
            "第 $it 行：用来撑满小组件高度，验证长内容仍然可以在桌面上下滚动阅读"
        }
        list.adapter = ArrayAdapter(app, R.layout.widget_list_item, manyLines)
        layoutWidget(root)

        assertTrue(
            "列表高度不得超过小组件高度（超出会被父布局裁掉、且自身不再有滚动余量）",
            list.height <= root.height
        )
        assertTrue("长内容必须能在小组件内滚动", list.canScrollVertically(1))
        assertTrue(
            "可见的最后一项目前不应该是列表末尾（否则根本滚不动）",
            list.lastVisiblePosition < list.count - 1
        )
    }

    // ---------- 组装结果（Provider） ----------

    @Test
    fun provider_putsClickAndBackgroundOnTheRootContainer() {
        app.prefs.edit()
            .putInt(WidgetSettings.KEY_BG_COLOR, Color.RED)
            .putInt(WidgetSettings.KEY_BG_ALPHA, 100)
            .commit()

        val root = TextWidgetProvider.buildWidgetViews(app, appWidgetId = 7).apply(app, null)
        val list = root.findViewById<ListView>(R.id.widget_list)

        assertTrue("Provider 产出的根必须是 FrameLayout（widget_layout 的根）", root is FrameLayout)
        assertEquals(
            "列表仍必须是 wrap_content（高度交给内容）",
            ViewGroup.LayoutParams.WRAP_CONTENT,
            list.layoutParams.height
        )
        assertTrue(
            "点击必须挂在根容器上：列表之外的空白由它接收（列表项点击走 template + fill-in）",
            root.hasOnClickListeners()
        )
        assertEquals(
            "背景色必须画在根容器上（列表只有内容那么高，其余面积由根绘制）",
            Color.RED,
            (root.background as ColorDrawable).color
        )
    }

    // ---------- 列表数据（RemoteViewsFactory） ----------

    @Test
    fun factory_rendersSavedContentAsOneItem_withoutStretchingIt() {
        assertTrue("前置条件：写入内容", runBlocking { ContentStore.write("第一行\n第二行") })

        val factory = TextWidgetService.TextWidgetFactory(app)
        factory.onCreate()

        assertEquals("整段内容仍作为单条列表项渲染", 1, factory.getCount())
        val item = factory.getViewAt(0).apply(app, null) as TextView
        assertEquals("第一行\n第二行", item.text.toString())
        assertEquals(
            "列表项不能再被撑高（旧实现会按宿主报告的高度 setMinimumHeight）",
            0,
            item.minimumHeight
        )
    }

    @Test
    fun factory_emptyContent_showsEmptyHint() {
        assertTrue("前置条件：写入空内容", runBlocking { ContentStore.write("") })

        val factory = TextWidgetService.TextWidgetFactory(app)
        factory.onCreate()

        val item = factory.getViewAt(0).apply(app, null) as TextView
        assertEquals(app.getString(R.string.widget_empty_hint), item.text.toString())
    }

    @Test
    fun factory_readFailure_showsReadFailedInsteadOfBlank() {
        ContentStore.readOverride = { ContentRead.Failed(IOException("模拟读失败")) }

        val factory = TextWidgetService.TextWidgetFactory(app)
        factory.onCreate()

        val item = factory.getViewAt(0).apply(app, null) as TextView
        assertEquals(
            "读盘失败不能显示成空白/空便签（v8.4.0 审计修复）",
            app.getString(R.string.widget_read_failed),
            item.text.toString()
        )
    }

    @Test
    fun service_buildsFactoryFromIntentWithoutWidgetId() {
        // 新实现不需要 EXTRA_APPWIDGET_ID，也不需要读 OPTION_APPWIDGET_MIN_HEIGHT
        val service = Robolectric.buildService(TextWidgetService::class.java).create().get()
        val factory = service.onGetViewFactory(Intent())
        factory.onCreate()

        assertEquals(1, factory.getCount())
        assertNotNull("列表项视图必须能正常创建", factory.getViewAt(0))
    }

    // ---------- 工具 ----------

    private fun inflateWidget(): Pair<View, ListView> {
        val root = LayoutInflater.from(app).inflate(R.layout.widget_layout, null)
        return root to root.findViewById(R.id.widget_list)
    }

    private fun linesAdapter(vararg lines: String): ArrayAdapter<String> =
        ArrayAdapter(app, R.layout.widget_list_item, lines.toList())

    /** 按给定的「小组件尺寸」测量并布局，模拟桌面量出来的固定大小 */
    private fun layoutWidget(
        root: View,
        widthPx: Int = 400,
        heightPx: Int = 400
    ) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, widthPx, heightPx)
    }

    private fun dispatchTap(target: View, x: Float, y: Float) {
        target.dispatchTouchEvent(
            MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        )
        target.dispatchTouchEvent(
            MotionEvent.obtain(0L, 80L, MotionEvent.ACTION_UP, x, y, 0)
        )
    }
}
