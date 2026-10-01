package moe.hellowidget

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.EditText
import moe.hellowidget.sync.ConflictReason
import moe.hellowidget.sync.RemoteFile
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
 * 同步状态的持久化、触发闸门与「未启用时行为与 v7.1 完全一致」的回归测试。
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
    fun recordSuccess_storesBaselineAndClearsConflict() {
        SyncSettings.recordConflict(context, ConflictReason.REMOTE_MODIFIED, RemoteFile("\"e1\"", 5L, 5L))
        assertTrue(SyncSettings.pendingConflict(context))
        assertEquals(SyncEngine.RESULT_CONFLICT, SyncSettings.lastResult(context))

        SyncSettings.recordSuccess(context, "hash-1", RemoteFile("\"e2\"", 9L, 7L))

        assertEquals(SyncEngine.RESULT_SUCCESS, SyncSettings.lastResult(context))
        assertEquals("hash-1", SyncSettings.lastUploadedHash(context))
        assertEquals("\"e2\"", SyncSettings.lastRemoteEtag(context))
        assertEquals(9L, SyncSettings.lastRemoteMtime(context))
        assertEquals(7L, SyncSettings.lastRemoteSize(context))
        assertFalse("成功后必须清掉待处理冲突，否则自动同步会一直被暂停", SyncSettings.pendingConflict(context))
        assertTrue(SyncSettings.lastSuccessAt(context) > 0)
    }

    @Test
    fun recordFailure_keepsTheUploadedHashSoPendingChangesAreStillDetected() {
        SyncSettings.recordSuccess(context, "hash-1", RemoteFile("\"e1\"", 1L, 1L))
        SyncSettings.recordFailure(context, WebDavError.UNAUTHORIZED)

        assertEquals(SyncEngine.RESULT_FAILED, SyncSettings.lastResult(context))
        assertEquals(WebDavError.UNAUTHORIZED.name, SyncSettings.lastError(context))
        // 关键：失败不能把上次上传成功的哈希清掉，否则「有未上传改动」的判断会失真
        assertEquals("hash-1", SyncSettings.lastUploadedHash(context))
    }

    @Test
    fun recordConflict_keepsBaselineAndShowsReason() {
        SyncSettings.recordSuccess(context, "hash-1", RemoteFile("\"e1\"", 1L, 1L))
        SyncSettings.recordConflict(context, ConflictReason.BOTH_MODIFIED, RemoteFile("\"e9\"", 9L, 9L))

        assertTrue(SyncSettings.pendingConflict(context))
        assertEquals(ConflictReason.BOTH_MODIFIED, SyncSettings.conflictReason(context))
        assertEquals("\"e9\"", SyncSettings.lastRemoteEtag(context))
        // 冲突未解决前，哈希基线保持不变：用户解决后仍能正确判断
        assertEquals("hash-1", SyncSettings.lastUploadedHash(context))

        SyncSettings.clearConflict(context)
        assertFalse(SyncSettings.pendingConflict(context))
    }

    @Test
    fun config_isNullWhenNotFilledIn() {
        SyncSettings.setEnabled(context, true)
        assertNull("地址为空时不应拿到配置", SyncSettings.config(context))

        // 非法的 scheme 同样拿不到配置（避免把请求发到莫名其妙的地方）
        context.let { ctx ->
            ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SyncSettings.KEY_BASE_URL, "ftp://x/y/")
                .apply()
        }
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

    @Test
    fun needsSyncOnOpen_respectsDisabledSwitch() {
        configure(enabled = false)
        assertFalse(SyncManager.needsSyncOnOpen(context, "任意内容"))
    }

    @Test
    fun needsSyncOnOpen_trueWhenPreviousAttemptFailed() {
        configure(enabled = true)
        SyncSettings.recordFailure(context, WebDavError.NETWORK)
        assertTrue(SyncManager.needsSyncOnOpen(context, "内容"))
    }

    @Test
    fun needsSyncOnOpen_trueWhenThereAreUnuploadedChanges() {
        configure(enabled = true)
        SyncSettings.recordSuccess(context, SyncEngine.sha256Hex("旧内容".toByteArray()), null)
        assertTrue(SyncManager.needsSyncOnOpen(context, "新内容"))
        assertFalse(SyncManager.needsSyncOnOpen(context, "旧内容"))
    }

    @Test
    fun needsSyncOnOpen_isFalseWhileAConflictIsPending() {
        configure(enabled = true)
        SyncSettings.recordConflict(context, ConflictReason.REMOTE_MODIFIED, null)
        assertFalse("冲突未解决时不自动发起同步", SyncManager.needsSyncOnOpen(context, "内容"))
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
