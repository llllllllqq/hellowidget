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
 * 用户实际报障：同步到坚果云时「有时能成功，但反复出现 409」。三个独立的第三方修复
 * 记录指向同一组非标准行为（都不是 RFC 4918 规定的语义）：
 *
 *  1. **MOVE 覆盖已存在的目标返回 409**（RFC 要求 Overwrite:T 成功、Overwrite:F 412）
 *     —— keepass2android#3010（PR #3078 的说明）、all-api-hub#637 的修复注释。
 *     这正好打在「本地改了内容 → 覆盖云端」这条最常用的路径上。
 *  2. **对已存在的集合 MKCOL 返回 409**（RFC 要求 405）
 *     —— anx-reader#410「因重复创建目录导致 409 Conflict 而同步失败」。
 *  3. **读不存在的路径返回 409**（正文含 `AncestorsNotFound`，RFC 要求 404）
 *     —— all-api-hub#633 / #637。
 *
 * 本用例跑在 CI 里第二台桩服务器上（`--nutstore`，见
 * `.github/scripts/run_webdav_e2e.sh`），它按坚果云的脾气回上述状态码；
 * 断言的是「在真实 Android 上、对着这样的服务器，同步仍然成功且不乱覆盖」。
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

    private val fileName = "note.txt"

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
    fun firstSync_createsFolderAndFile_whenServerAnswers409ForMissingAncestors() {
        val content = "坚果云首次同步\n第二行 with emoji 📝"
        assertTrue("前置条件：本地写入成功", runBlocking { ContentStore.write(content) })

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("首次同步应成功（409 应被理解为「文件/目录还不存在」），实际：$status", status is SyncStatus.Success)
        assertTrue("应确实上传了内容", (status as SyncStatus.Success).uploaded)
        assertEquals("服务器端字节必须与编辑器内容一致", content, cloud(fileName))

        val log = control("log")
        // 这条 409 是桩服务器**真的**按坚果云返回的（HEAD 缺失路径 → 409 + AncestorsNotFound）
        assertTrue("应先出现坚果云式的 409：$log", log.contains("HEAD /dav/$dirName/$fileName -> 409"))
        assertTrue("随后应创建目录并把内容原子写上去：$log", log.contains("MKCOL /dav/$dirName -> 201"))
        assertTrue("应使用 MOVE 原子换名：$log", log.contains("MOVE /dav/$dirName/$fileName.uploading"))
    }

    @Test
    fun secondSync_replacesExistingFile_whenNutstoreRejectsMoveWith409() {
        assertTrue(runBlocking { ContentStore.write("第一版内容") })
        assertTrue(
            "第一次同步（创建）应成功",
            runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) } is SyncStatus.Success
        )

        // 用户改内容后再次同步：这条路径要覆盖「已存在」的云端文件 —— 坚果云正是在这里回 409
        assertTrue(runBlocking { ContentStore.write("第二版内容，已修改") })
        val second = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("覆盖同步必须成功，实际：$second", second is SyncStatus.Success)
        assertTrue("应确实上传了内容", (second as SyncStatus.Success).uploaded)
        assertEquals("云端必须被替换成新内容", "第二版内容，已修改", cloud(fileName))

        val log = control("log")
        assertTrue("集合已存在时的 MKCOL 应被当作成功（坚果云回 409）：$log", log.contains("MKCOL /dav/$dirName -> 409"))
        assertTrue(
            "MOVE 覆盖应被坚果云以 409 拒绝：$log",
            log.contains("MOVE /dav/$dirName/$fileName.uploading -> 409")
        )
        // 降级路径：直接 PUT 到正式文件（带 If-Match 前置条件），临时文件随后清掉
        assertTrue("应降级为直接 PUT 到正式文件：$log", log.contains("PUT /dav/$dirName/$fileName -> 204"))
        assertTrue("临时文件必须被清理：$log", log.contains("DELETE /dav/$dirName/$fileName.uploading"))
    }

    @Test
    fun conflictWithExternalEdit_isStillDetected_underNutstoreQuirks() {
        assertTrue(runBlocking { ContentStore.write("本地第一版") })
        assertTrue(runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) } is SyncStatus.Success)

        // 模拟「电脑端改了云端」，本地也改了：降级为直接 PUT 之后**绝不能**静默覆盖云端
        controlPost("file?path=/dav/$dirName/$fileName", "云端被电脑改过")
        assertTrue(runBlocking { ContentStore.write("本地第二版") })

        val conflict = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("应识别为冲突，实际：$conflict", conflict is SyncStatus.Conflict)
        assertEquals("冲突时绝不能静默覆盖云端", "云端被电脑改过", cloud(fileName))
        assertTrue("应留下待处理冲突（自动同步暂停）", SyncSettings.pendingConflict(context))
    }

    @Test
    fun deepMissingPath_reportsActionableParentError() {
        // /dav/<dir>/sub/ 的父集合也不存在：坚果云无法一次创建多级目录，
        // 这时必须报「上级目录不存在」这种可操作的原因，而不是一个莫名其妙的 409
        configure(davUrl!! + dirName + "/sub/")
        assertTrue(runBlocking { ContentStore.write("内容无处安放") })

        val status = runBlocking { SyncManager.performSync(context, SyncTrigger.MANUAL, null) }
        assertTrue("应失败，实际：$status", status is SyncStatus.Failed)
        val failed = status as SyncStatus.Failed
        assertEquals("应分类为「上级目录不存在」", WebDavError.PARENT_NOT_FOUND, failed.error)
        assertTrue(
            "错误详情应带出服务器给的异常名，用户/开发者才定位得到：${failed.detail}",
            failed.detail.contains("AncestorsNotFound")
        )
        assertFalse("不能留下半个文件", control("exists?path=/dav/$dirName/sub/$fileName").trim() == "1")
    }

    // ------------------------------------------------------------ 工具

    /** 云端文件内容（经过 control 面读取，不经过被测客户端） */
    private fun cloud(name: String): String = control("file?path=/dav/$dirName/$name")

    private fun configure(baseUrl: String) {
        SyncSettings.setEnabled(context, true)
        SyncSettings.saveConfig(
            context,
            SyncConfig(baseUrl = baseUrl, fileName = fileName, username = "test", password = "test")
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
}
