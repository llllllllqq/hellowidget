package moe.hellowidget

import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * v8.5.2：`MainActivityUndoTest.undoHistory_survivesSystemRecreation` 偶发失败（`undoDepth`
 * 期望 1、实际 2）的回归用例。
 *
 * ## 根因（CI 诊断证据）
 * 系统重建时，框架会在 `restoreInstanceState` 里把保存的文本 `setText` 回来 —— 这也是一次
 * 文本变更，会触发注册在编辑器上的 TextWatcher。旧实现把「撤回监听器」挂在异步读盘的收尾里：
 * 而读盘偶尔比 `restoreInstanceState` 更早完成，于是监听器先被挂上、编辑器先被解禁，
 * 随后系统恢复的 `setText` 就被 [UndoHistory] 记成一次「用户编辑」，`undoDepth` 从 1 变 2。
 * 2026-11-07 的 CI 诊断里 200 次 recreate 复现 15 次；2026-10-06 的历史失败断言消息同样是
 * `expected:<1> but was:<2>`。
 *
 * 修复：只有 `onPostCreate`（在 `restoreInstanceState` 之后）与「磁盘内容已就位」两个条件
 * 都满足时，才挂监听器、解禁编辑（见 MainActivity.finishContentLoadIfPossible）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityUndoRecreateRaceTest {

    /**
     * 确定性地复现「读盘先于系统恢复完成」这条交错：
     *
     * 手工驱动生命周期，并在 `create()` 与 `restoreInstanceState()` 之间显式 idle 主线程，
     * 让异步读盘的收尾先跑完（旧实现此时已经挂上监听器），然后才做系统恢复。
     *
     * 正确的行为是：系统恢复写入的文本**不算**一次用户编辑。这个新 Activity 的 ViewModel 是
     * 全新的（没有任何历史），所以正确值是 `undoDepth == 0`。
     */
    @Test
    fun restoreText_isNotRecorded_whenContentLoadsBeforeRestore() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("baseline") })

        // 先造一份「重建时会恢复」的实例状态：内容 baselineX，并且历史里有一笔用户编辑
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitEditorEnabled(first.get())
        editorOf(first.get()).text.append("X")
        assertEquals(1, first.get().undoDepth)

        val state = Bundle()
        first.pause().stop().saveInstanceState(state).destroy()

        // 手工驱动新 Activity：create → （读盘先跑完）→ restore → postCreate
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.create(state)
        idleMain(times = 50)
        controller.start()
        controller.restoreInstanceState(state)
        controller.postCreate(state).resume().visible()
        awaitEditorEnabled(controller.get())

        assertEquals("系统恢复的文本不得进入撤回历史", 0, controller.get().undoDepth)
        assertEquals("系统恢复的内容必须完整显示", "baselineX", editorText(controller.get()))
        runCatching { controller.pause().stop().destroy() }
    }

    /**
     * 统计型回归：真实失败路径依赖异步读盘与系统恢复的先后，单次运行不一定触发，
     * 因此在同一进程里连续 recreate 多次（诊断时 200 次复现 15 次 ≈ 7.5%/次，
     * 60 次至少命中一次的概率 ≈ 99%）。每次都必须「内容正确、历史深度仍为 1」。
     */
    @Test
    fun recreate_neverAddsAnExtraUndoStep() {
        repeat(RECREATE_ITERATIONS) { i ->
            assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("baseline") })
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            awaitEditorEnabled(controller.get())
            editorOf(controller.get()).text.append("X")

            controller.recreate()

            val activity = controller.get()
            awaitEditorEnabled(activity)
            assertEquals("第 $i 次 recreate 后内容必须被恢复", "baselineX", editorText(activity))
            assertEquals("第 $i 次 recreate 不得多记一次编辑", 1, activity.undoDepth)
            runCatching { controller.pause().stop().destroy() }
        }
    }

    private companion object {
        /**
         * 循环次数：诊断实测单次 recreate 出现交错的概率约 7.5%，
         * 60 次全不命中的概率约 1%（0.925^60 ≈ 0.9%），足以在 CI 上稳定守住这条回归。
         */
        const val RECREATE_ITERATIONS = 60
    }
}
