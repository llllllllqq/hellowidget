package moe.hellowidget

import moe.hellowidget.sync.ConflictReason
import moe.hellowidget.sync.GateResult
import moe.hellowidget.sync.RemoteState
import moe.hellowidget.sync.SyncDecision
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 同步决策核心的回归测试（纯 JVM，无 Android 依赖）。
 *
 * 这一层覆盖的是**「什么时候该传、什么时候绝不能传」**：
 * 判错的代价是静默毁掉用户在某台设备上的修改，因此把决策表逐行钉死。
 */
class SyncEngineTest {

    private fun state(etag: String? = "\"e1\"", mtime: Long = 1_000L, size: Long = 10L) =
        RemoteState(etag, mtime, size)

    // ------------------------------------------------------------ 决策表

    @Test
    fun decide_createsWhenCloudFileMissing() {
        val decision = SyncEngine.decide(
            remote = null,
            localHash = "abc",
            lastUploadedHash = "abc",
            seenEtag = null,
            seenMtime = -1,
            seenSize = -1
        )
        assertEquals(SyncDecision.Create, decision)
    }

    @Test
    fun decide_comparesFirstWhenNeverUploadedButCloudHasFile() {
        // 本机从未上传过、云端却已有文件：先取回来比对，绝不直接覆盖
        val decision = SyncEngine.decide(
            remote = state(),
            localHash = "abc",
            lastUploadedHash = null,
            seenEtag = null,
            seenMtime = -1,
            seenSize = -1
        )
        assertEquals(SyncDecision.CompareWithRemote, decision)
    }

    @Test
    fun decide_uptoDateWhenNeitherSideChanged() {
        val decision = SyncEngine.decide(
            remote = state(etag = "\"e1\""),
            localHash = "abc",
            lastUploadedHash = "abc",
            seenEtag = "\"e1\"",
            seenMtime = 1_000L,
            seenSize = 10L
        )
        assertEquals(SyncDecision.UpToDate, decision)
    }

    @Test
    fun decide_uploadsWhenOnlyLocalChanged() {
        val decision = SyncEngine.decide(
            remote = state(etag = "\"e1\""),
            localHash = "changed",
            lastUploadedHash = "abc",
            seenEtag = "\"e1\"",
            seenMtime = 1_000L,
            seenSize = 10L
        )
        assertEquals(SyncDecision.Upload, decision)
    }

    @Test
    fun decide_conflictsWhenOnlyCloudChanged() {
        // 本机没动，云端被别的设备改过 —— 这条路径**绝不能**走成 Upload
        val decision = SyncEngine.decide(
            remote = state(etag = "\"e2\""),
            localHash = "abc",
            lastUploadedHash = "abc",
            seenEtag = "\"e1\"",
            seenMtime = 1_000L,
            seenSize = 10L
        )
        assertEquals(SyncDecision.Conflict(ConflictReason.REMOTE_MODIFIED), decision)
    }

    @Test
    fun decide_conflictsWhenBothChanged() {
        val decision = SyncEngine.decide(
            remote = state(etag = "\"e2\""),
            localHash = "changed",
            lastUploadedHash = "abc",
            seenEtag = "\"e1\"",
            seenMtime = 1_000L,
            seenSize = 10L
        )
        assertEquals(SyncDecision.Conflict(ConflictReason.BOTH_MODIFIED), decision)
    }

    // ------------------------------------------------------------ 云端变化判定

    @Test
    fun remoteChanged_prefersEtag() {
        assertFalse(SyncEngine.remoteChanged(state(etag = "\"e1\""), "\"e1\"", 1_000L, 10L))
        assertTrue(SyncEngine.remoteChanged(state(etag = "\"e9\""), "\"e1\"", 1_000L, 10L))
        // ETag 变了就是变了，即使大小与时间都没动
        assertTrue(SyncEngine.remoteChanged(state(etag = "\"e9\"", mtime = 1_000L, size = 10L), "\"e1\"", 1_000L, 10L))
    }

    @Test
    fun remoteChanged_fallsBackToMtimeWhenNoEtag() {
        assertFalse(SyncEngine.remoteChanged(state(etag = null, mtime = 5_000L, size = 10L), null, 5_000L, 10L))
        // 时间变小也算变化（外部上传可能保留旧 mtime），宁可多问一次也不要静默覆盖
        assertTrue(SyncEngine.remoteChanged(state(etag = null, mtime = 4_000L, size = 10L), null, 5_000L, 10L))
        assertTrue(SyncEngine.remoteChanged(state(etag = null, mtime = 6_000L, size = 10L), null, 5_000L, 10L))
    }

