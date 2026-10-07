package moe.hellowidget.sync

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

enum class SkipReason { NOT_ENABLED, NOT_CONFIGURED }

/**
 * 同步编排：「本地变了没有」→ 强制覆盖上传 → 记录状态。
 *
 * ## v7.5 的单向语义
 * 这里**从不读取云端**：不 HEAD、不 PROPFIND、不 GET，因此也没有冲突、没有合并、
 * 没有「云端被改过」的判断。云端对本应用而言只是一个写入目的地：
 * 本机内容一变，下一次同步就整份覆盖它；本机没变，就一个请求都不发。
 *
 * ## v7.8：自动上传只由「保存」驱动
 * 打开应用（含旋转 / 深色模式重建）**不再**发起任何同步，只调用 [hasPendingUpload]
 * 做一次纯检测，结果交给界面在「立即上传」按钮上显示橙点。
 * 自动上传只发生在「保存内容」之后（返回键 / 失焦 / 切后台 / 旋转 / 深色模式等所有保存路径）。
 *
 * ## v8.1.0：删掉 1 分钟闸门，换成「单飞 + 合并」
 * 旧实现过闸门后无条件写 `lastAttemptAt`，于是**一次零请求的空跑**（本地没改动、
 * 一个请求都没发）也会把闸门关上 60 秒，把紧随其后的真实上传静默跳过 —— 真机日志里
 * 156 秒的"退出没上传"就是这么来的。现在：
 *  - **没有任何成功节流**：每次保存都立刻尝试；要不要发请求只由内容哈希决定；
 *  - **同一时刻只有一个上传**：[mutex] 是硬保证（日志里 `开始同步` 与 `同步结束` 必然成对不交错）；
 *  - **合并的粒度是"一趟"**：领跑者开始之后登记的请求会并入它，因此 PUT 次数由**上传批次**决定
 *    （一趟上传吸收它开始前积累的所有保存），而不是由保存次数决定；持续保存时，每一趟跑完
 *    只会再有一趟代表最新内容 —— 详见 [performSync] 的注释。
 *
 * 线程模型：全部网络与磁盘操作跑在调用方的 IO 协程里；
 * [requestSeq] 在进锁前登记、[claimedSeq] / [lastResult] 只在锁内读写。
 */
object SyncManager {

