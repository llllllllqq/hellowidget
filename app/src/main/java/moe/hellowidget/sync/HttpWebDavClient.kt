package moe.hellowidget.sync

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 零依赖的 WebDAV 客户端：直接基于 `Socket` / `SSLSocket` 手写 HTTP/1.1。
 *
 * ## 为什么不用 HttpURLConnection
 * `HttpURLConnection.setRequestMethod()` 在 JDK 里走的是**白名单**（GET/POST/HEAD/OPTIONS/
 * PUT/DELETE/TRACE），自定义方法会抛 `ProtocolException`；Android 的实现虽然宽一些，
 * 但官方文档写的仍是同一份白名单。WebDAV 依赖的 `PROPFIND` / `MKCOL` / `MOVE` / `COPY`
 * 全部在白名单之外 —— 把功能压在「Android 具体版本恰好没做这个校验」上不可接受。
 *
 * 手写之后还有两个附带好处：
 *  1. 同一份代码在 JVM 单测（打本机 ServerSocket 桩）与模拟器上**走完全相同的路径**，
 *     单测因此能真正验证「MKCOL/MOVE 请求长什么样」，而不只是验证我们的分支逻辑；
 *  2. 可以强制 `Connection: close` 且**绝不自动跟随跳转** —— 后者是 Basic 凭据泄漏的
 *     经典途径（302 到第三方域名会把 Authorization 一起送出去）。
 *
 * ## 取舍
 * 每个请求新建一条 TCP/TLS 连接（不复用连接池）。一次同步只有 3~8 个请求，
 * 换来的是没有连接状态、没有保活线程，与「同步结束就停、不留后台」的产品要求一致。
 */
