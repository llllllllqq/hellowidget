package moe.hellowidget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import moe.hellowidget.databinding.ActivityMainBinding
import moe.hellowidget.sync.SyncLauncher
import moe.hellowidget.sync.SyncManager
import moe.hellowidget.sync.SyncRetry
import moe.hellowidget.sync.SyncSettings
import moe.hellowidget.sync.SyncStatus
import moe.hellowidget.sync.SyncTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * 撤回历史（v7.7）。放在 ViewModel 里 → 旋转 / 深色模式重建后依然可用，
     * 见 [UndoHistoryViewModel] 的说明。
     */
    private val undoViewModel: UndoHistoryViewModel by viewModels()

    private val undoHistory: UndoHistory get() = undoViewModel.history

    /** 顶部导航栏里的「撤回」动作项；创建菜单后持有，用于实时切换可用状态 */
    private var undoMenuItem: MenuItem? = null

    /** 顶部导航栏里的「立即上传」动作项；创建菜单后持有，用于切换橙点角标 */
    private var uploadMenuItem: MenuItem? = null

    /**
     * 「立即上传」按钮上是否显示橙点（= 上次成功上传之后本地又有改动）。
     *
     * v7.8 语义：**只在检测点**重算（打开应用、回到前台、每次同步结束之后、保存后没能启动同步时），
     * 不跟随每次按键。检测本身零网络、零落盘（见 [SyncManager.hasPendingUpload]）。
     */
    @VisibleForTesting
    internal var uploadPending = false
        private set

    /** 检测的序号：异步算 hash 时只让最新一次的结果生效，避免旧结果覆盖新结果 */
    private var pendingCheckSeq = 0

    /** 已记录的可撤回步数（0 = 内容已回到刚打开时的样子）；供单测观察撤回历史 */
    @VisibleForTesting
    internal val undoDepth: Int get() = undoHistory.size

    /** 异步加载内容是否已完成（用于判断编辑器可否编辑） */
    private var loadCompleted = false

    /**
     * 本次是否为「全新进入应用」（false = 系统重建：旋转 / 深色模式切换 / 进程恢复）。
     * 只影响光标位置：全新进入时按设计放到第一行行首，系统重建时保留框架恢复出来的选区。
     */
    private var freshEntry = false

    /**
     * 已向系统发出「弹出输入法」请求的次数。
     *
     * Robolectric/JVM 里没有真实输入法，单测只能验证**请求时机**（加载完成后、且仅一次）；
     * 输入法在真机上究竟有没有弹出来，由 androidTest 里的模拟器仪器化测试覆盖。
     */
    @VisibleForTesting
    internal var imeRequestCount = 0
        private set

    /** 用户是否已手动输入过（含系统恢复实例状态时触发的文本变化） */
    private var editorTouched = false

    /** 返回键退出流程是否已开始（防重入；也用于区分 onStop 的来源） */
    private var exitingByBack = false

    /** 是否正在打开设置页（onStop 时用于区分「切后台」与「应用内跳转」，避免误弹 Toast） */
    private var openingSettings = false

    /** 本次离开（失焦/Home/多任务键）是否已保存过，防止重复保存 */
    private var savedOnLeave = false

    /** 最近一次记录的夜间模式标志位（Configuration.UI_MODE_NIGHT_MASK），用于检测深色模式切换 */
    private var lastNightMode = -1

    /** 深色模式切换触发的自动保存是否进行中（防重复触发） */
    private var nightSwitchSaving = false

    /** 「已达最大长度」提示的限流时间戳，避免连续输入时 Toast 刷屏 */
    private var lastTooLongToastAt = 0L

    /**
     * 内容长度上限（字符）。
     *
     * 存在两个目的：
     *  1. 防止粘贴超大文本导致 OOM —— 旧版本没有任何上限，10MB 级文本会在
     *     序列化（同时持有 3 份副本）时直接 OutOfMemoryError 崩溃；
     *  2. 顺带把单次 DataStore 读写耗时限制在毫秒级，磁盘 IO 不再可能长时间拖住任何线程。
     *
     * 100,000 字符 ≈ 约 300KB UTF-8（中文），对「桌面便签」场景远远够用。
     */
    private val maxLengthFilter = InputFilter { source, start, end, dest, dstart, dend ->
        val keep = MAX_CONTENT_CHARS - (dest.length - (dend - dstart))
        when {
            keep <= 0 -> {
                notifyTooLong()
                ""
            }
            keep >= end - start -> null // 未超限，放行
            else -> {
                notifyTooLong()
                source.subSequence(start, start + keep)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // savedInstanceState == null ⇔ 全新进入应用；非 null ⇔ 系统重建（旋转/深色模式/进程恢复）
        freshEntry = savedInstanceState == null
        // targetSdk 35 起系统强制边到边（Android 15+），必须自行消费系统栏 insets
        enableEdgeToEdge()

        // 记录当前夜间模式位，之后仅在夜间模式真正变化时才触发切换流程
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        // 顶部导航栏（v7.7）：外观设置 / WebDAV 同步 / 立即上传 / 撤回 四个入口都在这里
        setSupportActionBar(binding.toolbar)

        // 加载完成前禁止编辑：避免「用户已输入但磁盘旧内容尚未读入」时，
        // 把磁盘上的旧内容覆盖成用户刚敲的几个字（另一种形式的数据丢失）。
        // 正常情况读盘只有几毫秒，用户无感知。
        binding.editor.isEnabled = false
        binding.editor.filters = arrayOf(maxLengthFilter)

        // 标记用户输入（主线程串行执行，天然无竞态）。
        // afterTextChanged 在所有 watcher 的 onTextChanged 之后执行，
        // 因此此刻撤回历史已经记下这一步，可以安全刷新「撤回」的可用状态。
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                editorTouched = true
            }

            override fun afterTextChanged(s: Editable?) {
                refreshUndoAction()
            }
        })

        // 异步恢复上次保存的内容
        lifecycleScope.launch {
            val savedText = withContext(Dispatchers.IO) { ContentStore.read() }
            loadCompleted = true
            if (!editorTouched) {
                binding.editor.setText(savedText)
            }
            // 撤回历史的记录器必须在「初始内容就位」之后才挂上：
            // 否则「读盘写入」或「系统重建恢复文本」会被当成一次用户编辑，
            // 撤回就会一路退成空文本，而不是回到刚打开时的内容。
            binding.editor.addTextChangedListener(undoHistory)
            // 顺序很重要：先解禁编辑（disabled 的 View 拿不到焦点），再聚焦，最后才定位光标
            binding.editor.isEnabled = true
            binding.editor.requestFocus()
            // 光标定位必须放在 requestFocus() **之后**：EditText 获得焦点时框架会按
            // 「获得焦点默认行为」把光标带到文末（Editor.onFocusChanged → MovementMethod.onTakeFocus），
            // 聚焦前设的选区会被这次行为覆盖（已由 MainActivityEntryTest 固化）。
            // 只在全新进入应用时置 0；系统重建（旋转/深色模式/进程恢复）不动选区，
            // 保留框架恢复出来的光标位置。
            if (freshEntry) {
                binding.editor.setSelection(0)
            }
            requestImeShow()
            // v7.8：打开应用**只检测、不上传** —— 「上次成功上传后本地又有改动」就点亮橙点
            detectPendingUpload(savedText)
        }

        // v7.8：每次同步结束（成功 / 失败 / 被节流跳过）后重算橙点：
        // 成功 → 熄灭；失败或被节流 → 亮起（磁盘上确实还有没传上去的内容）。
        // SyncManager 与 SyncService 同进程，因此从服务发起的上传这里也收得到。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncManager.status.collect { status ->
                    if (status is SyncStatus.Success ||
                        status is SyncStatus.Failed ||
                        status is SyncStatus.Skipped
                    ) {
                        detectPendingUpload(binding.editor.text.toString())
                    }
                }
            }
        }

        installBackHandler()
    }

    // ------------------------------------------------------------ 「待上传」橙点

    /**
     * 重算「待上传」状态并刷新「立即上传」按钮上的橙点。
     *
     * 只做一次 SHA-256（≤300KB，毫秒级）且跑在 IO 线程；结果永远来自
     * 「当前文本 vs 上次成功上传的哈希」这个纯函数，因此进程重启后自动重现，不需要额外持久化。
     */
    private fun detectPendingUpload(text: String) {
        val seq = ++pendingCheckSeq
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                SyncManager.hasPendingUpload(applicationContext, text)
            }
            if (seq != pendingCheckSeq) return@launch // 已有更新的一次检测，丢弃本次结果
            setUploadPending(pending)
        }
    }

    private fun setUploadPending(pending: Boolean) {
        if (pending == uploadPending) return
        uploadPending = pending
        refreshUploadBadge()
    }

    /**
     * 刷新按钮外观：**替换的只是同一个上传图标的叠加状态**，不会换成别的图标 ——
     * 有待上传内容时在图标右上角叠一个橙色圆点（小米橙 #FF6900，紫底上最醒目），
     * 没有时就是原来的白云上传图标。橙点用代码合成（[Drawable] 叠加），不新增任何图标资源。
     */
    private fun refreshUploadBadge() {
        val item = uploadMenuItem ?: return
        item.setIcon(uploadIcon(uploadPending))
        item.setTitle(if (uploadPending) R.string.menu_upload_now_pending else R.string.menu_upload_now)
    }

    /**
     * 上传图标 + 可选的橙点角标。
     *
     * 橙点用 `LayerDrawable` 叠在原图标上（**不替换图标、不新增图标资源**），
     * 位置用 **inset** 手工算到右上角：`setLayerGravity` 需要 API 23，而本项目 minSdk 21
     * （CI 的 lint 会直接报 `NewApi` 错误），inset 从 API 1 就在，一条代码路径通吃 21~35。
     */
    private fun uploadIcon(withDot: Boolean): Drawable? {
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_action_upload) ?: return null
        if (!withDot) return icon
        val density = resources.displayMetrics.density
        val dotSize = (UPLOAD_DOT_SIZE_DP * density).roundToInt()
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@MainActivity, R.color.upload_pending_dot))
            // 1dp 白描边：把橙点与白色的云朵图标笔画分开，紫底上轮廓也更清楚
            setStroke(density.roundToInt().coerceAtLeast(1), Color.WHITE)
            setSize(dotSize, dotSize)
        }
        val iconWidth = icon.intrinsicWidth.takeIf { it > 0 } ?: (ICON_SIZE_DP * density).roundToInt()
        val iconHeight = icon.intrinsicHeight.takeIf { it > 0 } ?: (ICON_SIZE_DP * density).roundToInt()
        return LayerDrawable(arrayOf(icon, dot)).apply {
            // 让第 2 层只占右上角 dotSize×dotSize 那一小块
            setLayerInset(1, iconWidth - dotSize, 0, 0, iconHeight - dotSize)
        }
    }

    /**
     * 返回键退出：异步原子写盘 → 写盘结果确认后收尾 → 最后才 finish()。
     * Activity 在写盘期间保持可见，写盘不会再被界面销毁打断。
     */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitingByBack) return // 防重入：保存/退出流程进行中忽略再次返回
                exitingByBack = true
                if (shouldPersist()) {
                    saveContent(
                        notifyFailure = true,
                        syncTrigger = SyncTrigger.CLOSE_EDITOR
                    ) { finish() }
                } else {
                    // 尚未加载出任何内容且用户没输入过：磁盘上已有完整数据，无需保存，
                    // 因此也**不触发上传**（v7.8：上传只跟在「保存」后面）
                    finish()
                }
            }
        })
    }

    // ------------------------------------------------------------ 顶部导航栏

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        undoMenuItem = menu.findItem(R.id.action_undo)
        uploadMenuItem = menu.findItem(R.id.action_upload)
        refreshUndoAction()
        // 菜单可能在「待上传」状态已知之后才创建（启动检测是异步的），这里补齐橙点外观
        refreshUploadBadge()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        // 打开小组件外观设置页
        R.id.action_appearance -> {
            openingSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        // 打开 WebDAV 同步设置页
        R.id.action_webdav -> {
            openingSettings = true
            startActivity(SyncActivity.intent(this))
            true
        }
        // 立即上传：先落盘再以 MANUAL 触发（不受 1 分钟节流限制）
        R.id.action_upload -> {
            uploadToCloudNow()
            true
        }
        R.id.action_undo -> {
            undoLastEdit()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /** 「撤回」的可用状态 = 还有可撤回的编辑（false 时动作项置灰；撤回次数不设上限） */
    private fun refreshUndoAction() {
        undoMenuItem?.isEnabled = undoHistory.canUndo
    }

    /**
     * 撤回一步。
     *
     * 撤回的终点是「刚打开应用（或系统重建恢复）时的内容」：历史里的每一步都记着
     * 那次编辑之前的文本，反向套用一遍就精确回到最初状态（见 [UndoHistory]）。
     */
    private fun undoLastEdit() {
        if (!binding.editor.isEnabled) return // 内容尚未加载完，此时没有可撤回的编辑
        val caret = undoHistory.undo(binding.editor.text) ?: return
        binding.editor.setSelection(caret)
        refreshUndoAction()
    }

    /**
     * 「立即上传到云端」。
     *
     * 先把编辑器里的当前内容原子落盘，再用 [SyncTrigger.MANUAL] 触发同步：
     * 同步读的是磁盘上的内容，不先落盘就会把**旧内容**推上云端（而且看起来「同步成功」）。
     * MANUAL 不受 1 分钟节流限制 —— 这是用户明确的当下意图。
     */
    private fun uploadToCloudNow() {
        if (!SyncLauncher.isReady(this)) {
            Toast.makeText(this, R.string.sync_upload_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        saveContent(notifyFailure = true, syncTrigger = SyncTrigger.MANUAL)
    }

    /**
     * 是否可以保存编辑器内容。
     *
     * 关键修复：旧实现只看 `loadCompleted`，导致「加载尚未完成、但用户已经输入」时
     * 三条保存路径全部跳过，用户敲的字被静默丢弃。
     * 现在只要用户输入过（`editorTouched`）就一定会保存；
     * 而「编辑器在加载完成前禁用」保证了此时编辑器里的内容就是完整的（要么是刚读到的
     * 旧内容，要么是系统恢复的实例状态），不会用残缺内容覆盖磁盘。
     */
    private fun shouldPersist(): Boolean = loadCompleted || editorTouched

    /**
     * 窗口失去焦点（Home / 多任务键 / 切到其他应用 / 下拉通知栏等）即保存。
     * 比 onUserLeaveHint 更早触发，写盘与（失败时的）提示都发生在应用窗口退场之前，
     * 不会被后台限制吞掉。应用内跳转（设置页）与旋转已用标志排除。
     * v7.7 起保存成功不再弹 toast。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            savedOnLeave = false
        } else if (shouldSaveOnLeave()) {
            saveOnLeave()
        }
    }

    /**
     * Home / 多任务键按下时回调（兜底触发，通常已被 [onWindowFocusChanged] 覆盖）。
     * 注意：应用内 startActivity（如打开设置页）也会回调此方法，已用 openingSettings 排除。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldSaveOnLeave()) {
            saveOnLeave()
        }
    }

    private fun shouldSaveOnLeave(): Boolean =
        !openingSettings && !isChangingConfigurations && !exitingByBack && shouldPersist() && !savedOnLeave

    /**
     * 失焦保存。写盘走应用级作用域（[ContentStore.saveScope]），**不阻塞主线程**
     * ——旧实现在此用 `runBlocking` 同步写盘，内容大时会把 UI 线程按住几百毫秒以上。
     */
    private fun saveOnLeave() {
        savedOnLeave = true
        saveContent(notifyFailure = true, syncTrigger = SyncTrigger.CLOSE_EDITOR)
    }

    /**
     * 保存时机：仅在退出 / 返回 / 切后台（onStop）时执行，不做编辑自动保存。
     * - 返回键退出：由 [installBackHandler] 处理（保存 → 收尾 → finish）
     * - 失焦（Home/多任务键等）：已在 [onWindowFocusChanged] / [onUserLeaveHint] 处理
     * - 最近任务滑动移除：此处兜底静默保存
     * - 应用内跳转（设置页）/ 旋转：静默保存
     * v7.7 起保存成功不再弹 toast，只有写盘失败才提示（避免内容丢失无感知）。
     *
     * v7.8：**只要是保存，就一定触发一次自动上传**（[SyncTrigger.CLOSE_EDITOR]，受 1 分钟闸门约束）——
     * 包括应用内跳设置页与旋转这两种「静默保存」。反复保存（例如旋转 + onStop）由闸门合并成一次上传。
     */
    override fun onStop() {
        super.onStop()
        if (!exitingByBack && !savedOnLeave && shouldPersist()) {
            val leavingApp = !openingSettings && !isChangingConfigurations
            saveContent(
                notifyFailure = leavingApp,
                syncTrigger = SyncTrigger.CLOSE_EDITOR
            )
        }
    }

    override fun onResume() {
        super.onResume()
        openingSettings = false
        savedOnLeave = false
        applyEditorAppearance()
        // v7.8：从同步设置页回来（可能刚启用/改了配置）后重算一次橙点；内容尚未加载完时不猜
        if (loadCompleted) {
            detectPendingUpload(binding.editor.text.toString())
        }
    }

    /**
     * 消费系统栏、刘海与输入法 insets，把内容避开状态栏/导航栏/键盘（targetSdk 35 边到边必需）。
     *
     * v7.7.1 修复：顶部系统栏那一条的高度**由独立的占位视图承担**
     * （`status_bar_spacer`，见 activity_main.xml），**绝不再加到导航栏自己的 padding 上**。
     *
     * v7.7 的实现把 `bars.top` 当作 Toolbar 的 paddingTop，而 Toolbar 的高度是固定的
     * `?attr/actionBarSize`（56dp）。在顶部系统栏很高的设备上（由用户截图反推，
     * 该机 `systemBars() ∪ displayCutout()` 的 top ≈ 53dp），导航栏内容只剩约 3dp 可用高度：
     * AppCompat 的 `Toolbar.onLayout` 在空间不足时会把标题**贴底**放置（`space = height
     * - paddingTop - paddingBottom`），于是标题被裁成底部一条几个像素高的缝
     * （用户截图里那排「字母上半部分」的小白点），4 个按钮则完全不可见。
     * 现在导航栏自身高度恒定，insets 只能加高它上方那条占位视图，
     * 内容区永远是完整的一条导航栏 —— 与系统栏高度无关。
     *
     * 输入法部分（v7.1）：targetSdk 35 + enableEdgeToEdge() 之后，窗口不再为键盘让位
     * （adjustResize 不再自动生效），必须自己把内容顶到键盘之上，否则聚焦的编辑区会被键盘永久挡住。
     */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // API 30 以下 ime() 由 systemWindowInsets 推导（含导航栏高度），取 max 避免重复叠加
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applyStatusBarStrip(bars.top)
            view.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = maxOf(bars.bottom, imeBottom)
            )
            keepEditorFocusedWhileImeVisible(insets)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * 把「顶部系统栏那一条」的高度交给占位视图（颜色与导航栏相同，视觉上连成一条）。
     *
     * 幂等：insets 会在键盘弹出/收起、旋转、分屏等时机反复分发，高度没变就不动
     * LayoutParams —— 否则每次回调都会触发一次布局，白白浪费一帧。
     */
    private fun applyStatusBarStrip(heightPx: Int) {
        val strip = binding.statusBarSpacer
        val params = strip.layoutParams
        if (params.height == heightPx) return
        params.height = heightPx
        strip.layoutParams = params
    }

    /**
     * 输入法可见时把焦点维持在编辑器上。
     *
     * 实测（CI 的 API 34 模拟器，见 V7.1_RELEASE_REPORT.md）：输入法首帧可见时，焦点偶尔会从
     * 编辑器上脱落 —— Activity 仍有窗口焦点，但没有任何 View 持有焦点，此时光标不闪烁、
     * 按键无处可去，而这恰好发生在「自动弹出输入法」这条新路径上。
     * 编辑区是唯一的可输入控件（顶部导航栏的按钮在触摸模式下不可聚焦），
     * 因此这里幂等地把焦点拉回编辑器，让「打开即输入」在任何时序下都成立。
     */
    private fun keepEditorFocusedWhileImeVisible(insets: WindowInsetsCompat) {
        if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return
        if (!binding.editor.isEnabled || binding.editor.hasFocus()) return
        binding.editor.requestFocus()
    }

    /**
     * 主动请求弹出输入法（调用前编辑器必须已解禁并已获得焦点）。
     *
     * 为什么不只用 Manifest 的 `windowSoftInputMode="stateVisible"`：
     * 编辑器在异步读盘完成前是 disabled 的（防止用户输入被磁盘旧内容覆盖），
     * 而禁用的 View 拿不到焦点，系统也就不会为它弹出输入法。
     * 因此只能在读盘完成、编辑器解禁之后主动请求。
     *
     * 为什么用 WindowInsetsControllerCompat#show(ime()) 而不是
     * InputMethodManager.showSoftInput()：官方文档明确指出后者在 Activity 启动阶段
     * 常被系统忽略（窗口尚未聚焦时编辑器不被视为已连上输入法），
     * 而前者「guaranteed to be scheduled after the window is focused」——
     * AOSP 里 ImeInsetsSourceConsumer 会先把请求记入 requested-visible 类型，
     * 并在窗口获得焦点（onWindowFocusGained）时补上，所以此刻窗口还没聚焦也不会丢请求。
     */
    private fun requestImeShow() {
        if (isFinishing || isDestroyed) return
        val controller: WindowInsetsControllerCompat? =
            WindowCompat.getInsetsController(window, binding.editor)
        if (controller != null) {
            imeRequestCount++
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    /**
     * 按当前系统浅色/深色模式应用用户自定义的编辑器背景色与文字色，
     * 并把编辑区字号同步成设置页里的「字体大小」（v7.7：与桌面小组件共用一个值）。
     * 每次回到前台（含从设置页返回、深色模式重建后）都会重新应用。
     */
    private fun applyEditorAppearance() {
        val night = isNightMode()
        val bg = EditorSettings.bg(this, night)
        val text = EditorSettings.textColor(this, night)
        binding.editor.setTextColor(text)
        // 字号跟随「小组件字体大小」：设置页改一次，小组件与编辑区同时生效。
        // 用 sp，跟随系统字体缩放设置（与布局里的 textSize 单位一致）。
        binding.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, WidgetSettings.fontSp(this))
        // 「无背景」（透明）= 跟随系统主题背景（浅色=白，深色=黑）
        binding.editorScroll.setBackgroundColor(if (bg == Color.TRANSPARENT) Color.TRANSPARENT else bg)
        // 提示文字用文字色半透明，保证任意配色下都清晰可见
        binding.editor.setHintTextColor(
            Color.argb(128, Color.red(text), Color.green(text), Color.blue(text))
        )
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 深色模式切换（系统设置变更）：本 Activity 在 Manifest 中声明了
     * configChanges="uiMode"，系统不会自动重建，而是回调本方法。
     * 这里先触发一次自动保存，再重建 Activity 以应用新的浅色/深色主题。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastNightMode) {
            lastNightMode = nightMode
            handleNightModeSwitch()
        }
    }

    /**
     * 深色模式切换时的自动保存流程（静默保存：
     * 只有写盘失败才提示，成功不弹 toast）：保存完成后重建 Activity 应用新主题。
     *
     * v7.8：这也是一次「保存」，因此同样触发自动上传（受 1 分钟闸门约束，
     * 紧接着的旋转 / onStop 保存会被闸门合并掉）。
     */
    private fun handleNightModeSwitch() {
        if (nightSwitchSaving) return
        if (shouldPersist()) {
            nightSwitchSaving = true
            savedOnLeave = true // 重建过程中的 onStop 不再重复保存
            saveContent(
                notifyFailure = false,
                syncTrigger = SyncTrigger.CLOSE_EDITOR
            ) {
                nightSwitchSaving = false
                if (!isFinishing && !isDestroyed) {
                    recreate()
                }
            }
        } else {
            recreate()
        }
    }

    /**
     * 原子写入保存：
     * 1. 运行在独立于 Activity 生命周期的应用级作用域，Activity 销毁也不影响写盘
     * 2. updateData 挂起直到内容真正写盘（fsync + 原子重命名）成功后返回
     * 3. 写盘结果确认后，才刷新桌面小组件、按需提示失败、按需触发同步
     * 4. 失败时清除 savedOnLeave，让后续 onStop 有机会重试（旧实现失败后不再重试）
     *
     * @param notifyFailure 写盘**失败**时是否提示。v7.7 起保存成功不再弹「已保存」toast
     *   （用户要求删除全部保存成功提示），失败仍然提示 —— 那是可能丢内容的信号。
     * @param syncTrigger 写盘成功后要触发的一次 WebDAV 同步，null = 不触发。
     *   v7.8：**每一条保存路径都传 [SyncTrigger.CLOSE_EDITOR]**（旋转 / 深色模式 / 跳设置页
     *   这些静默保存也一样）——「任何保存操作都触发自动上传」，密集保存由 1 分钟闸门合并。
     *   MANUAL 只用于顶部「立即上传」按钮（不受闸门限制）。
     */
    private fun saveContent(
        notifyFailure: Boolean,
        syncTrigger: SyncTrigger? = null,
        onDone: () -> Unit = {}
    ) {
        val text = binding.editor.text.toString()
        val appContext = applicationContext
        ContentStore.saveScope.launch {
            val ok = ContentStore.write(text)
            // v7.9：写盘成功就**先**把「这次改动还没传上去」记到系统里（一次性持久化重试任务）。
            // 放在触发上传之前是有意的：接下来的触发有可能根本没跑起来 —— 前台服务被系统
            // 静默拒绝、进程立刻被冻结/回收 —— 那正是 v7.8.0「只保存、不上传、橙点不灭」的形态，
            // 而那种情况下如果没有这一步，就没有任何东西记得"还欠一次上传"。
            // 上传成功后 SyncManager 会撤销它；内容没变时它醒来也一个请求都不发。
            if (ok && syncTrigger != null) SyncRetry.schedule(appContext)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    // 写盘失败：允许后续离开时机重试，避免只剩内存里这一份
                    savedOnLeave = false
                }
                // 无论成功与否都刷新小组件（失败时小组件继续显示磁盘上的旧内容，保持一致）
                TextWidgetProvider.updateWidgets(appContext)

                // 只在写盘成功后才上传：否则会把磁盘上的旧内容推到云端
                if (ok && syncTrigger != null) {
                    val started = SyncLauncher.request(appContext, syncTrigger)
                    // 没能启动同步（未启用/未配置，或前台服务被系统拒绝且降级也失败）：
                    // 不会再有同步终态回调，直接重算橙点，让用户看到「还有东西没传上去」
                    if (!started) detectPendingUpload(text)
                }

                if (notifyFailure && !ok) {
                    Toast.makeText(appContext, R.string.save_failed, Toast.LENGTH_SHORT).show()
                }
                onDone()
            }
        }
    }

    /** 达到长度上限时提示一次（限流，避免每次按键都弹） */
    private fun notifyTooLong() {
        val now = System.currentTimeMillis()
        if (now - lastTooLongToastAt < 2000L) return
        lastTooLongToastAt = now
        Toast.makeText(
            applicationContext,
            resources.getQuantityString(
                R.plurals.editor_max_length_reached,
                MAX_CONTENT_CHARS,
                MAX_CONTENT_CHARS
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val PREFS_NAME = "hello_prefs"
        const val KEY_SAVED_TEXT = "saved_text"

        /** 编辑器内容上限（字符），见 [maxLengthFilter] 的说明 */
        const val MAX_CONTENT_CHARS = 100_000

        /** 「立即上传」按钮上橙点的直径（dp）——只在原图标右上角叠一个圆点，不新增图标资源 */
        private const val UPLOAD_DOT_SIZE_DP = 9f

        /** 图标基准边长（dp）：拿不到图标 intrinsic 尺寸时用它兜底 */
        private const val ICON_SIZE_DP = 24f

        val Context.prefs
            get() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
