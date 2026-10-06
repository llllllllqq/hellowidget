package moe.hellowidget.sync

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * WebDAV 客户端（OkHttp 5.4.0）：单向上传需要的 `PUT` 与 `MKCOL`。
 *
 * ## 为什么从手写 Socket 换成 OkHttp
 * 旧实现自己拼 HTTP/1.1，`output.write()` / `flush()` **没有任何写超时**，`close()` 还是空实现：
 * 一条半开（half-open）的 TCP 连接能让一次上传卡住好几分钟，而同步的全局锁在此期间一直被占着 ——
 * 后续同步只会静默排队，用户看到的现象就是「再也传不上去」。
 * OkHttp 给每个阶段都设了上限：连接 15s、写 30s、读 30s，再用 `callTimeout(60s)` 兜住
 * 「DNS 解析 + 连接 + 写正文 + 服务器处理 + 读响应」整条链路，任何一步挂住都会在确定时限内失败；
 * `close()` 也能真正取消进行中的请求（见 [close]）。
 *
 * 版本从 4.12.0 升到 5.4.0 的前提（Kotlin ≥ 2.1）由 AGP 9 的内置 Kotlin 满足；
 * 为什么不是 5.5.0（它要求 compileSdk 37）见 `app/build.gradle.kts` 的依赖注释。
 *
 * ## 语义与安全策略与旧实现一致
 *  - 只有两个动作：`PUT`（强制覆盖，不带任何 `If-Match` / `If-None-Match` / `If-*-Since`）与 `MKCOL`；
 *  - **绝不跟随跳转**：302 到第三方域名会把 Basic 凭据一起送出去，这是凭据泄漏的经典途径；
 *  - Basic 凭据按 UTF-8 编码（RFC 7617 允许，且旧实现就是这么做的）；
 *  - TOFU 证书固定（见 [TlsPinning]）：系统信任锚永远有效，用户确认过的指纹只是**额外**信任，
 *    既不受系统信任又没被固定的证书必须回调上层记下指纹、且本次仍然失败。
 */
