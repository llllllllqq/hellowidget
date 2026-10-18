package moe.hellowidget

import android.text.SpannableStringBuilder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * v8.4.0（用户决定）：正文上限 100,000 → **10,000** 字符。
 *
 * 上限降低后，磁盘上可能还躺着更长的历史内容（老版本写下的）。这里的契约是：
 *  1. **完整加载，一个字都不丢**（加载时临时摘掉 InputFilter，不依赖框架细节）；
 *  2. 只能删不能加 —— 任何净增长都被拒绝，删除永远放行；
 *  3. 给一次明确提示，而不是让用户对着"打不进字"的编辑器发呆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityOversizeContentTest {

    @After
    fun tearDown() {
        ContentStore.readOverride = null
        ContentStore.writeOverride = null
    }

    @Test
    fun oversizedStoredContent_loadsInFull_andOnlyAllowsTrimming() {
        val limit = MainActivity.MAX_CONTENT_CHARS
        val oversized = "字".repeat(limit + 500)
        assertTrue("前置条件：写入超限内容", runBlocking { ContentStore.write(oversized) })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor = editorOf(activity)
        awaitEditorEnabled(activity)

        assertEquals("超长内容必须完整加载（一个字都不能丢）", oversized, editor.text.toString())
        assertEquals(
            "超限时必须给一次明确提示（否则用户只知道打不进字）",
            activity.getString(R.string.editor_over_limit_after_upgrade, oversized.length, limit),
            ShadowToast.getTextOfLatestToast()
        )

        val filter = editor.filters.single()

        // 超限状态下：任何净增长的输入都必须被拒绝
        val insertion = filter.filter("x", 0, 1, editor.text, 0, 0)
        assertTrue("超限时插入必须被完全拒绝，实际：$insertion", insertion != null && insertion.isEmpty())

        // 删除永远放行（不拦截、不提示）
        assertNull(
            "删除必须放行（超限内容只能靠删减回到上限以内）",
            filter.filter("", 0, 0, editor.text, 0, 3)
        )

        // 删到上限以内之后，输入应当恢复正常
        val atLimitMinusOne = SpannableStringBuilder(editor.text.subSequence(0, limit - 1))
        assertNull(
            "删到上限以内后必须能继续输入",
            filter.filter("x", 0, 1, atLimitMinusOne, 0, 0)
        )
        // 正好在上限时，再增加一个字符仍然要被拒绝
        val atLimit = SpannableStringBuilder(editor.text.subSequence(0, limit))
        val overLimitInsertion = filter.filter("x", 0, 1, atLimit, 0, 0)
        assertTrue(
            "正好到达上限后不得再增长",
            overLimitInsertion != null && overLimitInsertion.isEmpty()
        )
    }
}