    private const val TAG = "SyncManager"

    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)

    /** 请求登记序号：**进锁之前**递增，代表"此刻内容已经在盘上" */
    private val requestSeq = AtomicLong(0L)

    /** 已被某一趟同步"认领"到的最大序号。只在 [mutex] 内访问 */
    private var claimedSeq = 0L

    /** 最近一趟同步的终态，给并入者复用。只在 [mutex] 内访问 */
    private var lastResult: SyncStatus = SyncStatus.Idle

    /**
     * 被并入（因此没有重复上传）的请求次数。
     *
     * 只用于测试与日志：一条"突发 6 次保存"的用例据此断言**确实发生了合并**，
     * 而不是靠"`put` 次数没变多"间接推断（内容恰好相同也会让 put 次数不变）。
     */
    @VisibleForTesting
    internal var coalescedRequests: Int = 0
        private set

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
     * v8.0.3：窗口内重试的**时间基准**。
     *
     * 生产环境就是系统时钟（`System::currentTimeMillis`），留这个缝只是为了单测能模拟
     * "第一次尝试很慢才失败"（[SyncWindowRetry.RETRY_DEADLINE_MS] 的边界），
     * 否则那个用例得真等 20 秒。
     */
    @VisibleForTesting
    internal var elapsedClock: () -> Long = System::currentTimeMillis

    /**
     * 进程内执行一次同步（没有前台服务，因此这里自己维护进度通知）。
     * 只在 [SyncLauncher] 无法启动前台服务时使用。
     */
    fun requestInProcess(context: Context, trigger: SyncTrigger) {
        val appContext = context.applicationContext
        fallbackScope.launch {
            try {
                performSync(
                    appContext,
                    trigger,
                    progress = true,
                    // 这条路径同样处在"用户刚离开界面"的窗口里，值得享受窗口内快速重试；
                    // 通知由本路径自己持有，所以阶段文案也由它更新
                    onWindowPhase = { text -> SyncNotifier.postProgress(appContext, text) }
                )
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

    /**
     * 前台服务路径（进度通知由服务的 `startForeground` 负责）。
     *
     * [onWindowPhase] 非 null = 这条路径**拥有可见的前台通知**，因此允许窗口内快速重试
     * （[SyncWindowRetry]），并把阶段文案（重试中 / 恢复默认）交给它显示；
     * 传 null = 没有前台窗口（系统兜底任务），既不改通知也不做窗口内重试。
     */
    suspend fun performSync(
        context: Context,
        trigger: SyncTrigger,
        onWindowPhase: ((String?) -> Unit)? = null
    ): SyncStatus = performSync(context, trigger, progress = false, onWindowPhase = onWindowPhase)

    /**
     * 单飞 + 合并（v8.1.0）。
     *
     * ## 为什么是"等它跑完再返回"，而不是"提前返回"
     * 前台通知是 **service 级**的（所有请求共用同一个 `ID_PROGRESS`）。如果被并入的请求
     * 提前返回，它的那一次 `stopForeground(REMOVE)` 会把**正在上传**的那条通知撤掉。
     * 等锁的写法天然没有这个问题：并入者是在领跑者结束之后才做自己的收尾。
     *
     * ## 为什么领跑者认领的是 `requestSeq.get()` 而不是自己的序号
     * 这一行决定**合并的粒度**。领跑者在**读取内容之前**认领"此刻已登记的全部请求"，
     * 于是所有在它开始前登记的请求（序号 ≤ 认领值）在拿到锁时都会发现自己是"已被覆盖"，
     * 直接复用它的结果；只有"领跑者开始之后才登记"的请求会成为下一趟 —— 它代表更新的内容，
     * 必须自己跑。
     *
     * 若只认领自己的序号，N 个排队者就会依次各跑一趟（旧行为）。
     * 注意这不是"任何情况下都只有两趟"：持续保存时，每一趟跑完都会再有一趟代表最新内容
     * （仍然串行、仍然绝不并发），所以**上传次数由"上传批次"决定，而不是由保存次数决定**。
     *
     * ## 用户按下的按钮不合并
     * [SyncTrigger.MANUAL] 永远自己走一趟（等锁），这是"当下意图"该有的语义；
     * 代价最多是一次零请求的哈希比较（内容没变时）。
     *
     * ## 内容一定不会漏
     * 调用方**先写盘、后触发**，所以一个请求序号被登记时，它的内容已经在盘上；
     * 领跑者在认领之后才读内容，读到的必然不旧于任何一个被它覆盖的请求。
     */
    @VisibleForTesting
    internal suspend fun performSync(
        context: Context,
        trigger: SyncTrigger,
        progress: Boolean,
        onWindowPhase: ((String?) -> Unit)? = null
    ): SyncStatus {
        val mine = requestSeq.incrementAndGet()
        // v7.9：进锁前后各记一条日志。故障复发时「有『同步请求』却迟迟没有『获得同步锁』」
        // 就是「上一次同步卡住、一直占着锁」的决定性证据（用户只需要把 logcat 截下来）。
        Log.i(TAG, "同步请求：trigger=$trigger（#$mine）")
        return mutex.withLock {
            if (trigger != SyncTrigger.MANUAL && claimedSeq >= mine) {
                coalescedRequests++
                Log.i(
                    TAG,
                    "并入正在执行的那一趟同步：请求 #$mine 已被第 #$claimedSeq 趟覆盖，不重复上传"
                )
                return@withLock lastResult
            }
            claimedSeq = requestSeq.get()
            Log.i(TAG, "获得同步锁：trigger=$trigger（认领 #$claimedSeq）")
            val result = try {
                doSync(context.applicationContext, trigger, progress, onWindowPhase)
            } catch (e: CancellationException) {
                // 领跑者被取消（例如前台服务被销毁）时，下面的赋值不会执行 —— 若不在这里补一个
                // 终态，并入者会拿到**上一次的旧结果**（可能是一次 Success），于是兜底任务会
                // 误以为内容已经传完而结束。明确落一个"被取消"的失败终态（IO 属暂时性，会重试）。
                lastResult = SyncStatus.Failed(
                    System.currentTimeMillis(), WebDavError.IO, "同步被取消"
                )
                throw e
            }
            lastResult = result
            result
        }
    }

    // ------------------------------------------------------------------ 一次同步

    private suspend fun doSync(
        context: Context,
        trigger: SyncTrigger,
        progress: Boolean,
        onWindowPhase: ((String?) -> Unit)?
    ): SyncStatus {
        if (!SyncSettings.enabled(context)) return SyncStatus.Skipped(SkipReason.NOT_ENABLED)

        val config = SyncSettings.config(context) ?: run {
            SyncSettings.recordFailure(context, WebDavError.NOT_CONFIGURED)
            return SyncStatus.Failed(
                System.currentTimeMillis(), WebDavError.NOT_CONFIGURED, "同步设置不完整"
            )
        }

        val startedAt = System.currentTimeMillis()
        _status.value = SyncStatus.Running(trigger, startedAt)
        Log.i(TAG, "开始同步：trigger=$trigger")
        // 进程内路径没有前台服务，通知得自己发
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
            val outcome = attemptUploadWithWindowRetry(
                context = context,
                client = created,
                config = config,
                onWindowPhase = onWindowPhase
            )
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
            // v8.1.0：既然以失败告终，这一次至少**尝试过**访问云端（握手 / 超时 / 4xx-5xx）——
            // 记下锚点。它是诊断数据，不参与任何判断；唯一会略微高估的场合是"纯本地失败"
            // （例如读盘或构造客户端就抛了），而那种失败远比网络失败罕见。
            SyncSettings.setLastServerContactAt(context, System.currentTimeMillis())
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

    // ------------------------------------------------------------------ 前台窗口内的快速重试

    /**
     * v8.0.3：一次上传 + **窗口内快速重试**。
     *
     * 退出瞬间的网络抖动（刚切网 / 刚息屏 / DNS 抖动）会以[暂时性失败][isTransient]的形式
     * 立刻返回；旧行为是当场放弃、交给系统兜底任务（当时 65 秒后，v8.1.0 起 30 秒），
     * 用户看到的是"没传上去"。
     * 现在只要失败得足够早，就在**已经存在的**前台窗口里退避重试最多
     * [SyncWindowRetry.MAX_RETRIES] 次 —— 通知本来就亮着，窗口长度由工作决定，
     * 成功则用户直接看到「已上传到云端」。
     *
     * 与旧行为的其余部分**完全一致**：彻底失败时依旧把异常抛给 [doSync] 的 catch
     * （统一落盘 `lastError` + 弹一次失败 Toast + 终态 Failed），
     * 而兜底任务早在保存时就排好了，窗口里没救回来也不会丢。
     *
     * [onWindowPhase] 为 null（系统兜底任务路径）时直接单次尝试：那条路径没有前台服务、
     * 不显示任何通知，也有自己的系统退避与预算，不该在这里放大请求次数。
     */
    private suspend fun attemptUploadWithWindowRetry(
        context: Context,
        client: WebDavClient,
        config: SyncConfig,
        onWindowPhase: ((String?) -> Unit)?
    ): SyncStatus {
        if (onWindowPhase == null) return uploadOnce(context, client, config)

        val windowStartedAt = elapsedClock()
        var retries = 0
        while (true) {
            val attemptStartedAt = elapsedClock()
            try {
                val outcome = uploadOnce(context, client, config)
                // 重试成功后把通知文案恢复成默认的「正在上传最新内容…」：
                // 上传其实已经成功，不该让最后 1.5 秒的最短可见期显示"正在重试"
                if (retries > 0) onWindowPhase.invoke(null)
                return outcome
            } catch (e: CancellationException) {
                // 协程被取消（服务销毁）不是"失败"：必须原样上抛，由 doSync 记终态
                throw e
            } catch (e: Throwable) {
                val (error, detail) = classify(e)
                val elapsed = elapsedClock() - windowStartedAt
                if (!SyncWindowRetry.shouldRetry(retries, error, elapsed)) throw e
                retries++
                Log.i(
                    TAG,
                    "窗口内重试：第 $retries/${SyncWindowRetry.MAX_RETRIES} 次" +
                        "（上次失败：$error ${sanitize(detail, config.password).take(120)}，" +
                        "本次尝试 ${elapsedClock() - attemptStartedAt}ms，累计 ${elapsed}ms）"
                )
                onWindowPhase.invoke(
                    SyncNotifier.retryText(context, retries, SyncWindowRetry.MAX_RETRIES)
                )
                delay(SyncWindowRetry.delayBeforeNext(retries - 1))
            }
        }
    }

    // ------------------------------------------------------------------ 单向覆盖上传

    private suspend fun uploadOnce(context: Context, client: WebDavClient, config: SyncConfig): SyncStatus {
        val localText = contentReader()
        val bytes = localText.toByteArray(Charsets.UTF_8)
        val localHash = SyncEngine.sha256Hex(bytes)

        if (!SyncEngine.hasLocalChanges(localHash, SyncSettings.lastUploadedHash(context))) {
            // 本地自上次成功上传后没有任何改动：一个请求都不发（省流量、省服务器配额）
            // D3：显式记 uploaded=false —— 否则设置页会把这次"什么都没做"显示成"上次同步成功"
            // v8.1.0：**刻意不更新** lastServerContactAt —— 这一趟根本没碰云端，
            // 而"上次访问云端"这一行必须能诚实地反映"最后一次真的发过请求是什么时候"。
            SyncSettings.recordSuccess(
                context,
                localHash,
                SyncSettings.lastUploadedTs(context),
                uploaded = false
            )
            return SyncStatus.Success(System.currentTimeMillis(), uploaded = false)
        }

        // 每次上传都写一个新文件：文件名 = 前缀 + unix 毫秒时间戳 + 扩展名
        val timestampSec = SyncEngine.nextUploadTimestamp(
            nowSec = System.currentTimeMillis(),
            lastUploadedSec = SyncSettings.lastUploadedTs(context)
        )
        // v8.1.0：从这一刻起"真的要去碰服务器了"。这个锚点不参与任何跳过判断，
        // 它只是给排查用的一个事实：上一次真的发请求是什么时候。
        SyncSettings.setLastServerContactAt(context, System.currentTimeMillis())
        putNewFile(client, config, bytes, timestampSec)
        SyncSettings.recordSuccess(context, localHash, timestampSec, uploaded = true)
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
