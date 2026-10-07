package moe.hellowidget

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncSettings
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
 * v8.0.2：WebDAV 同步设置页的状态 / 诊断区**可复制**。
 *
 * 需求原话：「把你的 webdav 设置界面的调试信息改成能长按复制的样子」。
 *
 * 两条路径都必须成立，所以分两组用例：
 *  1. **长按**：`sync_status` 必须是 `textIsSelectable` —— 这是框架提供的「长按选中 →
 *     系统浮层里的复制/全选」的唯一开关（`TextView.setTextIsSelectable()` 会同时把它设为
 *     focusable/clickable/longClickable）。它是个声明式属性，Robolectric 只能断言属性本身，
 *     真机上的选择手柄由系统负责；因此下面还会断言「内容确实全都在这一个 TextView 里」，
 *     否则长按能选中的只是半截信息。
 *  2. **「复制全部」按钮**：一次把整块文字放进剪贴板 —— 这条是可执行断言，覆盖到底。
 *
 * 另外两条与体验直接相关的约束：
 *  - 复制内容与屏幕**逐字一致**（不能偷偷拼接版本号/设备名）；
 *  - API 33 起系统自带到剪贴板的标准浮层，本应用**不得**再自弹 Toast（否则同一动作两条提示），
 *    API 32 及以下才由应用自己给反馈（官方 Copy and paste 指引）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncDebugCopyTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun clipboard(): ClipboardManager = app.getSystemService(ClipboardManager::class.java)

    private fun clipboardText(): String? = clipboard().primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(app)
        ?.toString()

    /**
     * 诊断行只在「同步已启用 + 配置完整」时才出现在状态区里（未启用时不给用户看一堆 n/a），
     * 所以要先直接落盘这两项，才能验证「诊断信息也在可复制的范围内」。
     */
    private fun enableSyncWithConfig() {
        SyncSettings.setEnabled(app, true)
        SyncSettings.saveConfig(
            app,
            SyncConfig(
                baseUrl = "https://dav.example.com/dav/",
                fileName = "note.txt",
                username = "tester",
                password = "secret"
            )
        )
    }

    private fun launch(): SyncActivity {
        val activity = Robolectric.buildActivity(SyncActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    private fun statusOf(activity: SyncActivity): TextView = activity.findViewById(R.id.sync_status)

    // ------------------------------------------------------------ 长按选中

    @Test
    fun statusTextIsSelectable_soLongPressOffersTheSystemCopyUi() {
        val activity = launch()
        assertTrue(
            "状态/诊断区必须 textIsSelectable，否则长按不会出系统选择手柄与「复制」",
            statusOf(activity).isTextSelectable
        )
    }

    @Test
    fun certificateFingerprintIsSelectableToo() {
        // 指纹同样是"要交给别人核对/粘贴"的字符串，只靠手抄必然会错
        val activity = launch()
        val tlsInfo = activity.findViewById<TextView>(R.id.sync_tls_info)
        assertTrue("证书指纹也必须可长按选中复制", tlsInfo.isTextSelectable)
    }

    @Test
    fun everythingUserNeedsToReportLivesInThatOneTextView() {
        // 长按只能选中被长按的那个 View 里的文字：状态、上次结果、诊断行必须都在里面，
        // 否则「长按复制」得到的信息是残缺的（这正是本次改动的前提）
        enableSyncWithConfig()
        val activity = launch()
        val text = statusOf(activity).text.toString()
        assertTrue("状态区应包含启用状态：$text", text.contains(activity.getString(R.string.sync_state_enabled)))
        // 文案不做硬编码：测试进程的 locale 未必是中文（values-en 会被选中），
        // 所以从资源里取出「系统诊断：」这一段前缀再断言
        val diagnosticsPrefix = activity.getString(R.string.sync_diagnostics, "X").substringBefore("X")
        assertTrue("状态区应包含诊断行（前缀「$diagnosticsPrefix」）：$text", text.contains(diagnosticsPrefix))
        assertTrue("诊断行里应有可 grep 的 ASCII token：$text", text.contains("bucket="))
        assertTrue("状态区应包含上次结果行：$text", text.contains(activity.getString(R.string.sync_status_never)))
    }

    // ------------------------------------------------------------ 「复制全部」按钮

    @Test
    fun copyButton_putsExactlyWhatIsShownOnTheClipboard() {
        enableSyncWithConfig()
        val activity = launch()
        val shown = statusOf(activity).text.toString()

        activity.findViewById<View>(R.id.sync_copy).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("复制的必须是屏幕上逐字一致的内容（所见即所得）", shown, clipboardText())
        assertTrue("必须真的把诊断行复制出来", clipboardText()!!.contains("bucket="))
    }

    @Test
    fun copyButton_showsNoToastOnAndroid13AndUp() {
        // Android 13 起系统自带「已复制」浮层，官方明确要求应用不要再弹自己的提示
        val activity = launch()
        activity.findViewById<View>(R.id.sync_copy).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(
            "API 33+ 不得再自弹复制提示（会与系统浮层重复）",
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    @Config(sdk = [32])
    fun copyButton_showsItsOwnToastOnAndroid12LAndBelow() {
        // API 32 及以下系统不给反馈，应用必须自己给
        val activity = launch()
        activity.findViewById<View>(R.id.sync_copy).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(app.getString(R.string.sync_copied), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun copyButton_leavesTheClipboardAloneWhenThereIsNothingToCopy() {
        val activity = launch()
        clipboard().setPrimaryClip(ClipData.newPlainText("sentinel", "剪贴板里的原有内容"))
        statusOf(activity).text = ""

        activity.findViewById<View>(R.id.sync_copy).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "没有内容可复制时什么都不该做，绝不能把用户剪贴板里原有的东西清掉",
            "剪贴板里的原有内容",
            clipboardText()
        )
    }
}
