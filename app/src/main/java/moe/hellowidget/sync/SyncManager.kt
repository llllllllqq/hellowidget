package moe.hellowidget.sync

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.hellowidget.ContentStore

/** 同步结果（界面据此渲染状态行） */
sealed interface SyncStatus {
    object Idle : SyncStatus
    data class Running(val trigger: SyncTrigger, val startedAt: Long) : SyncStatus

    /** uploaded = true 表示真的把内容传上去了；false 表示本地自上次上传后没有改动 */
    data class Success(val at: Long, val uploaded: Boolean) : SyncStatus
    data class Failed(val at: Long, val error: WebDavError, val detail: String) : SyncStatus
    data class Skipped(val reason: SkipReason) : SyncStatus
}

enum class SkipReason { NOT_ENABLED, NOT_CONFIGURED, THROTTLED }

/**
 * 同步编排：闸门（1 分钟节流）→ 「本地变了没有」→ 强制覆盖上传 → 记录状态。
 *
 * ## v7.5 的单向语义
 * 这里**从不读取云端**：不 HEAD、不 PROPFIND、不 GET，因此也没有冲突、没有合并、
 * 没有「云端被改过」的判断。云端对本应用而言只是一个写入目的地：
 * 本机内容一变，下一次同步就整份覆盖它；本机没变，就一个请求都不发。
 *
 * ## v7.8：自动上传只由「保存」驱动
 * 打开应用（含旋转 / 深色模式重建）**不再**发起任何同步，只调用 [hasPendingUpload]
 * 做一次纯检测，结果交给界面在「立即上传」按钮上显示橙点。
 * 自动上传只发生在「保存内容」之后（返回键 / 失焦 / 切后台 / 旋转 / 深色模式等所有保存路径），
 * 且统一受 1 分钟闸门约束；手动按钮走 [SyncTrigger.MANUAL]，不受闸门限制。
 *
 * 线程模型：全部网络与磁盘操作跑在调用方的 IO 协程里；[mutex] 保证同一时刻只有一次同步，
 * 重复请求会排队，且自动触发会被刚更新过的 `lastAttemptAt` 直接节流掉。
 */
object SyncManager {

    private const val TAG = "SyncManager"

    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /** 同步状态（界面订阅它渲染状态行；用 getter 暴露不可变视图，避免多引一个扩展函数） */
    val status: StateFlow<SyncStatus> get() = _status

    /**
     * 客户端构造入口。默认发真实请求；单测里替换成假实现，
     * 就能在 JVM 上断言「有没有发请求、发的什么」而不需要网络。
     */
    @VisibleForTesting
    internal var clientFactory: (SyncConfig, (String) -> Unit) -> WebDavClient =
        { config, onUntrusted -> OkHttpWebDavClient(config, onUntrusted) }

    /** 待上传内容的读取入口；单测里替换掉，避免依赖 DataStore 的具体实现 */
    @VisibleForTesting
    internal var contentReader: suspend () -> String = { ContentStore.read() }

    /** 前台服务无法启动时的兜底（进程内同步；进度通知由这里自己发） */
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 进程内执行一次同步（没有前台服务，因此这里自己维护进度通知）。
     * 只在 [SyncLauncher] 无法启动前台服务时使用。
     */
    fun requestInProcess(context: Context, trigger: SyncTrigger) {
        val appContext = context.applicationContext
        fallbackScope.launch {
            try {
                performSync(appContext, trigger, progress = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "进程内同步异常", e)
            }
        }
    }

