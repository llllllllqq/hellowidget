package com.helloworld

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.helloworld.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 恢复上次保存的内容
        val savedText = prefs.getString(KEY_SAVED_TEXT, "")
        binding.editor.setText(savedText)
        // 光标移到末尾
        if (!savedText.isNullOrEmpty()) {
            binding.editor.setSelection(savedText.length)
        }

        // 打开小组件外观设置页
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        saveText()
    }

    private fun saveText() {
        val text = binding.editor.text.toString()
        prefs.edit().putString(KEY_SAVED_TEXT, text).apply()
        TextWidgetProvider.updateWidgets(this)
        Toast.makeText(this, R.string.save_success, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PREFS_NAME = "hello_prefs"
        const val KEY_SAVED_TEXT = "saved_text"

        val Context.prefs
            get() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
