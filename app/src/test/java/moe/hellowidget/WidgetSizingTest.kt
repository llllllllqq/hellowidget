package moe.hellowidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 小组件尺寸契约（v8.5.1）。
 *
 * 这些值只影响「添加到桌面时默认多大」与「最小能拖到多小」，渲染本身与尺寸无关
 * （v8.5.0 起列表按内容高度、空白由根容器接住点击）。launcher 的格子折算无法在 CI 里
 * 观测，所以这里只钉住**我们声明的契约**，实际效果由用户在桌面上验收。
 *
 * 数值依据官方折算表（1 格 = 40dp，n 格 ≈ 70n − 30dp）：
 *  - 默认 4 列 × 3 行：minWidth 250dp / minHeight 180dp（API ≤30 的等效默认值）
 *    + targetCellWidth 4 / targetCellHeight 3（API 31+ 的默认值）
 *  - 缩放下限 2 列 × 1 行：minResizeWidth 110dp / minResizeHeight 40dp
 *    （不声明 minResize* 时下限会默认等于 minWidth/minHeight，等于永远缩不小）
 */
class WidgetSizingTest {

    @Test
    fun declaredSizing_matchesTheAgreedContract() {
        val provider = widgetInfoRoot()

        // Android 12+ 的默认摆放：4×3
        assertEquals("4", provider.attr("targetCellWidth"))
        assertEquals("3", provider.attr("targetCellHeight"))

        // Android 11 及以下的等效默认（按 70n−30 折算：250dp≈4 格，180dp≈3 格）
        assertEquals("250dp", provider.attr("minWidth"))
        assertEquals("180dp", provider.attr("minHeight"))

        // 缩放下限：2 列 × 1 行（官方表 110dp≈2 格、40dp=1 格）
        assertEquals("110dp", provider.attr("minResizeWidth"))
        assertEquals("40dp", provider.attr("minResizeHeight"))

        // 下限必须真的小于默认，否则等于不允许缩小
        assertTrue(
            "minResizeWidth 必须小于 minWidth（否则用户缩不到 2 列）",
            dpOf(provider.attr("minResizeWidth")) < dpOf(provider.attr("minWidth"))
        )
        assertTrue(
            "minResizeHeight 必须小于 minHeight（否则用户缩不到 1 行）",
            dpOf(provider.attr("minResizeHeight")) < dpOf(provider.attr("minHeight"))
        )

        // 其余绑定：布局、缩放方向、无常驻刷新
        assertEquals("@layout/widget_layout", provider.attr("initialLayout"))
        assertEquals("@layout/widget_preview", provider.attr("previewLayout"))
        assertTrue(
            "必须允许横向与纵向缩放",
            provider.attr("resizeMode").contains("horizontal") &&
                provider.attr("resizeMode").contains("vertical")
        )
        assertEquals(
            "不许有定时刷新（应用只在保存/设置变化时主动刷新）",
            "0",
            provider.attr("updatePeriodMillis")
        )
    }

    // ---------- 工具 ----------

    private fun widgetInfoRoot(): Element {
        // Gradle 单测的工作目录通常是模块目录（app/），但不同 AGP/IDE 下可能是仓库根，
        // 两个都试一遍；都找不到时把当前目录打进错误信息，便于定位而不是静默跳过。
        val file = listOf(
            File("src/main/res/xml/widget_info.xml"),
            File("app/src/main/res/xml/widget_info.xml")
        ).firstOrNull { it.isFile }
            ?: error("找不到 widget_info.xml（当前工作目录：${File(".").absolutePath}）")

        val factory = DocumentBuilderFactory.newInstance()
        val root = factory.newDocumentBuilder().parse(file).documentElement
        assertEquals("appwidget-provider", root.tagName)
        return root
    }

    /** 源码 XML 里 android 命名空间没有前缀绑定（`xmlns:android` 在根上），按限定名取即可 */
    private fun Element.attr(name: String): String = getAttribute("android:$name")

    private fun dpOf(value: String): Int =
        value.removeSuffix("dp").toIntOrNull()
            ?: error("尺寸必须写成整数 dp，实际：$value")
}
