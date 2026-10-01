package moe.hellowidget

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import moe.hellowidget.sync.WebDavError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * 坚果云（dav.jianguoyun.com）兼容性的**真机级**回归。
 *
 * 坚果云对 RFC 4918 有两处偏离，都会打在「要写云端」的路径上：
 *  1. **目标父集合不存在时 `PUT` 返回 409**（而不是 404）——
 *     本应用据此先 `MKCOL` 再重试一次，用户不必手动去网页端建目录；
 *  2. **对已存在的集合 `MKCOL` 返回 409**（RFC 要求 405）
 *     —— anx-reader#410；客户端必须把它当作「目录已经在了」。
 *  另外读到不存在的路径时它返回 `409 + AncestorsNotFound`（all-api-hub#633），
 *  多级缺失目录时会以这个标记的形式出现在错误详情里，方便定位。
 *
 * 本用例跑在 CI 里第二台桩服务器上（`--nutstore`，见 `.github/scripts/run_webdav_e2e.sh`）。
 *
 * 未传 `webdavNutstoreUrl` / `webdavNutstoreControlUrl` 时整体跳过。
 */
@RunWith(AndroidJUnit4::class)
class SyncNutstoreQuirkE2eInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()

    private val davUrl: String? get() = arguments.getString("webdavNutstoreUrl")
    private val controlUrl: String? get() = arguments.getString("webdavNutstoreControlUrl")

    @get:Rule
    val testName = TestName()

    /** 每个用例一个独立目录（目录本身也由被测代码创建），用例之间零耦合 */
    private val dirName: String get() = "nut-${testName.methodName}"

    private val dirPath: String get() = "/dav/$dirName"

    @Before
    fun setUp() {
        assumeTrue(
            "未提供 webdavNutstoreUrl/webdavNutstoreControlUrl，跳过坚果云兼容性测试",
            !davUrl.isNullOrBlank() && !controlUrl.isNullOrBlank()
        )
        configure(davUrl!! + dirName + "/")
    }

    // ------------------------------------------------------------ 场景

    @Test
    fun missingFolder_isCreatedAutomatically_thenUploadSucceeds() {
        val content = "坚果云首次同步\n第二行 with emoji 📝"
        assertTrue("前置条件：本地写入成功", runBlocking { ContentStore.write(content) })

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("目录不存在也应自动创建后上传成功，实际：$status", status is SyncStatus.Success)
        assertTrue("应确实上传了内容", (status as SyncStatus.Success).uploaded)

        val name = uploadedName()
        assertTrue("文件名必须是 note<unix 秒>.txt，实际：$name", NAME_PATTERN.matches(name))
        assertEquals("服务器端字节必须与编辑器内容一致", content, cloud(name))

        val log = control("log")
        // 坚果云式 409：目标父集合不存在 → 先 MKCOL 再重试这一次 PUT
        assertTrue("应出现 409 被挡住的第一次 PUT：$log", log.contains("PUT $dirPath/$name -> 409"))
        assertTrue("随后应创建目录：$log", log.contains("MKCOL $dirPath -> 201"))
        assertTrue("重试的 PUT 应成功：$log", log.contains("PUT $dirPath/$name -> 201"))
        assertFalse("不得使用临时文件 + MOVE：$log", log.contains(".uploading") || log.contains("MOVE"))
    }

    @Test
    fun oldFilesStayUntouched_andEveryUploadAddsANewFile() {
        // 目录里先放一份「历史文件」（模拟以前同步上去的内容）
        controlPost("file?path=$dirPath/legacy.txt", "旧的历史内容")

        assertTrue(runBlocking { ContentStore.write("本机第一版") })
        assertTrue(runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) } is SyncStatus.Success)
        val first = uploadedName()

        assertTrue(runBlocking { ContentStore.write("本机第二版") })
        assertTrue(runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) } is SyncStatus.Success)
        val second = uploadedName()

        assertTrue("两次上传必须是两个不同的文件：$first / $second", first != second)
        assertEquals("旧的历史文件必须原样保留", "旧的历史内容", cloud("legacy.txt"))
        assertEquals("第一版的那份仍在", "本机第一版", cloud(first))
        assertEquals("新文件是本次内容", "本机第二版", cloud(second))

        val log = control("log")
        assertFalse("不得删除/覆盖任何历史文件：$log",
            log.contains("DELETE $dirPath/legacy.txt") || log.contains("PUT $dirPath/legacy.txt"))
        assertFalse("不得读取云端：$log", log.contains("HEAD $dirPath") || log.contains("PROPFIND"))
    }

    @Test
    fun deepMissingPath_reportsActionableParentError() {
        // /dav/<dir>/sub/ 的父集合也不存在：坚果云无法一次创建多级目录，
        // 这时必须报「上级目录不存在」这种可操作的原因（并带上服务器的 AncestorsNotFound），
        // 而不是一个莫名其妙的 409
        configure(davUrl!! + dirName + "/sub/")
        assertTrue(runBlocking { ContentStore.write("内容无处安放") })

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL) }
        assertTrue("应失败，实际：$status", status is SyncStatus.Failed)
        val failed = status as SyncStatus.Failed
        assertEquals("应分类为「上级目录不存在」", WebDavError.PARENT_NOT_FOUND, failed.error)
        assertTrue(
            "错误详情应带出服务器给的异常名，用户/开发者才定位得到：${failed.detail}",
            failed.detail.contains("AncestorsNotFound")
        )
        assertEquals(
            "多级目录没有建起来，也就不会有半个文件",
            "0",
            control("exists?path=$dirPath/sub").trim()
        )
    }

    // ------------------------------------------------------------ 工具

    /** 本次实际上传用的文件名（由落盘的时间戳还原，不依赖任何测试侧约定） */
    private fun uploadedName(): String {
        val config = SyncSettings.config(context)
        assertNotNull("应有可用配置", config)
        return config!!.historyFileName(SyncSettings.lastUploadedTs(context))
    }

    /** 云端文件内容（经过 control 面读取，不经过被测客户端） */
    private fun cloud(name: String): String = control("file?path=$dirPath/$name")

    private fun configure(baseUrl: String) {
        SyncSettings.setEnabled(context, true)
        SyncSettings.saveConfig(
            context,
            SyncConfig(baseUrl = baseUrl, fileName = "note.txt", username = "test", password = "test")
        )
        SyncSettings.setTlsPin(context, null)
        SyncSettings.setPendingTlsPin(context, null)
        SyncSettings.resetRuntimeState(context)
    }

    private fun control(path: String): String {
        val connection = URL(controlUrl + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return try {
            String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun controlPost(path: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = URL(controlUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return try {
            connection.outputStream.use { it.write(bytes) }
            String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** `note1735689600123.txt`：前缀 + unix 毫秒时间戳 + 扩展名 */
        val NAME_PATTERN = Regex("""note\d{13}\.txt""")
    }
}
