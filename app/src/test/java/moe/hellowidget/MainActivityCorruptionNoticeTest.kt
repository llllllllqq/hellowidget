package moe.hellowidget

import android.app.Application
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/**
 * v8.4.0（审计修复④）：内容文件损坏（CRC 校验失败）时必须**让用户知道**。
 *
 * 损坏可能发生在小组件/后台进程的首次读取里（用户当时不在编辑页），因此事件被持久化，
 * 等下次编辑页成功读盘之后提示一次 —— 同一事件不会反复打扰
 * （"只提示一次"的语义由 `ContentStoreBehaviourTest.corruptionNotice_isDeliveredExactlyOnce` 钉住）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityCorruptionNoticeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ContentStore.readOverride = null
        ContentStore.writeOverride = null
        // 不要把"待提示"标记留给下一个用例
        ContentStore.takeCorruptionNotice(app)
    }

    @Test
    fun corruptionNotice_isShownOnTheNextSuccessfulLoad() {
        ContentStore.recordCorruptionNotice(app, "corrupt_999.dat")

        Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitMainIdle { ShadowToast.getTextOfLatestToast() != null }

        val toast = ShadowToast.getTextOfLatestToast()
        assertTrue(
            "损坏提示必须出现，并且带上现场文件名（便于排查/恢复），实际：$toast",
            toast != null && toast.contains("corrupt_999.dat")
        )
        assertNull("提示过一次之后标记必须清掉（不会再提示第二次）", ContentStore.takeCorruptionNotice(app))
    }
}
