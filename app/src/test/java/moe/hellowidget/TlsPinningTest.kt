package moe.hellowidget

import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import moe.hellowidget.sync.OkHttpWebDavClient
import moe.hellowidget.sync.PinningTrustManager
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.WebDavError
import moe.hellowidget.sync.WebDavException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * 自签名证书与指纹固定（TOFU）的回归测试 —— 前半段是真实 TLS 握手，后半段直接调用信任管理器。
 *
 * 注意：本用例**刻意不使用 Robolectric**。实测同一套「SSLServerSocket 桩 + 客户端」在纯 JVM
 * （SunJSSE）下往返正常，在 Robolectric 沙箱下则握手后拿不到响应（服务端 TLS 栈/类加载器差异）。
 * 被测代码是同一份 `OkHttpWebDavClient`，因此去掉 Robolectric 反而让 TLS 覆盖更真实；
 * 凭据留空即可（这些用例只验证证书策略，凭据编码由 OkHttpWebDavClientTest 覆盖）。
 *
 * 为什么要有这一层：证书策略是**安全关键代码**，而 CI 里的端到端用例走的是明文 http，覆盖不到它。
 * 这里用测试专用自签名证书（见 [TlsFixtures]）起一个真实的 `SSLServerSocket`，验证四件事：
 *  1. 未信任的自签名证书 → 报 `TLS_UNTRUSTED`，并把**指纹**回传给上层（供界面展示）；
 *  2. 用户确认后把指纹存进配置 → 握手放行，请求正常完成；
 *  3. 指纹不对 → 依然拒绝（证明不是「无条件信任」）；
 *  4. 系统信任锚认可的证书在没有指纹时照常放行（证明固定只是**额外**信任，不是替代系统校验）。
 */
class TlsPinningTest {

    /** 客户端只有 PUT / MKCOL 两个动作，TLS 用例用 PUT 走完整往返 */
    private fun putOk(): StubHttpServer.Response = StubHttpServer.Response(201, "Created")

    private fun clientFor(
        server: StubHttpServer,
        pin: String?,
        onUntrusted: (String) -> Unit = {}
    ): OkHttpWebDavClient = OkHttpWebDavClient(
        config = SyncConfig(
            baseUrl = server.baseUrl(),
            fileName = "note.txt",
            // 空凭据：与 TLS 策略无关，真正的请求头断言在 OkHttpWebDavClientTest
            username = "",
            password = "",
            tlsPinSha256 = pin
        ),
        onUntrustedCertificate = onUntrusted
    )

    @Test
    fun selfSignedCertificate_isRejectedAndItsFingerprintIsCaptured() {
        var captured: String? = null
        StubHttpServer(TlsFixtures.sslContext) { putOk() }.use { server ->
            val error = try {
                clientFor(server, pin = null) { captured = it }
                    .put(server.baseUrl() + "note.txt", "abc".toByteArray())
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
        StubHttpServer(TlsFixtures.sslContext) { putOk() }.use { server ->
            try {
                clientFor(server, pin = TlsFixtures.fingerprint)
                    .put(server.baseUrl() + "note.txt", "abc".toByteArray())
            } catch (e: WebDavException) {
                fail("HTTPS 往返失败：${e.message}；服务端异常：${server.errors}")
                return
            }
            assertNotNull("指纹匹配后握手应成功", server.requests.firstOrNull())
            assertEquals("PUT /dav/note.txt", server.targets()[0])
            assertEquals("abc", server.requests[0].bodyText())
        }
    }

    @Test
    fun wrongFingerprint_isStillRejected_andTheRealFingerprintIsReported() {
        var captured: String? = null
        StubHttpServer(TlsFixtures.sslContext) { putOk() }.use { server ->
            val error = try {
                clientFor(server, pin = TlsFixtures.wrongFingerprint) { captured = it }
                    .put(server.baseUrl() + "note.txt", "abc".toByteArray())
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

    // ------------------------------------------------- 直接调用信任管理器（不经过网络）

    @Test
    fun systemTrustedAnchor_isAccepted_withoutAnyPin() {
        val manager = PinningTrustManager(
            pin = null,
            delegate = trustManagerTrusting(testCertificate()),
            onUntrusted = { fail("系统信任锚认可的证书不该被要求「待确认」：$it") }
        )
        // 不抛异常 = 通过：没有配置指纹时，安全策略完全来自系统校验，而不是「一律拒绝」
        manager.checkServerTrusted(arrayOf(testCertificate()), "RSA")
    }

    @Test
    fun unknownCa_isReportedAndRejected_whenNoPinIsConfigured() {
        val captured = mutableListOf<String>()
        val manager = PinningTrustManager(
            pin = null,
            delegate = systemTrustManager(),
            onUntrusted = { captured += it }
        )
        try {
            manager.checkServerTrusted(arrayOf(testCertificate()), "RSA")
            fail("系统不信任的自签名证书必须被拒绝")
        } catch (expected: CertificateException) {
            // 预期路径
        }
        assertEquals("回调必须给出服务器当前的真实指纹", listOf(TlsFixtures.fingerprint), captured)
    }

    @Test
    fun matchingPin_isAccepted_evenWhenTheSystemAnchorDoesNotTrustIt() {
        val manager = PinningTrustManager(
            pin = TlsFixtures.fingerprint,
            delegate = systemTrustManager(),
            onUntrusted = { fail("指纹匹配就不该走「待确认」回调：$it") }
        )
        // 用户确认过的指纹是**额外**信任：不抛异常 = 通过
        manager.checkServerTrusted(arrayOf(testCertificate()), "RSA")
    }

    // ------------------------------------------------------------------ 测试夹具

    /**
     * [TlsFixtures] 只暴露指纹；这里按同一份测试资源重建证书对象。
     * 不直接把证书加进 TlsFixtures：那个文件不在本次改动范围内。
     */
    private fun testCertificate(): X509Certificate {
        val pem = checkNotNull(javaClass.classLoader?.getResourceAsStream(CERT_RESOURCE)) {
            "测试资源缺失：$CERT_RESOURCE"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        val base64 = pem.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
        val der = java.util.Base64.getDecoder().decode(base64)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
    }

    /** 只信任测试证书作为锚点的「系统」校验器（模拟用户把自建 CA 装进了系统证书库） */
    private fun trustManagerTrusting(certificate: X509Certificate): X509TrustManager {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("stub-server", certificate)
        }
        return trustManagerFactory(keyStore)
    }

    /** 真正的系统默认校验器：不信任测试用的自签名证书 */
    private fun systemTrustManager(): X509TrustManager = trustManagerFactory(null)

    private fun trustManagerFactory(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private companion object {
        const val CERT_RESOURCE = "tls/test-server-cert.pem"
    }
}
