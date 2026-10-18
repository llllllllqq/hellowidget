package moe.hellowidget

import android.app.Application
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.fakes.RoboMenuItem
import org.robolectric.shadows.ShadowToast
import java.io.IOException

/**
 * v8.4.0（审计 P0-1）：**读盘失败不能被当成"便签是空的"**。
 *
 * 之前的 fail-open：读失败 → 编辑器显示空 → 用户离开时保存 → 磁盘上唯一那份内容被清空。
 * 现在的契约：读失败时编辑器保持禁用、所有保存路径都拒绝写盘、并且明确告诉用户为什么。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityReadFailureTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ContentStore.readOverride = null
        ContentStore.writeOverride = null
        SyncSettings.setEnabled(app, false)
        SyncSettings.resetRuntimeState(app)
    }

    @Test
    fun readFailure_disablesEditing_andNeverOverwritesTheDisk() {
        assertTrue("前置条件：磁盘里已有内容", runBlocking { ContentStore.write("不可被覆盖的内容") })
        // 让「立即上传」这条不经过 shouldPersist 的保存入口也走到 saveContent，
        // 验证 loadFailed 这道闸门在 saveContent 里同样生效
        SyncSettings.saveConfig(
            app,
            SyncConfig("https://dav.example.com/dav/", "note.txt", "u", "p")
        )
        SyncSettings.setEnabled(app, true)

        ContentStore.readOverride = { ContentRead.Failed(IOException("模拟读失败")) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor = editorOf(activity)

        // 等读失败被处理（会弹一次「读失败、本次禁止保存」）
        awaitMainIdle { ShadowToast.getTextOfLatestToast() != null }

        assertFalse("读失败时编辑器必须保持禁用（不能让用户输入将被丢弃的内容）", editor.isEnabled)
        assertEquals("编辑器里不能显示空便签的样子（那会被当成用户的真实内容）", "", editor.text.toString())
        assertEquals(
            "必须明确告诉用户读失败、本次禁止保存",
            activity.getString(R.string.editor_load_failed),
            ShadowToast.getTextOfLatestToast()
        )

        // 走一遍所有保存路径：失焦 / onStop / 立即上传 / 返回键
        activity.onWindowFocusChanged(false)
        controller.pause().stop()
        activity.onOptionsItemSelected(RoboMenuItem(R.id.action_upload))
        activity.onBackPressedDispatcher.onBackPressed()
        idleMain()

        assertEquals(
            "读失败后磁盘内容一个字都不能变",
            "不可被覆盖的内容",
            runBlocking { ContentStore.read() }
        )
    }
}
