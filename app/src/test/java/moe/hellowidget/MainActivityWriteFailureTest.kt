package moe.hellowidget

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * v8.4.0（审计 P0-3）：**返回键写盘失败时不能把编辑页销毁**。
 *
 * 旧行为：无论写盘成败都 `finish()` —— 磁盘写满这类失败会把用户正在编辑的内容
 * 随 Activity 一起丢掉。新行为：失败时留在编辑页（提示已弹），用户可以再次按返回重试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityWriteFailureTest {

    @After
    fun tearDown() {
        ContentStore.readOverride = null
        ContentStore.writeOverride = null
    }

    @Test
    fun backPressWriteFailure_keepsTheEditorOpenWithItsContent() {
        assertTrue("前置条件：磁盘可写", runBlocking { ContentStore.write("旧内容") })

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)
        editorOf(activity).setText("即将写入失败的新内容")

        // 让所有写盘都失败
        ContentStore.writeOverride = { false }

        activity.onBackPressedDispatcher.onBackPressed()
        // 等失败回调在主线程收尾（会弹「保存失败」，并且不再 finish）
        awaitMainIdle { ShadowToast.getTextOfLatestToast() != null }

        assertFalse("写盘失败时不得销毁编辑页（否则内容随 Activity 一起丢失）", activity.isFinishing)
        assertEquals(
            "内容必须还在编辑器里，让用户能重试或自行复制",
            "即将写入失败的新内容",
            editorOf(activity).text.toString()
        )
        assertEquals(
            "必须提示保存失败",
            activity.getString(R.string.save_failed),
            ShadowToast.getTextOfLatestToast()
        )
        assertEquals("磁盘上的旧内容不能被破坏", "旧内容", runBlocking { ContentStore.read() })
    }

    @Test
    fun backPressAfterARecoveredWrite_finishesNormally() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)
        editorOf(activity).setText("正常保存")

        // 第一次失败：留在编辑页
        ContentStore.writeOverride = { false }
        activity.onBackPressedDispatcher.onBackPressed()
        awaitMainIdle { ShadowToast.getTextOfLatestToast() != null }
        assertFalse(activity.isFinishing)

        // 恢复正常写盘：再按一次返回应当正常退出，并且内容已落盘
        ContentStore.writeOverride = null
        activity.onBackPressedDispatcher.onBackPressed()
        awaitMainIdle { activity.isFinishing }
        assertTrue("恢复后再次返回必须能正常退出", activity.isFinishing)
        assertEquals("正常保存", runBlocking { ContentStore.read() })
    }
}
