package moe.hellowidget

import android.app.Application
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import moe.hellowidget.MainActivity.Companion.prefs
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v8.2.0 备份 / 换机迁移：编解码 + 落盘。
 *
 * 这一层必须**能独立测试**，因为它是"用户数据能不能搬走"这件事的全部逻辑：
 * 界面（SAF 选文件）只是把字节交给它。用例覆盖三类风险：
 *  1. 往返（导出 → 导入）必须逐字段一致 —— 少一个字段就意味着换机后少恢复一样东西；
 *  2. 外部输入必须被怀疑：别的应用的 JSON、截断的 JSON、更新版本导出的备份、
 *     被手改成越界数值的外观设置，都不能让应用写出一份坏配置；
 *  3. **正文优先**：WebDAV 段坏掉时仍要把正文恢复回来（用户最不能丢的是文本）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCodecTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private val exportedAt = 1_767_225_600_000L // 2026-01-01 00:00:00 UTC，仅用于固定断言

    private fun sample(
        content: String = "第一行内容\n第二行 emoji 🙂",
        sync: BackupCodec.SyncAccount? = BackupCodec.SyncAccount(
            enabled = true,
            baseUrl = "https://dav.example.com/dav/",
            fileName = "note.txt",
            username = "user",
            password = "p@ss",
            tlsPinSha256 = "AB:CD"
        )
    ) = BackupCodec.Backup(
        content = content,
        widget = BackupCodec.WidgetAppearance(
            fontSp = 18f,
            textColor = Color.YELLOW,
            bgColor = Color.BLACK,
            bgAlpha = 70,
            fillMarginDp = 6
        ),
        editor = BackupCodec.EditorColors(
            lightBg = Color.WHITE,
            lightText = Color.BLACK,
            darkBg = Color.BLACK,
            darkText = Color.WHITE
        ),
        sync = sync
    )

    private fun encode(backup: BackupCodec.Backup): String =
        BackupCodec.encode(backup, exportedAt)

    private fun decodeOk(text: String): BackupCodec.Decoded.Ok {
        val decoded = BackupCodec.decode(text)
        assertTrue("期望解码成功，实际是 $decoded", decoded is BackupCodec.Decoded.Ok)
        return decoded as BackupCodec.Decoded.Ok
    }

    // ------------------------------------------------------------ 往返

    @Test
    fun encodeThenDecode_keepsEveryFieldExactly() {
        val original = sample()
        val decoded = decodeOk(encode(original))

        assertFalse("合法备份不该被判为「跳过了同步设置」", decoded.syncDropped)
        assertEquals(original, decoded.backup)
    }

    @Test
    fun encode_writesEverythingIncludingThePlaintextPassword() {
        val json = encode(sample())

        // 明文口令是有意为之（与它在 prefs 里的存法一致，用户已接受该取舍）：
        // 这里把它钉住，将来若有人加了加密，这条用例会立刻提醒"恢复流程要跟着改"。
        assertTrue("备份文件里必须有可读的正文", json.contains("第一行内容"))
        assertTrue("口令按用户要求明文写入", json.contains("p@ss"))
        assertTrue("必须带应用标识", json.contains(BackupCodec.APP_ID))
    }

    @Test
    fun suggestedFileName_isATimestampedJson() {
        val name = BackupCodec.suggestedFileName(exportedAt)
        assertTrue("文件名必须能看出是备份且带时间戳：$name", name.startsWith("hellowidget-backup-"))
        assertTrue("必须是 .json：$name", name.endsWith(".json"))
    }

    // ------------------------------------------------------------ 外部输入必须被怀疑

    @Test
    fun decode_rejectsAFileThatIsNotJson() {
        assertEquals(
            BackupCodec.BadReason.NOT_JSON,
            (BackupCodec.decode("{ 这不是合法 JSON") as BackupCodec.Decoded.Bad).reason
        )
    }

    @Test
    fun decode_rejectsAJsonFromAnotherApp() {
        val foreign = encode(sample()).replace(BackupCodec.APP_ID, "some-other-app")
        assertEquals(
            BackupCodec.BadReason.FOREIGN_APP,
            (BackupCodec.decode(foreign) as BackupCodec.Decoded.Bad).reason
        )
    }

    @Test
    fun decode_rejectsABackupFromANewerFormat() {
        val newer = encode(sample())
            .replace("\"format\": ${BackupCodec.FORMAT_VERSION}", "\"format\": 99")
        assertEquals(
            BackupCodec.BadReason.NEWER_FORMAT,
            (BackupCodec.decode(newer) as BackupCodec.Decoded.Bad).reason
        )
    }

    @Test
    fun decode_rejectsABackupWithoutContent() {
        val noContent = """{"app":"${BackupCodec.APP_ID}","format":1}"""
        assertEquals(
            BackupCodec.BadReason.NO_CONTENT,
            (BackupCodec.decode(noContent) as BackupCodec.Decoded.Bad).reason
        )
    }

    @Test
    fun decode_keepsTheText_butDropsAnInvalidSyncSection() {
        val broken = encode(
            sample(
                sync = BackupCodec.SyncAccount(
                    enabled = true,
                    // 缺 scheme：SyncConfigValidator 必须拒掉它
                    baseUrl = "dav.example.com/dav/",
                    fileName = "note.txt",
                    username = "u",
                    password = "p",
                    tlsPinSha256 = null
                )
            )
        )
        val decoded = decodeOk(broken)

        assertTrue("WebDAV 段不合法时必须标记为已跳过", decoded.syncDropped)
        assertNull("跳过的同步设置不能恢复成半截配置", decoded.backup.sync)
        assertEquals("正文必须照常恢复（用户最不能丢的是文本）", sample().content, decoded.backup.content)
        // 外观也要照常恢复
        assertEquals(18f, decoded.backup.widget.fontSp, 0.001f)
        assertEquals(70, decoded.backup.widget.bgAlpha)
    }

    @Test
    fun decode_keepsTheExistingSyncSettingsWhenTheBackupHasNoSyncSection() {
        val withoutSync = encode(sample(sync = null))
        val decoded = decodeOk(withoutSync)
        assertNull(decoded.backup.sync)
        assertFalse(
            "备份里根本没有同步段 ≠ 同步设置非法：不应提示「已跳过」",
            decoded.syncDropped
        )
        assertEquals(sample().content, decoded.backup.content)
    }

    @Test
    fun decode_normalizesTheServerUrl_soTheRestoredConfigIsTheSameShapeAsATypedOne() {
        val decoded = decodeOk(encode(sample()))
        assertEquals(
            "地址必须与设置页归一化后的形态一致（末尾补 /）",
            "https://dav.example.com/dav/",
            decoded.backup.sync?.baseUrl
        )
    }

    @Test
    fun decode_clampsAppearanceValuesThatWereHandEdited() {
        // 用正则改数值：org.json 在 Android 上会把 18.0 写成 18（去掉末尾的 0），
        // 按字面量匹配字符串会写出一条"没改到"的用例，等于什么都没测。
        val tampered = encode(sample())
            .replace(Regex("\"fontSp\":\\s*-?[0-9.]+"), "\"fontSp\": 400.0")
            .replace(Regex("\"bgAlpha\":\\s*-?[0-9]+"), "\"bgAlpha\": 9999")
            .replace(Regex("\"fillMarginDp\":\\s*-?[0-9]+"), "\"fillMarginDp\": -5")
        val decoded = decodeOk(tampered)

        assertEquals("字号必须夹回设置页的区间", 34f, decoded.backup.widget.fontSp, 0.001f)
        assertEquals("透明度必须夹回 0..100", 100, decoded.backup.widget.bgAlpha)
        assertEquals("余量必须夹回 0..20", 0, decoded.backup.widget.fillMarginDp)
    }

    @Test
    fun encodeThenDecode_keepsAMissingCertificatePinAsMissing() {
        // JSONObject.NULL 在 Android 的 optString 下会变成 "null" 这个字符串，
        // 若不显式判空，一份"没有指纹"的备份会被恢复成"指纹 = null"，
        // 之后任何自签名证书都会被这条假指纹判成不匹配。
        val decoded = decodeOk(encode(sample(sync = sample().sync?.copy(tlsPinSha256 = null))))
        assertNull("没有指纹就必须恢复成没有指纹", decoded.backup.sync?.tlsPinSha256)
    }

    // ------------------------------------------------------------ 与设备状态的对接口

    @Test
    fun snapshot_readsTheCurrentDeviceState() {
        runBlocking {
            assertTrue(ContentStore.write("当前设备上的内容"))
            app.prefs.edit()
                .putFloat(WidgetSettings.KEY_FONT_SIZE, 22f)
                .putInt(WidgetSettings.KEY_TEXT_COLOR, Color.GREEN)
                .putInt(WidgetSettings.KEY_BG_COLOR, Color.BLUE)
                .putInt(WidgetSettings.KEY_BG_ALPHA, 40)
                .putInt(WidgetSettings.KEY_FILL_MARGIN_DP, 9)
                .putInt(EditorSettings.KEY_LIGHT_BG, Color.LTGRAY)
                .putInt(EditorSettings.KEY_LIGHT_TEXT, Color.RED)
                .putInt(EditorSettings.KEY_DARK_BG, Color.DKGRAY)
                .putInt(EditorSettings.KEY_DARK_TEXT, Color.CYAN)
                .apply()
            SyncSettings.saveConfig(
                app,
                SyncConfig("https://dav.example.com/notes/", "memo.txt", "u", "p")
            )
            SyncSettings.setEnabled(app, true)
            SyncSettings.setTlsPin(app, "AA:BB")

            val snapshot = BackupStore.snapshot(app)

            assertEquals("当前设备上的内容", snapshot.content)
            assertEquals(22f, snapshot.widget.fontSp, 0.001f)
            assertEquals(Color.GREEN, snapshot.widget.textColor)
            assertEquals(9, snapshot.widget.fillMarginDp)
            assertEquals(Color.LTGRAY, snapshot.editor.lightBg)
            assertEquals(Color.CYAN, snapshot.editor.darkText)
            assertEquals(true, snapshot.sync?.enabled)
            assertEquals("https://dav.example.com/notes/", snapshot.sync?.baseUrl)
            assertEquals("memo.txt", snapshot.sync?.fileName)
            assertEquals("AA:BB", snapshot.sync?.tlsPinSha256)
        }
    }

    @Test
    fun restore_overwritesTheTextAndEverySetting() {
        runBlocking {
            // 本机现状（即将被备份覆盖）
            assertTrue(ContentStore.write("恢复前的旧内容"))
            SyncSettings.setEnabled(app, false)
            SyncSettings.setTlsPin(app, "OLD:PIN")
            SyncSettings.setPendingTlsPin(app, "PENDING:PIN")

            val backup = sample()
            assertTrue("恢复必须成功", BackupStore.restore(app, backup))

            assertEquals("正文必须变成备份里的内容", backup.content, ContentStore.read())
            assertEquals(18f, WidgetSettings.fontSp(app), 0.001f)
            assertEquals(Color.YELLOW, WidgetSettings.textColor(app))
            assertEquals(Color.BLACK, WidgetSettings.bgColor(app))
            assertEquals(70, WidgetSettings.bgAlpha(app))
            assertEquals(6, WidgetSettings.fillMarginDp(app))
            assertEquals(Color.WHITE, EditorSettings.lightBg(app))
            assertEquals(Color.BLACK, EditorSettings.darkBg(app))
            assertEquals(Color.WHITE, EditorSettings.darkText(app))

            assertTrue("同步开关必须跟着备份走", SyncSettings.enabled(app))
            val config = SyncSettings.config(app)
            assertNotNull("恢复后 WebDAV 配置必须可用", config)
            assertEquals("https://dav.example.com/dav/", config!!.baseUrl)
            assertEquals("note.txt", config.fileName)
            assertEquals("user", config.username)
            assertEquals("p@ss", config.password)
            assertEquals(
                "指纹必须跟着配置一起恢复（换服务器却留旧指纹会直接连不上）",
                "AB:CD",
                SyncSettings.tlsPin(app)
            )
            assertNull("待确认指纹是上一次失败的待办，换配置后必须清掉", SyncSettings.pendingTlsPin(app))
        }
    }

    @Test
    fun restore_withoutASyncSection_leavesTheWebdavConfigurationAlone() {
        runBlocking {
            SyncSettings.saveConfig(
                app,
                SyncConfig("https://keep.example.com/dav/", "keep.txt", "u", "p")
            )
            SyncSettings.setEnabled(app, true)

            assertTrue(BackupStore.restore(app, sample(sync = null)))

            assertEquals("备份没带同步段时不得清空已有的 WebDAV 配置", "keep.txt", SyncSettings.fileName(app))
            assertEquals("https://keep.example.com/dav/", SyncSettings.baseUrl(app))
            assertTrue(SyncSettings.enabled(app))
        }
    }
}
