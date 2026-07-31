package moe.hellowidget

import android.content.Context
import android.graphics.Color
import moe.hellowidget.MainActivity.Companion.prefs

/**
 * 桌面小组件外观设置：字体大小、字体颜色、背景颜色、背景透明度。
 * 所有设置存入 SharedPreferences，由设置页写入，
 * 由 TextWidgetProvider / TextWidgetService 读取并应用到小组件。
 */
object WidgetSettings {

    const val KEY_FONT_SIZE = "widget_font_size"   // Float，单位 sp
    const val KEY_TEXT_COLOR = "widget_text_color" // Int，ARGB
    const val KEY_BG_COLOR = "widget_bg_color"     // Int，ARGB（Color.TRANSPARENT = 无背景）
    const val KEY_BG_ALPHA = "widget_bg_alpha"     // Int，0..100（百分比）

    const val DEFAULT_FONT_SP = 14f
    const val DEFAULT_TEXT_COLOR = Color.WHITE
    const val DEFAULT_BG_COLOR = Color.TRANSPARENT
    const val DEFAULT_BG_ALPHA = 100

    fun fontSp(context: Context): Float =
        context.prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SP)

    fun textColor(context: Context): Int =
        context.prefs.getInt(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR)

    /** 用户选择的背景颜色（不含透明度） */
    fun bgColor(context: Context): Int =
        context.prefs.getInt(KEY_BG_COLOR, DEFAULT_BG_COLOR)

    /** 背景透明度 0..100 */
    fun bgAlpha(context: Context): Int =
        context.prefs.getInt(KEY_BG_ALPHA, DEFAULT_BG_ALPHA)

    /**
     * 背景颜色 × 透明度合成后的最终 ARGB 颜色。
     * 未选择背景（透明）时返回 Color.TRANSPARENT。
     */
    fun effectiveBgColor(context: Context): Int {
        val base = bgColor(context)
        if (base == Color.TRANSPARENT) return Color.TRANSPARENT
        val alpha = (bgAlpha(context).coerceIn(0, 100) * 255 / 100)
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }
}
