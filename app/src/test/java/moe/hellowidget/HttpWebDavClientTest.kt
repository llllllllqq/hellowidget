package moe.hellowidget

import android.util.Base64
import moe.hellowidget.sync.HttpWebDavClient
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.Assert.assertEquals
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
 * 这是本功能最重要的一层证据：`HttpWebDavClient` 是手写的 HTTP/1.1，
 * 因此「到底发出去了什么」可以在 JVM 上被真实观测 —— 断言的对象是**桩服务器收到的
 * 原始请求行、请求头与正文**，而不是我们自己的分支逻辑。
 * 需要 Robolectric 只是因为客户端的日志与 Basic 认证用了 android.util.Log / Base64。
 *
 * v7.5 起客户端只剩两个动作（`PUT` 与 `MKCOL`），而且 `PUT` 必须是**无条件**的：
 * 产品语义是「本地是唯一真相，上传即覆盖」，任何 `If-*` 头都会破坏这一点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpWebDavClientTest {

    private fun clientFor(
        server: StubHttpServer,
        tlsPin: String? = null,
        onUntrusted: (String) -> Unit = {}
    ): HttpWebDavClient = HttpWebDavClient(
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
            assertEquals("close", server.requests[0].header("connection"))
            assertEquals("127.0.0.1:${server.port}", server.requests[0].header("host"))
            assertEquals("3", server.requests[0].header("content-length"))
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
            HttpWebDavClient(config, userAgent = "HelloWidgetTest")
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
}
