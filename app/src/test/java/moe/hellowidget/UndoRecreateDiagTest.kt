package moe.hellowidget

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 临时诊断（qa/undo-flake-diag）：**不是正式用例**，只负责在 CI 上把
 * 「recreate 前后 undoDepth 的分布」与「程序化写入是否会被记进撤回历史」打出来。
 *
 * 起因：`MainActivityUndoTest.undoHistory_survivesSystemRecreation` 偶发失败；
 * 2026-10-06 的同一条历史失败里断言消息是 `expected:<1> but was:<2>`。
 * 证据收齐后本文件与 MainActivity / UndoHistory 里的 UNDODIAG 打印一起删除。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class UndoRecreateDiagTest {

    /**
     * 循环 recreate，统计每次的深度变化。
     *
     * 这里**刻意不做断言**：目的只是把分布打出来（偶发失败不能被单次断言吞掉）。
     */
    @Test
    fun recreateDepthDistribution() {
        val stats = LinkedHashMap<String, Int>()
        repeat(ITERATIONS) { i ->
            runBlocking { ContentStore.write("baseline") }
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            awaitEditorEnabled(controller.get())
            editorOf(controller.get()).text.append("X")
            val before = controller.get().undoDepth

            controller.recreate()

            val activity = controller.get()
            val rightAfter = activity.undoDepth
            val textRightAfter = editorText(activity)
            awaitEditorEnabled(activity)
            val after = activity.undoDepth
            val textAfter = editorText(activity)

            val key = "before=$before rightAfter=$rightAfter after=$after " +
                "textRightAfter='$textRightAfter' textAfter='$textAfter'"
            stats[key] = (stats[key] ?: 0) + 1
            println("UNDODIAG iter=$i $key")
            runCatching { controller.pause().stop().destroy() }
        }
        println("UNDODIAG SUMMARY $stats")
    }

    /**
     * 「系统恢复文本」在代码里就是一次程序化 `setText`。这里验证：监听器挂上之后
     * 再做程序化写入，会不会被记成一次用户编辑（失败形态的必要条件）。
     */
    @Test
    fun programmaticSetTextAfterListenerAttached_isRecorded() {
        runBlocking { ContentStore.write("baseline") }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)
        editorOf(activity).text.append("X")
        println("UNDODIAG programmatic beforeSetText depth=${activity.undoDepth}")

        editorOf(activity).setText("baselineX")
        println("UNDODIAG programmatic afterSetTextSameText depth=${activity.undoDepth}")

        // 编辑器处于禁用态（加载未完成）时写入，是否也会被记录？
        editorOf(activity).isEnabled = false
        editorOf(activity).setText("baselineXY")
        println("UNDODIAG programmatic afterSetTextWhileDisabled depth=${activity.undoDepth}")
    }

    private companion object {
        /** 单个 JVM 用例里循环 200 次 recreate，尽量提高偶发竞态的复现概率 */
        const val ITERATIONS = 200
    }
}
