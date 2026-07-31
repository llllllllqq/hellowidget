package moe.hellowidget

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import moe.hellowidget.MainActivity.Companion.prefs
import moe.hellowidget.databinding.ActivitySettingsBinding

/**
 * 小组件外观设置页：字体大小、字体颜色、背景颜色、背景透明度。
 * 所有修改即时保存并刷新桌面小组件。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val customTextColor = Color.parseColor("#E91E63")

    /** 字体色板 */
    private val textColors = listOf(
        Color.WHITE, Color.BLACK, Color.LTGRAY, Color.GRAY,
        Color.RED, Color.parseColor("#FF9800"), Color.YELLOW, Color.GREEN,
        Color.CYAN, Color.BLUE, Color.MAGENTA, customTextColor
    )

    /** 背景色板（第一个为"无背景"） */
    private val bgColors = listOf(
        Color.TRANSPARENT, Color.WHITE, Color.BLACK, Color.DKGRAY,
        Color.parseColor("#1A237E"), Color.parseColor("#01579B"),
        Color.parseColor("#004D40"), Color.parseColor("#33691E"),
        Color.parseColor("#E65100"), Color.parseColor("#4E342E")
    )

    private var fontSp = WidgetSettings.DEFAULT_FONT_SP
    private var textColor = WidgetSettings.DEFAULT_TEXT_COLOR
    private var bgColor = WidgetSettings.DEFAULT_BG_COLOR
    private var bgAlpha = WidgetSettings.DEFAULT_BG_ALPHA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.settings_title)

        loadSettings()
        // 预览框初始背景
        binding.previewBox.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        setupFontSizeSeek()
        setupBgAlphaSeek()
        renderTextSwatches()
        renderBgSwatches()
        updatePreview()

        binding.btnReset.setOnClickListener { resetSettings() }
    }

    // ---------- 读取 / 保存 ----------

    private fun loadSettings() {
        fontSp = WidgetSettings.fontSp(this)
        textColor = WidgetSettings.textColor(this)
        bgColor = WidgetSettings.bgColor(this)
        bgAlpha = WidgetSettings.bgAlpha(this)
    }

    /** 保存全部设置并立即刷新桌面小组件 */
    private fun persist() {
        prefs.edit()
            .putFloat(WidgetSettings.KEY_FONT_SIZE, fontSp)
            .putInt(WidgetSettings.KEY_TEXT_COLOR, textColor)
            .putInt(WidgetSettings.KEY_BG_COLOR, bgColor)
            .putInt(WidgetSettings.KEY_BG_ALPHA, bgAlpha)
            .apply()
        TextWidgetProvider.updateWidgets(this)
    }

    // ---------- 字体大小 ----------

    private fun setupFontSizeSeek() {
        // 进度 0..24 → 10..34sp
        binding.fontSizeSeek.progress = (fontSp - 10).toInt().coerceIn(0, 24)
        binding.fontSizeValue.text = "${fontSp.toInt()}sp"
        binding.fontSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fontSp = 10f + progress
                binding.fontSizeValue.text = "${fontSp.toInt()}sp"
                persist()
                updatePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------- 背景透明度 ----------

    private fun setupBgAlphaSeek() {
        binding.bgAlphaSeek.progress = bgAlpha
        binding.bgAlphaValue.text = "$bgAlpha%"
        binding.bgAlphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bgAlpha = progress
                binding.bgAlphaValue.text = "$progress%"
                persist()
                updatePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------- 色板 ----------

    private fun renderTextSwatches() {
        binding.textColorSwatches.removeAllViews()
        textColors.forEach { color ->
            binding.textColorSwatches.addView(
                makeSwatch(color, color == textColor) {
                    textColor = it
                    persist()
                    renderTextSwatches()
                    updatePreview()
                }
            )
        }
        // 自定义取色按钮
        binding.textColorSwatches.addView(
            makeCustomSwatch(textColor !in textColors) {
                showColorDialog(getString(R.string.color_picker_title_text), textColor) { color ->
                    textColor = color
                    persist()
                    renderTextSwatches()
                    updatePreview()
                }
            }
        )
    }

    private fun renderBgSwatches() {
        binding.bgColorSwatches.removeAllViews()
        bgColors.forEach { color ->
            binding.bgColorSwatches.addView(
                makeSwatch(color, color == bgColor) {
                    bgColor = it
                    persist()
                    renderBgSwatches()
                    updatePreview()
                }
            )
        }
        binding.bgColorSwatches.addView(
            makeCustomSwatch(bgColor !in bgColors) {
                showColorDialog(getString(R.string.color_picker_title_bg), bgColor) { color ->
                    bgColor = color
                    persist()
                    renderBgSwatches()
                    updatePreview()
                }
            }
        )
        // 未选择背景时，透明度滑杆不可用
        binding.bgAlphaSeek.isEnabled = bgColor != Color.TRANSPARENT
        binding.bgAlphaLabel.isEnabled = bgColor != Color.TRANSPARENT
    }

    /** 一个颜色方块；选中时显示高亮边框 */
    private fun makeSwatch(color: Int, selected: Boolean, onClick: (Int) -> Unit): View {
        val swatch = View(this)
        swatch.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }

        val gd = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            if (color == Color.TRANSPARENT) {
                // "无背景"：浅灰底 + 深灰边
                setColor(Color.parseColor("#EEEEEE"))
                setStroke(dp(2), if (selected) Color.parseColor("#FF4081") else Color.parseColor("#999999"))
            } else {
                setColor(color)
                setStroke(dp(if (selected) 3 else 1),
                    if (selected) Color.parseColor("#FF4081") else Color.parseColor("#DDDDDD"))
            }
        }
        swatch.background = gd
        swatch.setOnClickListener { onClick(color) }
        return swatch
    }

    /** "＋"自定义颜色按钮 */
    private fun makeCustomSwatch(active: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "＋"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.parseColor("#555555"))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(if (active) 3 else 1),
                    if (active) Color.parseColor("#FF4081") else Color.parseColor("#BBBBBB"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) }
            setOnClickListener { onClick() }
        }
    }

    /** 自定义 RGB 取色对话框 */
    private fun showColorDialog(title: String, initial: Int, onPicked: (Int) -> Unit) {
        val rgb = intArrayOf(Color.red(initial), Color.green(initial), Color.blue(initial))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val preview = TextView(this).apply {
            text = getString(R.string.color_preview_text)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        }
        content.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT))

        // R / G / B 三根滑杆
        listOf("R", "G", "B").forEachIndexed { index, label ->
            val bar = SeekBar(this).apply { max = 255; progress = rgb[index] }
            val value = TextView(this).apply {
                text = rgb[index].toString()
                setTextColor(Color.BLACK)
                gravity = Gravity.END
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    setTextColor(Color.BLACK)
                    layoutParams = LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(value, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            content.addView(row)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    rgb[index] = progress
                    value.text = progress.toString()
                    (preview.background as GradientDrawable).setColor(Color.rgb(rgb[0], rgb[1], rgb[2]))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        (preview.background as GradientDrawable).setColor(Color.rgb(rgb[0], rgb[1], rgb[2]))

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onPicked(Color.rgb(rgb[0], rgb[1], rgb[2]))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 预览 / 重置 ----------

    private fun updatePreview() {
        binding.previewText.textSize = fontSp
        binding.previewText.setTextColor(textColor)

        val bg = if (bgColor == Color.TRANSPARENT) {
            Color.TRANSPARENT
        } else {
            val a = bgAlpha.coerceIn(0, 100) * 255 / 100
            Color.argb(a, Color.red(bgColor), Color.green(bgColor), Color.blue(bgColor))
        }
        (binding.previewBox.background as? GradientDrawable)?.setColor(bg)
    }

    private fun resetSettings() {
        fontSp = WidgetSettings.DEFAULT_FONT_SP
        textColor = WidgetSettings.DEFAULT_TEXT_COLOR
        bgColor = WidgetSettings.DEFAULT_BG_COLOR
        bgAlpha = WidgetSettings.DEFAULT_BG_ALPHA
        persist()
        binding.fontSizeSeek.progress = (fontSp - 10).toInt()
        binding.bgAlphaSeek.progress = bgAlpha
        renderTextSwatches()
        renderBgSwatches()
        updatePreview()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
