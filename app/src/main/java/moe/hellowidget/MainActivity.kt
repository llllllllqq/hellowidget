package moe.hellowidget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import moe.hellowidget.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 异步加载内容是否已完成（用于判断编辑器可否编辑） */
    private var loadCompleted = false

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
        // targetSdk 35 起系统强制边到边（Android 15+），必须自行消费系统栏 insets
        enableEdgeToEdge()

        // 记录当前夜间模式位，之后仅在夜间模式真正变化时才触发切换流程
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        // 加载完成前禁止编辑：避免「用户已输入但磁盘旧内容尚未读入」时，
        // 把磁盘上的旧内容覆盖成用户刚敲的几个字（另一种形式的数据丢失）。
        // 正常情况读盘只有几毫秒，用户无感知。
        binding.editor.isEnabled = false
        binding.editor.filters = arrayOf(maxLengthFilter)

        // 标记用户输入（主线程串行执行，天然无竞态）
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                editorTouched = true
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 异步恢复上次保存的内容
        lifecycleScope.launch {
            val savedText = withContext(Dispatchers.IO) { ContentStore.read() }
            loadCompleted = true
            if (!editorTouched) {
                binding.editor.setText(savedText)
                if (savedText.isNotEmpty()) {
                    binding.editor.setSelection(savedText.length)
                }
            }
            binding.editor.isEnabled = true
        }

        // 打开小组件外观设置页
        binding.btnSettings.setOnClickListener {
            openingSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        /**
         * 返回键退出：异步原子写盘 → 写盘结果确认后发 Toast → 最后才 finish()。
         * Activity 在写盘期间保持可见，Toast 不会因界面销毁而丢失。
         */
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitingByBack) return // 防重入：保存/退出流程进行中忽略再次返回
                exitingByBack = true
                if (shouldPersist()) {
                    saveContent(showToast = true) { finish() }
                } else {
                    // 尚未加载出任何内容且用户没输入过：磁盘上已有完整数据，无需保存
                    finish()
                }
            }
        })
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
     * 窗口失去焦点（Home / 多任务键 / 切到其他应用 / 下拉通知栏等）即保存并弹 Toast。
     * 比 onUserLeaveHint 更早触发，Toast 发出时应用窗口尚未退场，不会被后台 Toast 抑制。
     * 应用内跳转（设置页）与旋转已用标志排除。
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
        saveContent(showToast = true)
    }

    /**
     * 保存时机：仅在退出 / 返回 / 切后台（onStop）时执行，不做编辑自动保存。
     * - 返回键退出：由 [OnBackPressedCallback] 处理（保存 → Toast → finish）
     * - 失焦（Home/多任务键等）：已在 [onWindowFocusChanged] / [onUserLeaveHint] 处理
     * - 最近任务滑动移除：此处兜底静默保存
     * - 应用内跳转（设置页）/ 旋转：静默保存，不弹 Toast
     */
    override fun onStop() {
        super.onStop()
        if (!exitingByBack && !savedOnLeave && shouldPersist()) {
            saveContent(showToast = !openingSettings && !isChangingConfigurations)
        }
    }

    override fun onResume() {
        super.onResume()
        openingSettings = false
        savedOnLeave = false
        applyEditorColors()
    }

    /** 消费系统栏与刘海 insets，把内容避开状态栏/导航栏（targetSdk 35 边到边必需） */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * 按当前系统浅色/深色模式应用用户自定义的编辑器背景色与文字色。
     * 每次回到前台（含从设置页返回、深色模式重建后）都会重新应用。
     */
    private fun applyEditorColors() {
        val night = isNightMode()
        val bg = EditorSettings.bg(this, night)
        val text = EditorSettings.textColor(this, night)
        binding.editor.setTextColor(text)
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
     * 深色模式切换时的自动保存流程（静默保存，不弹 Toast）：
     * 保存完成后重建 Activity 应用新主题。
     */
    private fun handleNightModeSwitch() {
        if (nightSwitchSaving) return
        if (shouldPersist()) {
            nightSwitchSaving = true
            savedOnLeave = true // 重建过程中的 onStop 不再重复保存
            saveContent(showToast = false) {
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
     * 3. 写盘结果确认后，才刷新桌面小组件、发送 Toast
     * 4. 失败时清除 savedOnLeave，让后续 onStop 有机会重试（旧实现失败后不再重试）
     */
    private fun saveContent(showToast: Boolean, onDone: () -> Unit = {}) {
        val text = binding.editor.text.toString()
        val appContext = applicationContext
        ContentStore.saveScope.launch {
            val ok = ContentStore.write(text)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    // 写盘失败：允许后续离开时机重试，避免只剩内存里这一份
                    savedOnLeave = false
                }
                // 无论成功与否都刷新小组件（失败时小组件继续显示磁盘上的旧内容，保持一致）
                TextWidgetProvider.updateWidgets(appContext)

                if (showToast) {
                    Toast.makeText(
                        appContext,
                        if (ok) R.string.save_success else R.string.save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
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
            getString(R.string.editor_max_length_reached, MAX_CONTENT_CHARS),
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val PREFS_NAME = "hello_prefs"
        const val KEY_SAVED_TEXT = "saved_text"

        /** 编辑器内容上限（字符），见 [maxLengthFilter] 的说明 */
        const val MAX_CONTENT_CHARS = 100_000

        val Context.prefs
            get() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
