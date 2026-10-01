package moe.hellowidget

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * JVM 单测用的极简 HTTP/1.1 桩服务器。
 *
 * 存在的意义：`HttpWebDavClient` 是手写的 Socket 实现，因此「PROPFIND/MKCOL/MOVE/COPY
 * 这些自定义方法到底发出去了什么」可以在纯 JVM 上被**真实地**观测到 ——
 * 断言的是桩服务器收到的原始请求行与请求头，而不是我们自己的分支逻辑。
 *
 * 行为约定（与客户端一致）：一个连接只处理一个请求，响应后立即关闭
 * （客户端固定发送 `Connection: close`）。
 *
 * 传入 [sslContext] 即可变成 HTTPS 桩服务器（用于验证自签名证书与指纹固定）。
 */
class StubHttpServer(
    sslContext: SSLContext? = null,
    private val responder: (Request) -> Response
) : Closeable {

    data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
        fun bodyText(): String = String(body, Charsets.UTF_8)
    }

    data class Response(
        val status: Int,
        val reason: String = "OK",
        val headers: List<Pair<String, String>> = emptyList(),
        val body: ByteArray = ByteArray(0),
        /** 用分块传输编码发送正文（验证客户端的分块解码） */
        val chunked: Boolean = false,
        /** 不发 Content-Length：正文靠 EOF 定界（验证客户端的降级路径） */
        val omitContentLength: Boolean = false
    )

    private val serverSocket: ServerSocket = if (sslContext != null) {
        sslContext.serverSocketFactory.createServerSocket(0)
    } else {
        ServerSocket(0)
    }

    private val scheme: String = if (sslContext != null) "https" else "http"

    /** 按到达顺序记录的所有请求 */
    val requests: MutableList<Request> = CopyOnWriteArrayList()

    val port: Int get() = serverSocket.localPort

    private val worker = thread(isDaemon = true, name = "stub-http-server") { acceptLoop() }

    fun targets(): List<String> = requests.map { "${it.method} ${it.target}" }

    fun baseUrl(path: String = "/dav/"): String = "$scheme://127.0.0.1:$port$path"

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                return
            }
            try {
                handle(socket)
            } catch (e: Exception) {
                // 连接被客户端提前关掉是正常情况（例如 TOO_LARGE 直接放弃读取）
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        // TLS 握手：客户端拒绝证书时会在这里抛 SSLException，由 acceptLoop 兜住
        if (socket is SSLSocket) socket.startHandshake()
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        val method = parts.getOrElse(0) { "GET" }
        val target = parts.getOrElse(1) { "/" }

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) readExactly(input, length) else ByteArray(0)

        val request = Request(method, target, headers, body)
        requests.add(request)

        val response = responder(request)
        writeResponse(output, method, response)
        output.flush()
    }

    private fun writeResponse(output: BufferedOutputStream, method: String, response: Response) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        for ((name, value) in response.headers) {
            head.append(name).append(": ").append(value).append("\r\n")
        }
        when {
            response.chunked -> head.append("Transfer-Encoding: chunked\r\n")
            // HEAD 也要带上实体长度（这正是 HEAD 的语义），但正文不发送
            !response.omitContentLength ->
                head.append("Content-Length: ").append(response.body.size).append("\r\n")
            else -> Unit
        }
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))

        if (method == "HEAD") return
        if (response.chunked) {
            val half = response.body.size / 2
            writeChunk(output, response.body.copyOfRange(0, half))
            writeChunk(output, response.body.copyOfRange(half, response.body.size))
            output.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        } else {
            output.write(response.body)
        }
    }

    private fun writeChunk(output: BufferedOutputStream, chunk: ByteArray) {
        if (chunk.isEmpty()) return
        output.write("${chunk.size.toString(16)}\r\n".toByteArray(Charsets.ISO_8859_1))
        output.write(chunk)
        output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
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

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) buffer else buffer.copyOf(read)
    }

    override fun close() {
        runCatching { serverSocket.close() }
        worker.interrupt()
    }
}
