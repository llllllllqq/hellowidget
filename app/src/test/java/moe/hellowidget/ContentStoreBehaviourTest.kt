package moe.hellowidget

import android.app.Application
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException

/**
 * v8.4.0：`ContentStore` 的新契约。
 *
 *  - 读盘结果必须区分「读到空便签」与「读失败」（审计 P0-1 的底层支撑）；
 *  - 内容没变时**不写盘**（用户选定的可选优化①）；
 *  - 保存作用域单线程 —— 并发保存按启动顺序落盘，旧快照不能覆盖新快照（审计 P1-1）；
 *  - 损坏通知"只提示一次"（审计修复④的持久化部分）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ContentStoreBehaviourTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ContentStore.readOverride = null
        ContentStore.writeOverride = null
    }

    @Test
    fun readResult_distinguishesEmptyNoteFromReadFailure() {
        assertTrue("前置条件：写入一份内容", runBlocking { ContentStore.write("正常内容") })
        assertEquals(
            "正常读盘必须是 Success",
            ContentRead.Success("正常内容"),
            runBlocking { ContentStore.readResult() }
        )

        // 模拟 IO 失败：readResult 必须返回 Failed，而不是把它压成空串
        ContentStore.readOverride = { ContentRead.Failed(IOException("模拟读失败")) }
        val failed = runBlocking { ContentStore.readResult() }
        assertTrue("读失败不能被当成空内容，实际：$failed", failed is ContentRead.Failed)

        // 兼容入口 read() 对失败返回空串 —— 它只给测试/降级场景用，生产代码已改用 readResult
        assertEquals("", runBlocking { ContentStore.read() })
    }

    @Test
    fun write_skipsDiskWhenContentIsUnchanged() {
        val before = ContentStore.diskWriteCount
        assertTrue(runBlocking { ContentStore.write("固定内容") })
        val afterFirst = ContentStore.diskWriteCount
        assertEquals("第一次写（内容变了）必须真的写盘", before + 1, afterFirst)

        assertTrue(runBlocking { ContentStore.write("固定内容") })
        assertEquals("内容没变时不得再写盘（省 IO 与闪存写入）", afterFirst, ContentStore.diskWriteCount)

        assertTrue(runBlocking { ContentStore.write("改了内容") })
        assertEquals("内容变了必须写盘", afterFirst + 1, ContentStore.diskWriteCount)
    }

    @Test
    fun savesDoNotLandOutOfOrder_olderSnapshotsNeverOverwriteNewerOnes() {
        // 模拟生产路径：在主线程按顺序取号（就像 saveContent 那样），再把写盘丢进
        // 并发的 IO 线程池。无论协程怎么被调度，最终落盘的必须是**序号最大**的快照。
        // （第一版实现只用了 `limitedParallelism(1)`，被这条用例在 CI 上证伪：
        //   协程挂起时会释放调度许可，20 次并发保存的最终内容会漂移成 v18。）
        val jobs = (0 until 20).map { index ->
            val sequence = ContentStore.nextSaveSequence()
            ContentStore.saveScope.launch { ContentStore.write("v$index", sequence) }
        }
        runBlocking { jobs.forEach { it.join() } }

        assertEquals(
            "最后取号的保存快照必须是最终落盘内容（旧快照不能覆盖新快照）",
            "v19",
            runBlocking { ContentStore.read() }
        )
    }

    @Test
    fun corruptionNotice_isDeliveredExactlyOnce() {
        ContentStore.recordCorruptionNotice(app, "corrupt_123.dat")
        assertEquals("corrupt_123.dat", ContentStore.takeCorruptionNotice(app))
        assertNull("同一个损坏事件只能提示一次", ContentStore.takeCorruptionNotice(app))
    }
}
