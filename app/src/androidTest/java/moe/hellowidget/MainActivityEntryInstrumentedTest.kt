package moe.hellowidget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * v7.1 新功能的**真机级**验证（CI 里跑在 Android 模拟器上），v7.7 起同时覆盖顶部导航栏。
 *
 * 为什么需要这一层：JVM/Robolectric 里没有真实输入法，「输入法到底有没有弹出来」
 * 只能在真实 Android 运行环境里观察到。这里验证：
 *  1. 进入应用后光标落在第一行行首、编辑器持有焦点；
 *  2. **输入法真的变得可见**（WindowInsetsCompat.Type.ime()）；
 *  3. 输入法弹出后编辑器最终仍持有焦点，且**敲键真的能打进编辑器、插在行首**
 *     —— 这才是「打开即输入」真正可用的证据；
 *  4. 输入法弹出后 Activity 仍持有窗口焦点，且**没有触发失焦保存**
 *     （AOSP 中 IME 窗口带 FLAG_NOT_FOCUSABLE，不会夺走 Activity 的窗口焦点）；
 *  5. **顶部导航栏真的装载了 4 个入口**（设置 / WebDAV 同步 / 立即上传 / 撤回），
 *     顶部系统栏高度由独立占位条承担、导航栏内容区高度完整，并且**标题与 4 个图标
 *     真的被画进了像素里**（v7.7.1 回归：旧实现把系统栏 inset 当成导航栏自己的 padding，
 *     在系统栏很高的手机上标题被裁成底部一条缝、4 个按钮完全看不见；
 *     v8.3.0 起顶栏与编辑区同色，判据随之改成「与底色形成对比的像素」，
 *     见 [contrastingPixelsIn]）；
 *  6. 编辑区没有被键盘遮住（targetSdk 35 边到边下必须自行消费 ime insets）。
 *
 * 关于「最终」：实测输入法首帧可见时焦点可能短暂不在编辑器上，
 * 因此第 3 条允许过渡重试（并把焦点时间线写进失败信息与 logcat，便于定位）。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityEntryInstrumentedTest {

    private val seed = "第一行内容\n第二行内容"
    private val tag = "HelloWidgetEntryTest"

    /** 与 ContentStore 内部文件名一致；用于检测「不该发生的写盘」 */
    private val contentFileName = "user_content.dat"

    @Before
    fun seedContent() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write(seed) })
    }

    /**
     * 本类跑完把同步状态复位：同一次 instrumentation 里还跑着同步相关的测试类，
     * 残留的「已启用同步 + 已排重试任务」会让后续用例看到不属于它们的后台行为。
     * （v8.2.0 删掉橙点用例后这条隔离仍然保留 —— 它防的是**其它类**留下的状态。）
     */
    @After
    fun resetSyncStateAfterEachTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SyncSettings.setEnabled(context, false)
        SyncSettings.resetRuntimeState(context)
    }

    @Test
    fun enteringApp_showsIme_withCursorAtStartOfFirstLine() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitEditorEnabled(scenario)

            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.editor)
                assertEquals("必须先恢复已保存的内容", seed, editor.text.toString())
                assertTrue("编辑器必须获得焦点", editor.hasFocus())
                assertEquals("光标必须落在第一行行首", 0, editor.selectionStart)
                assertEquals("不能存在选区", 0, editor.selectionEnd)
            }

            val contentModifiedBeforeIme = contentFileLastModified()
            assertTrue("进入应用后必须自动弹出输入法", awaitImeVisible(scenario))

            val timeline = awaitEditorFocused(scenario, timeoutMs = 5_000)
            Log.i(tag, "输入法弹出后的焦点时间线: ${timeline.description}")
            assertTrue(
                "输入法弹出后编辑器必须持有焦点。焦点时间线：${timeline.description}",
                timeline.editorFocused
            )

            assertEquals("输入法弹出后光标必须仍在第一行行首", 0, editorSelectionStart(scenario))

            // 功能性断言：键盘已弹出的情况下，输入必须真的进入编辑器，且插在光标处（行首）
            sendKey(KeyEvent.KEYCODE_X)
            val typed = awaitText(scenario) { it == "x$seed" }
            assertEquals("键盘弹出后输入必须真的落进编辑器（插在行首）", "x$seed", typed)

            scenario.onActivity { activity ->
                assertTrue("输入法弹出后 Activity 必须仍持有窗口焦点", activity.hasWindowFocus())
            }

            assertEquals(
                "弹出输入法不得触发失焦保存（内容文件不应被重写）",
                contentModifiedBeforeIme,
                contentFileLastModified()
            )

            assertEditorNotCoveredByIme(scenario)
        }
    }

    /**
     * v7.7 顶部导航栏：真机上 `toolbar.menu` 必须真的装载了 4 个入口
     * （Robolectric 只能验证我们调用了 onCreateOptionsMenu，无法证明框架把菜单装上了工具栏），
     * 且**标题与 4 个按钮必须占据完整高度、并且真的被画出来**。
     *
     * v7.7.1 的教训：旧实现在这里断言的是「toolbar.paddingTop >= 状态栏高度」——
     * 等于把事故的成因写成了预期行为，而模拟器的状态栏只有 24dp，
     * 于是「导航栏内容被系统栏挤没」这件事在两个 API 上全绿通过，用户的手机上却完全不可用。
     * 现在断言的是用户真正在意的结果：内容区高度完整、标题与图标真的出现在像素里
     * （v8.3.0 起判据是"与顶栏底色形成对比"，而不是固定的"白色/亮像素"）。
     */
    @Test
    fun topBar_holdsTheFourEntries_andItsContentIsNeverSqueezed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // 动作菜单与按钮视图在首次布局时装载，给它一点时间（不能在 onActivity 里 sleep：那是主线程）
            var menuSize = 0
            var itemViews: List<View> = emptyList()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                    menuSize = toolbar.menu.size()
                    itemViews = actionItemViews(toolbar)
                }
                if (menuSize >= 4 && itemViews.size >= 4 &&
                    itemViews.all { it.width > 0 && it.height > 0 }
                ) {
                    break
                }
                SystemClock.sleep(50)
            }
            assertEquals(
                "顶部导航栏必须装载 4 个入口（设置 / WebDAV 同步 / 立即上传 / 撤回）",
                4,
                menuSize
            )

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                for (id in intArrayOf(
                    R.id.action_appearance,
                    R.id.action_webdav,
                    R.id.action_upload,
                    R.id.action_undo
                )) {
                    assertNotNull("顶部导航栏缺少入口 id=$id", toolbar.menu.findItem(id))
                }

                val barHeight = activity.resources.getDimensionPixelSize(R.dimen.top_bar_height)
                val strip = activity.findViewById<View>(R.id.status_bar_spacer)
                val insets = ViewCompat.getRootWindowInsets(toolbar)
                assertNotNull("必须能读到窗口 insets", insets)
                val windowInsets = requireNotNull(insets) { "必须能读到窗口 insets" }
                val topInset = maxOf(
                    windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                    windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
                )
                assertTrue("顶部系统栏 inset 必须大于 0", topInset > 0)
                Log.i(
                    tag,
                    "顶部系统栏=${topInset}px，占位条高=${strip.height}px，" +
                        "导航栏高=${toolbar.height}px paddingTop=${toolbar.paddingTop}px"
                )

                // 1) 系统栏那一条必须由占位条承担；导航栏自身高度固定，内容区不被 insets 挤压
                assertEquals("系统栏高度必须由占位条承担", topInset, strip.height)
                assertEquals("导航栏自己不得被系统栏 inset 挤压", 0, toolbar.paddingTop)
                assertEquals("导航栏高度必须是固定值", barHeight, toolbar.height)
                val contentHeight = toolbar.height - toolbar.paddingTop - toolbar.paddingBottom
                assertTrue(
                    "导航栏内容可用高度必须完整：${contentHeight}px（应为 ${barHeight}px）",
                    contentHeight >= barHeight - 2
                )
                assertEquals("导航栏必须紧贴在占位条下方", strip.height, toolbar.top)

                // 2) 标题必须真的以正常字号呈现（而不是被挤成一条缝）
                val title = (0 until toolbar.childCount)
                    .map { toolbar.getChildAt(it) }
                    .filterIsInstance<TextView>()
                    .firstOrNull()
                assertNotNull("导航栏标题视图必须存在", title)
                val titleView = requireNotNull(title) { "导航栏标题视图必须存在" }
                Log.i(
                    tag,
                    "标题='${titleView.text}' 高=${titleView.height}px 字号=${titleView.textSize}px " +
                        "可见=${titleView.isShown}"
                )
                assertTrue("标题必须可见", titleView.isShown)
                assertTrue(
                    "标题高度必须容得下它的字号（旧实现只剩几个像素）：" +
                        "${titleView.height}px vs 字号 ${titleView.textSize}px",
                    titleView.height >= titleView.textSize * 0.8f
                )

                // 3) 4 个按钮都必须有正常尺寸且可见
                val items = actionItemViews(toolbar)
                assertEquals("顶部导航栏必须渲染出 4 个动作按钮", 4, items.size)
                val minTap = (24 * activity.resources.displayMetrics.density).toInt()
                items.forEachIndexed { index, item ->
                    assertTrue("第 ${index + 1} 个动作按钮必须可见", item.isShown)
                    assertTrue(
                        "第 ${index + 1} 个动作按钮尺寸必须正常：${item.width}x${item.height}px" +
                            "（至少 ${minTap}x${minTap}）",
                        item.width >= minTap && item.height >= minTap
                    )
                }

                // 4) 最终证据：把导航栏画进 Bitmap，标题与 4 个按钮的位置上必须真的出现
                //    **与底色形成对比**的像素（v8.3.0 起底色 = 编辑器配色：
                //    浅色白底黑字 / 深色黑底白字，所以不能再数"亮像素"——
                //    白底上到处都是亮像素，那样等于什么都没验证）。
                val barColor = (toolbar.background as? ColorDrawable)?.color ?: Color.TRANSPARENT
                val night = (activity.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                val editorBg = EditorSettings.bg(activity, night)
                if (editorBg != Color.TRANSPARENT) {
                    assertEquals("顶栏底色必须与编辑区背景色相同", editorBg, barColor)
                } else {
                    // 用 assertTrue 而不是 assertNotEquals：JUnit 对 (String, int, int) 的
                    // assertNotEquals 重载在 Kotlin 下容易撞上装箱/加宽二义性，这里不需要冒这个险。
                    assertTrue(
                        "「无背景」时顶栏取窗口底色，也不能再是旧品牌紫",
                        barColor != Color.parseColor("#FF6200EE")
                    )
                }
                assertEquals(
                    "状态栏那条占位条必须与顶栏同色（视觉上是一条整顶栏）",
                    barColor,
                    (strip.background as? ColorDrawable)?.color
                )
                Log.i(tag, "顶栏底色=#${Integer.toHexString(barColor)}（编辑器背景=#${Integer.toHexString(editorBg)}）")

                val bitmap = Bitmap.createBitmap(toolbar.width, toolbar.height, Bitmap.Config.ARGB_8888)
                toolbar.draw(Canvas(bitmap))
                val toolbarLocation = IntArray(2)
                toolbar.getLocationInWindow(toolbarLocation)
                val titlePainted =
                    contrastingPixelsIn(bitmap, boundsInToolbar(toolbarLocation, titleView), barColor)
                assertTrue("标题必须真的被画出来（与底色不同的像素=$titlePainted）", titlePainted >= 30)
                items.forEachIndexed { index, item ->
                    val painted =
                        contrastingPixelsIn(bitmap, boundsInToolbar(toolbarLocation, item), barColor)
                    assertTrue(
                        "第 ${index + 1} 个按钮的图标必须真的被画出来（与底色不同的像素=$painted）",
                        painted >= 30
                    )
                }

                // 5) 编辑区必须从导航栏下方开始，不能压在导航栏上
                val editorLocation = IntArray(2)
                activity.findViewById<View>(R.id.editor_scroll).getLocationOnScreen(editorLocation)
                assertTrue(
                    "编辑区必须从顶部导航栏下方开始：编辑区顶部=${editorLocation[1]}，" +
                        "导航栏底部=${toolbarLocation[1] + toolbar.height}",
                    editorLocation[1] >= toolbarLocation[1] + toolbar.height - 16
                )
            }

            // 6) 留一张真机截图给 CI 归档（最直观的证据）。此刻 MainActivity 就在前台，
            //    由 `adb shell am start` 拉起再截图的做法在模拟器上并不可靠（keyguard / 时序），
            //    所以截图直接由测试自己拍。
            saveScreenshot("topbar_normal")
        }
    }

    // ---------- 断言辅助 ----------

    private class FocusTimeline(val editorFocused: Boolean, val description: String)

    /**
     * 把当前屏幕存成 PNG，并复制到 `/sdcard/Download/hellowidget-shots/` 供 CI `adb pull` 归档。
     *
     * 为什么要复制到公共目录：`connectedAndroidTest` 跑完后 AGP 会**卸载应用并删掉应用数据目录**，
     * 只写在应用自己目录里的截图会被一并清掉（实测 `adb pull` 报 No such file or directory）。
     *
     * 为什么由测试自己截图：模拟器上「`adb shell am start` 拉起应用再 screencap」并不可靠
     * （keyguard、启动时序、以及上面那条卸载行为都可能让截图拍到桌面）。测试执行到这里时
     * MainActivity 必然在前台且已完成布局，`UiAutomation#takeScreenshot` 拍到的就是用户看到的界面。
     */
    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = try {
            instrumentation.uiAutomation.takeScreenshot()
        } catch (error: Throwable) {
            Log.w(tag, "截图失败（不影响测试结论）：$error")
            null
        }
        val dir = instrumentation.targetContext.filesDir
        if (bitmap == null || dir == null) {
            Log.w(tag, "截图不可用（bitmap=$bitmap, dir=$dir）")
            return
        }
        val file = File(dir, "${name}_api${Build.VERSION.SDK_INT}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(tag, "已保存截图 ${file.absolutePath}（${bitmap.width}x${bitmap.height}）")

        // 再让 shell 用 run-as 把文件读出来重定向到公共目录：写在自己目录里的截图会随
        // connectedAndroidTest 结束后的卸载被清掉，而 shell 又读不到 /sdcard/Android/data/…
        // （API 30+ 的分区存储限制，实测 `adb pull` 拿到 0 个文件）。
        val publicDir = "/sdcard/Download/hellowidget-shots"
        try {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "mkdir -p $publicDir && run-as ${instrumentation.targetContext.packageName} " +
                    "cat ${file.name} > $publicDir/${file.name}"
            )
            val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            Log.i(tag, "已复制截图到 $publicDir/${file.name}（shell 输出 ${output.size} 字节）")
        } catch (error: Throwable) {
            Log.w(tag, "复制截图到公共目录失败（不影响测试结论）：$error")
        }
    }

    /**
     * 工具栏里 AppCompat 为 `showAsAction="always"` 创建的按钮视图。
     *
     * 按类名匹配而不是按 id：按钮视图是 AppCompat 在运行时创建的（`ActionMenuItemView`），
     * 我们自己的布局里没有它，也不该依赖它的具体 id。
     */
    private fun actionItemViews(toolbar: ViewGroup): List<View> {
        val found = mutableListOf<View>()
        fun walk(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child.javaClass.name.contains("ActionMenuItemView")) found += child
                if (child is ViewGroup) walk(child)
            }
        }
        walk(toolbar)
        return found
    }

    /** 子视图相对于 [origin]（窗口坐标）的边界：left, top, right, bottom */
    private fun boundsInToolbar(origin: IntArray, view: View): IntArray {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val left = location[0] - origin[0]
        val top = location[1] - origin[1]
        return intArrayOf(left, top, left + view.width, top + view.height)
    }

    /**
     * 统计位图指定矩形内「与顶栏底色形成对比」的像素数。
     *
     * v8.3.0 起顶栏底色 = 编辑器配色（默认浅色=白底黑字、深色=黑底白字），
     * 因此判据必须**相对实际底色**，而不是像 v7.7 那样固定数"亮像素"：
     * 白底上到处都是亮像素，固定阈值等于什么都没验证。
     *
     * 这里数亮度差 ≥ 60 的像素 —— 这才是"文字和按钮真的画到了屏幕上"的证据，
     * 只看视图尺寸不够（v7.7 的事故里视图都在、尺寸也不为零，但内容被裁得看不见）。
     * 阈值 60 对禁用状态也成立：禁用图标是 50% alpha 的黑叠在白底上（亮度约 127 vs 255）。
     */
    private fun contrastingPixelsIn(bitmap: Bitmap, bounds: IntArray, background: Int): Int {
        val backgroundLuminance = luminanceOf(background)
        var count = 0
        for (y in maxOf(0, bounds[1]) until minOf(bitmap.height, bounds[3])) {
            for (x in maxOf(0, bounds[0]) until minOf(bitmap.width, bounds[2])) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) < 200) continue
                if (abs(luminanceOf(pixel) - backgroundLuminance) >= 60) count++
            }
        }
        return count
    }

    private fun luminanceOf(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    /** 记录焦点归属时间线，直到编辑器拿到焦点或超时 */
    private fun awaitEditorFocused(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long
    ): FocusTimeline {
        val samples = mutableListOf<String>()
        var lastSample: String? = null
        var focused = false
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            var sample = ""
            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.editor)
                focused = editor.hasFocus()
                val current = activity.currentFocus
                sample = "编辑器有焦点=$focused" +
                    ", 当前焦点视图=${current?.let { "${it.javaClass.simpleName}#${it.id}" } ?: "无"}" +
                    ", Activity有窗口焦点=${activity.hasWindowFocus()}" +
                    ", 编辑器可见=${editor.isShown}" +
                    ", 触摸模式可聚焦=${editor.isFocusableInTouchMode}" +
                    ", 处于触摸模式=${editor.isInTouchMode}"
            }
            if (sample != lastSample) {
                samples += "[${SystemClock.uptimeMillis()}ms] $sample"
                lastSample = sample
            }
            if (focused || SystemClock.uptimeMillis() >= deadline) break
            SystemClock.sleep(100)
        }
        return FocusTimeline(focused, samples.joinToString(" → "))
    }

    private fun awaitEditorEnabled(scenario: ActivityScenario<MainActivity>, timeoutMs: Long = 10_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var enabled = false
        while (SystemClock.uptimeMillis() < deadline && !enabled) {
            scenario.onActivity { activity ->
                enabled = activity.findViewById<EditText>(R.id.editor).isEnabled
            }
            if (!enabled) SystemClock.sleep(50)
        }
        assertTrue("等待内容加载完成（编辑器解禁）超时", enabled)
    }

    private fun awaitImeVisible(scenario: ActivityScenario<MainActivity>, timeoutMs: Long = 8_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var visible = false
        while (SystemClock.uptimeMillis() < deadline && !visible) {
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.findViewById<View>(R.id.editor))
                visible = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime())
            }
            if (!visible) SystemClock.sleep(100)
        }
        return visible
    }

    private fun awaitText(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 3_000,
        predicate: (String) -> Boolean
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var text = editorText(scenario)
        while (!predicate(text) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            text = editorText(scenario)
        }
        return text
    }

    private fun sendKey(keyCode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun editorText(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { activity -> text = activity.findViewById<EditText>(R.id.editor).text.toString() }
        return text
    }

    private fun editorSelectionStart(scenario: ActivityScenario<MainActivity>): Int {
        var start = -1
        scenario.onActivity { activity -> start = activity.findViewById<EditText>(R.id.editor).selectionStart }
        return start
    }

    private fun contentFileLastModified(): Long = File(
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        contentFileName
    ).lastModified()

    /**
     * 输入法弹出时**编辑区**不能被键盘遮住：targetSdk 35 边到边之后窗口不再为键盘让位，
     * 只有自行消费 ime insets 才能保证聚焦/可见的内容留在键盘之上（v7.1 起就存在的约束，
     * v7.7 把底部按钮移走后改为对编辑区本身断言）。
     */
    private fun assertEditorNotCoveredByIme(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<View>(android.R.id.content)
            val editor = activity.findViewById<View>(R.id.editor_scroll)
            val insets = ViewCompat.getRootWindowInsets(root)
            assertNotNull("必须能读到窗口 insets", insets)

            val imeBottom = insets!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            assertTrue(
                "输入法可见时其 inset 高度必须大于 0；诊断=" + ImeInsetsDiag.dump(activity),
                imeBottom > 0
            )

            val editorLocation = IntArray(2)
            editor.getLocationOnScreen(editorLocation)
            val editorBottom = editorLocation[1] + editor.height
            val imeTop = activity.resources.displayMetrics.heightPixels - imeBottom
            assertTrue(
                "编辑区不能被输入法遮挡：编辑区底部=$editorBottom，键盘顶部=$imeTop",
                editorBottom <= imeTop + 16 // 16px 容差，避免亚像素/取整差异
            )
        }
    }
}
