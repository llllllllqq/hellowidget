package moe.hellowidget.sync

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * 一次同步所需的全部配置（已归一化）。
 *
 * 这个文件里**没有任何 Android 依赖**：URL 归一化、文件名校验、HTTP 日期解析
 * 都可以直接在 JVM 单测里跑，不需要 Robolectric，也不需要模拟器。
 */
data class SyncConfig(
    /** 归一化后的目录地址，一定以 '/' 结尾（用户填的地址会先过 [SyncConfigValidator]） */
    val baseUrl: String,
    /**
     * 用户填写的文件名模板（不含路径分隔符），如 note.txt。
     *
     * v7.6 起它不再是「云端那一个文件的名字」，而是**前缀 + 扩展名**：
     * 每次上传都会写成 `<前缀><unix 秒时间戳><扩展名>`，例如 `note1735689600.txt`。
     */
    val fileName: String,
    val username: String,
    val password: String,
    /** TOFU 固定的证书公钥指纹（SHA-256 十六进制），null = 只信任系统/用户 CA */
    val tlsPinSha256: String? = null
) {
    /** 远端目录（MKCOL 的目标） */
    val directoryUrl: String get() = baseUrl

    /**
     * 本次上传的文件名：`<前缀><unix 秒时间戳><扩展名>`（如 `note1735689600.txt`）。
     *
     * 每次上传都是**新文件**，云端天然保留每一次上传的历史，旧文件永远不会被覆盖 ——
     * 因此完全不需要列出、比对或清理远端（省流量，也不怕历史积累）。
     */
    fun historyFileName(timestampSec: Long): String = "$stem$timestampSec$extension"

    /** 本次上传的完整 URL（文件名已做百分号编码） */
    fun historyFileUrl(timestampSec: Long): String =
        baseUrl + UrlCodec.encodePathSegment(historyFileName(timestampSec))

    /** 界面上展示的命名规则，如 `note<时间戳>.txt` */
    val historyFilePattern: String get() = "$stem<时间戳>$extension"

    /** 是否明文 http（界面需要给出风险提示） */
    val isCleartext: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    /** 主机名（用于界面展示与错误信息，不含端口） */
    val host: String get() = runCatching { URI(baseUrl).host ?: "" }.getOrDefault("")

    /** 去掉扩展名前的前缀：`note.txt` → `note`；没有扩展名时就是整个名字 */
    private val stem: String
        get() = if (fileName.lastIndexOf('.') > 0) fileName.substring(0, fileName.lastIndexOf('.')) else fileName

    /** 扩展名（含点）：`note.txt` → `.txt`；没有扩展名时默认 `.txt` */
    private val extension: String
        get() = if (fileName.lastIndexOf('.') > 0) fileName.substring(fileName.lastIndexOf('.')) else ".txt"
}

enum class ConfigError {
    EMPTY_URL,
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    MISSING_HOST,
    URL_HAS_QUERY,
    EMPTY_FILE_NAME,
    INVALID_FILE_NAME
}

sealed interface ConfigValidation {
    data class Ok(val config: SyncConfig) : ConfigValidation
    data class Invalid(val error: ConfigError, val detail: String = "") : ConfigValidation
}

/**
 * 配置校验与归一化。
 *
 * 规则（尽量宽容，但绝不猜）：
 *  - 地址必须以 http:// 或 https:// 开头；缺失 scheme 直接报错，不自动补 https
 *    （补错 scheme 只会让用户看到更难懂的错误）
 *  - 目录地址末尾自动补 '/'，让「目录」语义无歧义（界面会把归一化结果显示出来）
 *  - 不接受带 query/fragment 的地址（WebDAV 目录地址不该有这些）
 *  - 文件名不允许路径分隔符与 `..`，避免写到目录之外
 */
object SyncConfigValidator {

    fun validate(
        baseUrlRaw: String,
        fileNameRaw: String,
        username: String,
        password: String,
        tlsPinSha256: String? = null
    ): ConfigValidation {
        val normalized = normalizeBaseUrl(baseUrlRaw)
        if (normalized is ConfigValidation.Invalid) return normalized
        val baseUrl = (normalized as ConfigValidation.Ok).config.baseUrl

        val fileName = fileNameRaw.trim()
        if (fileName.isEmpty()) return ConfigValidation.Invalid(ConfigError.EMPTY_FILE_NAME)
        validateFileName(fileName)?.let { return it }

        return ConfigValidation.Ok(
            SyncConfig(
                baseUrl = baseUrl,
                fileName = fileName,
                username = username.trim(),
                password = password,
                tlsPinSha256 = tlsPinSha256
            )
        )
    }

