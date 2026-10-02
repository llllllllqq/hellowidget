package moe.hellowidget

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.EditText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * 需求 4：「删除全部已保存的 toast」。
 *
 * 范围（与用户确认过）：所有**保存成功**的提示 ——
 *  - 编辑页失焦保存（Home/多任务键）
 *  - 编辑页返回键保存
 *  - WebDAV 设置页「保存设置」
 *
 * 保留：写盘失败提示（`save_failed`，那是可能丢内容的信号）、上传成功提示（v7.5 明确要求）、
 * 校验错误与证书信任提示。
 *
 * 这里同时断言「内容确实保存了」——否则「没有 toast」可能只是因为压根没保存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SaveToastTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun awaitContent(expected: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var actual = ""
        while (System.currentTimeMillis() < deadline) {
            actual = runBlocking { ContentStore.read() }
            if (actual == expected) return
            Thread.sleep(20)
        }
        assertEquals("等待写盘超时", expected, actual)
    }

    @Test
    fun losingFocus_savesTheContentButShowsNoToast() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("旧内容") })

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)
        editorOf(activity).setText("失焦时保存的新内容")

        activity.onWindowFocusChanged(false)

        awaitContent("失焦时保存的新内容")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("保存成功不得再弹任何提示", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun backPress_savesTheContentButShowsNoToast() {
        assertTrue("前置条件：DataStore 必须可写", runBlocking { ContentStore.write("旧内容") })

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        awaitEditorEnabled(activity)
        editorOf(activity).setText("返回键保存的新内容")

        activity.onBackPressedDispatcher.onBackPressed()

        awaitContent("返回键保存的新内容")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("返回键保存成功不得再弹任何提示", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun webdavSettingsSave_showsNoToast() {
        val activity = Robolectric.buildActivity(SyncActivity::class.java).setup().get()
        activity.findViewById<EditText>(R.id.sync_server)
            .setText("https://dav.example.com/dav/")
        activity.findViewById<EditText>(R.id.sync_file_name).setText("note.txt")

        activity.findViewById<View>(R.id.sync_save).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // 保存必须真的生效（失败会弹校验提示，那样下面的断言也会失败，属于有效反例）
        assertEquals(
            "https://dav.example.com/dav/",
            moe.hellowidget.sync.SyncSettings.baseUrl(app)
        )
        assertNull(
            "WebDAV 设置页保存成功不得再弹「设置已保存」",
            ShadowToast.getTextOfLatestToast()
        )
    }
}
