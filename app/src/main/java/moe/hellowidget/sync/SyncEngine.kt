package moe.hellowidget.sync

import java.security.MessageDigest

/** 同步触发来源（只有手动按钮不受 30 分钟节流限制） */
enum class SyncTrigger { CLOSE_EDITOR, APP_OPEN, MANUAL }

/** 触发闸门的结果 */
enum class GateResult { RUN, SKIP_THROTTLED }

/**
 * 同步决策核心：**纯函数，零 Android 依赖，可 JVM 单测**。
 *
 * ## v7.5 起的语义：完全单向上传
 * 只判断一件事 —— **本地内容相对「上次成功上传的内容」有没有变**：
 *  - 变了（含本机从未上传过）→ 直接 `PUT` 覆盖云端文件；
 *  - 没变 → 不发任何请求。
 *
 * 不再读取、也不再比较云端的任何状态（不看 ETag / 修改时间 / 大小，更不下载云端内容），
 * 因此不存在「冲突」这个概念：云端被谁改过、改成什么，都与本机无关，
 * 下一次上传直接覆盖。判定基准只有 `localHash`（内容哈希）与
 * `lastUploadedHash`（上次成功上传的哈希）两个 —— 内容寻址，天然免疫时间戳与时钟漂移。
 */
object SyncEngine {

    /** 自动触发之间的最低间隔：30 分钟（防止高频访问被 WebDAV 服务器限流） */
    const val MIN_SYNC_INTERVAL_MS = 30 * 60 * 1000L

    const val RESULT_NEVER = "never"
    const val RESULT_SUCCESS = "success"
    const val RESULT_FAILED = "failed"

    /** 内容哈希（十六进制小写）；是否上传全部用它做基准 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    /**
     * 本地内容相对「上次成功上传的内容」是否变了。
     * `lastUploadedHash == null`（本机从未上传过）同样返回 true ——
     * 按产品要求，这时不做任何云端比对，直接覆盖。
     */
    fun hasLocalChanges(localHash: String, lastUploadedHash: String?): Boolean =
        lastUploadedHash == null || localHash != lastUploadedHash

    /**
     * 节流闸门。
     *
     * **所有自动触发**（关闭编辑器 / 打开应用）都受 30 分钟最低间隔限制，
     * 手动按钮不受限（那是用户明确的当下意图）。
     * `lastAttemptAt` 用的是「尝试」时间而不是「成功」时间，避免失败后立刻重试形成风暴。
     * 若系统时钟被回拨（delta < 0），放行而不是长时间卡死。
     */
    fun gate(
        trigger: SyncTrigger,
        now: Long,
        lastAttemptAt: Long,
        intervalMs: Long = MIN_SYNC_INTERVAL_MS
    ): GateResult {
        if (trigger == SyncTrigger.MANUAL) return GateResult.RUN
        if (lastAttemptAt <= 0) return GateResult.RUN
        val delta = now - lastAttemptAt
        return if (delta in 0 until intervalMs) GateResult.SKIP_THROTTLED else GateResult.RUN
    }

    /**
     * 本次上传该用的 unix **秒**时间戳。
     *
     * 取 `max(now, 上次上传的时间戳 + 1)`，两个作用：
     *  - 同一秒内的第二次上传不会复用同一个文件名（那会覆盖上一份历史）；
     *  - 设备时钟被回拨时文件名依旧单调递增，云端按名字排序仍是正确的时间顺序。
     */
    fun nextUploadTimestamp(nowSec: Long, lastUploadedSec: Long): Long =
        if (nowSec > lastUploadedSec) nowSec else lastUploadedSec + 1

    /**
     * 打开应用时是否需要补一次同步：
     * 上次没成功、或本机从未上传过、或本地内容与上次上传的不一致（例如进程被杀在同步之前）。
     */
    fun shouldSyncOnOpen(lastResult: String, localHash: String, lastUploadedHash: String?): Boolean =
        lastResult != RESULT_SUCCESS || hasLocalChanges(localHash, lastUploadedHash)

    private val HEX = "0123456789abcdef".toCharArray()
}
