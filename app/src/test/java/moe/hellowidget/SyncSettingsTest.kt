package moe.hellowidget

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncLauncher
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncTrigger
import moe.hellowidget.sync.WebDavError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/**
 * 同步状态的持久化、触发入口，以及「未启用时行为与 v7.1 完全一致」的回归测试。
 *
 * 最后一条尤其重要：同步是 v7.2 新增的能力，**默认关闭**，
 * 未启用时不得启动任何服务、不得触碰编辑器的焦点与光标（v7.1 的「进入即输入」是发布门禁）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SyncSettingsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun configure(enabled: Boolean = true) {
        SyncSettings.setEnabled(context, enabled)
        SyncSettings.saveConfig(
            context,
            SyncConfig(
                baseUrl = "http://127.0.0.1:1/dav/",
                fileName = "note.txt",
                username = "u",
                password = "p"
            )
        )
    }

    // ------------------------------------------------------------ 状态持久化

    @Test
    fun recordSuccess_storesTheUploadedHashAsTheOnlyBaseline() {
        SyncSettings.recordSuccess(context, "hash-1", 1_735_689_600L)

        assertEquals(SyncEngine.RESULT_SUCCESS, SyncSettings.lastResult(context))
        assertEquals("hash-1", SyncSettings.lastUploadedHash(context))
        assertEquals("", SyncSettings.lastError(context))
        assertEquals(1_735_689_600L, SyncSettings.lastUploadedTs(context))
        assertTrue(SyncSettings.lastSuccessAt(context) > 0)
    }

    /**
     * v8.0.1（D3）：上一次「成功」是**真的传上去了**，还是「本地无改动、一个请求都没发」。
     *
     * 两者都会写 `RESULT_SUCCESS`，所以旧设置页那句「上次同步成功」**无法**证明内容到了云端
     * —— 实测排查时正是它造成了一次误判。
     */
    @Test
    fun recordSuccess_remembersWhetherAnythingWasActuallyUploaded() {
        SyncSettings.recordSuccess(context, "hash-1", 1_735_689_600L, uploaded = false)
        assertEquals("没发请求时必须记为 false", false, SyncSettings.lastSuccessUploaded(context))

        SyncSettings.recordSuccess(context, "hash-2", 1_735_689_601L, uploaded = true)
        assertEquals("真上传了必须记为 true", true, SyncSettings.lastSuccessUploaded(context))

        // 旧调用点（不传 uploaded）沿用"确实上传了"的语义，行为不变
        SyncSettings.recordSuccess(context, "hash-3", 1_735_689_602L)
        assertEquals(true, SyncSettings.lastSuccessUploaded(context))

        // 重置运行时状态必须把它一并清掉，否则会显示上一次运行期的旧结论
        SyncSettings.resetRuntimeState(context)
        assertNull(SyncSettings.lastSuccessUploaded(context))
    }

    @Test
    fun recordSuccess_clearsAPreviousFailure() {
        SyncSettings.recordFailure(context, WebDavError.UNAUTHORIZED)
        SyncSettings.recordSuccess(context, "hash-1", 1_735_689_600L)

        assertEquals(SyncEngine.RESULT_SUCCESS, SyncSettings.lastResult(context))
        assertEquals("", SyncSettings.lastError(context))
    }

    @Test
    fun recordFailure_keepsTheUploadedHashSoPendingChangesAreStillDetected() {
        SyncSettings.recordSuccess(context, "hash-1", 1_735_689_600L)
        SyncSettings.recordFailure(context, WebDavError.UNAUTHORIZED)

        assertEquals(SyncEngine.RESULT_FAILED, SyncSettings.lastResult(context))
        assertEquals(WebDavError.UNAUTHORIZED.name, SyncSettings.lastError(context))
        // 关键：失败不能把上次上传成功的哈希清掉，否则「有未上传改动」的判断会失真
        assertEquals("hash-1", SyncSettings.lastUploadedHash(context))
    }

    @Test
    fun lastUploadedHash_isNullBeforeTheFirstUpload() {
        SyncSettings.resetRuntimeState(context)
        assertNull("从未上传过：第一次同步必须无条件上传", SyncSettings.lastUploadedHash(context))
    }

    @Test
    fun resetRuntimeState_clearsResultAndHashButKeepsCredentials() {
        configure(enabled = true)
        SyncSettings.recordSuccess(context, "hash-1", 1_735_689_600L)
        SyncSettings.setLastServerContactAt(context, 123L)

        SyncSettings.resetRuntimeState(context)

        assertEquals(SyncEngine.RESULT_NEVER, SyncSettings.lastResult(context))
        assertNull(SyncSettings.lastUploadedHash(context))
        assertEquals(0L, SyncSettings.lastUploadedTs(context))
        assertEquals(0L, SyncSettings.lastServerContactAt(context))
        assertEquals(0L, SyncSettings.nextRetryAt(context))
        assertEquals("note.txt", SyncSettings.fileName(context))
        assertEquals("http://127.0.0.1:1/dav/", SyncSettings.baseUrl(context))
    }

    @Test
    fun config_isNullWhenNotFilledIn() {
        SyncSettings.setEnabled(context, true)
        assertNull("地址为空时不应拿到配置", SyncSettings.config(context))

        // 非法的 scheme 同样拿不到配置（避免把请求发到莫名其妙的地方）
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SyncSettings.KEY_BASE_URL, "ftp://x/y/")
            .apply()
        assertNull(SyncSettings.config(context))
    }

    @Test
    fun config_normalizesUrlAndKeepsPin() {
        SyncSettings.setEnabled(context, true)
        SyncSettings.setTlsPin(context, "AB:CD")
        SyncSettings.saveConfig(
            context,
            SyncConfig("https://dav.example.com/dav/", "note.txt", "u", "p", tlsPinSha256 = null)
        ) // 注意：saveConfig 不写 pin，pin 单独持久化
        val config = SyncSettings.config(context)
        assertNotNull(config)
        assertEquals("https://dav.example.com/dav/", config!!.baseUrl)
        assertEquals("note.txt", config.fileName)
        assertEquals("AB:CD", config.tlsPinSha256)
    }

    // ------------------------------------------------------------ 打开应用时的「待上传」检测（v7.8）
    //
    // 这组用例固化 v7.8 的核心语义：**打开应用只检测、不上传**，检测结果只用来决定
    // 「立即上传」按钮上那个橙点亮不亮。检测必须是纯函数式的：不建客户端、不发请求、
    // 不写 lastServerContactAt（那是"真的碰过云端"的证据，检测一次网络都没碰）。

    @Test
    fun hasPendingUpload_falseWhenSyncIsDisabled() {
        configure(enabled = false)
        assertFalse(SyncManager.hasPendingUpload(context, "任意内容"))
    }

    @Test
    fun hasPendingUpload_falseWhenNothingWasEverUploadedAndContentIsEmpty() {
        configure(enabled = true)
        assertFalse("刚装好、还没写东西时不该亮橙点", SyncManager.hasPendingUpload(context, ""))
    }

    @Test
    fun hasPendingUpload_trueWhenNothingWasEverUploadedButContentExists() {
        configure(enabled = true)
        assertTrue("从未上传过且有内容 → 有待上传改动", SyncManager.hasPendingUpload(context, "内容"))
    }

    @Test
    fun hasPendingUpload_tracksChangesSinceTheLastSuccessfulUpload() {
        configure(enabled = true)
        SyncSettings.recordSuccess(context, SyncEngine.sha256Hex("旧内容".toByteArray()), 1_735_689_600L)
        assertTrue(SyncManager.hasPendingUpload(context, "新内容"))
        assertFalse("内容与上次成功上传的一致 → 橙点必须熄灭", SyncManager.hasPendingUpload(context, "旧内容"))
    }

    @Test
    fun hasPendingUpload_trueWhenThePreviousAttemptFailed() {
        // 失败不写 lastUploadedHash，因此「没传上去」这件事天然被检测出来
        configure(enabled = true)
        SyncSettings.recordSuccess(context, SyncEngine.sha256Hex("上次成功的内容".toByteArray()), 1_735_689_600L)
        SyncSettings.recordFailure(context, WebDavError.NETWORK)
        assertTrue(SyncManager.hasPendingUpload(context, "新内容"))
    }

    @Test
    fun hasPendingUpload_trueWhenUploadedContentWasDeletedToEmpty() {
        // 把已上传过的内容删空也是一次真实的改动（云端应收到空内容）
        configure(enabled = true)
        SyncSettings.recordSuccess(context, SyncEngine.sha256Hex("旧内容".toByteArray()), 1_735_689_600L)
        assertTrue(SyncManager.hasPendingUpload(context, ""))
    }

    @Test
    fun hasPendingUpload_neverStartsAServiceNorRecordsAnAttempt() {
        configure(enabled = true)
        SyncSettings.resetRuntimeState(context)

        assertTrue(SyncManager.hasPendingUpload(context, "内容"))

        assertNull("检测绝不允许启动任何同步服务", shadowOf(app).nextStartedService)
        assertEquals(
            "检测不得写 lastServerContactAt（它只记录「真的访问过云端」的时刻）",
            0L,
            SyncSettings.lastServerContactAt(context)
        )
    }

    // ------------------------------------------------------------ 触发入口

    @Test
    fun launcher_doesNothingWhenSyncIsDisabled() {
        SyncSettings.setEnabled(context, false)
        assertFalse(SyncLauncher.request(context, SyncTrigger.CLOSE_EDITOR))
        assertNull("未启用同步时不应该启动任何服务", shadowOf(app).nextStartedService)
    }

    @Test
    fun launcher_startsTheSyncForegroundServiceWhenConfigured() {
        configure(enabled = true)
        assertTrue(SyncLauncher.request(context, SyncTrigger.CLOSE_EDITOR))
        val started = shadowOf(app).nextStartedService
        assertNotNull("应启动 SyncService 承载同步", started)
        assertEquals(SyncService::class.java.name, started!!.component?.className)
    }

    // ------------------------------------------------------------ 与 v7.1 行为不冲突

    @Test
    fun mainActivity_withSyncDisabled_keepsV71BehaviorAndStartsNoService() {
        SyncSettings.setEnabled(context, false)
        SyncSettings.resetRuntimeState(context)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val editor = activity.findViewById<EditText>(R.id.editor)

        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (editor.isEnabled) break
            Thread.sleep(10)
        }
        assertTrue("前置条件：异步加载应已完成", editor.isEnabled)
        assertEquals("v7.1 行为：光标仍在第一行行首", 0, editor.selectionStart)

        // 离开应用（onStop 的同步触发路径）
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("同步未启用时离开应用不得启动服务", shadowOf(app).nextStartedService)
    }
}
