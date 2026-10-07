package moe.hellowidget

import android.content.Context
import android.util.Log
import moe.hellowidget.MainActivity.Companion.prefs
import moe.hellowidget.sync.ConfigValidation
import moe.hellowidget.sync.SyncConfig
import moe.hellowidget.sync.SyncConfigValidator
import moe.hellowidget.sync.SyncSettings
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v8.2.0：**整机备份 / 换机迁移**的编解码与落盘。
 *
 * ## 为什么要有它
 * 这个应用的全部价值就是那一份文本。v7.x 出于隐私考虑把备份通道全部关掉
 * （`allowBackup=false` + 全域名排除），代价是**换机等于从零开始** —— 系统备份、换机助手
 * 都带不走它，用户只能手抄。用户的要求很明确：**数据安全优先于隐私**。
 * 因此现在有两条并行的离机通道：
 *  1. **系统通道**（零 UI）：`allowBackup="true"` + `backup_rules.xml` / `data_extraction_rules.xml`
 *     显式包含 `file` 与 `sharedpref`，由 Auto Backup / 换机助手负责搬运；
 *  2. **用户自己的文件通道**（本文件）：导出一个 JSON 文件，换机或重装后从它恢复。
 *     系统通道是否真的执行取决于 ROM、账号与用户设置，**用户无法验证**；
 *     这条通道看得见、摸得着，也是"备份"这个词在用户心里真正的意思。
 *
 * ## 备份什么、不备份什么
 * 备份：正文、外观（小组件 + 编辑器配色）、WebDAV 配置（含口令）、TOFU 证书指纹。
 * **不备份运行时状态**（上次上传哈希 / 时间戳 / 重试预算 / 诊断行）：那些是"本机发生过什么"
 * 的记录，跟着数据搬到新设备只会制造难以解释的耦合（例如把旧机器的上传哈希带过去）。
 * 代价只是新设备上多传一次 —— 上传本身是幂等的、每次都写新文件。
 *
 * ## 口令明文
 * 备份文件里 WebDAV 口令是**明文**（与它在 `hello_prefs` 里的存法一致）。
 * 用户已明确接受这一取舍，界面在按钮下方写明；不引入加密的理由是"加密密钥也得存在同一台设备上"，
 * 只会让恢复多一个可能失败、且无法自助排查的环节。
 */
internal object BackupCodec {

    private const val TAG = "BackupCodec"

    /** 备份文件的标识：防止把别的 JSON 当成备份导入 */
    const val APP_ID = "hellowidget"

    /** 备份格式版本。将来字段含义变化时 +1，旧版本拒绝导入比猜着读安全 */
    const val FORMAT_VERSION = 1

    // 外观数值的可接受区间 = 设置页滑杆的区间（导入是外部输入，必须夹紧，
    // 否则一个手改过的备份就能让编辑器字号变成 1000sp）
    private const val MIN_FONT_SP = 10f
    private const val MAX_FONT_SP = 34f
    private const val MIN_MARGIN_DP = 0
    private const val MAX_MARGIN_DP = 20
    private const val MIN_ALPHA = 0
    private const val MAX_ALPHA = 100

    /** 小组件外观 */
    data class WidgetAppearance(
        val fontSp: Float,
        val textColor: Int,
        val bgColor: Int,
        val bgAlpha: Int,
        val fillMarginDp: Int
    )

    /** 编辑器配色（浅色 / 深色各一套） */
    data class EditorColors(
        val lightBg: Int,
        val lightText: Int,
        val darkBg: Int,
        val darkText: Int
    )

    /** WebDAV 账户与配置（口令明文）；`enabled` 表示"备份时同步是开着的" */
    data class SyncAccount(
        val enabled: Boolean,
        val baseUrl: String,
        val fileName: String,
        val username: String,
        val password: String,
        val tlsPinSha256: String?
    )

    data class Backup(
        val content: String,
        val widget: WidgetAppearance,
        val editor: EditorColors,
        val sync: SyncAccount?
    )

