package moe.hellowidget

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import moe.hellowidget.databinding.ActivitySyncBinding
import moe.hellowidget.sync.ConfigError
import moe.hellowidget.sync.ConfigValidation
import moe.hellowidget.sync.SyncConfigValidator
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncErrorText
import moe.hellowidget.sync.SyncLauncher
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncRetry
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import java.text.DateFormat
import java.util.Date

/**
 * WebDAV 同步设置页：地址 / 文件名 / 凭据 / 开关，以及同步状态、明文警告与证书指纹。
 *
 * 交互约定：
 *  - 「立即同步」会先把表单落盘再触发（用户改完地址直接点同步是最自然的操作）；
 *  - 同步是**纯单向上传**：本机内容一变就整份覆盖云端，不读取、不比对云端状态，
 *    因此这个页面没有也不需要冲突处理；
 *  - 自签名证书必须由用户在看到指纹后确认，确认结果按指纹固定（TOFU），
 *    指纹变化会再次要求确认 —— 不是「无条件信任所有证书」。
 */
class SyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncBinding

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也能同步，只是没有通知栏进度 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        title = getString(R.string.sync_title)

        loadIntoFields()

        // v7.7：保存设置成功不再弹「设置已保存」toast（用户要求删除全部「已保存」提示）。
        // 保存结果依然可见：下方状态区会立刻重绘（状态 / 实际写入地址 / 上次结果）。
        binding.syncSave.setOnClickListener { saveFromFields() }
        binding.syncNow.setOnClickListener {
            if (saveFromFields()) {
                SyncLauncher.request(this, SyncTrigger.MANUAL)
                renderStatus()
            }
        }
        binding.syncTrust.setOnClickListener { confirmPendingFingerprint() }
        // 监听必须在 loadIntoFields() 之后挂上，否则恢复开关状态时会误触发权限申请
        binding.syncEnable.setOnCheckedChangeListener { _, checked ->
            if (checked) requestNotificationPermission()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncManager.status.collect { renderStatus() }
            }
        }
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    /**
     * 消费系统栏、刘海与输入法 insets。
     *
     * 输入法部分与编辑页同一套写法，但**必须配合布局外的 FrameLayout 根**才有效：
     * targetSdk 35 边到边之后窗口不再为键盘让位，若把 ime 高度加在 ScrollView 自己的
     * padding 上，滚动内容的 padding 不会让可视区域变矮，键盘依旧盖住视口底部；
     * 只有让 ScrollView 的高度真正减掉 ime 高度，聚焦的输入框才会被滚到键盘之上。
     */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // API 30 以下 ime() 由 systemWindowInsets 推导（含导航栏高度），取 max 避免重复叠加
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, imeBottom)
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    // ------------------------------------------------------------ 表单

    private fun loadIntoFields() {
        binding.syncEnable.isChecked = SyncSettings.enabled(this)
        binding.syncServer.setText(SyncSettings.baseUrl(this))
        binding.syncFileName.setText(SyncSettings.fileName(this))
        binding.syncUsername.setText(SyncSettings.username(this))
        binding.syncPassword.setText(SyncSettings.password(this))
    }

    private fun saveFromFields(): Boolean {
        val urlRaw = binding.syncServer.text.toString()
        val nameRaw = binding.syncFileName.text.toString().ifBlank { SyncSettings.DEFAULT_FILE_NAME }
        val validation = SyncConfigValidator.validate(
            baseUrlRaw = urlRaw,
            fileNameRaw = nameRaw,
            username = binding.syncUsername.text.toString(),
            password = binding.syncPassword.text.toString(),
            tlsPinSha256 = SyncSettings.tlsPin(this)
        )
        return when (validation) {
            is ConfigValidation.Invalid -> {
                toast(validationText(validation))
                false
            }

            is ConfigValidation.Ok -> {
                SyncSettings.saveConfig(this, validation.config)
                SyncSettings.setEnabled(this, binding.syncEnable.isChecked)
                // 把归一化后的地址写回输入框：用户能立刻看到「实际会用的地址」
                binding.syncServer.setText(validation.config.baseUrl)
                if (binding.syncEnable.isChecked) requestNotificationPermission()
                renderStatus()
                true
            }
        }
    }

    private fun validationText(invalid: ConfigValidation.Invalid): String = when (invalid.error) {
        ConfigError.EMPTY_URL -> getString(R.string.sync_error_empty_url)
        ConfigError.INVALID_URL -> getString(R.string.sync_error_invalid_url, invalid.detail)
        ConfigError.UNSUPPORTED_SCHEME -> getString(R.string.sync_error_scheme, invalid.detail)
        ConfigError.MISSING_HOST -> getString(R.string.sync_error_missing_host)
        ConfigError.URL_HAS_QUERY -> getString(R.string.sync_error_url_query)
        ConfigError.EMPTY_FILE_NAME -> getString(R.string.sync_error_empty_file_name)
        ConfigError.INVALID_FILE_NAME -> getString(R.string.sync_error_invalid_file_name, invalid.detail)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (SyncNotifier.hasNotificationPermission(this)) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------ 状态渲染

    private fun renderStatus() {
        val config = SyncSettings.config(this)
        val enabled = SyncSettings.enabled(this)
        val builder = StringBuilder()
        builder.append(
            getString(if (enabled) R.string.sync_state_enabled else R.string.sync_state_disabled)
        )
        if (config != null) {
            builder.append('\n').append(
                getString(R.string.sync_effective_url, config.baseUrl + config.historyFilePattern)
            )
            builder.append('\n').append(getString(R.string.sync_history_note))
        }
        builder.append('\n').append(lastResultLine())
        attemptLine()?.let { builder.append('\n').append(it) }
        retryLine()?.let { builder.append('\n').append(it) }
        SyncManager.status.value.let { live ->
            when (live) {
                is SyncStatus.Running -> builder.append('\n')
                    .append(getString(R.string.sync_status_running))
                is SyncStatus.Skipped -> builder.append('\n')
                    .append(getString(skipText(live.reason)))
                else -> Unit
            }
        }
        binding.syncStatus.text = builder.toString()

        val cleartext = config?.isCleartext == true
        binding.syncWarning.visibility = if (cleartext) View.VISIBLE else View.GONE
        if (cleartext) binding.syncWarning.text = getString(R.string.sync_cleartext_warning)

        renderTlsState()
    }

    private fun lastResultLine(): String {
        val successAt = SyncSettings.lastSuccessAt(this)
        val attemptAt = SyncSettings.lastAttemptAt(this)
        return when (SyncSettings.lastResult(this)) {
            SyncEngine.RESULT_SUCCESS ->
                getString(R.string.sync_status_success, formatTime(successAt))
            SyncEngine.RESULT_FAILED ->
                getString(
                    R.string.sync_status_failed,
                    formatTime(attemptAt),
                    SyncErrorText.ofName(this, SyncSettings.lastError(this)) ?: ""
                )
            else -> getString(R.string.sync_status_never)
        }
    }

    /**
     * v7.9：最后一次**尝试**（无论成败）的时间。
     *
     * 状态行原本只说「上次结果」，而排查「保存了却没上传」时最需要的恰恰是
     * 「最后一次尝试发生在什么时候」—— 配上 [retryLine] 用户拍一张截图就够了。
     */
    private fun attemptLine(): String? {
        val attemptAt = SyncSettings.lastAttemptAt(this)
        if (attemptAt <= 0) return null
        return getString(R.string.sync_last_attempt, formatTime(attemptAt))
    }

    /**
     * v7.9：系统级重试任务的状态。
     * 「已排队」= 本地还有没传上去的改动，系统会在有网络时自动重试；
     * 「已用完」= 自动重试用满 [SyncRetry.MAX_ATTEMPTS] 次后停下（不做长期后台驻留），
     * 需要用户点「立即同步」或再次保存才会重新排队 —— 这条提示必须说清楚，不能让用户以为已经传上去了。
     */
    private fun retryLine(): String? {
        if (!SyncSettings.enabled(this) || SyncSettings.config(this) == null) return null
        return when {
            SyncRetry.isScheduled(this) -> getString(R.string.sync_retry_queued)
            SyncSettings.retryAttempts(this) >= SyncRetry.MAX_ATTEMPTS ->
                getString(R.string.sync_retry_exhausted, SyncRetry.MAX_ATTEMPTS)
            else -> getString(R.string.sync_retry_none)
        }
    }

    private fun skipText(reason: moe.hellowidget.sync.SkipReason): Int = when (reason) {
        moe.hellowidget.sync.SkipReason.NOT_ENABLED -> R.string.sync_skip_disabled
        moe.hellowidget.sync.SkipReason.NOT_CONFIGURED -> R.string.sync_skip_not_configured
        moe.hellowidget.sync.SkipReason.THROTTLED -> R.string.sync_skip_throttled
    }

    private fun renderTlsState() {
        val pending = SyncSettings.pendingTlsPin(this)
        val trusted = SyncSettings.tlsPin(this)
        when {
            pending != null -> {
                binding.syncTlsInfo.visibility = View.VISIBLE
                binding.syncTlsInfo.text = getString(R.string.sync_tls_pending, pending)
                binding.syncTrust.visibility = View.VISIBLE
            }

            trusted != null -> {
                binding.syncTlsInfo.visibility = View.VISIBLE
                binding.syncTlsInfo.text = getString(R.string.sync_tls_trusted, trusted)
                binding.syncTrust.visibility = View.GONE
            }

            else -> {
                binding.syncTlsInfo.visibility = View.GONE
                binding.syncTrust.visibility = View.GONE
            }
        }
    }

    private fun confirmPendingFingerprint() {
        val pending = SyncSettings.pendingTlsPin(this) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_trust_dialog_title)
            .setMessage(getString(R.string.sync_trust_dialog_message, pending))
            .setPositiveButton(R.string.sync_trust_confirm) { _, _ ->
                SyncSettings.setTlsPin(this, pending)
                SyncSettings.setPendingTlsPin(this, null)
                toast(getString(R.string.sync_trusted))
                renderStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatTime(at: Long): String =
        if (at <= 0) getString(R.string.sync_time_never)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SyncActivity::class.java)
    }
}
