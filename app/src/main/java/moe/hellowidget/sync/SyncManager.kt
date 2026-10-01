package moe.hellowidget.sync

import android.content.Context
import android.util.Log
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
import moe.hellowidget.TextWidgetProvider

/** 同步结果（界面据此渲染状态行） */
sealed interface SyncStatus {
    object Idle : SyncStatus
    data class Running(val trigger: SyncTrigger, val startedAt: Long) : SyncStatus

    /** uploaded = true 表示真的上传了；false 表示确认云端已是最新 */
    data class Success(val at: Long, val uploaded: Boolean) : SyncStatus
    data class Failed(val at: Long, val error: WebDavError, val detail: String) : SyncStatus
    data class Conflict(val at: Long, val reason: ConflictReason) : SyncStatus
    data class Skipped(val reason: SkipReason) : SyncStatus
}

enum class SkipReason { NOT_ENABLED, NOT_CONFIGURED, THROTTLED, PENDING_CONFLICT }

/**
 * 同步编排：闸门（30 分钟节流 / 待处理冲突）→ 决策 → 上传或产生冲突 → 记录状态。
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

    /** 前台服务无法启动时的兜底（进程内同步，没有通知栏进度） */
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun requestInProcess(context: Context, trigger: SyncTrigger, resolution: ConflictChoice? = null) {
        val appContext = context.applicationContext
        fallbackScope.launch {
            try {
                performSync(appContext, trigger, resolution)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "进程内同步异常", e)
            }
        }
    }

    /**
     * 打开应用时是否需要补一次同步。
     * 判断放在这里而不是 MainActivity：编辑器只需把当前文本交进来，逻辑留在可单测的地方。
     */
    fun needsSyncOnOpen(context: Context, localText: String): Boolean {
        if (!SyncSettings.enabled(context)) return false
        if (SyncSettings.config(context) == null) return false
        if (SyncSettings.pendingConflict(context)) return false
        val hash = SyncEngine.sha256Hex(localText.toByteArray(Charsets.UTF_8))
        return SyncEngine.shouldSyncOnOpen(
            lastResult = SyncSettings.lastResult(context),
            localHash = hash,
            lastUploadedHash = SyncSettings.lastUploadedHash(context)
        )
    }

    suspend fun performSync(
        context: Context,
        trigger: SyncTrigger,
        resolution: ConflictChoice? = null
    ): SyncStatus = mutex.withLock { doSync(context.applicationContext, trigger, resolution) }

    // ------------------------------------------------------------------ 闸门

    private suspend fun doSync(
        context: Context,
        trigger: SyncTrigger,
        resolution: ConflictChoice?
    ): SyncStatus {
        if (!SyncSettings.enabled(context)) return SyncStatus.Skipped(SkipReason.NOT_ENABLED)

        val config = SyncSettings.config(context) ?: run {
            SyncSettings.recordFailure(context, WebDavError.NOT_CONFIGURED)
            return SyncStatus.Failed(
                System.currentTimeMillis(), WebDavError.NOT_CONFIGURED, "同步设置不完整"
            )
        }

        val now = System.currentTimeMillis()
        if (resolution == null) {
            // 有待处理的冲突时绝不自动上传：否则下一次「关闭编辑器」会静默覆盖云端那份外部修改
            if (SyncSettings.pendingConflict(context)) {
                SyncNotifier.postConflict(context, config.fileName)
                return SyncStatus.Skipped(SkipReason.PENDING_CONFLICT)
            }
            if (SyncEngine.gate(trigger, now, SyncSettings.lastAttemptAt(context)) == GateResult.SKIP_THROTTLED) {
                Log.i(TAG, "距上次同步不足 ${SyncEngine.MIN_SYNC_INTERVAL_MS / 60_000} 分钟，本次跳过")
                return SyncStatus.Skipped(SkipReason.THROTTLED)
            }
        }

        SyncSettings.setLastAttemptAt(context, now)
        _status.value = SyncStatus.Running(trigger, now)

        val client = HttpWebDavClient(
            config = config,
            onUntrustedCertificate = { fingerprint ->
                // 只记录「待确认」，本次仍然失败：证书信任必须由用户在看到指纹后决定
                SyncSettings.setPendingTlsPin(context, fingerprint)
            }
        )

        return try {
            val outcome = if (resolution != null) {
                resolve(context, client, config, resolution)
            } else {
                syncOnce(context, client, config)
            }
            _status.value = outcome
            when (outcome) {
                is SyncStatus.Conflict -> SyncNotifier.postConflict(context, config.fileName)
                is SyncStatus.Success -> {
                    SyncNotifier.cancelConflict(context)
                    SyncNotifier.cancelFailure(context)
                }
                else -> Unit
            }
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val (error, detail) = classify(e)
            val safeDetail = sanitize(detail, config.password)
            Log.w(TAG, "同步失败：$error ${safeDetail.take(200)}")
            if (error == WebDavError.TLS_UNTRUSTED) SyncSettings.setPendingTlsPin(context, detail)
            SyncSettings.recordFailure(context, error)
            SyncNotifier.postFailure(context, error, safeDetail)
            SyncStatus.Failed(System.currentTimeMillis(), error, safeDetail).also { _status.value = it }
        } finally {
            client.close()
        }
    }

    // ------------------------------------------------------------------ 正常同步

    private suspend fun syncOnce(context: Context, client: WebDavClient, config: SyncConfig): SyncStatus {
        val url = config.fileUrl
        val localText = ContentStore.read()
        val localHash = SyncEngine.sha256Hex(localText.toByteArray(Charsets.UTF_8))
        val remote = client.stat(url)

        return when (val decision = SyncEngine.decide(
            remote = remote?.toState(),
            localHash = localHash,
            lastUploadedHash = SyncSettings.lastUploadedHash(context),
            seenEtag = SyncSettings.lastRemoteEtag(context),
            seenMtime = SyncSettings.lastRemoteMtime(context),
            seenSize = SyncSettings.lastRemoteSize(context)
        )) {
            is SyncDecision.UpToDate -> {
                SyncSettings.recordSuccess(context, localHash, remote)
                SyncStatus.Success(System.currentTimeMillis(), uploaded = false)
            }

            is SyncDecision.Create -> upload(
                context, client, config, localText, localHash,
                ifNoneMatchStar = true, expected = null
            )

            is SyncDecision.Upload -> upload(
                context, client, config, localText, localHash,
                ifNoneMatchStar = false, expected = remote
            )

            // 首次同步且云端已有文件：先取回比对，内容相同就只是「确认一致」，
            // 不同则交给用户决定（绝不默认覆盖）
            is SyncDecision.CompareWithRemote -> {
                val bytes = client.get(url, SyncEngine.MAX_REMOTE_BYTES)
                when {
                    bytes == null -> upload(
                        context, client, config, localText, localHash,
                        ifNoneMatchStar = true, expected = null
                    )

                    SyncEngine.sha256Hex(bytes) == localHash -> {
                        SyncSettings.recordSuccess(context, localHash, client.stat(url) ?: remote)
                        SyncStatus.Success(System.currentTimeMillis(), uploaded = false)
                    }

                    else -> recordConflict(context, ConflictReason.REMOTE_EXISTS_ON_FIRST_SYNC, remote)
                }
            }

            is SyncDecision.Conflict -> recordConflict(context, decision.reason, remote)
        }
    }

    private fun upload(
        context: Context,
        client: WebDavClient,
        config: SyncConfig,
        localText: String,
        localHash: String,
        ifNoneMatchStar: Boolean,
        expected: RemoteFile?
    ): SyncStatus {
        val bytes = localText.toByteArray(Charsets.UTF_8)
        ensureDirectory(client, config)

        val result: RemoteFile? = try {
            client.putAtomic(
                url = config.fileUrl,
                body = bytes,
                ifNoneMatchStar = ifNoneMatchStar,
                ifMatch = if (ifNoneMatchStar) null else expected?.etag?.takeIf { it.isNotBlank() },
                ifUnmodifiedSinceMs = if (ifNoneMatchStar) null else expected?.lastModifiedMs?.takeIf { it > 0 }
            )
        } catch (e: WebDavException) {
            if (e.error != WebDavError.PRECONDITION_FAILED) throw e
            // 决策之后云端又被改过：用最新状态重新判定一次（只重试一次，不形成循环）
            val fresh = client.stat(config.fileUrl)
            when (val retry = SyncEngine.decide(
                remote = fresh?.toState(),
                localHash = localHash,
                lastUploadedHash = SyncSettings.lastUploadedHash(context),
                seenEtag = SyncSettings.lastRemoteEtag(context),
                seenMtime = SyncSettings.lastRemoteMtime(context),
                seenSize = SyncSettings.lastRemoteSize(context)
            )) {
                is SyncDecision.Upload, is SyncDecision.Create -> client.putAtomic(
                    url = config.fileUrl,
                    body = bytes,
                    ifNoneMatchStar = retry is SyncDecision.Create,
                    ifMatch = if (retry is SyncDecision.Create) null else fresh?.etag?.takeIf { it.isNotBlank() },
                    ifUnmodifiedSinceMs = if (retry is SyncDecision.Create) null else fresh?.lastModifiedMs?.takeIf { it > 0 }
                )

                is SyncDecision.UpToDate -> {
                    SyncSettings.recordSuccess(context, localHash, fresh)
                    return SyncStatus.Success(System.currentTimeMillis(), uploaded = false)
                }

                is SyncDecision.CompareWithRemote -> {
                    return recordConflict(context, ConflictReason.REMOTE_EXISTS_ON_FIRST_SYNC, fresh)
                }

                is SyncDecision.Conflict -> return recordConflict(context, retry.reason, fresh)
            }
        }

        SyncSettings.recordSuccess(context, localHash, result)
        return SyncStatus.Success(System.currentTimeMillis(), uploaded = true)
    }

    // ------------------------------------------------------------------ 冲突解决

    private suspend fun resolve(
        context: Context,
        client: WebDavClient,
        config: SyncConfig,
        choice: ConflictChoice
    ): SyncStatus {
        val url = config.fileUrl
        val localText = ContentStore.read()
        val localBytes = localText.toByteArray(Charsets.UTF_8)
        val localHash = SyncEngine.sha256Hex(localBytes)
        val remote = client.stat(url)

        return when (choice) {
            // 保留本地：先把云端那份存成冲突副本，再用本地覆盖主文件
            ConflictChoice.KEEP_LOCAL -> {
                if (remote != null) backupRemoteToConflictCopy(client, config, url)
                ensureDirectory(client, config)
                val result = client.putAtomic(
                    url = url,
                    body = localBytes,
                    ifNoneMatchStar = remote == null,
                    ifMatch = remote?.etag?.takeIf { it.isNotBlank() },
                    ifUnmodifiedSinceMs = remote?.lastModifiedMs?.takeIf { it > 0 }
                )
                SyncSettings.clearConflict(context)
                SyncSettings.recordSuccess(context, localHash, result)
                SyncStatus.Success(System.currentTimeMillis(), uploaded = true)
            }

            // 保留云端：先把本地那份上传成冲突副本，再用云端内容覆盖本地编辑器与小组件
            ConflictChoice.USE_REMOTE -> {
                val bytes = client.get(url, SyncEngine.MAX_REMOTE_BYTES)
                    ?: throw WebDavException(
                        WebDavError.NOT_FOUND, 404, "云端文件已不存在，无法用云端内容覆盖本地"
                    )
                putConflictCopy(client, config, localBytes)
                val remoteText = String(bytes, Charsets.UTF_8)
                if (!ContentStore.write(remoteText)) {
                    throw WebDavException(WebDavError.IO, detail = "云端内容已取回，但写入本地失败")
                }
                TextWidgetProvider.updateWidgets(context)
                // 让还活着的编辑页知道自己手里的内容已经过期
                SyncSettings.setContentReplacedAt(context, System.currentTimeMillis())
                SyncSettings.clearConflict(context)
                SyncSettings.recordSuccess(context, SyncEngine.sha256Hex(bytes), remote)
                SyncStatus.Success(System.currentTimeMillis(), uploaded = false)
            }
        }
    }

    /** 把云端现有内容复制成冲突副本（优先服务端 COPY，不支持时才 GET + PUT） */
    private fun backupRemoteToConflictCopy(client: WebDavClient, config: SyncConfig, url: String) {
        val now = System.currentTimeMillis()
        for (attempt in 0 until MAX_COPY_ATTEMPTS) {
            val destUrl = config.conflictCopyUrl(SyncEngine.conflictCopyName(config.fileName, now, attempt))
            try {
                client.copy(url, destUrl, overwrite = false)
                return
            } catch (e: WebDavException) {
                when (e.error) {
                    WebDavError.NOT_SUPPORTED -> {
                        val bytes = client.get(url, SyncEngine.MAX_REMOTE_BYTES)
                            ?: throw WebDavException(
                                WebDavError.NOT_FOUND, 404, "云端文件已不存在，无法生成冲突副本"
                            )
                        if (putConflictCopyTo(client, destUrl, bytes, attempt)) return
                    }
                    // 副本重名（同一秒内的第二次冲突）：换个名字再来
                    WebDavError.PRECONDITION_FAILED -> Unit
                    else -> throw e
                }
            }
        }
        throw WebDavException(WebDavError.PRECONDITION_FAILED, 412, "无法生成冲突副本名称")
    }

    /** 把本地内容上传成冲突副本 */
    private fun putConflictCopy(
        client: WebDavClient,
        config: SyncConfig,
        bytes: ByteArray
    ) {
        val now = System.currentTimeMillis()
        for (attempt in 0 until MAX_COPY_ATTEMPTS) {
            val destUrl = config.conflictCopyUrl(SyncEngine.conflictCopyName(config.fileName, now, attempt))
            if (putConflictCopyTo(client, destUrl, bytes, attempt)) return
        }
        throw WebDavException(WebDavError.PRECONDITION_FAILED, 412, "无法生成冲突副本名称")
    }

    /** @return true = 已写入；false = 该名字已存在，换下一个 */
    private fun putConflictCopyTo(
        client: WebDavClient,
        destUrl: String,
        bytes: ByteArray,
        attempt: Int
    ): Boolean = try {
        client.putAtomic(destUrl, bytes, ifNoneMatchStar = true, ifMatch = null, ifUnmodifiedSinceMs = null)
        true
    } catch (e: WebDavException) {
        if (e.error == WebDavError.PRECONDITION_FAILED && attempt < MAX_COPY_ATTEMPTS - 1) false else throw e
    }

    // ------------------------------------------------------------------ 工具

    private fun ensureDirectory(client: WebDavClient, config: SyncConfig) {
        try {
            client.mkcol(config.directoryUrl)
        } catch (e: WebDavException) {
            // 服务器不支持 MKCOL 时不要在这里失败，交给 PUT 报出更准确的错误
            if (e.error != WebDavError.NOT_SUPPORTED) throw e
        }
    }

    private fun recordConflict(context: Context, reason: ConflictReason, remote: RemoteFile?): SyncStatus {
        SyncSettings.recordConflict(context, reason, remote)
        return SyncStatus.Conflict(System.currentTimeMillis(), reason)
    }

    private fun classify(e: Throwable): Pair<WebDavError, String> = when (e) {
        is WebDavException -> e.error to e.detail
        is SecurityException -> WebDavError.IO to (e.message ?: "权限不足")
        else -> WebDavError.IO to (e.message ?: e.javaClass.simpleName)
    }

    /** 日志里绝不能出现口令 */
    private fun sanitize(detail: String, password: String): String =
        if (password.isNotEmpty()) detail.replace(password, "***") else detail

    private const val MAX_COPY_ATTEMPTS = 3
}