    /** 导入失败的原因。用枚举而不是字符串：文案留在资源里，判定留在可单测的地方 */
    enum class BadReason {
        /** 不是 JSON（用户选错了文件，或文件被截断） */
        NOT_JSON,

        /** 是 JSON，但不是本应用的备份 */
        FOREIGN_APP,

        /** 缺少 / 非法的格式版本号 */
        BAD_FORMAT,

        /** 备份由更新版本的应用导出，本版本的代码读不懂它 */
        NEWER_FORMAT,

        /** 备份里没有正文（这是唯一不可缺的一段） */
        NO_CONTENT
    }

    sealed interface Decoded {
        /**
         * @param syncDropped 备份里的 WebDAV 段存在但**不合法**，恢复时会跳过它
         *   （正文与外观照常恢复 —— 宁可少恢复一段设置，也不能让用户拿不回自己的文本）
         */
        data class Ok(val backup: Backup, val syncDropped: Boolean) : Decoded

        data class Bad(val reason: BadReason) : Decoded
    }

    // ------------------------------------------------------------------ 编码

    fun encode(backup: Backup, exportedAt: Long): String {
        val root = JSONObject()
            .put("app", APP_ID)
            .put("format", FORMAT_VERSION)
            .put("exportedAt", exportedAt)
            .put("content", backup.content)
            .put(
                "widget",
                JSONObject()
                    .put("fontSp", backup.widget.fontSp.toDouble())
                    .put("textColor", backup.widget.textColor)
                    .put("bgColor", backup.widget.bgColor)
                    .put("bgAlpha", backup.widget.bgAlpha)
                    .put("fillMarginDp", backup.widget.fillMarginDp)
            )
            .put(
                "editor",
                JSONObject()
                    .put("lightBg", backup.editor.lightBg)
                    .put("lightText", backup.editor.lightText)
                    .put("darkBg", backup.editor.darkBg)
                    .put("darkText", backup.editor.darkText)
            )
        backup.sync?.let { sync ->
            root.put(
                "sync",
                JSONObject()
                    .put("enabled", sync.enabled)
                    .put("baseUrl", sync.baseUrl)
                    .put("fileName", sync.fileName)
                    .put("username", sync.username)
                    .put("password", sync.password)
                    // JSONObject.put(key, null) 会**删掉**这个键，所以用 JSONObject.NULL 显式表达"没有指纹"
                    .put(
                        "tlsPinSha256",
                        sync.tlsPinSha256?.let { it as Any } ?: JSONObject.NULL
                    )
            )
        }
        // 缩进 2 空格：备份文件是给人看的（也能直接手工核对/抢救内容）
        return root.toString(2)
    }

    // ------------------------------------------------------------------ 解码

    fun decode(text: String): Decoded {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            Log.w(TAG, "备份文件不是合法 JSON", e)
            return Decoded.Bad(BadReason.NOT_JSON)
        }
        val app = root.optString("app", "")
        if (app != APP_ID) return Decoded.Bad(BadReason.FOREIGN_APP)
        val format = root.optInt("format", 0)
        if (format < 1) return Decoded.Bad(BadReason.BAD_FORMAT)
        if (format > FORMAT_VERSION) return Decoded.Bad(BadReason.NEWER_FORMAT)
        if (!root.has("content") || root.isNull("content")) return Decoded.Bad(BadReason.NO_CONTENT)

