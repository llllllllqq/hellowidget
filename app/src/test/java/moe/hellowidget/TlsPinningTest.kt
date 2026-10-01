package moe.hellowidget

import moe.hellowidget.sync.HttpWebDavClient
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * 自签名证书与指纹固定（TOFU）的回归测试 —— 真实 TLS 握手，不是打桩。
 *
 * 注意：本用例**刻意不使用 Robolectric**。实测同一套「SSLServerSocket 桩 + 客户端」
 * 在纯 JVM（SunJSSE）下往返正常，在 Robolectric 沙箱下则握手后拿不到响应
 * （服务端 TLS 栈/类加载器差异）。而被测代码是同一份 `HttpWebDavClient`，
 * 因此去掉 Robolectric 反而让 TLS 覆盖更真实。
 * 代价是无法使用 `android.util.Base64`（Robolectric 提供），所以这里把凭据留空 ——
 * 客户端在无凭据时不会生成 Authorization 头，正好绕开这个 Android 类；
 * 需要凭据的请求路径已由 HttpWebDavClientTest（Robolectric）覆盖。
 *
 * 为什么要有这一层：`HttpWebDavClient` 的证书策略是**安全关键代码**，
 * 而 CI 里的端到端用例走的是明文 http，覆盖不到它。这里用测试专用自签名证书
 * （见 [TlsFixtures]）起一个真实的 `SSLServerSocket`，验证三件事：
 *  1. 未信任的自签名证书 → 报 `TLS_UNTRUSTED`，并把**指纹**回传给上层（供界面展示）；
 *  2. 用户确认后把指纹存进配置 → 握手放行，请求正常完成；
 *  3. 指纹不对 → 依然拒绝（证明不是「无条件信任」）。
 */
class TlsPinningTest {

    private fun headOk(): StubHttpServer.Response = StubHttpServer.Response(
        200,
        headers = listOf("ETag" to "\"e1\"", "Content-Length" to "3")
    )

    private fun clientFor(
        server: StubHttpServer,
        pin: String?,
        onUntrusted: (String) -> Unit = {}
    ): HttpWebDavClient = HttpWebDavClient(
        config = SyncConfig(
            baseUrl = server.baseUrl(),
            fileName = "note.txt",
            // 空凭据：不触发 android.util.Base64（纯 JVM 测试里不可用），与 TLS 策略无关
            username = "",
            password = "",
            tlsPinSha256 = pin
        ),
        onUntrustedCertificate = onUntrusted
    )

    @Test
    fun selfSignedCertificate_isRejectedAndItsFingerprintIsCaptured() {
        var captured: String? = null
        StubHttpServer(TlsFixtures.sslContext) { headOk() }.use { server ->
            val error = try {
                clientFor(server, pin = null) { captured = it }.stat(server.baseUrl() + "note.txt")
                fail("未信任的自签名证书必须被拒绝")
                return
            } catch (e: WebDavException) {
                e
            }
            assertEquals(WebDavError.TLS_UNTRUSTED, error.error)
            assertEquals("上层应拿到可展示给用户的指纹", TlsFixtures.fingerprint, captured)
            assertEquals("错误详情里也要带指纹", TlsFixtures.fingerprint, error.detail)
        }
    }

    @Test
    fun confirmedFingerprint_letsTheHandshakeThrough() {
        StubHttpServer(TlsFixtures.sslContext) { headOk() }.use { server ->
            val remote = try {
                clientFor(server, pin = TlsFixtures.fingerprint)
                    .stat(server.baseUrl() + "note.txt")
            } catch (e: WebDavException) {
                fail("HTTPS 往返失败：${e.message}；服务端异常：${server.errors}")
                return
            }
            assertNotNull("指纹匹配后握手应成功", remote)
            assertEquals("\"e1\"", remote!!.etag)
            assertEquals("HEAD /dav/note.txt", server.targets()[0])
        }
    }

    @Test
    fun wrongFingerprint_isStillRejected_andTheRealFingerprintIsReported() {
        var captured: String? = null
        StubHttpServer(TlsFixtures.sslContext) { headOk() }.use { server ->
            val error = try {
                clientFor(server, pin = TlsFixtures.wrongFingerprint) { captured = it }
                    .stat(server.baseUrl() + "note.txt")
                fail("指纹不匹配时必须拒绝")
                return
            } catch (e: WebDavException) {
                e
            }
            assertEquals(WebDavError.TLS_UNTRUSTED, error.error)
            // 已配置的指纹对不上时，回调报出的是**服务器当前的真实指纹**：
            // 这样证书轮换后用户能在同步页看到新指纹并重新确认，而不是看到一个假的待确认项
            assertEquals(TlsFixtures.fingerprint, captured)
        }
    }
}
