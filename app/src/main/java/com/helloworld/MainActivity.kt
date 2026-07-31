package com.helloworld

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.helloworld.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 异步加载内容的协程（保存前需等它完成，避免把旧内容覆盖成空） */
    private var loadCompleted = false

    /** 用户是否已手动输入过（防止异步加载覆盖用户输入） */
    private var editorTouched = false

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
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * 保存时机：仅在退出 / 返回 / 切后台（onStop）时执行，不做编辑自动保存。
     * 内容加载完成前不保存：此时编辑器还是空的，磁盘上已是最近一次完整内容，
     * 直接保存反而会把旧内容覆盖成空。
     */
    override fun onStop() {
        super.onStop()
        if (loadCompleted) {
            saveContent(showToast = isFinishing)
        }
    }

    /**
     * 原子写入保存：
     * 1. 运行在独立于 Activity 生命周期的应用级作用域，确保 Activity 立即销毁时写盘也一定执行完成
     * 2. updateData 挂起直到内容真正写盘（fsync + 原子重命名）成功后返回
     * 3. 写盘结果确认后，才刷新桌面小组件、发送 Toast（退出场景）
     * 4. 保存完成即结束，无任何后台任务，CPU 占用随之释放
     */
    private fun saveContent(showToast: Boolean) {
        val text = binding.editor.text.toString()
        ContentStore.saveScope.launch {
            val ok = ContentStore.write(text)
            withContext(Dispatchers.Main) {
                // 无论成功与否都刷新小组件（失败时小组件继续显示磁盘上的旧内容，保持一致）
                TextWidgetProvider.updateWidgets(this@MainActivity)

                if (showToast) {
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) R.string.save_success else R.string.save_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
