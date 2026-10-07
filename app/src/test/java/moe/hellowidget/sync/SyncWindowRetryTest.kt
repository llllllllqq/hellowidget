package moe.hellowidget.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v8.0.3：**前台窗口内快速重试**策略的纯逻辑测试（不碰 Android、不碰网络）。
 *
 * 背景：前台服务的窗口长度本身不值钱，值钱的是窗口里有没有事情在做。
 * 退出瞬间的网络抖动（刚切网 / 刚息屏 / DNS 抖动）会让那一次 PUT 以"暂时性失败"立刻返回，
 * 旧行为是当场放弃、交给 65 秒后的系统兜底任务，用户看到的是"没传上去"。
 * 现在这段窗口被用来把抖动吃掉 —— 但必须守住三条边界，这个文件就是钉那三条边界的：
 *
 *  1. **只重试"暂时性"失败**，且与系统兜底任务用**同一份定义**（[WebDavError.isTransient]）；
 *  2. **只在失败得足够早时重试**（[SyncWindowRetry.RETRY_DEADLINE_MS]）——
 *     晚失败说明链路确实慢或不通，把剩余时间交给兜底任务更划算；
 *  3. **退避很短、次数很少**，绝不把前台服务推向 `shortService` 的约 3 分钟上限。
 */
class SyncWindowRetryTest {

    private fun shouldRetry(
        retriesDone: Int = 0,
        error: WebDavError = WebDavError.NETWORK,
        elapsedMs: Long = 0
    ): Boolean = SyncWindowRetry.shouldRetry(retriesDone, error, elapsedMs)

    @Test
    fun transientError_thatFailedEarly_isRetried() {
        assertTrue("网络抖动正是这个机制存在的理由", shouldRetry(error = WebDavError.NETWORK))
        assertTrue(shouldRetry(error = WebDavError.TIMEOUT))
        assertTrue(shouldRetry(error = WebDavError.IO))
        assertTrue(shouldRetry(error = WebDavError.SERVER_ERROR))
    }

    @Test
    fun needAHumanToFixIt_errors_areNeverRetried() {
        val mustNotRetry = listOf(
            WebDavError.NOT_CONFIGURED,
            WebDavError.UNAUTHORIZED,
            WebDavError.FORBIDDEN,
            WebDavError.NOT_SUPPORTED,
            WebDavError.REDIRECT,
            WebDavError.TLS_UNTRUSTED,
            WebDavError.TLS,
            WebDavError.BAD_RESPONSE
        )
        mustNotRetry.forEach { error ->
            assertFalse("$error 需要用户先动手，重试一万次也没用", shouldRetry(error = error))
        }
    }

    @Test
    fun retryableSet_isExactlyTheSameDefinitionTheFallbackJobUses() {
        // 两处各写一份判断，只会在某次维护后悄悄分叉：一个重试、另一个不重试，
        // 而用户看到的现象一模一样（"有时补传、有时不补"）。所以这里断言两者恒等。
        enumValues<WebDavError>().forEach { error ->
            assertEquals(
                "$error 在「窗口内重试」与「系统兜底任务」中的判定必须一致",
                error.isTransient,
                shouldRetry(error = error)
            )
        }
    }

    @Test
    fun retriesStopAtTheConfiguredMaximum() {
        assertTrue(shouldRetry(retriesDone = SyncWindowRetry.MAX_RETRIES - 1))
        assertFalse(
            "预算用完就必须停：剩下的交给系统兜底任务，不在前台继续占着",
            shouldRetry(retriesDone = SyncWindowRetry.MAX_RETRIES)
        )
    }

    @Test
    fun lateFailure_isLeftToTheFallbackJob() {
        assertTrue(
            "刚好到时限仍可重试",
            shouldRetry(elapsedMs = SyncWindowRetry.RETRY_DEADLINE_MS)
        )
        assertFalse(
            "超时 30 秒这种「晚失败」说明链路确实慢或不通：不该在窗口里再发起一次可能很长的请求",
            shouldRetry(elapsedMs = SyncWindowRetry.RETRY_DEADLINE_MS + 1)
        )
    }

    @Test
    fun delays_areShortAndMatchTheRetryBudget() {
        assertEquals(listOf(1_000L, 3_000L), SyncWindowRetry.DELAYS_MS.toList())
        assertEquals(
            "退避次数必须与最大重试次数一致（少一个会让最后一次重试没有等待）",
            SyncWindowRetry.MAX_RETRIES,
            SyncWindowRetry.DELAYS_MS.size
        )
    }

    @Test
    fun retryMachinery_addsAtMostHalfAMinuteToTheForegroundWindow() {
        // 不变量的意义：窗口内重试**不允许**把前台服务推向 shortService 的约 3 分钟上限。
        // 早失败(≤20s) + 全部退避(4s) 仍远小于上限；真出现慢失败时策略本身会先停手。
        val worstCase = SyncWindowRetry.RETRY_DEADLINE_MS + SyncWindowRetry.DELAYS_MS.sum()
        assertTrue("最坏情况 $worstCase ms 必须远小于 shortService 的 ~180000ms 上限", worstCase <= 30_000)
    }
}
