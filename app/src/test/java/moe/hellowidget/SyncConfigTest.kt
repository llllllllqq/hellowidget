package moe.hellowidget

import moe.hellowidget.sync.ConfigError
import moe.hellowidget.sync.ConfigValidation
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncConfigValidator
import moe.hellowidget.sync.UrlCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置校验 / URL 编码的单元测试（纯 JVM）。
 *
 * 这些是最容易被忽略、又最能坑用户的一层：地址里少一个斜杠、文件名带空格没编码，
 * 表现出来都是「同步失败」而不是「参数写错了」。
 */
class SyncConfigTest {

    private fun ok(rawUrl: String, fileName: String = "note.txt"): SyncConfig {
        val result = SyncConfigValidator.validate(rawUrl, fileName, "u", "p")
        assertTrue("期望合法：$rawUrl，实际 $result", result is ConfigValidation.Ok)
        return (result as ConfigValidation.Ok).config
    }

    private fun error(rawUrl: String, fileName: String = "note.txt"): ConfigError {
        val result = SyncConfigValidator.validate(rawUrl, fileName, "u", "p")
        assertTrue("期望非法：$rawUrl，实际 $result", result is ConfigValidation.Invalid)
        return (result as ConfigValidation.Invalid).error
    }

    // ------------------------------------------------------------ 地址归一化

    @Test
    fun baseUrl_getsTrailingSlash() {
        assertEquals("https://dav.example.com/dav/", ok("https://dav.example.com/dav").baseUrl)
        assertEquals("https://dav.example.com/dav/", ok("https://dav.example.com/dav/").baseUrl)
        assertEquals("https://dav.example.com/", ok("https://dav.example.com").baseUrl)
        // 首尾空格会被裁掉（用户从浏览器复制地址时很常见）
        assertEquals("https://dav.example.com/dav/", ok("  https://dav.example.com/dav  ").baseUrl)
    }

    @Test
    fun baseUrl_keepsPortAndScheme() {
        assertEquals("https://dav.example.com:8443/dav/", ok("https://dav.example.com:8443/dav").baseUrl)
        assertEquals("http://192.168.1.5:5005/dav/", ok("http://192.168.1.5:5005/dav").baseUrl)
    }

    @Test
    fun baseUrl_neverKeepsCredentialsInTheUrl() {
        // 用户把用户名口令写进地址时，绝不把它落盘/显示出来（否则会进 prefs 与界面）
        val config = ok("https://user:secret@dav.example.com/dav")
        assertEquals("https://dav.example.com/dav/", config.baseUrl)
        assertTrue(!config.baseUrl.contains("secret"))
        assertTrue(!config.baseUrl.contains("user:"))
    }

    @Test
    fun baseUrl_rejectsBadInput() {
        assertEquals(ConfigError.EMPTY_URL, error("   "))
        // 没有 scheme：不猜、不自动补 https，直接报错让用户改对
        assertEquals(ConfigError.INVALID_URL, error("dav.example.com/dav"))
        assertEquals(ConfigError.UNSUPPORTED_SCHEME, error("ftp://dav.example.com/dav"))
        assertEquals(ConfigError.URL_HAS_QUERY, error("https://dav.example.com/dav?x=1"))
        assertEquals(ConfigError.INVALID_URL, error("https://dav example.com/dav"))
    }

    @Test
    fun cleartextFlag_drivesTheWarning() {
        assertTrue(ok("http://192.168.1.5/dav").isCleartext)
        assertTrue(!ok("https://dav.example.com/dav").isCleartext)
    }

    // ------------------------------------------------------------ 文件名

    @Test
    fun fileName_allowsCjkSpacesAndDots() {
        assertEquals("我的 笔记.txt", ok("https://dav.example.com/dav", "我的 笔记.txt").fileName)
        assertEquals("a.b.txt", ok("https://dav.example.com/dav", "a.b.txt").fileName)
    }

    @Test
    fun fileName_rejectsPathSeparatorsAndTraversal() {
        assertEquals(ConfigError.EMPTY_FILE_NAME, error("https://dav.example.com/dav", ""))
        assertEquals(ConfigError.EMPTY_FILE_NAME, error("https://dav.example.com/dav", "   "))
        assertEquals(ConfigError.INVALID_FILE_NAME, error("https://dav.example.com/dav", "../note.txt"))
        assertEquals(ConfigError.INVALID_FILE_NAME, error("https://dav.example.com/dav", "sub/note.txt"))
        assertEquals(ConfigError.INVALID_FILE_NAME, error("https://dav.example.com/dav", "sub\\note.txt"))
        assertEquals(ConfigError.INVALID_FILE_NAME, error("https://dav.example.com/dav", ".."))
        assertEquals(ConfigError.INVALID_FILE_NAME, error("https://dav.example.com/dav", "a:b.txt"))
    }

    // ------------------------------------------------------------ URL 编码

    @Test
    fun urlCodec_percentEncodesPathSegments() {
        assertEquals("note.txt", UrlCodec.encodePathSegment("note.txt"))
        assertEquals("AZaz09-._~", UrlCodec.encodePathSegment("AZaz09-._~"))
        // 空格必须是 %20 而不是 +（+ 在路径里就是加号本身，服务器会找不到文件）
        assertEquals("%E6%88%91%E7%9A%84%20%E7%AC%94%E8%AE%B0.txt", UrlCodec.encodePathSegment("我的 笔记.txt"))
        assertEquals("a%3Fb%23c%25d", UrlCodec.encodePathSegment("a?b#c%d"))
    }

    @Test
    fun historyFileName_isPrefixPlusUnixTimestampPlusExtension() {
        val config = SyncConfig(
            baseUrl = "https://dav.example.com/dav/",
            fileName = "note.txt",
            username = "u",
            password = "p"
        )
        assertEquals("note1735689600.txt", config.historyFileName(1735689600L))
        assertEquals(
            "https://dav.example.com/dav/note1735689600.txt",
            config.historyFileUrl(1735689600L)
        )
        assertEquals("note<时间戳>.txt", config.historyFilePattern)
        assertEquals("dav.example.com", config.host)
    }

    @Test
    fun historyFileName_handlesCustomNamesAndMissingExtension() {
        fun config(name: String) = SyncConfig("https://d/x/", name, "u", "p")
        // 自定义前缀与扩展名照旧生效
        assertEquals("我的笔记1759312800.md", config("我的笔记.md").historyFileName(1759312800L))
        // 多个点：只把最后一段当扩展名
        assertEquals("a.b1759312800.txt", config("a.b.txt").historyFileName(1759312800L))
        // 没写扩展名时默认 .txt
        assertEquals("memo1759312800.txt", config("memo").historyFileName(1759312800L))
    }

    @Test
    fun historyFileUrl_percentEncodesCjkAndSpaces() {
        val config = SyncConfig(
            baseUrl = "https://dav.example.com/dav/",
            fileName = "我的 笔记.txt",
            username = "u",
            password = "p"
        )
        // 空格必须是 %20 而不是 +（+ 在路径里就是加号本身，服务器会找不到文件）
        assertEquals(
            "https://dav.example.com/dav/%E6%88%91%E7%9A%84%20%E7%AC%94%E8%AE%B0" +
                "1759312800.txt",
            config.historyFileUrl(1759312800L)
        )
    }
}