    @Test
    fun remoteChanged_fallsBackToSizeThenGivesUp() {
        assertTrue(SyncEngine.remoteChanged(state(etag = null, mtime = -1, size = 11L), null, -1, 10L))
        assertFalse(SyncEngine.remoteChanged(state(etag = null, mtime = -1, size = 10L), null, -1, 10L))
        // 连基线都没有：不打扰用户（宁可不报冲突，也不要每次都弹假冲突）
        assertFalse(SyncEngine.remoteChanged(state(etag = null, mtime = -1, size = -1), null, -1, -1))
    }

    // ------------------------------------------------------------ 节流

    @Test
    fun gate_throttlesAutomaticTriggersWithin30Minutes() {
        val now = 10_000_000_000L
        val interval = SyncEngine.MIN_SYNC_INTERVAL_MS
        assertEquals(
            GateResult.SKIP_THROTTLED,
            SyncEngine.gate(SyncTrigger.CLOSE_EDITOR, now, now - interval + 1)
        )
        assertEquals(
            GateResult.SKIP_THROTTLED,
            SyncEngine.gate(SyncTrigger.APP_OPEN, now, now - 29 * 60 * 1000L)
        )
        // 恰好 30 分钟：放行
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.CLOSE_EDITOR, now, now - interval))
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.APP_OPEN, now, now - interval - 1))
    }

    @Test
    fun gate_neverHappenedBefore_allows() {
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.CLOSE_EDITOR, 1_000L, 0L))
    }

    @Test
    fun gate_manualAndConflictResolutionBypassThrottle() {
        val now = 10_000_000_000L
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.MANUAL, now, now - 1_000L))
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.CONFLICT_RESOLVE, now, now - 1_000L))
    }

    @Test
    fun gate_toleratesClockRollback() {
        // 系统时钟被回拨：放行，而不是把同步卡死 30 分钟
        assertEquals(GateResult.RUN, SyncEngine.gate(SyncTrigger.CLOSE_EDITOR, 1_000L, 10_000_000L))
    }

    // ------------------------------------------------------------ 打开应用时是否补同步

    @Test
    fun shouldSyncOnOpen_coversFailureAndPendingChanges() {
        // 上次成功且内容未变：不需要
        assertFalse(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_SUCCESS, "abc", "abc"))
        // 上次失败：补一次
        assertTrue(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_FAILED, "abc", "abc"))
        // 有未上传的改动（例如进程在上传前被杀）：补一次
        assertTrue(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_SUCCESS, "changed", "abc"))
        // 从未上传过：补一次
        assertTrue(SyncEngine.shouldSyncOnOpen(SyncEngine.RESULT_SUCCESS, "abc", null))
    }

    @Test
    fun hasLocalChanges_isContentAddressed() {
        assertTrue(SyncEngine.hasLocalChanges("abc", null))
        assertFalse(SyncEngine.hasLocalChanges("abc", "abc"))
        assertTrue(SyncEngine.hasLocalChanges("abc", "abd"))
    }

    // ------------------------------------------------------------ 哈希与命名

    @Test
    fun sha256Hex_matchesKnownVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SyncEngine.sha256Hex("abc".toByteArray(Charsets.UTF_8))
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SyncEngine.sha256Hex(ByteArray(0))
        )
        // 中文与 emoji 按 UTF-8 字节哈希，不能按字符
        assertEquals(
            SyncEngine.sha256Hex("笔记 📝".toByteArray(Charsets.UTF_8)),
            SyncEngine.sha256Hex("笔记 📝".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun conflictCopyName_keepsExtensionAndTimestamp() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
                .parse("2026-10-01 03:15:00")!!
                .time
            assertEquals("note.conflict-20261001-031500.txt", SyncEngine.conflictCopyName("note.txt", stamp))
            // 无扩展名时补 .txt，便于在 WebDAV 网页端直接打开
            assertEquals("memo.conflict-20261001-031500.txt", SyncEngine.conflictCopyName("memo", stamp))
            // 多重扩展名只在最后一个点处切分
            assertEquals("a.b.conflict-20261001-031500.txt", SyncEngine.conflictCopyName("a.b.txt", stamp))
            // 同一秒内的第二次冲突：加序号，绝不覆盖上一份副本
            assertEquals("note.conflict-20261001-031500-2.txt", SyncEngine.conflictCopyName("note.txt", stamp, 1))
            // 不可能出现路径穿越
            assertFalse(SyncEngine.conflictCopyName("note.txt", stamp).contains('/'))
        } finally {
            TimeZone.setDefault(zone)
        }
    }
}
