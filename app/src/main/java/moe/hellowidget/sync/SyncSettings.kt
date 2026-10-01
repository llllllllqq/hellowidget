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
     * 记录一次成功。`uploadedHash` 是本次同步结束时本地内容的哈希 ——
     * 它既是下一次「本地有没有变」的基准，也是「确认无需上传」时的基准；
     * `uploadedTs` 是这次实际上传用的 unix 秒时间戳（没有上传时传上一次的值）。
     */
    fun recordSuccess(context: Context, uploadedHash: String, uploadedTs: Long) {
        context.prefs.edit()
            .putString(KEY_LAST_RESULT, SyncEngine.RESULT_SUCCESS)
            .putString(KEY_LAST_ERROR, "")
            .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
            .putString(KEY_LAST_UPLOADED_HASH, uploadedHash)
            .putLong(KEY_LAST_UPLOADED_TS, uploadedTs)
            .apply()
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
            .apply()
    }
}