        val widget = root.optJSONObject("widget").let { obj ->
            WidgetAppearance(
                fontSp = (obj?.optDouble("fontSp", WidgetSettings.DEFAULT_FONT_SP.toDouble())
                    ?: WidgetSettings.DEFAULT_FONT_SP.toDouble())
                    .toFloat().coerceIn(MIN_FONT_SP, MAX_FONT_SP),
                textColor = obj?.optInt("textColor", WidgetSettings.DEFAULT_TEXT_COLOR)
                    ?: WidgetSettings.DEFAULT_TEXT_COLOR,
                bgColor = obj?.optInt("bgColor", WidgetSettings.DEFAULT_BG_COLOR)
                    ?: WidgetSettings.DEFAULT_BG_COLOR,
                bgAlpha = (obj?.optInt("bgAlpha", WidgetSettings.DEFAULT_BG_ALPHA)
                    ?: WidgetSettings.DEFAULT_BG_ALPHA).coerceIn(MIN_ALPHA, MAX_ALPHA),
                fillMarginDp = (obj?.optInt("fillMarginDp", WidgetSettings.DEFAULT_FILL_MARGIN_DP)
                    ?: WidgetSettings.DEFAULT_FILL_MARGIN_DP).coerceIn(MIN_MARGIN_DP, MAX_MARGIN_DP)
            )
        }
        val editor = root.optJSONObject("editor").let { obj ->
            EditorColors(
                lightBg = obj?.optInt("lightBg", EditorSettings.DEFAULT_LIGHT_BG)
                    ?: EditorSettings.DEFAULT_LIGHT_BG,
                lightText = obj?.optInt("lightText", EditorSettings.DEFAULT_LIGHT_TEXT)
                    ?: EditorSettings.DEFAULT_LIGHT_TEXT,
                darkBg = obj?.optInt("darkBg", EditorSettings.DEFAULT_DARK_BG)
                    ?: EditorSettings.DEFAULT_DARK_BG,
                darkText = obj?.optInt("darkText", EditorSettings.DEFAULT_DARK_TEXT)
                    ?: EditorSettings.DEFAULT_DARK_TEXT
            )
        }

        val syncObject = root.optJSONObject("sync")
        if (syncObject == null) {
            // 备份里没有 WebDAV 段（老版本导出 / 用户没配过同步）：恢复时*不动*现有设置，
            // 而不是把它清空 —— "没带这一段"不等于"用户没有同步"
            return Decoded.Ok(Backup(root.optString("content"), widget, editor, null), syncDropped = false)
        }
        val rawSync = SyncAccount(
            enabled = syncObject.optBoolean("enabled", false),
            baseUrl = syncObject.optString("baseUrl", ""),
            fileName = syncObject.optString("fileName", SyncSettings.DEFAULT_FILE_NAME),
            username = syncObject.optString("username", ""),
            password = syncObject.optString("password", ""),
            // 注意：Android 的 `optString` 遇到 JSONObject.NULL 会返回字面量 "null"
            // （JSON.toString(NULL) == "null"），因此必须**先判 isNull** ——
            // 否则没有指纹的备份会被恢复成"指纹 = null"，之后任何自签名证书都验不过。
            tlsPinSha256 = if (syncObject.isNull("tlsPinSha256")) {
                null
            } else {
                syncObject.optString("tlsPinSha256", "").takeIf { it.isNotBlank() }
            }
        )
        // 地址一律重新过一遍校验：备份文件是外部输入，恢复时绝不能把非法地址写进配置
        val validation = SyncConfigValidator.validate(
            baseUrlRaw = rawSync.baseUrl,
            fileNameRaw = rawSync.fileName,
            username = rawSync.username,
            password = rawSync.password,
            tlsPinSha256 = rawSync.tlsPinSha256
        )
        return when (validation) {
            is ConfigValidation.Ok -> Decoded.Ok(
                Backup(
                    content = root.optString("content"),
                    widget = widget,
                    editor = editor,
                    sync = rawSync.copy(
                        baseUrl = validation.config.baseUrl,
                        fileName = validation.config.fileName
                    )
                ),
                syncDropped = false
            )

            is ConfigValidation.Invalid -> {
                Log.w(TAG, "备份里的 WebDAV 配置不合法（${validation.error}），本次跳过同步设置")
                Decoded.Ok(
                    Backup(root.optString("content"), widget, editor, null),
                    syncDropped = true
                )
            }
        }
    }

    /** 导出文件名（SAF 默认名）：`hellowidget-backup-20260101-1200.json` */
    fun suggestedFileName(now: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "hellowidget-backup-$stamp.json"
    }
}

