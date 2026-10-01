package moe.hellowidget

import moe.hellowidget.sync.GateResult
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步决策核心的单元测试（纯 JVM，零 Android 依赖）。
 *
 * v7.5 起只剩一条判据：**本地内容相对「上次成功上传的哈希」有没有变**。
 * 这里把它的每个边界钉死 —— 因为没有云端比对之后，这层判断一旦出错就意味着
 * 「该传的没传」或者「不该传的白传」。
 */
class SyncEngineTest {

    // ------------------------------------------------------------ 内容哈希

    @Test
    fun sha256Hex_matchesTheStandardVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SyncEngine.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
        // 同一内容永远同一哈希（内容寻址，与时间戳无关）
        assertEquals(
            SyncEngine.sha256Hex("第一行\n第二行".toByteArray(Charsets.UTF_8)),
            SyncEngine.sha256Hex("第一行\n第二行".toByteArray(Charsets.UTF_8))
        )
        // 不同内容必须不同
        assertFalse(
            SyncEngine.sha256Hex("a".toByteArray()) == SyncEngine.sha256Hex("b".toByteArray())
        )
    }

    // ------------------------------------------------------------ 需不需要上传

    @Test
    fun hasLocalChanges_firstEverUpload_isTrue() {
        // 本机从未上传过：按产品要求直接覆盖云端，不做任何比对
        assertTrue(SyncEngine.hasLocalChanges("h1", null))
    }

    @Test
    fun hasLocalChanges_sameContent_isFalse() {
        assertFalse(SyncEngine.hasLocalChanges("h1", "h1"))
    }

    @Test
    fun hasLocalChanges_differentContent_isTrue() {
        assertTrue(SyncEngine.hasLocalChanges("h2", "h1"))
    }

    @Test
    fun hasLocalChanges_editingBackToTheUploadedContent_isFalse() {
        // 改了又改回来：内容哈希与上次上传一致 → 不需要再发一次请求
        assertFalse(SyncEngine.hasLocalChanges("h1", "h1"))
    }

    // ------------------------------------------------------------ 节流闸门

    private val now = 1_000_000_000_000L

    @Test
    fun gate_manualAlwaysRuns() {
        assertEquals(
            GateResult.RUN,
            SyncEngine.gate(SyncTrigger.MANUAL, now, lastAttemptAt = now - 1000)
        )
    }

    @Test
    fun gate_firstEverAttemptRuns() {
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.CLOSE_EDITOR, now, 0L))
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.APP_OPEN, now, 0L))
    }

    @Test
    fun gate_autoTriggerWithinIntervalIsThrottled() {
        assertEquals(
            GateResult.SKIP_THROTTLED,
            SyncEngine.gate(SyncTrigger.CLOSE_EDITOR, now, lastAttemptAt = now - 60_000)
        )
        assertEquals(
            GateResult.SKIP_THROTTLED,
            SyncEngine.gate(SyncTrigger.APP_OPEN, now, lastAttemptAt = now - 1)
        )
    }

    @Test
    fun gate_autoTriggerAtExactlyTheIntervalRuns() {
        assertEquals(
            GateResult.RUN,
            SyncEngine.gate(
                SyncTrigger.APP_OPEN, now,
                lastAttemptAt = now - SyncEngine.MIN_SYNC_INTERVAL_MS
            )
        )
        // 自定义间隔也遵守同一条边界
        assertEquals(
            GateResult.RUN,
            SyncEngine.gate(SyncTrigger.APP_OPEN, now, lastAttemptAt = now - 5_000, intervalMs = 5_000)
        )
    }

    @Test
    fun gate_clockRolledBack_doesNotBlockForever() {
        // 系统时钟被回拨：delta < 0，应放行而不是长时间卡死
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.APP_OPEN, now, lastAttemptAt = now + 999_999))
    }

    // ------------------------------------------------------------ 打开应用时是否补一次

    @Test
    fun shouldSyncOnOpen_coversTheThreeReasons() {
        // 1) 上次没成功
        assertTrue(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_FAILED, "h1", "h1"))
        assertTrue(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_NEVER, "h1", "h1"))
        // 2) 本机从未上传过
        assertTrue(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_SUCCESS, "h1", null))
        // 3) 本地内容与上次上传的不一致（例如进程被杀在同步之前）
        assertTrue(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_SUCCESS, "h2", "h1"))
    }

    @Test
    fun shouldSyncOnOpen_isFalseWhenEverythingIsInSync() {
        assertFalse(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_SUCCESS, "h1", "h1"))
    }
}
