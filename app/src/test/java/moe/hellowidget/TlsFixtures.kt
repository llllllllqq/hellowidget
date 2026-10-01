package moe.hellowidget

import moe.hellowidget.sync.SyncEngine
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 测试专用的自签名证书夹具。
 *
 * `app/src/test/resources/tls/` 下的证书与私钥（`openssl req -x509 -newkey rsa:2048 -nodes`，
 * CN=hellowidget-test，SAN 含 `IP:127.0.0.1`，有效期 100 年）**只服务于 JVM 单元测试里的
 * 桩服务器**，用来验证「自签名证书 → 指纹固定（TOFU）」这条安全路径：
 * 它们不进入 APK、不参与任何生产逻辑、也没有任何真实用途（100 年后连服务器都不在了）。
 *
 * 之所以必须自带证书：JDK 无法在运行期凭空生成 X.509 证书（除非使用内部 API），
 * 而这条路径如果完全不测，就是「安全代码从未被执行过」。
 */
object TlsFixtures {

    private const val CERT_RESOURCE = "tls/test-server-cert.pem"
    private const val KEY_RESOURCE = "tls/test-server-key.pem"

    private val certificate: X509Certificate by lazy {
        val bytes = readPem(CERT_RESOURCE)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(bytes.inputStream()) as X509Certificate
    }

    /** 与生产代码**完全相同**的指纹算法：叶子证书公钥的 SHA-256（hex） */
    val fingerprint: String get() = SyncEngine.sha256Hex(certificate.publicKey.encoded)

    /** 一个必然不匹配的指纹（64 个 0），用于验证「指纹不对仍然拒绝」 */
    val wrongFingerprint: String get() = "0".repeat(64)

    val sslContext: SSLContext by lazy {
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(readPem(KEY_RESOURCE)))
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("stub-server", privateKey, CharArray(0), arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, CharArray(0)) }
        SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
    }

    private fun readPem(resource: String): ByteArray {
        val text = checkNotNull(TlsFixtures::class.java.classLoader?.getResourceAsStream(resource)) {
            "测试资源缺失：$resource"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        val base64 = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("-----") }
            .joinToString("")
        return java.util.Base64.getDecoder().decode(base64)
    }
}