    /**
     * v7.8：**只检测、不上传**——「上次成功上传之后，本地内容又有改动了吗？」
     *
     * 这是顶部导航栏「立即上传」按钮上那个橙点唯一的判定依据（见 MainActivity）：
     *  - **零网络、零落盘、零通知**：只是读一次已持久化的 `lastUploadedHash` + 算一次 SHA-256；
     *  - 打开应用（以及每次同步结束）时调用，**绝不因此发起任何同步**；
     *  - 判断放在这里而不是 MainActivity：编辑器只需把当前文本交进来，逻辑留在可单测的地方。
     *
     * 空内容的特例：本机**从未上传过**且内容为空（刚装好应用、还没写东西）时返回 false ——
     * 否则新用户一装好就会看到一个没有意义的橙点。删空一份已上传过的内容仍算「有改动」（true）。
     */
    fun hasPendingUpload(context: Context, localText: String): Boolean {
        if (!SyncSettings.enabled(context)) return false
        if (SyncSettings.config(context) == null) return false
        val lastUploadedHash = SyncSettings.lastUploadedHash(context)
        if (lastUploadedHash == null && localText.isEmpty()) return false
        val hash = SyncEngine.sha256Hex(localText.toByteArray(Charsets.UTF_8))
        return SyncEngine.hasLocalChanges(hash, lastUploadedHash)
    }

    /** 前台服务路径（进度通知由服务的 `startForeground` 负责） */
    suspend fun performSync(context: Context, trigger: SyncTrigger): SyncStatus =
        performSync(context, trigger, progress = false)

    @VisibleForTesting
    internal suspend fun performSync(
        context: Context,
        trigger: SyncTrigger,
        progress: Boolean
    ): SyncStatus {
        // v7.9：进锁前后各记一条日志。故障复发时「有『同步请求』却迟迟没有『获得同步锁』」
        // 就是「上一次同步卡住、一直占着锁」的决定性证据（用户只需要把 logcat 截下来）。
        Log.i(TAG, "同步请求：trigger=$trigger")
        return mutex.withLock {
            Log.i(TAG, "获得同步锁：trigger=$trigger")
            doSync(context.applicationContext, trigger, progress)
        }
    }

    // ------------------------------------------------------------------ 闸门

    private suspend fun doSync(
        context: Context,
        trigger: SyncTrigger,
        progress: Boolean
    ): SyncStatus {
        if (!SyncSettings.enabled(context)) return SyncStatus.Skipped(SkipReason.NOT_ENABLED)

        val config = SyncSettings.config(context) ?: run {
            SyncSettings.recordFailure(context, WebDavError.NOT_CONFIGURED)
            return SyncStatus.Failed(
                System.currentTimeMillis(), WebDavError.NOT_CONFIGURED, "同步设置不完整"
            )
        }

        val now = System.currentTimeMillis()
        if (SyncEngine.gate(trigger, now, SyncSettings.lastAttemptAt(context)) == GateResult.SKIP_THROTTLED) {
            Log.i(TAG, "距上次同步不足 ${SyncEngine.MIN_SYNC_INTERVAL_MS / 60_000} 分钟，本次跳过")
            return SyncStatus.Skipped(SkipReason.THROTTLED)
        }

        SyncSettings.setLastAttemptAt(context, now)
        val startedAt = System.currentTimeMillis()
        _status.value = SyncStatus.Running(trigger, startedAt)
        Log.i(TAG, "开始同步：trigger=$trigger")
        // 进程内路径没有前台服务，通知得自己发（放在闸门之后：被跳过时不打扰用户）
        if (progress) SyncNotifier.postProgress(context)

        var uploaded = false
        var client: WebDavClient? = null
        return try {
            // 客户端构造本身也可能抛（TLS/参数问题），因此也放进 try：
            // 否则会带着 Running 状态逃出去，状态行永远停在「正在同步…」
            val created = clientFactory(config) { fingerprint ->
                // 只记录「待确认」，本次仍然失败：证书信任必须由用户在看到指纹后决定
                SyncSettings.setPendingTlsPin(context, fingerprint)
            }
            client = created
            val outcome = uploadOnce(context, created, config)
            uploaded = outcome is SyncStatus.Success && outcome.uploaded
            _status.value = outcome
            Log.i(TAG, "同步结束：$outcome（耗时 ${System.currentTimeMillis() - startedAt}ms）")
            if (outcome is SyncStatus.Success) {
                // 内容已经在云端（或本地本来就没有改动）：撤销系统重试任务，不必再唤醒进程
                SyncRetry.cancel(context)
                if (outcome.uploaded) SyncNotifier.showUploadSucceededToast(context)
            }
            outcome
        } catch (e: CancellationException) {
            // 协程被取消也必须留下终态：否则状态行会永远停在「正在同步…」，
            // 让人误以为上传还在进行（SyncService 被销毁时就会发生）
            Log.w(TAG, "同步被取消：trigger=$trigger")
            _status.value = SyncStatus.Failed(
                System.currentTimeMillis(), WebDavError.IO, "同步被取消"
            )
            throw e
        } catch (e: Throwable) {
            val (error, detail) = classify(e)
            val safeDetail = sanitize(detail, config.password)
            Log.w(
                TAG,
                "同步失败：$error ${safeDetail.take(200)}（耗时 ${System.currentTimeMillis() - startedAt}ms）"
            )
            if (error == WebDavError.TLS_UNTRUSTED) SyncSettings.setPendingTlsPin(context, detail)
            SyncSettings.recordFailure(context, error)
            SyncNotifier.showUploadFailedToast(context, error, safeDetail)
            SyncStatus.Failed(System.currentTimeMillis(), error, safeDetail).also { _status.value = it }
        } finally {
            if (progress) {
                // 只有**真的上传了**才保证进度通知可见的时长：上传常常几百毫秒就结束，
                // 立即收掉用户根本看不到；而「本地没变、什么都没做」不该弹通知打扰用户
                if (uploaded) SyncNotifier.awaitProgressVisibleFor(startedAt)
                SyncNotifier.cancelProgress(context)
            }
            client?.close()
        }
    }

