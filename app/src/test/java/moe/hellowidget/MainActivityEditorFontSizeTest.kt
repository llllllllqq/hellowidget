package moe.hellowidget

import android.app.Application
import android.util.TypedValue
import moe.hellowidget.MainActivity.Companion.prefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 需求 5：「设置里的小部件文字大小，会同步对应修改编辑器区域的文字大小」。
 *
 * 小组件字体大小存在 [WidgetSettings.KEY_FONT_SIZE]（sp）。编辑器每次回到前台
 * 都会重新应用外观，因此「改完设置返回 → 字号立刻跟着变」是可验证的确定性行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityEditorFontSizeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    /** sp → px（与 setTextSize 内部用的是同一套度量，避免依赖模拟器密度） */
    private fun px(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, app.resources.displayMetrics)

    @Test
    fun editorFontSize_equalsTheWidgetFontSizeSetting() {
        app.prefs.edit().putFloat(WidgetSettings.KEY_FONT_SIZE, 22f).apply()

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)

        assertEquals(
            "编辑器字号必须等于设置里的「字体大小」",
            px(22f),
            editorOf(activity).textSize,
            0.01f
        )
    }

    @Test
    fun editorFontSize_defaultsToTheWidgetDefault() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)

        assertEquals(
            "未改过设置时，编辑器字号应当就是小组件默认字号",
            px(WidgetSettings.DEFAULT_FONT_SP),
            editorOf(activity).textSize,
            0.01f
        )
    }

    @Test
    fun editorFontSize_isReappliedAfterComingBackFromSettings() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)

        // 用户在设置页把字体大小拖到 30sp，然后返回编辑页（onResume 重新应用外观）
        app.prefs.edit().putFloat(WidgetSettings.KEY_FONT_SIZE, 30f).apply()
        controller.pause().resume()

        assertEquals(
            "从设置页返回后编辑器字号必须跟着更新",
            px(30f),
            editorOf(activity).textSize,
            0.01f
        )
    }
}