class OkHttpWebDavClient(
    private val config: SyncConfig,
    /** 证书不受信任时的回调：把指纹交给上层保存为「待确认」，本次仍然失败 */
    private val onUntrustedCertificate: (String) -> Unit = {},
    private val userAgent: String = DEFAULT_USER_AGENT,
    // 以下四个只给测试覆盖用，生产走默认值：
    // 单测要能在几百毫秒内证明「服务器不响应时调用会被上限打断」，而不是等满 60 秒
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS
) : WebDavClient {

    /** 只把诊断需要的东西从 `okhttp3.Response` 里取出来，避免把响应对象泄漏到调用方 */
    private class DavResponse(
        val status: Int,
        val reason: String,
        private val headers: Headers,
        val body: ByteArray
    ) {
        /** 与旧实现一致：同名头取**第一个**（`Headers.get` 取的是最后一个） */
        fun header(name: String): String? = headers.values(name).firstOrNull()
    }

    /**
     * 每个实例一个客户端：证书固定与「不受信任回调」都是按配置/按实例的，
     * 但底层连接池与 Dispatcher 与 [PROTOTYPE] 共享，不会每次同步都新建一套。
     */
    private val client: OkHttpClient by lazy { buildClient() }

    /** close() 要能取消进行中的请求，因此当前调用放在 `@Volatile` 字段里跨线程可见 */
    @Volatile
    private var currentCall: Call? = null

    // ---------------------------------------------------------------- 对外方法

    /** 单次 `PUT`，强制覆盖；没有任何前置条件，成功与否只看状态码 */
    override fun put(url: String, body: ByteArray) {
        val response = execute("PUT", url, body.toRequestBody(CONTENT_TYPE))
        if (response.status in 200..299) return
        throw httpError(response)
    }

    override fun mkcol(url: String) {
        val response = execute("MKCOL", url, body = null)
        when {
            response.status in 200..299 -> return
            // 已存在（RFC 4918 要求返回 405）或服务器根本不支持 MKCOL ——
            // 目录本来就在也没什么可做的，真正的成败由随后的 PUT 决定
            response.status == 405 || response.status == 301 -> return
            // 坚果云对「集合已存在」返回 409，而不是 RFC 4918 要求的 405。
            // 只有服务器**明确**说祖先集合缺失（AncestorsNotFound）时才算真错误。
            response.status == 409 && !ancestorsMissing(response) -> return
            else -> throw httpError(response)
        }
    }

    /**
     * 取消进行中的调用（旧的空实现是这次换 OkHttp 的核心动机之一）。
     * 调用方放弃一次同步时调用它，半开连接就不会再把全局同步锁一直占住。
     */
    override fun close() {
        currentCall?.cancel()
    }

    // ---------------------------------------------------------------- HTTP 细节

    private fun buildClient(): OkHttpClient {
        val tls = TlsPinning(config.tlsPinSha256, onUntrustedCertificate)
        return PROTOTYPE.newBuilder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            // 绝不跟随跳转：这是凭据泄漏防护（旧实现根本没有跳转逻辑）
            .followRedirects(false)
            .followSslRedirects(false)
            // 保留 OkHttp 默认的失败重连（true）：只在连接层面失败时换条连接重试，抗瞬时抖动。
            // 代价是 408（且无 Retry-After）会被自动重试一次 —— 对「一次性覆盖上传」无害。
            .retryOnConnectionFailure(true)
            // 只跑 HTTP/1.1：WebDAV 的 MKCOL 用不上 HTTP/2，固定协议版本也避免
            // ALPN 协商在只认 HTTP/1.1 的测试桩/老 NAS 上引入不确定性
            .protocols(listOf(Protocol.HTTP_1_1))
            // OkHttp 要求传入的 TrustManager 必须与 SocketFactory 是同一个实例
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            .hostnameVerifier(tls.hostnameVerifier)
            .build()
    }

    private fun buildRequest(method: String, url: String, body: RequestBody?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            // OkHttp 的 Credentials.basic(user, pass) 默认按 ISO-8859-1 编码，非 ASCII 凭据会被
            // 替换成 '?'；旧实现明确用 UTF-8，所以这里必须传字符集而不是用那个两参重载
            builder.header(
                "Authorization",
                Credentials.basic(config.username, config.password, Charsets.UTF_8)
            )
        }
        return builder.method(method, body).build()
    }

    private fun execute(method: String, url: String, body: RequestBody?): DavResponse {
        val request = try {
            buildRequest(method, url, body)
        } catch (e: IllegalArgumentException) {
            // 与旧实现一致：地址无法解析归到 BAD_RESPONSE，而不是把 IllegalArgumentException 漏给上层
            throw WebDavException(WebDavError.BAD_RESPONSE, detail = "URL 无法解析：$url", cause = e)
        }

        val call = client.newCall(request)
        currentCall = call
        try {
            // 响应必须关闭，否则连接不会归还连接池；`use` 也在异常路径上保证这一点
            return call.execute().use { buildResponse(it) }
        } catch (e: IOException) {
            throw ioError(e)
        } finally {
            currentCall = null
        }
    }

    private fun buildResponse(response: Response): DavResponse {
        val status = response.code
        // 4xx/5xx 的正文是最有用的诊断信息：坚果云在 409 正文里给出 AncestorsNotFound 之类的异常名。
        // 这里是**尽力而为**：读不到就只留状态码，绝不让诊断把自己变成另一个错误
        val body = if (status >= 400) {
            runCatching { errorBody(response, ERROR_BODY_LIMIT) }.getOrDefault(ByteArray(0))
        } else {
            ByteArray(0)
        }
        return DavResponse(status, response.message, response.headers, body)
    }

    /**
     * 读**错误响应**的正文（只用于诊断）：最多 [limit] 字节，永不因为正文更长而抛错。
     *
     * 只在能确定正文长度时才读：既没有 `Content-Length` 也不是分块时，正文只能靠 EOF 定界，
     * 对端不关连接就会卡满读超时 —— 旧实现同样放弃这种正文，诊断不值得把失败反馈拖慢 30 秒。
     */
    private fun errorBody(response: Response, limit: Int): ByteArray {
        val declaredLength = response.header("Content-Length")?.trim()?.toLongOrNull()
        val chunked = response.header("Transfer-Encoding")
            ?.contains("chunked", ignoreCase = true) == true
        if (declaredLength == null && !chunked) return ByteArray(0)
        val wanted = if (declaredLength != null) minOf(declaredLength, limit.toLong()) else limit.toLong()
        if (wanted <= 0L) return ByteArray(0)
        // peekBody 只取前 wanted 字节，剩下的直接放弃（连接本来就是一次性的）
        return response.peekBody(wanted).bytes()
    }

    /**
     * IOException → [WebDavError] 的映射（与旧实现同一张表）。
     * OkHttp 的 `callTimeout` 到点会抛 `InterruptedIOException("timeout")`，
     * `close()` 取消则抛 `IOException("Canceled")`，两者都落在下面这两条分支里。
     */
    private fun ioError(e: IOException): WebDavException = when (e) {
        is SocketTimeoutException -> WebDavException(WebDavError.TIMEOUT, detail = "连接或读取超时", cause = e)
        is InterruptedIOException -> WebDavException(WebDavError.TIMEOUT, detail = "连接或读取超时", cause = e)
        is UnknownHostException -> WebDavException(WebDavError.NETWORK, detail = "无法解析主机名", cause = e)
        is SSLException -> {
            val fingerprint = findUntrustedFingerprint(e)
            if (fingerprint != null) {
                // 上面的证书回调已经把指纹交出去了，这里只需要把「不受信任」这个分类带上去
                WebDavException(WebDavError.TLS_UNTRUSTED, detail = fingerprint, cause = e)
            } else {
                WebDavException(WebDavError.TLS, detail = e.message ?: "TLS 握手失败", cause = e)
            }
        }
        else -> WebDavException(WebDavError.IO, detail = e.message ?: e.javaClass.simpleName, cause = e)
    }

    /**
     * 服务器是否**明确**说了「祖先集合不存在」。
     *
     * 坚果云的 409 有两种含义，只能靠正文区分：目标已存在（`Conflict`）和父目录缺失
     * （`AncestorsNotFound`）。判断不出来就当作「不是祖先缺失」（更保守）。
     */
    private fun ancestorsMissing(response: DavResponse): Boolean =
        response.body.isNotEmpty() &&
            String(response.body, Charsets.UTF_8).contains(ANCESTORS_NOT_FOUND, ignoreCase = true)

    /**
     * 从错误正文里取出可读的诊断信息：优先异常名（`<D:exception>AncestorsNotFound</D:exception>`），
     * 否则给一段压成一行的正文摘要。用户把这句话发过来就能定位问题。
     */
    private fun serverMessage(response: DavResponse): String? {
        if (response.body.isEmpty()) return null
        val text = String(response.body, Charsets.UTF_8)
        val exception = EXCEPTION_TAG.find(text)?.groupValues?.get(1)?.trim()
        if (!exception.isNullOrEmpty()) return unescapeXml(exception).take(EXCERPT_LIMIT)
        return text.replace(Regex("\\s+"), " ").trim().take(EXCERPT_LIMIT).ifEmpty { null }
    }

    /** 注意：`&amp;` 必须最后替换，否则 `&amp;lt;` 会被错误地还原成 `<` */
    private fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun isRedirect(status: Int): Boolean =
        status == 301 || status == 302 || status == 303 || status == 307 || status == 308

    private fun httpError(response: DavResponse): WebDavException {
        val error = when {
            isRedirect(response.status) -> WebDavError.REDIRECT
            response.status == 401 -> WebDavError.UNAUTHORIZED
            response.status == 403 -> WebDavError.FORBIDDEN
            response.status == 404 || response.status == 410 -> WebDavError.NOT_FOUND
            response.status == 405 || response.status == 501 -> WebDavError.NOT_SUPPORTED
            response.status == 409 -> WebDavError.PARENT_NOT_FOUND
            response.status == 423 -> WebDavError.LOCKED
            response.status == 507 -> WebDavError.INSUFFICIENT_STORAGE
            response.status in 500..599 -> WebDavError.SERVER_ERROR
            else -> WebDavError.BAD_RESPONSE
        }
        val extra = when (error) {
            WebDavError.UNAUTHORIZED -> response.header("WWW-Authenticate")?.let { "（认证方式：$it）" } ?: ""
            WebDavError.REDIRECT -> "（服务器要求跳转，请直接填写最终地址）"
            else -> ""
        }
        // 服务器在正文里给的原因（若有）比裸状态码有用得多
        val server = serverMessage(response)?.let { "（服务器：$it）" } ?: ""
        return WebDavException(error, response.status, "HTTP ${response.status} ${response.reason}$extra$server")
    }

    companion object {
        /** 与旧实现相同的默认 User-Agent（OkHttp 自己的 UA 会暴露库名与版本，这里保持精简） */
        const val DEFAULT_USER_AGENT = "HelloWidget"

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 30_000L

        /** 整次调用的上限：DNS + 连接 + 写正文 + 服务器处理 + 读响应（本次改动的核心） */
        private const val CALL_TIMEOUT_MS = 60_000L

        /** 错误正文的读取上限（只是拿来写诊断信息，不需要完整正文） */
        private const val ERROR_BODY_LIMIT = 8 * 1024

        /** 附在错误详情里的正文摘要长度上限 */
        private const val EXCERPT_LIMIT = 120

        /** 坚果云在 409 正文里用来表示「父集合不存在」的异常名 */
        private const val ANCESTORS_NOT_FOUND = "AncestorsNotFound"

        private val EXCEPTION_TAG = Regex("<[^>]*exception[^>]*>([^<]*)<", RegexOption.IGNORE_CASE)

        private val CONTENT_TYPE: MediaType = "text/plain; charset=utf-8".toMediaType()

        /**
         * 共享原型客户端：OkHttp 官方保证 `newBuilder()` 复用同一个连接池与 Dispatcher，
         * 所以「每次同步新建一个 OkHttpWebDavClient」不会每次都泄漏一套连接池 + 调度线程。
         * 每个实例仍然需要自己的 Builder：证书固定与「不受信任回调」是按实例区分的。
         */
        private val PROTOTYPE = OkHttpClient()
    }
}
