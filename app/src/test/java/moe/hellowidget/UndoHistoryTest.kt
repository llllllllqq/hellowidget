package moe.hellowidget

import android.text.SpannableStringBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v7.7「无限次撤回」算法回归测试。
 *
 * 需求原文：「撤回按钮，可以无限次撤回用户编辑，直到变回刚打开时的内容」，
 * 对应下面三条契约：每一次编辑都能撤（不设深度上限）、撤回终点精确等于初始内容、
 * 撤回本身不会被记成一步新编辑。
 *
 * 这里用 [Doc] 按框架的真实时序驱动 [UndoHistory]（旧文本 → `beforeTextChanged`
 * → 真正替换 → `onTextChanged`，与 TextView 内部 ChangeWatcher 完全一致）。
 * 之所以不把 `UndoHistory` 当作 span 挂到 `SpannableStringBuilder` 上：
 * 实测 `SpannableStringBuilder` 只把回调发给与变更区间**重叠**的 watcher span，
 * 零长度 span 会漏掉绝大多数编辑，测试会得到「什么都没记录」的假象。
 * 「真实 EditText + addTextChangedListener」这条端到端接线由 [MainActivityUndoTest] 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UndoHistoryTest {

    /** 一份「文本 + 挂在它上面的撤回历史」，编辑动作按框架时序走 */
    private class Doc(initial: String) {
        val text = SpannableStringBuilder(initial)
        val history = UndoHistory()

        fun insert(where: Int, what: String) = replace(where, where, what)

        fun append(what: String) = insert(text.length, what)

        fun delete(start: Int, end: Int) = replace(start, end, "")

        /** 旧文本 → beforeTextChanged → 真正替换 → onTextChanged */
        fun replace(start: Int, end: Int, replacement: String) {
            val removedCount = end - start
            history.beforeTextChanged(text, start, removedCount, replacement.length)
            text.replace(start, end, replacement)
            history.onTextChanged(text, start, removedCount, replacement.length)
        }

        fun undo(): Int? = history.undo(text)

        override fun toString(): String = text.toString()
    }

    @Test
    fun undo_restoresTheTextFromBeforeTheOnlyEdit() {
        val doc = Doc("abc")
        doc.insert(1, "X")
        assertEquals("前置条件：编辑生效", "aXbc", doc.toString())

        val caret = doc.undo()

        assertNotNull("必须撤回成功", caret)
        assertEquals("撤回后必须回到编辑前的内容", "abc", doc.toString())
        assertFalse("已经回到最初内容，不该还有可撤回的编辑", doc.history.canUndo)
    }

    @Test
    fun undo_walksBackEveryEdit_untilTheContentAtOpen() {
        val doc = Doc("hello world")
        doc.insert(0, "#")           // #hello world
        doc.delete(1, 6)             // # world
        doc.replace(2, 7, "EARTH")   // # EARTH
        doc.append("!")              // # EARTH!
        assertEquals("# EARTH!", doc.toString())
        assertEquals("四次编辑必须全部被记录", 4, doc.history.size)

        assertNotNull(doc.undo())
        assertEquals("# EARTH", doc.toString())
        assertNotNull(doc.undo())
        assertEquals("# world", doc.toString())
        assertNotNull(doc.undo())
        assertEquals("#hello world", doc.toString())
        assertNotNull(doc.undo())
        assertEquals("最后一次撤回必须精确回到刚打开时的内容", "hello world", doc.toString())

        assertEquals(0, doc.history.size)
        assertNull("已经回到最初内容后再撤回应当无动作", doc.undo())
        assertEquals("无边界的连续撤回不能改变内容", "hello world", doc.toString())
    }

    @Test
    fun undo_isNotRecordedAsANewEdit() {
        val doc = Doc("abc")
        doc.append("1")
        doc.append("2")
        assertEquals(2, doc.history.size)

        doc.undo()

        assertEquals("撤回本身不能被记成一步新编辑（否则会变成「重做」）", 1, doc.history.size)
        assertTrue("还有一步可撤回", doc.history.canUndo)
    }

    @Test
    fun aNewEditAfterUndo_replacesTheUndonePath_andStillUndoesToTheOpenContent() {
        val doc = Doc("abc")
        doc.append("1") // abc1
        doc.undo()
        assertEquals("abc", doc.toString())

        doc.append("2") // abc2
        assertEquals(1, doc.history.size)

        doc.undo()
        assertEquals("撤销后新编辑的撤回目标仍然是刚打开时的内容", "abc", doc.toString())
        assertFalse(doc.history.canUndo)
    }

    @Test
    fun undo_hasNoDepthLimit() {
        val doc = Doc("0")
        repeat(60) { doc.append("x") }
        assertEquals(60, doc.history.size)

        var undone = 0
        while (doc.history.canUndo) {
            assertNotNull(doc.undo())
            undone++
        }

        assertEquals("60 次编辑必须全部可以撤回（不设深度上限）", 60, undone)
        assertEquals("0", doc.toString())
    }

    @Test
    fun undo_putsTheCaretAtTheChangeSite() {
        val doc = Doc("abcdef")
        doc.insert(3, "XY") // abcXYdef
        assertEquals("撤销「插入」后光标应落在插入点", 3, doc.undo())
        assertEquals("abcdef", doc.toString())

        doc.insert(3, "XY") // abcXYdef
        doc.delete(2, 6)    // abef（删掉 cXYd）
        assertEquals("abef", doc.toString())
        assertEquals("撤销「删除」后光标应落在被还原内容的末尾", 6, doc.undo())
        assertEquals("abcXYdef", doc.toString())
    }

    @Test
    fun undoOfAMultiCharReplacement_restoresTheWholeRemovedRun() {
        val doc = Doc("1234567890")
        doc.replace(2, 7, "abcd") // 12abcd890
        assertEquals("12abcd890", doc.toString())

        doc.undo()

        assertEquals("一次替换掉 5 个字符，撤回必须把它们整段还原", "1234567890", doc.toString())
    }

    @Test
    fun undo_withEmptyHistory_returnsNullAndLeavesTextAlone() {
        val doc = Doc("打开时的内容")

        assertNull(doc.undo())

        assertEquals("打开时的内容", doc.toString())
    }
}