    // ------------------------------------------------------------------ 单向覆盖上传

    private suspend fun uploadOnce(context: Context, client: WebDavClient, config: SyncConfig): SyncStatus {
        val localText = contentReader()
        val bytes = localText.toByteArray(Charsets.UTF_8)
        val localHash = SyncEngine.sha256Hex(bytes)

        if (!SyncEngine.hasLocalChanges(localHash, SyncSettings.lastUploadedHash(context))) {
            // 本地自上次成功上传后没有任何改动：一个请求都不发（省流量、省服务器配额）
            SyncSettings.recordSuccess(context, localHash, SyncSettings.lastUploadedTs(context))
            return SyncStatus.Success(System.currentTimeMillis(), uploaded = false)
        }

        // 每次上传都写一个新文件：文件名 = 前缀 + unix 毫秒时间戳 + 扩展名
        val timestampSec = SyncEngine.nextUploadTimestamp(
            nowSec = System.currentTimeMillis(),
            lastUploadedSec = SyncSettings.lastUploadedTs(context)
        )
        putNewFile(client, config, bytes, timestampSec)
        SyncSettings.recordSuccess(context, localHash, timestampSec)
        return SyncStatus.Success(System.currentTimeMillis(), uploaded = true)
    }

    /**
     * 把内容写成一个**新文件**（`<前缀><unix 秒时间戳><扩展名>`）。
     *
     * 新文件名意味着云端不会丢任何历史；客户端也从不读取或清理远端，
     * 只做「往这个目录里放一份带时间戳的新内容」这一件事。
     *
     * 目标目录不存在时（服务器回 409/404）先 `MKCOL` 再重试一次 ——
     * 这样正常路径只需要一个请求，而首次使用（用户填的目录还没建）也能自动建好。
     */
    private fun putNewFile(client: WebDavClient, config: SyncConfig, bytes: ByteArray, timestampSec: Long) {
        val url = config.historyFileUrl(timestampSec)
        try {
            client.put(url, bytes)
        } catch (e: WebDavException) {
            if (e.error != WebDavError.PARENT_NOT_FOUND && e.error != WebDavError.NOT_FOUND) throw e
            Log.i(TAG, "云端目录不存在（${e.error}），先创建目录再重试上传")
            client.mkcol(config.directoryUrl)
            client.put(url, bytes)
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun classify(e: Throwable): Pair<WebDavError, String> = when (e) {
        is WebDavException -> e.error to e.detail
        is SecurityException -> WebDavError.IO to (e.message ?: "权限不足")
        else -> WebDavError.IO to (e.message ?: e.javaClass.simpleName)
    }

    /** 日志里绝不能出现口令 */
    private fun sanitize(detail: String, password: String): String =
        if (password.isNotEmpty()) detail.replace(password, "***") else detail
}
