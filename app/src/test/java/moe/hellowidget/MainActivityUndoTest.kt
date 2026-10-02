package moe.hellowidget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.fakes.RoboMenuItem

/**
 * v7.7「顶部导航栏撤回按钮」的接线测试（Robolectric）。
 *
 * 算法本身由 [UndoHistoryTest] 覆盖；这里固化的是**产品需求**：
 *  - 点撤回 → 退回上一步编辑；
 *  - 次数不设上限，一直到「刚打开应用时的内容」为止，再点无动作；
 *  - 旋转 / 深色模式这类系统重建之后，撤回历史依然可用（ViewModel 保留）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityUndoTest {

    private fun clickUndo(activity: MainActivity) {
        assertTrue(
            "顶部导航栏的「撤回」必须被处理",
            activity.onOptionsItemSelected(RoboMenuItem(R.id.action_undo))
        )
    }

    @Test
    fun rightAfterOpen_thereIsNothingToUndoAndTheContentIsUntouched() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("打开时的内容") })

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)

        assertEquals("刚打开时不该有任何可撤回的编辑", 0, activity.undoDepth)
        clickUndo(activity)
        assertEquals("无历史时点撤回不得改变内容", "打开时的内容", editorText(activity))
    }

    @Test
    fun undo_walksBackEveryEdit_untilTheContentAtOpen() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("hello world") })

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)

        editorOf(activity).text.insert(0, "#")   // #hello world
        editorOf(activity).text.delete(1, 6)     // # world
        editorOf(activity).text.append("!")      // # world!
        assertEquals("# world!", editorText(activity))
        assertEquals("三次编辑都必须可撤回", 3, activity.undoDepth)

        clickUndo(activity)
        assertEquals("# world", editorText(activity))
        clickUndo(activity)
        assertEquals("#hello world", editorText(activity))
        clickUndo(activity)
        assertEquals("撤回终点必须精确等于刚打开时的内容", "hello world", editorText(activity))
        assertEquals(0, activity.undoDepth)

        clickUndo(activity)
        assertEquals("已到最初内容后再点撤回不得改变任何东西", "hello world", editorText(activity))
    }

    @Test
    fun undoHistory_survivesSystemRecreation() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("baseline") })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitEditorEnabled(controller.get())
        editorOf(controller.get()).text.append("X")
        assertEquals("baselineX", editorText(controller.get()))

        // 旋转 / 深色模式 recreate / 进程恢复都会走到这里
        controller.recreate()

        val activity = controller.get()
        awaitEditorEnabled(activity)
        assertEquals("系统重建必须保留已恢复的内容", "baselineX", editorText(activity))
        assertEquals("系统重建后撤回历史必须仍然在", 1, activity.undoDepth)

        clickUndo(activity)
        assertEquals("系统重建后依然要能撤回到打开时的内容", "baseline", editorText(activity))
    }
}
