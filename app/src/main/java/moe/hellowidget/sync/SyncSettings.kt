package moe.hellowidget.sync

import android.content.Context
import moe.hellowidget.MainActivity.Companion.prefs

/**
 * WebDAV 同步的全部持久化状态（沿用应用已有的 `hello_prefs`）。
 *
 * 安全说明：WebDAV 口令以明文存进应用私有 SharedPreferences。这是刻意的取舍 ——
 *  - 该 prefs 文件已被 `res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml`
 *    **完全排除**在云备份与换机迁移之外，不会随系统备份离开设备；
 *  - 在未 root 的设备上，应用私有目录其他应用读不到；真正的威胁模型（拿到已解锁设备的人）
 *    下，本机加密密钥同样在设备里，边际收益很小；
 *  - 界面明确建议使用 WebDAV 侧的「应用专用密码」，让这份凭据可以随时单独吊销。
 *
 * ## v7.5：只剩「本地状态」
 * 因为同步是纯单向上传（不读云端、不比对、无冲突），这里不再保存云端的任何元数据
 * （ETag / 修改时间 / 大小）与冲突状态，只保留：配置、上次成功上传的内容哈希、结果与时间。
 *
 * 除 URL / 文件名校验（在 [SyncConfigValidator]）外，本文件只做读写，不含业务判断。
 */
object SyncSettings {

    const val KEY_ENABLED = "sync_enabled"
    const val KEY_BASE_URL = "sync_base_url"
    const val KEY_FILE_NAME = "sync_file_name"
    const val KEY_USERNAME = "sync_username"
    const val KEY_PASSWORD = "sync_password"
    const val KEY_TLS_PIN = "sync_tls_pin_sha256"
    const val KEY_TLS_PENDING_PIN = "sync_tls_pending_pin"

    const val KEY_LAST_RESULT = "sync_last_result"
    const val KEY_LAST_ERROR = "sync_last_error"
    const val KEY_LAST_ATTEMPT_AT = "sync_last_attempt_at"
    const val KEY_LAST_SUCCESS_AT = "sync_last_success_at"
    const val KEY_LAST_UPLOADED_HASH = "sync_last_uploaded_hash"
    const val KEY_LAST_UPLOADED_TS = "sync_last_uploaded_ts"
    const val KEY_RETRY_ATTEMPTS = "sync_retry_attempts"

    /**
     * v8.0.1（D3）：上一次「成功」到底是**真的传上去了**，还是「本地没改动、一个请求都没发」。
     *
     * 这两件事都会写 [KEY_LAST_RESULT] = success，于是设置页那句「上次同步成功」**无法**证明
     * 内容到了云端 —— 实测排查时正是它造成了一次误判。落盘这个标志后，
     * 页面才能明确区分「上次已上传」与「上次无改动、无需上传」。
     */
    const val KEY_LAST_SUCCESS_UPLOADED = "sync_last_success_uploaded"

    /**
     * v8.0.1：「最后一次自动上传是怎么启动的」的原始证据。
     *
     * 存成一段 ASCII token（例如 `fgs-rejected:ForegroundServiceStartNotAllowedException`
     * 或 `fgs-foreground:shortService`），与 logcat 里的措辞完全一致 ——
     * 这样用户不用抓日志，在同步设置页拍一张截图就能说清"是没启动、还是启动了没传上去"。
     */
    const val KEY_LAUNCH_NOTE = "sync_last_launch_note"
    const val KEY_LAUNCH_NOTE_AT = "sync_last_launch_note_at"

    const val DEFAULT_FILE_NAME = "note.txt"

    // ------------------------------------------------------------ 基本配置

    fun enabled(context: Context): Boolean = context.prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun baseUrl(context: Context): String = context.prefs.getString(KEY_BASE_URL, "") ?: ""

    fun fileName(context: Context): String =
        context.prefs.getString(KEY_FILE_NAME, DEFAULT_FILE_NAME) ?: DEFAULT_FILE_NAME

    fun username(context: Context): String = context.prefs.getString(KEY_USERNAME, "") ?: ""

    fun password(context: Context): String = context.prefs.getString(KEY_PASSWORD, "") ?: ""