class HttpWebDavClient(
    private val config: SyncConfig,
    /** 证书不受信任时的回调：把指纹交给上层保存为「待确认」，本次仍然失败 */
    private val onUntrustedCertificate: (String) -> Unit = {},
    private val userAgent: String = "HelloWidget"
) : WebDavClient {

    private class Response(
        val status: Int,
        val reason: String,
        val headers: Map<String, List<String>>,
        val body: ByteArray
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]?.firstOrNull()
    }

    private val sslFactory: SSLSocketFactory by lazy { buildSslFactory() }

    // ---------------------------------------------------------------- 对外方法

    override fun stat(url: String): RemoteFile? {
        val head = execute("HEAD", url)
        when {
            head.status == 404 || head.status == 410 -> return null
            isRedirect(head.status) -> throw httpError(head)
            head.status in 200..299 -> {
                val etag = head.header("ETag")
                val mtime = HttpDates.parse(head.header("Last-Modified"))
                val size = head.header("Content-Length")?.toLongOrNull() ?: -1L
                // 状态信息齐全就不用再发一次 PROPFIND（少一个请求，服务器限流也更友好）
                if (etag != null || mtime > 0 || size >= 0) return RemoteFile(etag, mtime, size)
            }
            head.status != 405 && head.status != 501 -> throw httpError(head)
        }
        return propfindStat(url)
    }

    override fun get(url: String, maxBytes: Int): ByteArray? {
        val response = execute("GET", url, maxBody = maxBytes)
        return when {
            response.status == 404 || response.status == 410 -> null
            response.status in 200..299 -> response.body
            else -> throw httpError(response)
        }
    }

    /**
     * 原子上传。步骤：
     *  1. 无条件 PUT 到 `<url>.uploading`（临时文件固定命名，不会无限堆积）
     *  2. 覆盖正式文件前**二次确认**云端状态未变（把竞态窗口从「整个上传时长」缩到毫秒级）
     *  3. MOVE 覆盖正式文件（创建场景用 `Overwrite: F`，目标已被并发创建时得到 412）
     *
     * MOVE 的 `If-Match` 按 RFC 4918 校验的是请求 URI（也就是我们的临时文件），表达不了
     * 「目标必须还是我看到的那个版本」，所以第 2 步的二次 stat 才是真正的守护。
     * 服务器不支持 MOVE（405/501）时降级为直接 PUT：放弃原子性，但保留前置条件语义。
     */
    override fun putAtomic(
        url: String,
        body: ByteArray,
        ifNoneMatchStar: Boolean,
        ifMatch: String?,
        ifUnmodifiedSinceMs: Long?
    ): RemoteFile? {
        val tempUrl = url + TEMP_SUFFIX
        // 1) 写临时文件。临时文件名固定，上一次失败残留的临时文件会被这次 PUT 直接覆盖，
        //    因此不需要先在正常路径上多发一个 DELETE（那会白白消耗服务器的请求配额）
        putDirect(tempUrl, body, ifNoneMatchStar = false, ifMatch = null, ifUnmodifiedSinceMs = null)

        var moved = false
        try {
            // 2) 覆盖前二次确认
            if (!ifNoneMatchStar) {
                val current = stat(url)
                if (current != null) {
                    if (ifMatch != null) {
                        if (current.etag != null && current.etag != ifMatch) {
                            throw WebDavException(
                                WebDavError.PRECONDITION_FAILED, 412, "云端已被修改（ETag 不再匹配）"
                            )
                        }
                    } else if (ifUnmodifiedSinceMs != null && ifUnmodifiedSinceMs > 0 &&
                        current.lastModifiedMs > ifUnmodifiedSinceMs
                    ) {
                        throw WebDavException(
                            WebDavError.PRECONDITION_FAILED, 412, "云端已被修改（修改时间已更新）"
                        )
                    }
                }
            }
            // 3) 原子换名
            move(tempUrl, url, overwrite = !ifNoneMatchStar)
            moved = true
        } catch (e: WebDavException) {
            if (e.error == WebDavError.NOT_SUPPORTED) {
                // 服务器不支持 MOVE：退化为直接 PUT（临时文件在 finally 里清掉）
                Log.w(TAG, "服务器不支持 MOVE，降级为直接 PUT")
                return putDirect(url, body, ifNoneMatchStar, ifMatch, ifUnmodifiedSinceMs)
            }
            throw e
        } finally {
            if (!moved) runCatching { delete(tempUrl) }
        }

        return runCatching { stat(url) }.getOrNull() ?: RemoteFile(null, -1L, body.size.toLong())
    }

    override fun copy(sourceUrl: String, destUrl: String, overwrite: Boolean) {
        val response = execute(
            "COPY", sourceUrl,
            extraHeaders = listOf(
                "Destination" to destUrl,
                "Overwrite" to if (overwrite) "T" else "F"
            )
        )
        if (response.status in 200..299) return
        throw httpError(response)
    }

    override fun mkcol(url: String) {
        val response = execute("MKCOL", url)
        when {
            response.status in 200..299 -> return
            // 已存在（RFC 4918 要求返回 405）或服务器根本不支持 MKCOL —— 都交给后续 PUT 决定
            response.status == 405 || response.status == 301 -> return
            else -> throw httpError(response)
        }
    }

    override fun delete(url: String) {
        val response = execute("DELETE", url)
        if (response.status in 200..299 || response.status == 404 || response.status == 410) return
        throw httpError(response)
    }

    /** 无连接池、无长连接，close 是空实现（保留接口是为了将来需要时的兼容） */
    override fun close() = Unit

    // ---------------------------------------------------------------- HTTP 细节

    private class UntrustedCertificateException(val fingerprint: String) :
        CertificateException("证书不受信任（指纹 $fingerprint）")

    private fun execute(
        method: String,
        url: String,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        maxBody: Int = 0
    ): Response {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw WebDavException(WebDavError.BAD_RESPONSE, detail = "URL 无法解析：$url", cause = e)
        }
        val https = "https".equals(uri.scheme, ignoreCase = true)
        val rawHost = uri.host ?: throw WebDavException(WebDavError.BAD_RESPONSE, detail = "URL 缺少主机名")
        val host = rawHost.removePrefix("[").removeSuffix("]")
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        val target = buildString {
            append(uri.rawPath ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        var socket: Socket? = null
        try {
            socket = if (https) openTlsSocket(host, port) else openPlainSocket(host, port)
            socket.soTimeout = READ_TIMEOUT_MS
            val output = BufferedOutputStream(socket.getOutputStream())
            val input = BufferedInputStream(socket.getInputStream())

            val head = StringBuilder(256)
            head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(hostHeader(host, port)).append("\r\n")
            head.append("User-Agent: ").append(userAgent).append("\r\n")
            head.append("Accept: */*\r\n")
            // 明确要求短连接：响应体以 EOF 定界时不会因为对端不关连接而卡住
            head.append("Connection: close\r\n")
            if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
                val token = Base64.encodeToString(
                    "${config.username}:${config.password}".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                head.append("Authorization: Basic ").append(token).append("\r\n")
            }
            for ((name, value) in extraHeaders) {
                if (value.isNotEmpty()) head.append(name).append(": ").append(value).append("\r\n")
            }
            if (body != null) {
                head.append("Content-Length: ").append(body.size).append("\r\n")
            } else if (method in METHODS_WITH_EMPTY_BODY) {
                head.append("Content-Length: 0\r\n")
            }
            head.append("\r\n")
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null && body.isNotEmpty()) output.write(body)
            output.flush()

            val statusLine = readLine(input)
                ?: throw WebDavException(WebDavError.BAD_RESPONSE, detail = "服务器没有返回状态行")
            val parts = statusLine.split(' ', limit = 3)
            val status = parts.getOrNull(1)?.toIntOrNull()
                ?: throw WebDavException(WebDavError.BAD_RESPONSE, detail = "无法解析状态行：$statusLine")
            val reason = parts.getOrNull(2).orEmpty()

            val headers = LinkedHashMap<String, MutableList<String>>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim().lowercase(Locale.US)
                headers.getOrPut(name) { mutableListOf() }.add(line.substring(idx + 1).trim())
            }

            val responseBody = if (maxBody > 0) readBody(input, headers, maxBody) else ByteArray(0)
            return Response(status, reason, headers, responseBody)
        } catch (e: WebDavException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw WebDavException(WebDavError.TIMEOUT, detail = "连接或读取超时", cause = e)
        } catch (e: UnknownHostException) {
            throw WebDavException(WebDavError.NETWORK, detail = "无法解析主机名", cause = e)
        } catch (e: IOException) {
            throw WebDavException(WebDavError.IO, detail = e.message ?: e.javaClass.simpleName, cause = e)
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun openPlainSocket(host: String, port: Int): Socket =
        Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

    private fun openTlsSocket(host: String, port: Int): Socket {
        val socket = sslFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            socket.startHandshake()
            val session = socket.session
            val pinned = config.tlsPinSha256 != null && pinMatches(session)
            if (!pinned && !HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                throw WebDavException(
                    WebDavError.TLS_UNTRUSTED,
                    detail = "证书与主机名不匹配：$host（如需继续，可信任同步页显示的证书指纹）"
                )
            }
            return socket
        } catch (e: WebDavException) {
            runCatching { socket.close() }
            throw e
        } catch (e: SSLException) {
            runCatching { socket.close() }
            val fingerprint = findUntrustedFingerprint(e)
            if (fingerprint != null) {
                throw WebDavException(WebDavError.TLS_UNTRUSTED, detail = fingerprint, cause = e)
            }
            throw WebDavException(WebDavError.TLS, detail = e.message ?: "TLS 握手失败", cause = e)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }

    /** 从异常链里找出「证书不受信任」的标记，拿到可展示的指纹 */
    private fun findUntrustedFingerprint(e: Throwable): String? {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 16) {
            if (current is UntrustedCertificateException) return current.fingerprint
            current = current.cause
            depth++
        }
        return null
    }

    private fun pinMatches(session: javax.net.ssl.SSLSession): Boolean {
        val pin = config.tlsPinSha256 ?: return false
        val certificate = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        } ?: return false
        return SyncEngine.sha256Hex(certificate.publicKey.encoded).equals(pin, ignoreCase = true)
    }

    private fun buildSslFactory(): SSLSocketFactory {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val defaultManager = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw WebDavException(WebDavError.TLS, detail = "系统没有可用的 X509TrustManager")
        val context = SSLContext.getInstance("TLS")
        context.init(
            null,
            arrayOf<TrustManager>(
                PinningTrustManager(config.tlsPinSha256, defaultManager, onUntrustedCertificate)
            ),
            null
        )
        return context.socketFactory
    }

    private fun hostHeader(host: String, port: Int): String {
        val bracketed = if (host.contains(':')) "[$host]" else host
        val defaultPort = if (config.isCleartext) 80 else 443
        return if (port == defaultPort) bracketed else "$bracketed:$port"
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            b = input.read()
        }
        return String(buffer.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readBody(
        input: InputStream,
        headers: Map<String, List<String>>,
        maxBytes: Int
    ): ByteArray {
        val transferEncoding = headers["transfer-encoding"]?.joinToString(",")?.lowercase(Locale.US)
        if (transferEncoding != null && transferEncoding.contains("chunked")) {
            return readChunked(input, maxBytes)
        }
        val declaredLength = headers["content-length"]?.firstOrNull()?.trim()?.toLongOrNull()
        if (declaredLength != null) {
            if (declaredLength > maxBytes) {
                throw WebDavException(
                    WebDavError.TOO_LARGE,
                    detail = "云端文件 $declaredLength 字节，超过上限 $maxBytes 字节"
                )
            }
            return readExactly(input, declaredLength.toInt())
        }
        // 没有长度也没有分块：靠 Connection: close 的 EOF 定界
        return readToEnd(input, maxBytes)
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        if (length == 0) return ByteArray(0)
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) {
                throw WebDavException(
                    WebDavError.BAD_RESPONSE,
                    detail = "响应正文提前结束（期望 $length 字节，实际 $read 字节）"
                )
            }
            read += n
        }
        return buffer
    }

    private fun readToEnd(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArrayOutputStream(8 * 1024)
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = try {
                input.read(chunk)
            } catch (e: SocketTimeoutException) {
                // 服务器既不给长度也不关连接：已经读到的部分仍然可用
                if (buffer.size() > 0) break else throw e
            }
            if (n < 0) break
            buffer.write(chunk, 0, n)
            if (buffer.size() > maxBytes) {
                throw WebDavException(
                    WebDavError.TOO_LARGE,
                    detail = "云端文件超过上限 $maxBytes 字节"
                )
            }
        }
        return buffer.toByteArray()
    }

    private fun readChunked(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArrayOutputStream(8 * 1024)
        while (true) {
            val sizeLine = readLine(input)
                ?: throw WebDavException(WebDavError.BAD_RESPONSE, detail = "分块响应提前结束")
            val sizeText = sizeLine.substringBefore(';').trim()
            val size = sizeText.toIntOrNull(16)
                ?: throw WebDavException(WebDavError.BAD_RESPONSE, detail = "无法解析分块长度：$sizeLine")
            if (size == 0) {
                // 读掉 trailer（可能有多行），直到空行
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            buffer.write(readExactly(input, size))
            if (buffer.size() > maxBytes) {
                throw WebDavException(
                    WebDavError.TOO_LARGE,
                    detail = "云端文件超过上限 $maxBytes 字节"
                )
            }
            readLine(input) // 分块数据后的 CRLF
        }
        return buffer.toByteArray()
    }

    private fun putDirect(
        url: String,
        body: ByteArray,
        ifNoneMatchStar: Boolean,
        ifMatch: String?,
        ifUnmodifiedSinceMs: Long?
    ): RemoteFile? {
        val extra = mutableListOf("Content-Type" to "text/plain; charset=utf-8")
        if (ifNoneMatchStar) extra += "If-None-Match" to "*"
        if (ifMatch != null) extra += "If-Match" to ifMatch
        if (ifMatch == null && ifUnmodifiedSinceMs != null && ifUnmodifiedSinceMs > 0) {
            extra += "If-Unmodified-Since" to HttpDates.format(ifUnmodifiedSinceMs)
        }
        val response = execute("PUT", url, extra, body)
        if (response.status in 200..299) {
            return RemoteFile(response.header("ETag"), HttpDates.parse(response.header("Last-Modified")), body.size.toLong())
        }
        throw httpError(response)
    }

    private fun move(sourceUrl: String, destUrl: String, overwrite: Boolean) {
        val response = execute(
            "MOVE", sourceUrl,
            extraHeaders = listOf(
                "Destination" to destUrl,
                "Overwrite" to if (overwrite) "T" else "F"
            )
        )
        if (response.status in 200..299) return
        throw httpError(response)
    }

    /** HEAD 拿不到元数据时的退路：PROPFIND Depth: 0，只解析我们关心的三个属性 */
    private fun propfindStat(url: String): RemoteFile? {
        val response = execute(
            "PROPFIND", url,
            extraHeaders = listOf(
                "Depth" to "0",
                "Content-Type" to "application/xml; charset=utf-8"
            ),
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
            maxBody = SyncEngine.MAX_PROPFIND_BYTES
        )
        when {
            response.status == 404 || response.status == 410 -> return null
            response.status == 207 || response.status in 200..299 -> {
                val xml = String(response.body, Charsets.UTF_8)
                val etag = tagText(xml, "getetag")
                val mtime = HttpDates.parse(tagText(xml, "getlastmodified"))
                val size = tagText(xml, "getcontentlength")?.toLongOrNull() ?: -1L
                if (etag == null && mtime <= 0 && size < 0) {
                    throw WebDavException(
                        WebDavError.BAD_RESPONSE,
                        detail = "服务器既不支持 HEAD 元数据也不返回 WebDAV 状态属性，无法判断云端是否被修改"
                    )
                }
                return RemoteFile(etag, mtime, size)
            }
            else -> throw httpError(response)
        }
    }

    private fun tagText(xml: String, tag: String): String? {
        val match = Regex("<[^>]*$tag[^>]*>([^<]*)<", RegexOption.IGNORE_CASE).find(xml) ?: return null
        return unescapeXml(match.groupValues[1].trim()).ifEmpty { null }
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

    private fun httpError(response: Response): WebDavException {
        val error = when {
            isRedirect(response.status) -> WebDavError.REDIRECT
            response.status == 401 -> WebDavError.UNAUTHORIZED
            response.status == 403 -> WebDavError.FORBIDDEN
            response.status == 404 || response.status == 410 -> WebDavError.NOT_FOUND
            response.status == 405 || response.status == 501 -> WebDavError.NOT_SUPPORTED
            response.status == 409 -> WebDavError.PARENT_NOT_FOUND
            response.status == 412 -> WebDavError.PRECONDITION_FAILED
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
        return WebDavException(error, response.status, "HTTP ${response.status} ${response.reason}$extra")
    }

    private class PinningTrustManager(
        private val pin: String?,
        private val delegate: X509TrustManager,
        private val onUntrusted: (String) -> Unit
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            delegate.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("服务器未提供证书")
            val fingerprint = SyncEngine.sha256Hex(leaf.publicKey.encoded)
            // 用户明确信任过的指纹：直接放行（自签名 NAS 的证书通常主机名也对不上）
            if (pin != null && fingerprint.equals(pin, ignoreCase = true)) return
            try {
                delegate.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                // 只把指纹交给上层，不在这里信任 —— 必须由用户在看到指纹后确认
                onUntrusted(fingerprint)
                throw UntrustedCertificateException(fingerprint)
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    companion object {
        private const val TAG = "HttpWebDavClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val TEMP_SUFFIX = ".uploading"
        private val METHODS_WITH_EMPTY_BODY = setOf("PUT", "MKCOL", "MOVE", "COPY", "PROPFIND")
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<D:propfind xmlns:D="DAV:"><D:prop><D:getetag/><D:getlastmodified/>""" +
                """<D:getcontentlength/></D:prop></D:propfind>"""
    }
}