    /** 只做地址归一化（界面在用户输入时即时预览归一化结果） */
    fun normalizeBaseUrl(baseUrlRaw: String): ConfigValidation {
        val raw = baseUrlRaw.trim()
        if (raw.isEmpty()) return ConfigValidation.Invalid(ConfigError.EMPTY_URL)

        val schemeSep = raw.indexOf("://")
        if (schemeSep <= 0) {
            return ConfigValidation.Invalid(ConfigError.INVALID_URL, raw)
        }
        val scheme = raw.substring(0, schemeSep).lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return ConfigValidation.Invalid(ConfigError.UNSUPPORTED_SCHEME, scheme)
        }

        // 单独拼一个保证路径以 '/' 结尾的地址再交给 URI 解析：
        // 直接对 "http://host/dir" 拼 '/' 是安全的，但用户可能填了 "http://host"（无路径）。
        val withSlash = if (raw.endsWith("/")) raw else "$raw/"
        val uri = try {
            URI(withSlash)
        } catch (e: URISyntaxException) {
            return ConfigValidation.Invalid(ConfigError.INVALID_URL, e.message ?: raw)
        }
        if (uri.host.isNullOrEmpty() || uri.rawAuthority.isNullOrEmpty()) {
            return ConfigValidation.Invalid(ConfigError.MISSING_HOST, raw)
        }
        if (uri.rawQuery != null || uri.rawFragment != null) {
            return ConfigValidation.Invalid(ConfigError.URL_HAS_QUERY, raw)
        }
        val path = uri.rawPath ?: "/"
        val normalizedPath = if (path.isEmpty()) "/" else if (path.endsWith("/")) path else "$path/"
        // 只保留 host + port：如果用户把用户名口令写进了 URL（https://user:pass@host/…），
        // 绝不能把它们落盘到 prefs 里，也不能显示在界面上
        val rawHost = uri.host ?: return ConfigValidation.Invalid(ConfigError.MISSING_HOST, raw)
        val hostPart = if (rawHost.contains(':') && !rawHost.startsWith("[")) "[$rawHost]" else rawHost
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val normalized = "$scheme://$hostPart$portPart$normalizedPath"
        return ConfigValidation.Ok(SyncConfig(baseUrl = normalized, fileName = "", username = "", password = ""))
    }

    /** @return null = 合法；否则返回具体错误 */
    fun validateFileName(fileName: String): ConfigValidation.Invalid? {
        if (fileName.isEmpty()) return ConfigValidation.Invalid(ConfigError.EMPTY_FILE_NAME)
        if (fileName.length > 128) return ConfigValidation.Invalid(ConfigError.INVALID_FILE_NAME, fileName)
        if (fileName == "." || fileName == "..") {
            return ConfigValidation.Invalid(ConfigError.INVALID_FILE_NAME, fileName)
        }
        val forbidden = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        if (fileName.any { it in forbidden || it.code < 0x20 || it.code == 0x7F }) {
            return ConfigValidation.Invalid(ConfigError.INVALID_FILE_NAME, fileName)
        }
        return null
    }
}

/**
 * 路径段的百分号编码。
 *
 * 不能直接用 `URLEncoder.encode`：它把空格编成 '+'（那是 form 编码，不是路径编码），
 * 而 WebDAV 服务器会把它当成文件名里的加号，导致中文/空格文件名找不到文件。
 * 保留 RFC 3986 的 unreserved 集合，其余一律按 UTF-8 逐字节编码。
 */
object UrlCodec {

    private const val UNRESERVED_EXTRA = "-._~"

    fun encodePathSegment(segment: String): String {
        val sb = StringBuilder(segment.length + 8)
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val c = value.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || UNRESERVED_EXTRA.indexOf(c) >= 0) {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append(HEX[(value shr 4) and 0xF])
                sb.append(HEX[value and 0xF])
            }
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
