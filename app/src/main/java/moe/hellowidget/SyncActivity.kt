package moe.hellowidget

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import moe.hellowidget.sync.SyncDiagnostics
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
 *    指纹变化会再次要求确认 —— 不是「无条件信任所有证书」；
 *  - 状态区（含 v8.0.1 起的诊断行）是**可复制的报告块**：长按选中，或点「复制全部」，
 *    见 [copyStatusToClipboard]。
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
        // v8.0.2：「复制全部」—— 与 sync_status 的长按选中复制等价，只是不需要用户去拖选择手柄
        binding.syncCopy.setOnClickListener { copyStatusToClipboard() }
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
        serverContactLine()?.let { builder.append('\n').append(it) }
        retryLine()?.let { builder.append('\n').append(it) }
        launchLine()?.let { builder.append('\n').append(it) }
        diagnosticsLine()?.let { builder.append('\n').append(it) }
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
        val contactAt = SyncSettings.lastServerContactAt(this)
        return when (SyncSettings.lastResult(this)) {
            SyncEngine.RESULT_SUCCESS -> {
                // v8.0.1（D3）：区分「真的传上去了」与「本地无改动、一个请求都没发」——
                // 两者都会写 RESULT_SUCCESS，旧文案让后者也显示成「上次同步成功」，
                // 实测排查时正是它造成了一次误判（以为内容已在云端）。
                // null = 升级前的老数据，保持原来的中性文案。
                val text = when (SyncSettings.lastSuccessUploaded(this)) {
                    false -> R.string.sync_status_success_noop
                    true -> R.string.sync_status_success_uploaded
                    null -> R.string.sync_status_success
                }
                getString(text, formatTime(successAt))
            }
            SyncEngine.RESULT_FAILED ->
                // v8.1.0：失败时间取"上次访问云端"（失败一定更新它）；升级瞬间可能还没写过，
                // 这时退回上次成功时间，宁可显示一个真实的旧时间，也不要显示「从未」。
                getString(
                    R.string.sync_status_failed,
                    formatTime(if (contactAt > 0) contactAt else successAt),
                    SyncErrorText.ofName(this, SyncSettings.lastError(this)) ?: ""
                )
            else -> getString(R.string.sync_status_never)
        }
    }

    /**
     * v7.9：最后一次**真的访问云端**的时刻；v8.1.0 起语义收紧了。
     *
     * 状态行原本只说「上次结果」，而排查「保存了却没上传」时最需要的恰恰是
     * 「最后一次真的发请求发生在什么时候」—— 配上 [retryLine] 用户拍一张截图就够了。
     *
     * **"本地没改动、一个请求都没发"的空跑不再更新它**（旧字段 `lastAttemptAt` 会更新，
     * 因为它是 1 分钟闸门的锚点；闸门已删除）。这样这一行才真的能回答
     * "是没变化、还是发了请求但没成功"，而不是把两者混成同一个时间戳。
     */
    private fun serverContactLine(): String? {
        val contactAt = SyncSettings.lastServerContactAt(this)
        if (contactAt <= 0) return null
        return getString(R.string.sync_last_server_contact, formatTime(contactAt))
    }

    /**
     * v7.9：系统级重试任务的状态；v8.1.0 起补上**预计补传时刻**。
     * 「已排队」= 本地还有没传上去的改动，系统会在有网络时自动重试；
     * 「已用完」= 自动重试用满 [SyncRetry.MAX_ATTEMPTS] 次后停下（不做长期后台驻留），
     * 需要用户点「立即同步」或再次保存才会重新排队 —— 这条提示必须说清楚，不能让用户以为已经传上去了。
     *
     * 时刻取自排任务时自己记下的 [SyncSettings.nextRetryAt]：`JobInfo` 不暴露绝对截止时刻，
     * 而"任务为什么还没跑"恰恰要拿这个时间与当前时间比（真机日志里就出现过任务比
     * 最短延迟晚了 20 多秒才被执行的情况）。升级瞬间可能还没记过，则退回不带时刻的旧文案。
     */
    private fun retryLine(): String? {
        if (!SyncSettings.enabled(this) || SyncSettings.config(this) == null) return null
        return when {
            SyncRetry.isScheduled(this) -> {
                val nextAt = SyncSettings.nextRetryAt(this)
                if (nextAt > 0) {
                    getString(R.string.sync_retry_queued_at, formatTime(nextAt))
                } else {
                    getString(R.string.sync_retry_queued)
                }
            }
            SyncSettings.retryAttempts(this) >= SyncRetry.MAX_ATTEMPTS ->
                getString(R.string.sync_retry_exhausted, SyncRetry.MAX_ATTEMPTS)
            else -> getString(R.string.sync_retry_none)
        }
    }

    /**
     * v8.0.1：上一次自动上传**是怎么启动的**（或者为什么没能启动）。
     *
     * 值是一段与 logcat 完全一致的 ASCII token，例如：
     *  - `fgs-foreground:shortService` —— 前台服务正常进了前台；
     *  - `fgs-rejected:ForegroundServiceStartNotAllowedException` —— 系统拒绝了后台启动；
     *  - `fgs-refused:ForegroundServiceTypeNotAllowedException` —— 类型不被允许；
     *  - `skipped:not-ready` —— 未启用或配置不完整。
     *
     * 这一行的意义：把「保存了却没传上去」拆成**可区分的几种**，
     * 用户不用抓 logcat，拍一张截图就能定位。
     */
    private fun launchLine(): String? {
        val note = SyncSettings.launchNote(this)
        if (note.isEmpty()) return null
        return getString(R.string.sync_last_launch, note, formatTime(SyncSettings.launchNoteAt(this)))
    }

    /**
     * v8.0.1：系统侧诊断（只读、零网络、零落盘）。
     *
     * `bucket=RARE` 配上 `pendingJobReason=APP_STANDBY`，
     * 就是「系统把兜底任务压在低优先级待机桶里（而 Rare 桶下后台网络是 Disabled）」的直接证据。
     * `pendingJobExpedited` 正常应为 `false`：本版的兜底任务**刻意**是常规 + 延迟
     * （加急不允许延迟，而立刻执行会抢走前台服务的可见上传，见 `SyncRetry.FIRST_RETRY_DELAY_MS` 的记录）；
     * 若它显示 `true`，说明系统改动了我们排出去的任务。
     * 只在同步已启用时显示，未启用时不给用户看一堆无意义的 n/a。
     */
    private fun diagnosticsLine(): String? {
        if (!SyncSettings.enabled(this) || SyncSettings.config(this) == null) return null
        return getString(R.string.sync_diagnostics, SyncDiagnostics.snapshot(this))
    }

    private fun skipText(reason: moe.hellowidget.sync.SkipReason): Int = when (reason) {
        moe.hellowidget.sync.SkipReason.NOT_ENABLED -> R.string.sync_skip_disabled
        moe.hellowidget.sync.SkipReason.NOT_CONFIGURED -> R.string.sync_skip_not_configured
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

    /**
     * v8.0.1（D4）：时间戳**精确到秒**（原来的 SHORT/SHORT 只有分钟）。
     *
     * 原因是一次真实排查卡在这里：页面显示「上次尝试 08:55」+「上次自动上传通道 08:56」，
     * 而两者相差 1 秒还是 119 秒，决定了这次同步是"跑了但没事可做"还是"压根没跑起来"，
     * 而分钟精度把这两种情况的截图变得一模一样。
     * MEDIUM 时间格式自带秒，且仍是本地化格式。
     */
    private fun formatTime(at: Long): String =
        if (at <= 0) getString(R.string.sync_time_never)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(at))

    /**
     * v8.0.2：把状态区（含诊断行）整块放进剪贴板。
     *
     * 起因：v8.0.1 加的诊断行只能靠截图转述，而排查「退出时没上传」恰恰要看
     * `bucket=… pendingJobReason=… scheduled=…` 这些 ASCII token —— 截图会糊、手抄会错。
     * 因此这块文字现在**有两条件路**：
     *  - 布局里 [ActivitySyncBinding.syncStatus] 设了 `textIsSelectable`，长按即出系统
     *    选择手柄与「复制 / 全选」（部分国产 ROM 是长按菜单）；
     *  - 「复制全部」按钮走这个方法，一次拿全，不需要用户会拖手柄。
     *
     * 复制内容与屏幕上**逐字一致**（所见即所得），不拼接版本号 / 设备名 ——
     * 否则用户会怀疑「我复制的和看到的不一样」。空文本时直接返回，宁可什么都不做，
     * 也不要把剪贴板里原有的内容清掉。
     *
     * 反馈遵循官方 Copy and paste 指引：Android 13（API 33）起系统自带到剪贴板的
     * 标准浮层，此时**不再自弹 Toast**（否则同一个动作会出现两条提示）；API 32 及以下才自己弹。
     */
    private fun copyStatusToClipboard() {
        val text = binding.syncStatus.text?.toString().orEmpty()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) toast(getString(R.string.sync_copied))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SyncActivity::class.java)
    }
}
