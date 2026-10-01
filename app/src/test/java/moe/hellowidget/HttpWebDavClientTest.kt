package moe.hellowidget

import android.util.Base64
import moe.hellowidget.sync.HttpWebDavClient
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    // ------------------------------------------------------------ stat

    @Test
    fun stat_usesHead_andParsesMetadata() {
        StubHttpServer { request ->
            if (request.method == "HEAD") {
                StubHttpServer.Response(
                    200,
                    headers = listOf(
                        "ETag" to "\"abc\"",
                        "Last-Modified" to "Thu, 01 Oct 2026 03:15:00 GMT",
                        "Content-Length" to "11"
                    )
                )
            } else {
                StubHttpServer.Response(405, "Method Not Allowed")
            }
        }.use { server ->
            val remote = clientFor(server).stat(noteUrl(server))
            assertNotNull(remote)
            assertEquals("\"abc\"", remote!!.etag)
            assertEquals(11L, remote.size)
            assertTrue(remote.lastModifiedMs > 0)
            // 元数据齐全时不该多发一个 PROPFIND（少一次请求 = 更不容易被限流）
            assertEquals(listOf("HEAD /dav/note.txt"), server.targets())
        }
    }

    @Test
    fun stat_fallsBackToPropfind_whenHeadHasNoMetadata() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/note.txt</D:href>
            <D:propstat><D:prop><D:getetag>&quot;xyz&quot;</D:getetag>
            <D:getlastmodified>Thu, 01 Oct 2026 03:15:00 GMT</D:getlastmodified>
            <D:getcontentlength>7</D:getcontentlength></D:prop></D:propstat>
            </D:response></D:multistatus>
        """.trimIndent()
        StubHttpServer { request ->
            when (request.method) {
                "HEAD" -> StubHttpServer.Response(200, omitContentLength = true)
                "PROPFIND" -> StubHttpServer.Response(
                    207, "Multi-Status",
                    headers = listOf("Content-Type" to "application/xml; charset=utf-8"),
                    body = xml.toByteArray(Charsets.UTF_8)
                )
                else -> StubHttpServer.Response(405)
            }
        }.use { server ->
            val remote = clientFor(server).stat(noteUrl(server))
            assertNotNull(remote)
            // &quot; 必须被还原：If-Match 要用这个原样的带引号值
            assertEquals("\"xyz\"", remote!!.etag)
            assertEquals(7L, remote.size)
            assertEquals(listOf("HEAD /dav/note.txt", "PROPFIND /dav/note.txt"), server.targets())
            assertEquals("0", server.requests[1].header("depth"))
        }
    }

    @Test
    fun stat_returnsNullOn404() {
        StubHttpServer { StubHttpServer.Response(404, "Not Found") }.use { server ->
            assertNull(clientFor(server).stat(noteUrl(server)))
        }
    }

    // ------------------------------------------------------------ 认证与请求头

    @Test
    fun requests_carryBasicAuthAndUserAgent() {
        StubHttpServer { StubHttpServer.Response(404) }.use { server ->
            clientFor(server).stat(noteUrl(server))
            val expected = Base64.encodeToString("test:test".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            assertEquals("Basic $expected", server.requests[0].header("authorization"))
            assertEquals("HelloWidgetTest", server.requests[0].header("user-agent"))
            assertEquals("close", server.requests[0].header("connection"))
            assertEquals("127.0.0.1:${server.port}", server.requests[0].header("host"))
        }
    }

    @Test
    fun fileNamesArePercentEncodedInTheRequestTarget() {
        StubHttpServer { StubHttpServer.Response(404) }.use { server ->
            val config = SyncConfig(
                baseUrl = server.baseUrl(),
                fileName = "我的 笔记.txt",
                username = "u",
                password = "p"
            )
            HttpWebDavClient(config).stat(config.fileUrl)
            assertEquals(
                "HEAD /dav/%E6%88%91%E7%9A%84%20%E7%AC%94%E8%AE%B0.txt",
                server.targets()[0]
            )
        }
    }

    // ------------------------------------------------------------ 原子上传

    @Test
    fun putAtomic_create_writesTempThenMovesWithOverwriteF() {
        StubHttpServer { request ->
            when {
                request.method == "PUT" && request.target.endsWith(".uploading") ->
                    StubHttpServer.Response(201, "Created")
                request.method == "MOVE" -> StubHttpServer.Response(201, "Created")
                request.method == "HEAD" -> StubHttpServer.Response(
                    200, headers = listOf("ETag" to "\"e2\"", "Content-Length" to "3")
                )
                else -> StubHttpServer.Response(405)
            }
        }.use { server ->
            val result = clientFor(server).putAtomic(
                url = noteUrl(server),
                body = "abc".toByteArray(Charsets.UTF_8),
                ifNoneMatchStar = true,
                ifMatch = null,
                ifUnmodifiedSinceMs = null
            )
            assertEquals(
                listOf("PUT /dav/note.txt.uploading", "MOVE /dav/note.txt.uploading", "HEAD /dav/note.txt"),
                server.targets()
            )
            // 正文与类型
            assertEquals("abc", server.requests[0].bodyText())
            assertEquals("text/plain; charset=utf-8", server.requests[0].header("content-type"))
            // 创建语义：临时文件无条件写，靠 MOVE 的 Overwrite:F 保证「只在不存在时创建」
            assertNull(server.requests[0].header("if-none-match"))
            assertEquals(noteUrl(server), server.requests[1].header("destination"))
            assertEquals("F", server.requests[1].header("overwrite"))
            assertEquals("\"e2\"", result!!.etag)
        }
    }

    @Test
    fun putAtomic_replace_rechecksTargetBeforeMove() {
        StubHttpServer { request ->
            when (request.method) {
                "PUT" -> StubHttpServer.Response(204, "No Content")
                // 二次确认：目标仍是决策时的那个版本
                "HEAD" -> StubHttpServer.Response(
                    200, headers = listOf("ETag" to "\"e1\"", "Content-Length" to "3")
                )
                "MOVE" -> StubHttpServer.Response(204, "No Content")
                else -> StubHttpServer.Response(405)
            }
        }.use { server ->
            clientFor(server).putAtomic(
                url = noteUrl(server),
                body = "abc".toByteArray(Charsets.UTF_8),
                ifNoneMatchStar = false,
                ifMatch = "\"e1\"",
                ifUnmodifiedSinceMs = null
            )
            // 顺序必须是 PUT 临时 → HEAD 二次确认 → MOVE 覆盖 → HEAD 读回最终状态
            assertEquals(
                listOf(
                    "PUT /dav/note.txt.uploading",
                    "HEAD /dav/note.txt",
                    "MOVE /dav/note.txt.uploading",
                    "HEAD /dav/note.txt"
                ),
                server.targets()
            )
            assertEquals("T", server.requests[2].header("overwrite"))
        }
    }

    @Test
    fun putAtomic_abortsWhenTargetChangedSinceDecision() {
        StubHttpServer { request ->
            when (request.method) {
                "PUT" -> StubHttpServer.Response(204)
                // 决策时是 e1，现在云端已经是 e9（别的设备刚改过）
                "HEAD" -> StubHttpServer.Response(
                    200, headers = listOf("ETag" to "\"e9\"", "Content-Length" to "3")
                )
                "DELETE" -> StubHttpServer.Response(204)
                else -> StubHttpServer.Response(405)
            }
        }.use { server ->
            assertThrowsDav(WebDavError.PRECONDITION_FAILED) {
                clientFor(server).putAtomic(
                    url = noteUrl(server),
                    body = "abc".toByteArray(Charsets.UTF_8),
                    ifNoneMatchStar = false,
                    ifMatch = "\"e1\"",
                    ifUnmodifiedSinceMs = null
                )
            }
            // 绝不能出现 MOVE：那会静默覆盖掉云端刚发生的修改
            assertTrue(server.targets().none { it.startsWith("MOVE") })
            // 临时文件要被清理掉，不在用户的目录里留垃圾
            assertTrue(server.targets().contains("DELETE /dav/note.txt.uploading"))
        }
    }

    @Test
    fun putAtomic_abortsWhenTargetMtimeAdvancedSinceDecision() {
        // 服务器不给 ETag 时用 Last-Modified 兜底：决策时看到的是旧时间，云端现在是新时间。
        // MOVE 的前置条件按 RFC 4918 校验的是源（临时文件），表达不了「目标必须没变」，
        // 所以这个判断只能在 MOVE 之前本地做 —— 这条用例正是把这条守护钉死。
        StubHttpServer { request ->
            when (request.method) {
                "PUT" -> StubHttpServer.Response(204)
                "HEAD" -> StubHttpServer.Response(
                    200,
                    headers = listOf(
                        "Last-Modified" to "Thu, 01 Oct 2026 04:00:00 GMT",
                        "Content-Length" to "3"
                    )
                )
                "DELETE" -> StubHttpServer.Response(204)
                else -> StubHttpServer.Response(405)
            }
        }.use { server ->
            assertThrowsDav(WebDavError.PRECONDITION_FAILED) {
                clientFor(server).putAtomic(
                    url = noteUrl(server),
                    body = "abc".toByteArray(Charsets.UTF_8),
                    ifNoneMatchStar = false,
                    ifMatch = null,
                    ifUnmodifiedSinceMs = java.time.Instant.parse("2026-10-01T03:15:00Z").toEpochMilli()
                )
            }
            assertTrue(server.targets().none { it.startsWith("MOVE") })
            assertTrue(server.targets().contains("DELETE /dav/note.txt.uploading"))
        }
    }

    @Test
    fun putAtomic_fallsBackToDirectPutWhenMoveIsUnsupported() {
        StubHttpServer { request ->
            when {
                request.method == "PUT" && request.target.endsWith(".uploading") ->
                    StubHttpServer.Response(204)
                request.method == "MOVE" -> StubHttpServer.Response(405, "Method Not Allowed")
                request.method == "DELETE" -> StubHttpServer.Response(204)
                request.method == "PUT" -> StubHttpServer.Response(
                    201, headers = listOf("ETag" to "\"d1\"")
                )
                request.method == "HEAD" -> StubHttpServer.Response(
                    200, headers = listOf("ETag" to "\"d1\"", "Content-Length" to "3")
                )
                else -> StubHttpServer.Response(405)
            }
        }.use { server ->
            val result = clientFor(server).putAtomic(
                url = noteUrl(server),
                body = "abc".toByteArray(Charsets.UTF_8),
                ifNoneMatchStar = true,
                ifMatch = null,
                ifUnmodifiedSinceMs = null
            )
            // 顺序：PUT 临时 → MOVE(405) → 直接 PUT 正式文件 → 清理临时文件
            // （Kotlin 的 `return expr` 会先求值 expr 再执行 finally，所以 DELETE 在直接 PUT 之后）
            assertEquals(
                listOf(
                    "PUT /dav/note.txt.uploading",
                    "MOVE /dav/note.txt.uploading",
                    "PUT /dav/note.txt",
                    "DELETE /dav/note.txt.uploading"
                ),
                server.targets()
            )
            // 降级路径必须保留「只在不存在时创建」的前置条件语义
            assertEquals("*", server.requests[2].header("if-none-match"))
            assertEquals("\"d1\"", result!!.etag)
        }
    }

    // ------------------------------------------------------------ 安全

    @Test
    fun redirects_areNeverFollowed_soCredentialsCannotLeak() {
        StubHttpServer {
            StubHttpServer.Response(
                302, "Found",
                headers = listOf("Location" to "http://evil.example.com/dav/note.txt")
            )
        }.use { server ->
            assertThrowsDav(WebDavError.REDIRECT) {
                clientFor(server).stat(noteUrl(server))
            }
            // 只有第一次请求：绝不能带着 Basic 头去访问跳转目标
            assertEquals(1, server.requests.size)
        }
    }

    // ------------------------------------------------------------ 错误映射

    @Test
    fun httpStatuses_mapToTypedErrors() {
        val cases = linkedMapOf(
            401 to WebDavError.UNAUTHORIZED,
            403 to WebDavError.FORBIDDEN,
            405 to WebDavError.NOT_SUPPORTED,
            409 to WebDavError.PARENT_NOT_FOUND,
            412 to WebDavError.PRECONDITION_FAILED,
            423 to WebDavError.LOCKED,
            500 to WebDavError.SERVER_ERROR,
            507 to WebDavError.INSUFFICIENT_STORAGE
        )
        for ((status, expected) in cases) {
            StubHttpServer { request ->
                if (status == 401) {
                    StubHttpServer.Response(401, "Unauthorized", listOf("WWW-Authenticate" to "Basic realm=\"dav\""))
                } else {
                    StubHttpServer.Response(status, "status-$status")
                }
            }.use { server ->
                val e = assertThrowsDav(expected) {
                    clientFor(server).putAtomic(
                        url = noteUrl(server),
                        body = "abc".toByteArray(Charsets.UTF_8),
                        ifNoneMatchStar = true,
                        ifMatch = null,
                        ifUnmodifiedSinceMs = null
                    )
                }
                assertEquals(status, e.httpStatus)
            }
        }
    }

    @Test
    fun unauthorizedDetail_showsTheChallengeScheme() {
        StubHttpServer {
            StubHttpServer.Response(401, "Unauthorized", listOf("WWW-Authenticate" to "Digest realm=\"dav\""))
        }.use { server ->
            val e = assertThrowsDav(WebDavError.UNAUTHORIZED) { clientFor(server).stat(noteUrl(server)) }
            // 用户需要看到服务器要求的是 Digest（本应用只支持 Basic）而不是干瞪眼
            assertTrue("detail 应包含认证方式：${e.detail}", e.detail.contains("Digest"))
        }
    }

    @Test
    fun get_returnsNullOn404_andBodyOn200() {
        StubHttpServer { request ->
            when (request.target) {
                "/dav/missing.txt" -> StubHttpServer.Response(404)
                else -> StubHttpServer.Response(200, body = "hello".toByteArray(Charsets.UTF_8))
            }
        }.use { server ->
            val client = clientFor(server)
            assertNull(client.get(server.baseUrl() + "missing.txt", 1024))
            assertEquals("hello", String(client.get(noteUrl(server), 1024)!!, Charsets.UTF_8))
        }
    }

    @Test
    fun get_enforcesTheSizeCapWithoutReadingTheBody() {
        StubHttpServer {
            StubHttpServer.Response(
                200,
                headers = listOf("Content-Length" to (2 * 1024 * 1024).toString()),
                omitContentLength = true
            )
        }.use { server ->
            assertThrowsDav(WebDavError.TOO_LARGE) {
                clientFor(server).get(noteUrl(server), 1 shl 20)
            }
        }
    }

    @Test
    fun get_decodesChunkedResponses() {
        StubHttpServer {
            StubHttpServer.Response(200, body = "分块内容".toByteArray(Charsets.UTF_8), chunked = true)
        }.use { server ->
            val body = clientFor(server).get(noteUrl(server), 4096)!!
            assertEquals("分块内容", String(body, Charsets.UTF_8))
        }
    }

    @Test
    fun get_handlesEofDelimitedResponses() {
        StubHttpServer {
            StubHttpServer.Response(200, body = "no-length".toByteArray(Charsets.UTF_8), omitContentLength = true)
        }.use { server ->
            assertEquals("no-length", String(clientFor(server).get(noteUrl(server), 4096)!!, Charsets.UTF_8))
        }
    }

    // ------------------------------------------------------------ 目录与冲突副本

    @Test
    fun mkcol_treatsAlreadyExistingAsSuccess() {
        StubHttpServer { request ->
            if (request.method == "MKCOL") StubHttpServer.Response(405, "Method Not Allowed")
            else StubHttpServer.Response(405)
        }.use { server ->
            clientFor(server).mkcol(server.baseUrl())
            assertEquals(listOf("MKCOL /dav/"), server.targets())
        }
    }

    @Test
    fun mkcol_reportsMissingParentDirectory() {
        StubHttpServer { StubHttpServer.Response(409, "Conflict") }.use { server ->
            assertThrowsDav(WebDavError.PARENT_NOT_FOUND) { clientFor(server).mkcol(server.baseUrl()) }
        }
    }

    @Test
    fun copy_sendsDestinationAndOverwrite() {
        StubHttpServer { StubHttpServer.Response(201, "Created") }.use { server ->
            clientFor(server).copy(
                sourceUrl = noteUrl(server),
                destUrl = server.baseUrl() + "note.conflict-20261001-031500.txt",
                overwrite = false
            )
            assertEquals("COPY /dav/note.txt", server.targets()[0])
            assertEquals(
                server.baseUrl() + "note.conflict-20261001-031500.txt",
                server.requests[0].header("destination")
            )
            assertEquals("F", server.requests[0].header("overwrite"))
        }
    }

    @Test
    fun delete_toleratesMissingFile() {
        StubHttpServer { StubHttpServer.Response(404) }.use { server ->
            clientFor(server).delete(noteUrl(server)) // 不应抛异常
        }
    }
}
