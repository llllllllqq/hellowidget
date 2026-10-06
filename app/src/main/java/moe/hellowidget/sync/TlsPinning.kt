package moe.hellowidget.sync

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TOFU（首次使用即信任）证书策略：**系统信任锚永远有效，用户确认过的指纹只是额外信任**。
 *
 * 之所以单独成文件：这是安全关键代码，既要能被 `OkHttpWebDavClient` 组装进 OkHttp
 * （`sslSocketFactory(factory, trustManager)` + 自定义 `HostnameVerifier`），
 * 也要能被单测直接调用。
 *
 * 三条规则（从旧的手写客户端原样搬过来）：
 *  1. 证书链先交给系统默认 `X509TrustManager`（含网络安全配置的信任锚）；
 *  2. 指纹与用户确认过的值一致时直接放行（自签名 NAS 的证书通常连主机名也对不上）；
 *  3. 两者都不满足时，把**真实指纹**交给上层保存为「待确认」，本次仍然失败 ——
 *     绝不在用户看到指纹并确认之前信任任何证书。
 */
internal class TlsPinning(
    pinSha256: String?,
    onUntrustedCertificate: (String) -> Unit
) {
    val trustManager: X509TrustManager
    val socketFactory: SSLSocketFactory
    val hostnameVerifier: HostnameVerifier

    init {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val delegate = factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw WebDavException(WebDavError.TLS, detail = "系统没有可用的 X509TrustManager")
        trustManager = PinningTrustManager(pinSha256, delegate, onUntrustedCertificate)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), null)
        socketFactory = context.socketFactory
        hostnameVerifier = PinnedHostnameVerifier(pinSha256)
    }
}

/**
 * 在「系统默认校验」之上**追加**一层指纹固定（TOFU）。
 *
 * lint 的 CustomX509TrustManager 警告针对的是「自己实现校验、绕过系统」的写法；
 * 这里相反：每个分支都会调用 [delegate]（系统默认 X509TrustManager，含网络安全配置的
 * 信任锚），只有「用户确认过的指纹」这一种情况才提前放行，而且指纹不匹配时**不是**
 * 直接失败，而是继续交给系统校验 —— 即安全策略只增不减。
 */
@SuppressLint("CustomX509TrustManager")
internal class PinningTrustManager(
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

/** 带指纹的自定义异常：异常链里靠它区分「证书不受信任」与其它 TLS 失败 */
internal class UntrustedCertificateException(val fingerprint: String) :
    CertificateException("证书不受信任（指纹 $fingerprint）")

/**
 * 指纹匹配时跳过主机名校验，其余情况一律交回平台默认实现。
 *
 * 旧实现在握手后手工做了同样的事：`HttpsURLConnection.getDefaultHostnameVerifier()`
 * 就是 Android/JDK 的默认校验器，换到 OkHttp 只是把它接到 `hostnameVerifier` 上，
 * 判断逻辑一字不差 —— 安全策略只增不减。
 */
internal class PinnedHostnameVerifier(private val pinSha256: String?) : HostnameVerifier {

    override fun verify(hostname: String, session: SSLSession): Boolean =
        pinMatches(pinSha256, session) || defaultVerifier.verify(hostname, session)

    private companion object {
        val defaultVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
    }
}

/** 会话叶子证书的公钥 SHA-256 是否等于固定值（拿不到证书时一律当作不匹配） */
private fun pinMatches(pin: String?, session: SSLSession): Boolean {
    if (pin == null) return false
    val certificate = try {
        session.peerCertificates.firstOrNull() as? X509Certificate
    } catch (_: SSLPeerUnverifiedException) {
        null
    } ?: return false
    return SyncEngine.sha256Hex(certificate.publicKey.encoded).equals(pin, ignoreCase = true)
}

/**
 * 从异常链里找出「证书不受信任」的标记，拿到可展示的指纹。
 *
 * 必须遍历原因链：JSSE 会把手写的 `UntrustedCertificateException` 包进
 * `SSLHandshakeException`（Android 与 JDK 的包装层数还不一样），
 * 深度上限只是防御性写法，避免异常链成环时死循环。
 */
internal fun findUntrustedFingerprint(e: Throwable): String? {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < 16) {
        if (current is UntrustedCertificateException) return current.fingerprint
        current = current.cause
        depth++
    }
    return null
}