    fun saveConfig(context: Context, config: SyncConfig) {
        context.prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_FILE_NAME, config.fileName)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .apply()
    }

    /** 当前配置；未配置或配置非法时返回 null（调用方据此跳过同步） */
    fun config(context: Context): SyncConfig? {
        val validation = SyncConfigValidator.validate(
            baseUrlRaw = baseUrl(context),
            fileNameRaw = fileName(context),
            username = username(context),
            password = password(context),
            tlsPinSha256 = tlsPin(context)
        )
        return (validation as? ConfigValidation.Ok)?.config
    }

    // ------------------------------------------------------------ 证书指纹（TOFU）

    fun tlsPin(context: Context): String? =
        context.prefs.getString(KEY_TLS_PIN, null)?.takeIf { it.isNotBlank() }

    fun setTlsPin(context: Context, fingerprint: String?) {
        context.prefs.edit().apply {
            if (fingerprint.isNullOrBlank()) remove(KEY_TLS_PIN) else putString(KEY_TLS_PIN, fingerprint)
        }.apply()
    }

    fun pendingTlsPin(context: Context): String? =
        context.prefs.getString(KEY_TLS_PENDING_PIN, null)?.takeIf { it.isNotBlank() }

    fun setPendingTlsPin(context: Context, fingerprint: String?) {
        context.prefs.edit().apply {
            if (fingerprint.isNullOrBlank()) remove(KEY_TLS_PENDING_PIN)
            else putString(KEY_TLS_PENDING_PIN, fingerprint)
        }.apply()
    }

    // ------------------------------------------------------------ 同步状态

    fun lastResult(context: Context): String =
        context.prefs.getString(KEY_LAST_RESULT, SyncEngine.RESULT_NEVER) ?: SyncEngine.RESULT_NEVER

    fun lastError(context: Context): String = context.prefs.getString(KEY_LAST_ERROR, "") ?: ""

    fun lastAttemptAt(context: Context): Long = context.prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L)

    fun lastSuccessAt(context: Context): Long = context.prefs.getLong(KEY_LAST_SUCCESS_AT, 0L)

    /** 上次成功上传所用的 unix 秒时间戳（0 = 从未上传过）—— 用于保证文件名单调递增 */
    fun lastUploadedTs(context: Context): Long = context.prefs.getLong(KEY_LAST_UPLOADED_TS, 0L)

    /** 上次**成功上传**的内容哈希；null = 本机从未上传过 */
    fun lastUploadedHash(context: Context): String? =
        context.prefs.getString(KEY_LAST_UPLOADED_HASH, null)?.takeIf { it.isNotBlank() }

    fun setLastAttemptAt(context: Context, at: Long) {
        context.prefs.edit().putLong(KEY_LAST_ATTEMPT_AT, at).apply()
    }

    /**
     * 本次「还有内容没传上去」已经自动重试过几次。
     * 上限见 [SyncRetry.MAX_ATTEMPTS]：用完就停，不做长期后台驻留 —— 剩下的交给橙点与用户。
     */
    fun retryAttempts(context: Context): Int = context.prefs.getInt(KEY_RETRY_ATTEMPTS, 0)

    fun setRetryAttempts(context: Context, attempts: Int) {
        context.prefs.edit().putInt(KEY_RETRY_ATTEMPTS, attempts).apply()
    }

    /**
     * 上一次自动上传**是怎么启动的**（或为什么没能启动）。
     *
     * 每次同步启动时覆盖写，空串 = 还没有过记录。
     * 只在启动通道上调用，不参与任何业务判断 —— 它纯粹是给人看的证据。
     */
    fun launchNote(context: Context): String =
        context.prefs.getString(KEY_LAUNCH_NOTE, "") ?: ""

    fun launchNoteAt(context: Context): Long = context.prefs.getLong(KEY_LAUNCH_NOTE_AT, 0L)

    fun setLaunchNote(context: Context, note: String) {
        context.prefs.edit()
            .putString(KEY_LAUNCH_NOTE, note)
            .putLong(KEY_LAUNCH_NOTE_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 记录一次成功。`uploadedHash` 是本次同步结束时本地内容的哈希 ——
     * 它既是下一次「本地有没有变」的基准，也是「确认无需上传」时的基准；
     * `uploadedTs` 是这次实际上传用的 unix 秒时间戳（没有上传时传上一次的值）。
     *
     * @param uploaded 这一次是否**真的**发生了上传。`false` = 本地自上次成功后没改动、
     *   一个请求都没发（见 [KEY_LAST_SUCCESS_UPLOADED] 的说明）。
     *   默认 `true`，因为绝大多数调用点是"确实传上去了"。
     */
    fun recordSuccess(
        context: Context,
        uploadedHash: String,
        uploadedTs: Long,
        uploaded: Boolean = true
    ) {
        context.prefs.edit()
            .putString(KEY_LAST_RESULT, SyncEngine.RESULT_SUCCESS)
            .putString(KEY_LAST_ERROR, "")
            .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
            .putString(KEY_LAST_UPLOADED_HASH, uploadedHash)
            .putLong(KEY_LAST_UPLOADED_TS, uploadedTs)
            .putBoolean(KEY_LAST_SUCCESS_UPLOADED, uploaded)
            .apply()
    }

    /** 上一次成功是否真的上传了；`null` = 从未成功过（或升级前的老数据） */
    fun lastSuccessUploaded(context: Context): Boolean? =
        if (context.prefs.contains(KEY_LAST_SUCCESS_UPLOADED)) {
            context.prefs.getBoolean(KEY_LAST_SUCCESS_UPLOADED, false)
        } else {
            null
        }

    fun recordFailure(context: Context, error: WebDavError) {
        context.prefs.edit()
            .putString(KEY_LAST_RESULT, SyncEngine.RESULT_FAILED)
            .putString(KEY_LAST_ERROR, error.name)
            .apply()
    }

    /** 仅用于单测/调试：清空全部同步状态（不动用户填的地址与口令） */
    fun resetRuntimeState(context: Context) {
        context.prefs.edit()
            .remove(KEY_LAST_RESULT)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_LAST_ATTEMPT_AT)
            .remove(KEY_LAST_SUCCESS_AT)
            .remove(KEY_LAST_UPLOADED_HASH)
            .remove(KEY_LAST_UPLOADED_TS)
            .remove(KEY_RETRY_ATTEMPTS)
            .remove(KEY_LAST_SUCCESS_UPLOADED)
            .remove(KEY_LAUNCH_NOTE)
            .remove(KEY_LAUNCH_NOTE_AT)
            .apply()
    }
}
