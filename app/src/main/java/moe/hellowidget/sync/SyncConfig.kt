package moe.hellowidget.sync

import java.net.URI
import java.net.URISyntaxException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 一次同步所需的全部配置（已归一化）。
 *
 * 这个文件里**没有任何 Android 依赖**：URL 归一化、文件名校验、HTTP 日期解析
 * 都可以直接在 JVM 单测里跑，不需要 Robolectric，也不需要模拟器。
 */
data class SyncConfig(
    /** 归一化后的目录地址，一定以 '/' 结尾（用户填的地址会先过 [SyncConfigValidator]） */
    val baseUrl: String,
    /** 远端文件名（不含路径分隔符），如 note.txt */
    val fileName: String,
    val username: String,
    val password: String,
    /** TOFU 固定的证书公钥指纹（SHA-256 十六进制），null = 只信任系统/用户 CA */
    val tlsPinSha256: String? = null
) {
    /** 远端目录（MKCOL 的目标） */
    val directoryUrl: String get() = baseUrl

    /** 主文件的完整 URL（文件名已做百分号编码） */
    val fileUrl: String get() = baseUrl + UrlCodec.encodePathSegment(fileName)

    /** 冲突副本的完整 URL */
    fun conflictCopyUrl(copyName: String): String = baseUrl + UrlCodec.encodePathSegment(copyName)

    /** 是否明文 http（界面需要给出风险提示） */
    val isCleartext: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    /** 主机名（用于界面展示与错误信息，不含端口） */
    val host: String get() = runCatching { URI(baseUrl).host ?: "" }.getOrDefault("")
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

/**
 * HTTP 日期（RFC 1123 / RFC 850 / asctime）解析与格式化。
 *
 * WebDAV 的 `getlastmodified` 与 `Last-Modified` 都是 RFC 1123 GMT 格式；
 * 少数服务器会返回另外两种历史格式，一并兼容。
 * `SimpleDateFormat` 不是线程安全的，因此每次调用都新建（同步频率很低，代价可忽略）。
 */
object HttpDates {

    private val RFC_1123 = "EEE, dd MMM yyyy HH:mm:ss zzz"
    private val RFC_850 = "EEEE, dd-MMM-yy HH:mm:ss zzz"
    private val ASCTIME = "EEE MMM d HH:mm:ss yyyy"

    /** @return epoch 毫秒；无法解析返回 -1 */
    fun parse(value: String?): Long {
        if (value.isNullOrBlank()) return -1L
        for (pattern in listOf(RFC_1123, RFC_850, ASCTIME)) {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            try {
                return sdf.parse(value)?.time ?: continue
            } catch (_: ParseException) {
                // 换下一种格式
            }
        }
        return -1L
    }

    /** 格式化成 RFC 1123 GMT（用于 If-Unmodified-Since） */
    fun format(epochMs: Long): String {
        val sdf = SimpleDateFormat(RFC_1123, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        return sdf.format(Date(epochMs))
    }
}
