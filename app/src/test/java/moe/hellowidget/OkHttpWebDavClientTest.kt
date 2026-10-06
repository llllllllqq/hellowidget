package moe.hellowidget

import android.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import moe.hellowidget.sync.OkHttpWebDavClient
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WebDAV 客户端的请求级测试。
 *
 * 这是本功能最重要的一层证据：断言的对象是**桩服务器收到的原始请求行、请求头与正文**，
 * 而不是我们自己的分支逻辑。需要 Robolectric 只是因为 Basic 认证的期望值用了
 * `android.util.Base64`。
 *
 * 客户端只剩两个动作（`PUT` 与 `MKCOL`），而且 `PUT` 必须是**无条件**的：
 * 产品语义是「本地是唯一真相，上传即覆盖」，任何 `If-*` 头都会破坏这一点。
 *
 * 另外两类用例对应换成 OkHttp 的直接动机：整次调用必须有上限（[callTimeout_boundsAServerThatNeverResponds]），
 * 以及 `close()` 必须能取消进行中的调用（[close_cancelsAnInFlightCall]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OkHttpWebDavClientTest {

    private fun clientFor(
        server: StubHttpServer,
        tlsPin: String? = null,
        onUntrusted: (String) -> Unit = {}
    ): OkHttpWebDavClient = OkHttpWebDavClient(
        config = SyncConfig(
            baseUrl = server.baseUrl(),
            fileName = "note.txt",
            username = "test",
            password = "test",
            tlsPinSha256 = tlsPin
        ),
        onUntrustedCertificate = onUntrusted,
        userAgent = "HelloWidgetTest"
    )

    private fun noteUrl(server: StubHttpServer) = server.baseUrl() + "note.txt"

    private fun assertThrowsDav(expected: WebDavError, block: () -> Unit): WebDavException {
        try {
            block()
        } catch (e: WebDavException) {
            assertEquals("HTTP 错误分类不符（detail=${e.detail}）", expected, e.error)
            return e
        }
        fail("期望抛出 WebDavException($expected)，但没有抛")
        throw IllegalStateException("unreachable")
    }

    // ------------------------------------------------------------ 上传（强制覆盖）

    @Test
    fun put_sendsTheBodyWithBasicAuthAndUserAgent() {
        StubHttpServer { StubHttpServer.Response(204, "No Content") }.use { server ->
            clientFor(server).put(noteUrl(server), "abc".toByteArray(Charsets.UTF_8))

            assertEquals(listOf("PUT /dav/note.txt"), server.targets())
            assertEquals("abc", server.requests[0].bodyText())
            assertEquals("text/plain; charset=utf-8", server.requests[0].header("content-type"))
            val expected = Base64.encodeToString("test:test".toByteArray(), Base64.NO_WRAP)
            assertEquals("Basic $expected", server.requests[0].header("authorization"))
            assertEquals("HelloWidgetTest", server.requests[0].header("user-agent"))
            assertEquals("127.0.0.1:${server.port}", server.requests[0].header("host"))
            assertEquals("3", server.requests[0].header("content-length"))
        }
    }

    @Test
    fun put_encodesBasicAuthCredentialsAsUtf8() {
        // 旧实现按 UTF-8 编码 "$user:$pass"；OkHttp 的 Credentials.basic(user, pass) 默认
        // ISO-8859-1，非 ASCII 字符会被替换成 '?'，因此必须用显式传字符集的重载
        StubHttpServer { StubHttpServer.Response(204, "No Content") }.use { server ->
            val config = SyncConfig(
                baseUrl = server.baseUrl(),
                fileName = "note.txt",
                username = "用户",
                password = "pässwörd"
            )
            OkHttpWebDavClient(config, userAgent = "HelloWidgetTest")
                .put(server.baseUrl() + "note.txt", "abc".toByteArray(Charsets.UTF_8))

            val expected = Base64.encodeToString(
                "用户:pässwörd".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            assertEquals("Basic $expected", server.requests[0].header("authorization"))
        }
    }

    @Test
    fun put_isUnconditional_forceOverwriteWithoutAnyPreconditions() {
        StubHttpServer { StubHttpServer.Response(204, "No Content") }.use { server ->
            clientFor(server).put(noteUrl(server), "强制覆盖".toByteArray(Charsets.UTF_8))

            // 单次请求、直写正式文件：没有临时文件、没有换名、没有任何前置条件
            assertEquals(listOf("PUT /dav/note.txt"), server.targets())
            assertNull("不得带 If-Match", server.requests[0].header("if-match"))
            assertNull("不得带 If-None-Match", server.requests[0].header("if-none-match"))
            assertNull("不得带 If-Unmodified-Since", server.requests[0].header("if-unmodified-since"))
            assertNull("不得带 If-Modified-Since", server.requests[0].header("if-modified-since"))
            assertNull("不得带 Destination（没有 MOVE）", server.requests[0].header("destination"))
        }
    }

    @Test
    fun historyFileUrl_isPercentEncodedInTheRequestTarget() {
        // 中文/空格文件名必须按路径段编码（空格是 %20 而不是 +），否则服务器找不到文件
        StubHttpServer { StubHttpServer.Response(201, "Created") }.use { server ->
            val config = SyncConfig(
                baseUrl = server.baseUrl(),
                fileName = "我的 笔记.txt",
                username = "test",
                password = "test"
            )
            OkHttpWebDavClient(config, userAgent = "HelloWidgetTest")
                .put(config.historyFileUrl(1_735_689_600L), "x".toByteArray(Charsets.UTF_8))
            assertEquals(
                "PUT /dav/%E6%88%91%E7%9A%84%20%E7%AC%94%E8%AE%B0" + "1735689600.txt",
                server.targets()[0]
            )
        }
    }

    @Test
    fun redirects_areNeverFollowed_soCredentialsCannotLeak() {
        StubHttpServer {
            StubHttpServer.Response(302, "Found", listOf("Location" to "http://evil.example.com/"))
        }.use { server ->
            assertThrowsDav(WebDavError.REDIRECT) {
                clientFor(server).put(noteUrl(server), "abc".toByteArray())
            }
            assertEquals("必须只发一次请求：绝不跟随跳转", 1, server.requests.size)
        }
    }

    // ------------------------------------------------------------ 建目录

    @Test
    fun mkcol_treatsAlreadyExistingAsSuccess() {
        StubHttpServer { request ->
            if (request.method == "MKCOL") StubHttpServer.Response(405, "Method Not Allowed")
            else StubHttpServer.Response(201)
        }.use { server ->
            clientFor(server).mkcol(server.baseUrl())
            assertEquals(listOf("MKCOL /dav/"), server.targets())
            // MKCOL 没有正文：不得出现 PUT 才有的 Content-Type
            assertNull("MKCOL 不得带 Content-Type", server.requests[0].header("content-type"))
        }
    }

    @Test
    fun mkcol_treatsNutstore409OnExistingCollectionAsSuccess() {
        // anx-reader#410：坚果云对「集合已存在」返回 409（RFC 4918 要求 405），
        // 客户端如果只认 405，就会在目录建好之后的每一次上传都失败
        val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:error xmlns:D=\"DAV:\">" +
            "<D:exception>Conflict</D:exception></D:error>").toByteArray(Charsets.UTF_8)
        StubHttpServer { StubHttpServer.Response(409, "Conflict", body = body) }.use { server ->
            clientFor(server).mkcol(server.baseUrl()) // 不应抛异常
            assertEquals(listOf("MKCOL /dav/"), server.targets())
        }
    }

    @Test
    fun mkcol_reportsMissingParentDirectory_whenServerSaysAncestorsNotFound() {
        // 409 的两种含义只能靠正文区分：AncestorsNotFound = 目录真的建不出来（配置错了），
        // 必须给出可操作的错误，而不是被当成「已存在」静默吞掉
        val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:error xmlns:D=\"DAV:\">" +
            "<D:exception>AncestorsNotFound</D:exception></D:error>").toByteArray(Charsets.UTF_8)
        StubHttpServer { StubHttpServer.Response(409, "Conflict", body = body) }.use { server ->
            val e = assertThrowsDav(WebDavError.PARENT_NOT_FOUND) { clientFor(server).mkcol(server.baseUrl()) }
            assertTrue("错误详情应带出服务器给的异常名：${e.detail}", e.detail.contains("AncestorsNotFound"))
        }
    }

    @Test
    fun mkcol_accepts301_becauseDirectoryAlreadyExistsUnsupported() {
        // 旧实现把 301 当作「目录已经存在 / 服务器不支持 MKCOL」直接放行；
        // 不跟随跳转是安全策略，但 301 在 MKCOL 上的语义必须保持不变
        StubHttpServer {
            StubHttpServer.Response(301, "Moved Permanently", listOf("Location" to "http://evil.example.com/"))
        }.use { server ->
            clientFor(server).mkcol(server.baseUrl()) // 不应抛异常
            assertEquals("必须只发一次请求", 1, server.requests.size)
        }
    }

    // ------------------------------------------------------------ 错误映射

    @Test
    fun httpStatuses_mapToTypedErrors() {
        val cases = linkedMapOf(
            401 to WebDavError.UNAUTHORIZED,
            403 to WebDavError.FORBIDDEN,
            404 to WebDavError.NOT_FOUND,
            405 to WebDavError.NOT_SUPPORTED,
            409 to WebDavError.PARENT_NOT_FOUND,
            423 to WebDavError.LOCKED,
            500 to WebDavError.SERVER_ERROR,
            507 to WebDavError.INSUFFICIENT_STORAGE
        )
        for ((status, expected) in cases) {
            StubHttpServer { request ->
                if (status == 401) {
                    StubHttpServer.Response(
                        401, "Unauthorized",
                        listOf("WWW-Authenticate" to "Basic realm=\"dav\"")
                    )
                } else {
                    StubHttpServer.Response(status, "status-$status")
                }
            }.use { server ->
                val e = assertThrowsDav(expected) {
                    clientFor(server).put(noteUrl(server), "abc".toByteArray(Charsets.UTF_8))
                }
                assertEquals(status, e.httpStatus)
                assertEquals("PUT /dav/note.txt", server.targets()[0])
            }
        }
    }

    @Test
    fun unauthorizedDetail_showsTheChallengeScheme() {
        StubHttpServer {
            StubHttpServer.Response(401, "Unauthorized", listOf("WWW-Authenticate" to "Digest realm=\"dav\""))
        }.use { server ->
            val e = assertThrowsDav(WebDavError.UNAUTHORIZED) {
                clientFor(server).put(noteUrl(server), "abc".toByteArray())
            }
            // 用户需要看到服务器要求的是 Digest（本应用只支持 Basic）而不是干瞪眼
            assertTrue("detail 应包含认证方式：${e.detail}", e.detail.contains("Digest"))
        }
    }

    @Test
    fun errorDetail_carriesTheServerExceptionName() {
        // 4xx 正文里的异常名是最有用的诊断信息（坚果云用它区分「已存在」与「父目录缺失」）
        val body = ("<?xml version=\"1.0\"?><D:error xmlns:D=\"DAV:\">" +
            "<D:exception>AncestorsNotFound</D:exception></D:error>").toByteArray(Charsets.UTF_8)
        StubHttpServer { StubHttpServer.Response(409, "Conflict", body = body) }.use { server ->
            val e = assertThrowsDav(WebDavError.PARENT_NOT_FOUND) {
                clientFor(server).put(noteUrl(server), "abc".toByteArray())
            }
            assertTrue("详情应包含 AncestorsNotFound 标记：${e.detail}", e.detail.contains("AncestorsNotFound"))
        }
    }

    @Test
    fun errorDetail_collapsesWhitespaceInTheBodyExcerpt() {
        // 正文里没有 <D:exception> 标记时，摘要必须压成一行再放进 detail（界面上的错误提示是一行）
        val body = "<html>\n  <body>boom\tbad</body>\n</html>".toByteArray(Charsets.UTF_8)
        StubHttpServer { StubHttpServer.Response(500, "Server Error", body = body) }.use { server ->
            val e = assertThrowsDav(WebDavError.SERVER_ERROR) {
                clientFor(server).put(noteUrl(server), "abc".toByteArray())
            }
            assertTrue(
                "摘要应把连续空白压成一个空格：${e.detail}",
                e.detail.contains("<html> <body>boom bad</body> </html>")
            )
        }
    }

    @Test
    fun errorResponses_withoutContentLength_doNotStall() {
        // 有些服务器对错误响应既不回 Content-Length 也不关连接：
        // 诊断信息的读取必须是「尽力而为」，绝不能把失败反馈拖到读超时
        StubHttpServer {
            StubHttpServer.Response(500, "Server Error", omitContentLength = true, body = ByteArray(0))
        }.use { server ->
            val startedAt = System.currentTimeMillis()
            assertThrowsDav(WebDavError.SERVER_ERROR) {
                clientFor(server).put(noteUrl(server), "abc".toByteArray())
            }
            val elapsed = System.currentTimeMillis() - startedAt
            assertTrue("不应等待读超时，实际耗时 ${elapsed}ms", elapsed < 5_000)
        }
    }

    @Test
    fun errorBody_isCappedAt8KiB_soAHugeDeclaredBodyCannotStallTheFailure() {
        // 服务器声称正文有 1 MiB，实际只发 8 KiB 就挂着不说话：错误正文的读取上限是 8 KiB，
        // 读满上限立刻返回。若上限失效，客户端会为读满 1 MiB 等到读超时（30s），下面的断言就会失败
        val partial = ByteArray(8 * 1024) { 'a'.code.toByte() }
        StubHttpServer {
            StubHttpServer.Response(
                status = 500,
                reason = "Server Error",
                body = partial,
                declaredContentLength = 1024 * 1024,
                stallAfterWriteMs = 15_000L
            )
        }.use { server ->
            val startedAt = System.currentTimeMillis()
            assertThrowsDav(WebDavError.SERVER_ERROR) {
                clientFor(server).put(noteUrl(server), "abc".toByteArray())
            }
            val elapsed = System.currentTimeMillis() - startedAt
            assertTrue("错误正文必须被 8 KiB 上限截断，实际耗时 ${elapsed}ms", elapsed < 5_000)
        }
    }

    // ------------------------------------------------------------ 超时与取消

    @Test
    fun callTimeout_boundsAServerThatNeverResponds() {
        // 桩服务器收下请求后既不响应也不关连接：没有整体超时的话，这次调用会挂满读超时（30s）
        StubHttpServer { _ ->
            Thread.sleep(30_000)
            StubHttpServer.Response(204, "No Content")
        }.use { server ->
            val client = OkHttpWebDavClient(
                config = SyncConfig(server.baseUrl(), "note.txt", "test", "test"),
                userAgent = "HelloWidgetTest",
                callTimeoutMs = 700L
            )
            val startedAt = System.currentTimeMillis()
            assertThrowsDav(WebDavError.TIMEOUT) {
                client.put(noteUrl(server), "abc".toByteArray(Charsets.UTF_8))
            }
            val elapsed = System.currentTimeMillis() - startedAt
            assertTrue("callTimeout 必须尽快结束调用，实际耗时 ${elapsed}ms", elapsed < 5_000)
        }
    }

    @Test
    fun close_cancelsAnInFlightCall() {
        // 旧实现的 close() 是空实现：半开连接会让 put() 永远挂着（并一直占着同步锁）。
        // 这里从另一个线程调用 close()，要求调用很快结束，且抛出可分类的异常
        val arrived = CountDownLatch(1)
        StubHttpServer { _ ->
            arrived.countDown()
            Thread.sleep(30_000)
            StubHttpServer.Response(204, "No Content")
        }.use { server ->
            val client = clientFor(server)
            var failure: Throwable? = null
            val caller = thread(name = "webdav-put") {
                try {
                    client.put(noteUrl(server), "abc".toByteArray(Charsets.UTF_8))
                } catch (t: Throwable) {
                    failure = t
                }
            }

            assertTrue("桩服务器应已收到请求", arrived.await(5, TimeUnit.SECONDS))
            val startedAt = System.currentTimeMillis()
            client.close()
            caller.join(3_000)
            val elapsed = System.currentTimeMillis() - startedAt

            assertFalse("close() 之后 put() 不应继续阻塞", caller.isAlive)
            assertTrue("close() 应立即取消，实际耗时 ${elapsed}ms", elapsed < 2_000)
            val e = failure
            assertTrue("应抛出 WebDavException，实际：$e", e is WebDavException)
            val error = (e as WebDavException).error
            assertTrue(
                "取消应映射为 TIMEOUT/IO，实际：$error",
                error == WebDavError.TIMEOUT || error == WebDavError.IO
            )
        }
    }
}
