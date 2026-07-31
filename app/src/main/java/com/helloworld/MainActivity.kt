package com.helloworld

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.helloworld.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 异步加载内容是否已完成（完成前不保存，避免把磁盘旧内容覆盖成空） */
    private var loadCompleted = false

    /** 用户是否已手动输入过（防止异步加载覆盖用户输入） */
    private var editorTouched = false

    /** 返回键退出流程是否已开始（防重入；也用于区分 onStop 的来源） */
    private var exitingByBack = false

    /** 是否正在打开设置页（onStop 时用于区分「切后台」与「应用内跳转」，避免误弹 Toast） */
    private var openingSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        }

        // 打开小组件外观设置页
        binding.btnSettings.setOnClickListener {
            openingSettings = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        /**
         * 返回键退出：先原子写盘 → 写盘结果确认后发 Toast → 最后才 finish()。
         * Activity 在整个过程中保持可见，Toast 不会因界面销毁而丢失。
         */
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (exitingByBack) return // 防重入：保存/退出流程进行中忽略再次返回
                exitingByBack = true
                if (loadCompleted) {
                    saveContent(showToast = true) { finish() }
                } else {
                    // 内容尚未加载完成：磁盘上已有完整数据，无需保存，直接退出
                    finish()
                }
            }
        })
    }

    /**
     * 保存时机：仅在退出 / 返回 / 切后台（onStop）时执行，不做编辑自动保存。
     * - 返回键退出：由 [OnBackPressedCallback] 处理（保存 → Toast → finish）
     * - Home / 多任务键 / 切到其他应用：保存并弹「已保存 ✓」Toast
     * - 应用内跳转（设置页）/ 旋转：静默保存，不弹 Toast
     * 内容加载完成前不保存：此时编辑器还是空的，磁盘上已是最近一次完整内容，
     * 直接保存反而会把旧内容覆盖成空。
     */
    override fun onStop() {
        super.onStop()
        if (!exitingByBack && loadCompleted) {
            saveContent(showToast = !openingSettings && !isChangingConfigurations)
        }
    }

    override fun onResume() {
        super.onResume()
        openingSettings = false
    }

    /**
     * 原子写入保存：
     * 1. 运行在独立于 Activity 生命周期的应用级作用域，Activity 销毁也不影响写盘
     * 2. updateData 挂起直到内容真正写盘（fsync + 原子重命名）成功后返回
     * 3. 写盘结果确认后，才刷新桌面小组件、发送 Toast（退出场景）
     * 4. 保存完成即结束，无任何后台任务，CPU 占用随之释放
     */
    private fun saveContent(showToast: Boolean, onDone: () -> Unit = {}) {
        val text = binding.editor.text.toString()
        val appContext = applicationContext
        ContentStore.saveScope.launch {
            val ok = ContentStore.write(text)
            withContext(Dispatchers.Main) {
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

    companion object {
        const val PREFS_NAME = "hello_prefs"
        const val KEY_SAVED_TEXT = "saved_text"

        val Context.prefs
            get() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
