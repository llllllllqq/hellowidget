package moe.hellowidget

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.fakes.RoboMenuItem

/**
 * v8.2.0 备份 / 换机迁移的**界面接线**（真正容易出错、又最难靠人肉发现的那一小段）。
 *
 * 编解码与落盘由 [BackupCodecTest] 覆盖；这里只钉住两个"跨 Activity 的约定"：
 *  1. 设置页的两个按钮必须真的打开系统文件选择器（SAF），否则用户根本导不出/导不入；
 *  2. **恢复后的正文必须原样回传到编辑页并立刻替换编辑器文本** ——
 *     这是整套设计里唯一的数据安全关口：编辑页内存里还留着恢复前那份内容，
 *     只要它在退出时被写回磁盘，用户刚恢复的东西就被盖掉了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BackupRestoreTest {

    // ------------------------------------------------------------ 设置页：两个按钮

    @Test
    fun settingsBackupButtons_openTheSystemFilePicker() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

        val export = activity.findViewById<View>(R.id.btn_export_backup)
        val restore = activity.findViewById<View>(R.id.btn_import_backup)
        assertNotNull("设置页必须有「导出备份文件」按钮", export)
        assertNotNull("设置页必须有「从备份恢复」按钮", restore)

        export.performClick()
        val exportRequest = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("导出必须打开系统文件选择器（不申请任何存储权限）", exportRequest)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, exportRequest.intent.action)
        val suggested = exportRequest.intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty()
        assertTrue(
            "默认文件名必须一眼看出是备份并带时间戳：$suggested",
            suggested.startsWith("hellowidget-backup-") && suggested.endsWith(".json")
        )

        restore.performClick()
        val importRequest = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("恢复必须打开系统文件选择器", importRequest)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, importRequest.intent.action)
    }

    // ------------------------------------------------------------ 编辑页：恢复结果

    @Test
    fun restoredText_comesBackThroughTheSettingsResult_andReplacesTheEditor() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("恢复前的旧内容") })
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)
        assertEquals("恢复前的旧内容", editorText(activity))

        // 打开设置页：必须走「要结果」的启动方式，否则恢复后的正文回不到编辑页
        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_appearance)))
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("打开设置必须请求结果（EXTRA_RESTORED_CONTENT 要回传）", request)

        // 设置页恢复成功 → RESULT_OK + 恢复后的正文
        shadowOf(activity).receiveResult(
            request.intent,
            Activity.RESULT_OK,
            Intent().putExtra(MainActivity.EXTRA_RESTORED_CONTENT, "备份里的正文")
        )

        assertEquals("编辑器必须立刻换成恢复后的正文", "备份里的正文", editorText(activity))
    }

    /**
     * **本版最重要的一条数据安全用例**：恢复之后退出，写回磁盘的必须是恢复后的内容。
     *
     * 编辑器里那份内容在恢复发生时已经过期（是恢复前用户看到的旧文本）；
     * 若退出保存把它写回去，用户刚恢复的备份就被静默覆盖 —— 那正是"备份功能反而害人"的形态。
     */
    @Test
    fun afterARestore_theExitSaveWritesTheRestoredText_notTheStaleEditorText() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("恢复前的旧内容") })
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)

        // 用户在备份恢复之前还改了几个字（这份内存内容随后就过期了）
        editorOf(activity).setText("用户改到一半的字")
        assertTrue("撤回历史应当记下这一步（稍后必须被清空）", activity.undoDepth >= 1)

        // 设置页恢复备份：磁盘被改写，正文原样回传
        assertTrue(runBlocking { ContentStore.write("备份里的正文") })
        activity.applyRestoredContent("备份里的正文")

        assertEquals("编辑器必须显示恢复后的正文", "备份里的正文", editorText(activity))
        assertEquals(
            "恢复必须清空撤回历史：否则「撤回」会把备份内容一步步退回恢复前的旧内容",
            0,
            activity.undoDepth
        )
        assertEquals("光标应回到第一行行首", 0, editorOf(activity).selectionStart)

        // 退出应用：磁盘上必须仍是恢复后的正文
        activity.onBackPressedDispatcher.onBackPressed()
        awaitDiskContent("备份里的正文")
    }

    /** 恢复后的正文是「刚打开时的内容」：编辑器里的后续写入不得让撤回退回到恢复前的文本 */
    @Test
    fun afterARestore_undoStopsAtTheRestoredText() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("之前的文本") })
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)

        activity.applyRestoredContent("备份正文")
        editorOf(activity).setText("备份正文+用户新加的一笔")
        assertEquals("恢复后的一次编辑 = 一步历史", 1, activity.undoDepth)

        // 撤回一步：应当停在"备份正文"，而不是退回到恢复前的文本
        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_undo)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("撤回的终点必须是恢复后的内容", "备份正文", editorText(activity))
        assertEquals("撤回到底后历史必须为空", 0, activity.undoDepth)
    }

    /** 设置页返回但是没有结果（用户取消）时，编辑器内容不得被改动 */
    @Test
    fun cancelledSettingsVisit_leavesTheEditorUntouched() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("磁盘上的内容") })
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)
        editorOf(activity).setText("用户正在写的内容")

        assertTrue(activity.onOptionsItemSelected(RoboMenuItem(R.id.action_appearance)))
        val request = shadowOf(activity).nextStartedActivityForResult
        assertNotNull(request)
        shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)

        assertEquals("用户取消设置页时编辑器内容保持不变", "用户正在写的内容", editorText(activity))
    }
}
