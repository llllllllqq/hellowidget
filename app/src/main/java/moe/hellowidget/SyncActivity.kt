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
import moe.hellowidget.sync.ConflictChoice
import moe.hellowidget.sync.SyncConfigValidator
import moe.hellowidget.sync.SyncEngine
import moe.hellowidget.sync.SyncErrorText
import moe.hellowidget.sync.SyncLauncher
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncNotifier
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import java.text.DateFormat
import java.util.Date

/**
 * WebDAV 同步设置页：地址 / 文件名 / 凭据 / 开关，以及同步状态、明文警告、证书指纹与冲突处理。
 *
 * 交互约定：
 *  - 「立即同步」会先把表单落盘再触发（用户改完地址直接点同步是最自然的操作）；
 *  - 冲突弹窗只在有待处理冲突时出现，选「稍后」本次不再打扰；
 *  - 自签名证书必须由用户在看到指纹后确认，确认结果按指纹固定（TOFU），
 *    指纹变化会再次要求确认 —— 不是「无条件信任所有证书」。
 */
class SyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncBinding

    /** 冲突弹窗每次进入页面只弹一次，选「稍后」后不再反复打扰 */
    private var conflictDialogShown = false

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

        binding.syncSave.setOnClickListener {
            if (saveFromFields()) toast(getString(R.string.sync_saved))
        }
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
            builder.append('\n').append(getString(R.string.sync_effective_url, config.fileUrl))
        }
        builder.append('\n').append(lastResultLine())
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
        renderConflictState()
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
            SyncEngine.RESULT_CONFLICT -> getString(R.string.sync_status_conflict)
            else -> getString(R.string.sync_status_never)
        }
    }

    private fun skipText(reason: moe.hellowidget.sync.SkipReason): Int = when (reason) {
        moe.hellowidget.sync.SkipReason.NOT_ENABLED -> R.string.sync_skip_disabled
        moe.hellowidget.sync.SkipReason.NOT_CONFIGURED -> R.string.sync_skip_not_configured
        moe.hellowidget.sync.SkipReason.THROTTLED -> R.string.sync_skip_throttled
        moe.hellowidget.sync.SkipReason.PENDING_CONFLICT -> R.string.sync_skip_conflict
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

    private fun renderConflictState() {
        if (!SyncSettings.pendingConflict(this)) {
            conflictDialogShown = false
            return
        }
        if (!conflictDialogShown) showConflictDialog()
    }

    private fun showConflictDialog() {
        if (conflictDialogShown || isFinishing || isDestroyed) return
        conflictDialogShown = true
        val fileName = SyncSettings.config(this)?.fileName ?: SyncSettings.fileName(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.sync_conflict_dialog_title)
            .setMessage(getString(R.string.sync_conflict_dialog_message, fileName))
            .setPositiveButton(R.string.sync_conflict_keep_local) { _, _ ->
                SyncLauncher.request(this, SyncTrigger.CONFLICT_RESOLVE, ConflictChoice.KEEP_LOCAL)
            }
            .setNeutralButton(R.string.sync_conflict_use_remote) { _, _ ->
                SyncLauncher.request(this, SyncTrigger.CONFLICT_RESOLVE, ConflictChoice.USE_REMOTE)
            }
            .setNegativeButton(R.string.sync_conflict_later, null)
            .show()
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
