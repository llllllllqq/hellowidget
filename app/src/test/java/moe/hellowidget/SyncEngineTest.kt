package moe.hellowidget

import moe.hellowidget.sync.SyncEngine
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

    // ------------------------------------------------------------ 上传文件名的时间戳（v8.4.0）

    private val shanghai: java.util.TimeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun nextUploadTimestamp_usesNowWhenItIsNewer() {
        assertEquals(
            1_735_689_600_000L,
            SyncEngine.nextUploadTimestamp(1_735_689_600_000L, 1_735_689_000_000L)
        )
    }

    @Test
    fun nextUploadTimestamp_sameMsStillAdvances() {
        // 同一毫秒内的第二次上传必须换一个文件名，否则会覆盖上一份历史
        assertEquals(
            1_735_689_600_001L,
            SyncEngine.nextUploadTimestamp(1_735_689_600_000L, 1_735_689_600_000L)
        )
    }

    @Test
    fun nextUploadTimestamp_clockRolledBack_stillIncreases() {
        // 设备时钟被回拨：时间戳仍单调递增，文件名（由时间戳格式化）也不会复用
        assertEquals(
            1_735_689_600_001L,
            SyncEngine.nextUploadTimestamp(1_000_000_000_000L, 1_735_689_600_000L)
        )
    }

    @Test
    fun nextUploadTimestamp_firstEverUpload_usesNow() {
        assertEquals(1_735_689_600_000L, SyncEngine.nextUploadTimestamp(1_735_689_600_000L, 0L))
    }

    @Test
    fun formatUploadTimestamp_isReadableLocalDateTimeWithMillis() {
        // 1735689600000ms = 2025-01-01T00:00:00Z = 上海时间 08:00:00.000
        assertEquals(
            "20250101_080000_000",
            SyncEngine.formatUploadTimestamp(1_735_689_600_000L, shanghai)
        )
        // 毫秒必须补零到三位（否则字典序会乱：_7 会排在 _12 后面）
        assertEquals(
            "20250102_075959_999",
            SyncEngine.formatUploadTimestamp(1_735_689_600_000L + 86_399_999L, shanghai)
        )
        // 换时区只是显示不同，同一时间戳不会改变「时间先后」
        assertEquals(
            "20250101_000000_000",
            SyncEngine.formatUploadTimestamp(
                1_735_689_600_000L,
                java.util.TimeZone.getTimeZone("UTC")
            )
        )
    }

    // v7.7.2 ~ v8.0.3 这里曾有 6 条「1 分钟节流闸门」用例（gate_* / throttleInterval_isOneMinute）。
    // v8.1.0 把闸门整个删除，它们随之失去意义 —— 取而代之的不变量（"成功不产生任何冷却"、
    // "同一时刻只有一个上传"、"一趟上传吸收它开始前登记的所有保存"、"零请求的空跑不更新访问锚点"）
    // 都无法用纯函数表达，全部由 SyncManagerUploadTest 在编排层钉住。
}
