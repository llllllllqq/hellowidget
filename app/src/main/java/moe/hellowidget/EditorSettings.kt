package moe.hellowidget

import android.content.Context
import android.graphics.Color
import moe.hellowidget.MainActivity.Companion.prefs

/**
 * 编辑器（编辑页）颜色设置：浅色模式 / 深色模式各自的背景色与文字色。
 * 与小组件颜色（WidgetSettings）相互独立，互不影响。
 */
object EditorSettings {

    const val KEY_LIGHT_BG = "editor_light_bg"     // Int，ARGB
    const val KEY_LIGHT_TEXT = "editor_light_text" // Int，ARGB
    const val KEY_DARK_BG = "editor_dark_bg"       // Int，ARGB
    const val KEY_DARK_TEXT = "editor_dark_text"   // Int，ARGB

    // 默认值与系统主题一致：浅色=白底黑字，深色=黑底白字
    const val DEFAULT_LIGHT_BG = Color.WHITE
    const val DEFAULT_LIGHT_TEXT = Color.BLACK
    const val DEFAULT_DARK_BG = Color.BLACK
    const val DEFAULT_DARK_TEXT = Color.WHITE

    fun lightBg(context: Context): Int =
        context.prefs.getInt(KEY_LIGHT_BG, DEFAULT_LIGHT_BG)

    fun lightText(context: Context): Int =
        context.prefs.getInt(KEY_LIGHT_TEXT, DEFAULT_LIGHT_TEXT)

    fun darkBg(context: Context): Int =
        context.prefs.getInt(KEY_DARK_BG, DEFAULT_DARK_BG)

    fun darkText(context: Context): Int =
        context.prefs.getInt(KEY_DARK_TEXT, DEFAULT_DARK_TEXT)

    /**
     * 当前模式下的编辑器背景色。
     * Color.TRANSPARENT（「无背景」）= 跟随系统主题背景（浅色=白，深色=黑）。
     */
    fun bg(context: Context, nightMode: Boolean): Int =
        if (nightMode) darkBg(context) else lightBg(context)

    /** 当前模式下的编辑器文字色 */
    fun textColor(context: Context, nightMode: Boolean): Int =
        if (nightMode) darkText(context) else lightText(context)
}