/**
 * 把 [BackupCodec.Backup] 读出来 / 写回去。
 *
 * 读：[ContentStore] + `hello_prefs`（外观走 [WidgetSettings]/[EditorSettings] 的键，同步走 [SyncSettings]）。
 * 写：**先写正文、成功后才动设置** —— 正文写盘失败时什么都不改，用户手上的数据保持原样。
 */
internal object BackupStore {

    private const val TAG = "BackupStore"

    /** 读取当前设备上的全部可迁移数据（正文 + 外观 + 编辑器配色 + WebDAV 配置） */
    suspend fun snapshot(context: Context): BackupCodec.Backup {
        val app = context.applicationContext
        val normalized = SyncSettings.config(app)
        return BackupCodec.Backup(
            content = ContentStore.read(),
            widget = BackupCodec.WidgetAppearance(
                fontSp = WidgetSettings.fontSp(app),
                textColor = WidgetSettings.textColor(app),
                bgColor = WidgetSettings.bgColor(app),
                bgAlpha = WidgetSettings.bgAlpha(app),
                fillMarginDp = WidgetSettings.fillMarginDp(app)
            ),
            editor = BackupCodec.EditorColors(
                lightBg = EditorSettings.lightBg(app),
                lightText = EditorSettings.lightText(app),
                darkBg = EditorSettings.darkBg(app),
                darkText = EditorSettings.darkText(app)
            ),
            sync = BackupCodec.SyncAccount(
                enabled = SyncSettings.enabled(app),
                // 配置合法时导出归一化后的地址（用户看到什么就存什么），否则原样带走
                baseUrl = normalized?.baseUrl ?: SyncSettings.baseUrl(app),
                fileName = normalized?.fileName ?: SyncSettings.fileName(app),
                username = SyncSettings.username(app),
                password = SyncSettings.password(app),
                tlsPinSha256 = SyncSettings.tlsPin(app)
            )
        )
    }

    /**
     * 用备份覆盖本机数据。
     *
     * @return true = 正文与设置都已写入；false = **正文**写盘失败（此时一个设置都没改）
     */
    suspend fun restore(context: Context, backup: BackupCodec.Backup): Boolean {
        val app = context.applicationContext
        if (!ContentStore.write(backup.content)) {
            Log.w(TAG, "恢复失败：正文未能写盘，设置保持不变")
            return false
        }
        app.prefs.edit()
            .putFloat(WidgetSettings.KEY_FONT_SIZE, backup.widget.fontSp)
            .putInt(WidgetSettings.KEY_TEXT_COLOR, backup.widget.textColor)
            .putInt(WidgetSettings.KEY_BG_COLOR, backup.widget.bgColor)
            .putInt(WidgetSettings.KEY_BG_ALPHA, backup.widget.bgAlpha)
            .putInt(WidgetSettings.KEY_FILL_MARGIN_DP, backup.widget.fillMarginDp)
            .putInt(EditorSettings.KEY_LIGHT_BG, backup.editor.lightBg)
            .putInt(EditorSettings.KEY_LIGHT_TEXT, backup.editor.lightText)
            .putInt(EditorSettings.KEY_DARK_BG, backup.editor.darkBg)
            .putInt(EditorSettings.KEY_DARK_TEXT, backup.editor.darkText)
            .apply()
        backup.sync?.let { sync ->
            SyncSettings.saveConfig(
                app,
                SyncConfig(
                    baseUrl = sync.baseUrl,
                    fileName = sync.fileName,
                    username = sync.username,
                    password = sync.password,
                    tlsPinSha256 = sync.tlsPinSha256
                )
            )
            SyncSettings.setEnabled(app, sync.enabled)
            // 指纹必须跟着配置一起恢复：换到另一台服务器却留着旧指纹，连接会直接失败
            SyncSettings.setTlsPin(app, sync.tlsPinSha256)
            // 待确认指纹是"上一次连接失败留下的待办"，换了配置就无意义了
            SyncSettings.setPendingTlsPin(app, null)
        }
        // 外观已经变了：桌面小组件立刻按新配色重绘
        TextWidgetProvider.updateWidgets(app)
        return true
    }
}
