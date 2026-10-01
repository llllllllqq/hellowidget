package moe.hellowidget.sync

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 云端的「状态指纹」：用于判断云端是否被本机之外的写入改过 */
data class RemoteState(val etag: String?, val lastModifiedMs: Long, val size: Long)

/** 同步触发来源（手动与冲突解决不受 30 分钟节流限制） */
enum class SyncTrigger { CLOSE_EDITOR, APP_OPEN, MANUAL, CONFLICT_RESOLVE }

enum class ConflictReason {
    /** 首次同步就发现云端已有内容，且与本地不同 */
    REMOTE_EXISTS_ON_FIRST_SYNC,

    /** 本机没改，云端被外部改过 */
    REMOTE_MODIFIED,

    /** 双方都改过 */
    BOTH_MODIFIED
}

/** 冲突的解决方式（用户在弹窗里选） */
enum class ConflictChoice { KEEP_LOCAL, USE_REMOTE }

sealed interface SyncDecision {
    /** 本地与云端一致（按上次上传的哈希与云端 ETag 判定），无需任何操作 */
    object UpToDate : SyncDecision

    /** 云端没有这个文件 —— 创建 */
    object Create : SyncDecision

    /** 本地有新内容，上传 */
    object Upload : SyncDecision

    /** 首次同步（本机从未上传过）且云端已有文件 —— 需要先 GET 回来比对哈希 */
    object CompareWithRemote : SyncDecision

    data class Conflict(val reason: ConflictReason) : SyncDecision
}

/** 触发闸门的结果 */
enum class GateResult { RUN, SKIP_THROTTLED }

/**
 * 同步决策核心：**纯函数，零 Android 依赖，可 JVM 单测**。
 *
 * 判定基准（这是整个功能的关键）：
 *  - `localHash`：当前本地内容的 SHA-256（内容寻址，不依赖时间戳，天然免疫时钟漂移）
 *  - `lastUploadedHash`：本机**上次成功上传**的内容哈希（null = 本机从未上传过）
 *  - 云端「是否被外部改过」：比对上次观察到的 ETag（首选）/ Last-Modified / 大小
 *
 * 只有「本地变了」才上传；「云端变了而本地没变」绝不上传 —— 那条路径会静默毁掉
 * 用户在电脑上做的修改，本应用的选择是弹窗让用户决定（见 [ConflictReason]）。
 */
object SyncEngine {

    /** 自动触发之间的最低间隔：30 分钟（防止高频访问被 WebDAV 服务器限流） */
    const val MIN_SYNC_INTERVAL_MS = 30 * 60 * 1000L

    /** 远端文件读取上限（1 MiB）——防止把超大文件读进内存导致 OOM */
    const val MAX_REMOTE_BYTES = 1 shl 20

    /** PROPFIND 响应解析上限（状态信息很小，64 KiB 足够） */
    const val MAX_PROPFIND_BYTES = 64 shl 10

    const val RESULT_NEVER = "never"
    const val RESULT_SUCCESS = "success"
    const val RESULT_FAILED = "failed"
    const val RESULT_CONFLICT = "conflict"

    /** 内容哈希（十六进制小写）；同步状态全部用它做基准 */
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

    fun hasLocalChanges(localHash: String, lastUploadedHash: String?): Boolean =
        lastUploadedHash == null || localHash != lastUploadedHash

    /**
     * 云端是否被外部改过。
     *
     * 三级降级：ETag 最可靠；服务器不给 ETag 就比 Last-Modified；两者都没有就比大小。
     * 如果连基线都没有（首次上传后拿不到任何云端元数据），返回 false ——
     * 宁可不打扰用户，也不要每次同步都弹一个假冲突。
     */
    fun remoteChanged(remote: RemoteState, seenEtag: String?, seenMtime: Long, seenSize: Long): Boolean {
        if (seenEtag != null) return remote.etag != seenEtag
        // 用 != 而不是 >：宁可多弹一次冲突（不丢数据），也不要因为服务器返回的
        // 时间戳变小（外部上传保留了旧 mtime）而漏判、进而静默覆盖云端
        if (seenMtime > 0 && remote.lastModifiedMs > 0) return remote.lastModifiedMs != seenMtime
        if (seenSize >= 0 && remote.size >= 0) return remote.size != seenSize
        return false
    }

    fun decide(
        remote: RemoteState?,
        localHash: String,
        lastUploadedHash: String?,
        seenEtag: String?,
        seenMtime: Long,
        seenSize: Long
    ): SyncDecision {
        // 云端没有该文件：直接创建（即使本地是空内容也是用户的本意）
        if (remote == null) return SyncDecision.Create

        // 本机从未上传过、云端却已有文件：不能直接覆盖，先取回来比对
        if (lastUploadedHash == null) return SyncDecision.CompareWithRemote

        val localChanged = localHash != lastUploadedHash
        val remoteChanged = remoteChanged(remote, seenEtag, seenMtime, seenSize)
        return when {
            !localChanged && !remoteChanged -> SyncDecision.UpToDate
            localChanged && !remoteChanged -> SyncDecision.Upload
            !localChanged -> SyncDecision.Conflict(ConflictReason.REMOTE_MODIFIED)
            else -> SyncDecision.Conflict(ConflictReason.BOTH_MODIFIED)
        }
    }

    /**
     * 节流闸门。
     *
     * 按产品决定：**所有自动触发**（关闭编辑器 / 打开应用）都受 30 分钟最低间隔限制，
     * 手动按钮与冲突解决不受限（那是用户明确的当下意图）。
     * `lastAttemptAt` 用的是「尝试」时间而不是「成功」时间，避免失败后立刻重试形成风暴。
     * 若系统时钟被回拨（delta < 0），放行而不是长时间卡死。
     */
    fun gate(
        trigger: SyncTrigger,
        now: Long,
        lastAttemptAt: Long,
        intervalMs: Long = MIN_SYNC_INTERVAL_MS
    ): GateResult {
        if (trigger == SyncTrigger.MANUAL || trigger == SyncTrigger.CONFLICT_RESOLVE) return GateResult.RUN
        if (lastAttemptAt <= 0) return GateResult.RUN
        val delta = now - lastAttemptAt
        return if (delta in 0 until intervalMs) GateResult.SKIP_THROTTLED else GateResult.RUN
    }

    /**
     * 打开应用时是否需要补一次同步：
     * 上次没成功、或本机从未上传过、或本地内容与上次上传的不一致（例如进程被杀在同步之前）。
     */
    fun shouldSyncOnOpen(lastResult: String, localHash: String, lastUploadedHash: String?): Boolean =
        lastResult != RESULT_SUCCESS || lastUploadedHash == null || localHash != lastUploadedHash

    /**
     * 冲突副本文件名：`note.txt` + 2026-10-01 03:15:00 → `note.conflict-20261001-031500.txt`
     *
     * `attempt` 用于同一秒内出现第二个冲突时避免重名（-2、-3…）。
     */
    fun conflictCopyName(fileName: String, timestampMs: Long, attempt: Int = 0): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ".txt"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestampMs))
        val dedupe = if (attempt > 0) "-${attempt + 1}" else ""
        return "$base.conflict-$stamp$dedupe$ext"
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
