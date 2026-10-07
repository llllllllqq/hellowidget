package moe.hellowidget.sync

import java.security.MessageDigest

/**
 * 同步触发来源。
 *
 * v7.8 起删除 `APP_OPEN`：打开应用**不再**自动上传，只做一次「有没有待上传的改动」的
 * 纯检测（见 `SyncManager.hasPendingUpload`），结果显示为「立即上传」按钮上的橙点。
 * 于是自动上传只剩 `CLOSE_EDITOR` 一类 —— 也就是「保存」这一个语义。
 *
 * v8.1.0 起两者在**时机**上完全一样（都没有任何节流，见下），区别只剩两点：
 *  - [MANUAL] 是用户按下的当下意图，因此**永不并入**正在执行的那一趟同步，自己走一趟；
 *  - [MANUAL] 不显示"保存触发"的进度文案，日志与状态页也据此区分来源。
 */
enum class SyncTrigger { CLOSE_EDITOR, MANUAL }

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
 *
 * ## v8.1.0：**删除了 1 分钟节流闸门**（原 `gate()` / `MIN_SYNC_INTERVAL_MS`）
 * 删掉的理由是一条实测结论：**防重复上传的一直是内容哈希短路，不是闸门**。
 * 内容没变时本来就一个请求都不发，闸门唯一的作用是让**真的变了**的内容晚 60 秒才上传
 * （真机日志里的病灶：10:13:23 那次"零请求的空跑"把闸门关到 10:14:23，于是 10:13:26
 * 那次有内容的退出被静默跳过，内容一直等到 10:14:31 的系统任务才上去，用户看到"退出没上传"）。
 *
 * 现在三类职责彼此独立、各有归属：
 *  1. **要不要发请求** → 内容哈希（本文件 [hasLocalChanges]）；
 *  2. **能不能同时发** → `SyncManager` 的单飞互斥 + 合并（一次突发最多 2 趟，绝不并发 PUT）；
 *  3. **错了多久再试** → 只有失败才产生间隔（前台窗口内 1s/3s，窗口外由系统任务指数退避）。
 *
 * 删除闸门**没有**削弱"打开应用只看不传"（v7.8）：那条语义靠的是"打开应用根本不触发同步"，
 * 而不是靠闸门；它的证据也从「`lastAttemptAt` 仍为 0」换成了
 * 「`lastServerContactAt` 仍为 0（一次请求都没发）」。
 */
object SyncEngine {

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
     * 本次上传该用的 unix **秒**时间戳。
     *
     * 取 `max(now, 上次上传的时间戳 + 1)`，两个作用：
     *  - 同一秒内的第二次上传不会复用同一个文件名（那会覆盖上一份历史）；
     *  - 设备时钟被回拨时文件名依旧单调递增，云端按名字排序仍是正确的时间顺序。
     */
    fun nextUploadTimestamp(nowSec: Long, lastUploadedSec: Long): Long =
        if (nowSec > lastUploadedSec) nowSec else lastUploadedSec + 1

    private val HEX = "0123456789abcdef".toCharArray()
}
