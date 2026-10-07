package moe.hellowidget

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.hellowidget.MainActivity.Companion.prefs
import moe.hellowidget.databinding.ActivitySettingsBinding
import java.util.Locale

/**
 * 设置页：小组件外观（字体大小 / 颜色 / 背景 / 防误触余量）、浅色与深色模式各自的编辑器配色，
 * 以及 **v8.2.0 起的备份与换机迁移**（导出 / 恢复一个备份文件）。
 *
 * QA 修复要点：
 *  - 滑杆拖动时只做廉价的本地预览，**松手才落盘 + 刷新桌面小组件**（原来每帧一次全量刷新）
 *  - 色板补齐无障碍语义（可访问名称、选中状态、48dp 触控目标）
 *  - targetSdk 35 边到边：消费系统栏 insets
 *
 * 备份的两个按钮都走系统的文件选择器（SAF），因此**不需要任何存储权限**，
 * 用户自己决定文件放在哪（本地 / 网盘 / U 盘都行）。
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
    private var fillMarginDp = WidgetSettings.DEFAULT_FILL_MARGIN_DP

    // 编辑器颜色（浅色/深色模式各自的背景色与文字色，与小组件颜色独立）
    private var editorLightBg = EditorSettings.DEFAULT_LIGHT_BG
    private var editorLightText = EditorSettings.DEFAULT_LIGHT_TEXT
    private var editorDarkBg = EditorSettings.DEFAULT_DARK_BG
    private var editorDarkText = EditorSettings.DEFAULT_DARK_TEXT

    /**
     * 导出备份：系统文件选择器（SAF `ACTION_CREATE_DOCUMENT`）。
     * 结果 uri 为 null = 用户取消，什么都不做。
     */
    private val exportBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
            uri?.let { writeBackupTo(it) }
        }

    /**
     * 从备份恢复：SAF `ACTION_OPEN_DOCUMENT`。
     *
     * MIME 刻意用通配类型（请求里就是「星号 + 斜杠 + 星号」这三个字符）：
     * 备份是 `.json`，但不同文件管理器/网盘给出的 MIME 五花八门
     * （`application/json` / `text/plain` / `application/octet-stream`，甚至空），
     * 卡 MIME 只会让用户"看不到自己的备份文件"。内容合法性由 [BackupCodec.decode] 判定。
     *
     * 注意这行 KDoc **不能**把通配 MIME 原样写出来：那三个字符里的后两个正好是块注释的
     * 结束标记，会把本段注释提前截断，后面的说明就变成代码（本版真的踩过一次，
     * 由"本地 Kotlin 语法自检 + aapt2 预检"抓到）。
     */
    private val importBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readBackupFrom(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        title = getString(R.string.settings_title)

        loadSettings()
        // 预览框初始背景
        binding.previewBox.background = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
        setupFontSizeSeek()
        setupBgAlphaSeek()
        setupFillMarginSeek()
        renderTextSwatches()
        renderBgSwatches()
        renderEditorSwatches()
        updatePreview()

        binding.btnReset.setOnClickListener { resetSettings() }

        // v8.2.0：备份 / 换机迁移。导出名带时间戳，用户连点两次不会互相覆盖
        binding.btnExportBackup.setOnClickListener {
            exportBackup.launch(BackupCodec.suggestedFileName(System.currentTimeMillis()))
        }
        binding.btnImportBackup.setOnClickListener { importBackup.launch(arrayOf("*/*")) }
    }

    /** 消费系统栏 insets，避免内容被状态栏/导航栏遮挡（targetSdk 35 边到边必需） */
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

    // ---------- 读取 / 保存 ----------

    private fun loadSettings() {
        fontSp = WidgetSettings.fontSp(this)
        textColor = WidgetSettings.textColor(this)
        bgColor = WidgetSettings.bgColor(this)
        bgAlpha = WidgetSettings.bgAlpha(this)
        fillMarginDp = WidgetSettings.fillMarginDp(this)
        editorLightBg = EditorSettings.lightBg(this)
        editorLightText = EditorSettings.lightText(this)
        editorDarkBg = EditorSettings.darkBg(this)
        editorDarkText = EditorSettings.darkText(this)
    }

    /** 保存全部设置并立即刷新桌面小组件（只在交互结束时调用，不在拖动过程中每帧调用） */
    private fun persist() {
        prefs.edit()
            .putFloat(WidgetSettings.KEY_FONT_SIZE, fontSp)
            .putInt(WidgetSettings.KEY_TEXT_COLOR, textColor)
            .putInt(WidgetSettings.KEY_BG_COLOR, bgColor)
            .putInt(WidgetSettings.KEY_BG_ALPHA, bgAlpha)
            .putInt(WidgetSettings.KEY_FILL_MARGIN_DP, fillMarginDp)
            .putInt(EditorSettings.KEY_LIGHT_BG, editorLightBg)
            .putInt(EditorSettings.KEY_LIGHT_TEXT, editorLightText)
            .putInt(EditorSettings.KEY_DARK_BG, editorDarkBg)
            .putInt(EditorSettings.KEY_DARK_TEXT, editorDarkText)
            .apply()
        TextWidgetProvider.updateWidgets(this)
    }

    // ---------- 滑杆：拖动只预览，松手才落盘 ----------

    private fun setupFontSizeSeek() {
        // 进度 0..24 → 10..34sp
        binding.fontSizeSeek.progress = (fontSp - 10).toInt().coerceIn(0, 24)
        binding.fontSizeValue.text = getString(R.string.settings_font_size_value, fontSp.toInt())
        binding.fontSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fontSp = 10f + progress
                binding.fontSizeValue.text =
                    getString(R.string.settings_font_size_value, fontSp.toInt())
                updatePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persist()
            }
        })
    }

    private fun setupBgAlphaSeek() {
        binding.bgAlphaSeek.progress = bgAlpha
        binding.bgAlphaValue.text = getString(R.string.settings_bg_alpha_value, bgAlpha)
        binding.bgAlphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                bgAlpha = progress
                binding.bgAlphaValue.text = getString(R.string.settings_bg_alpha_value, progress)
                updatePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persist()
            }
        })
    }

    private fun setupFillMarginSeek() {
        binding.fillMarginSeek.progress = fillMarginDp.coerceIn(0, 20)
        binding.fillMarginValue.text = getString(R.string.settings_fill_margin_value, fillMarginDp)
        binding.fillMarginSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fillMarginDp = progress
                binding.fillMarginValue.text =
                    getString(R.string.settings_fill_margin_value, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                persist()
            }
        })
    }

    // ---------- 色板 ----------

    private fun renderTextSwatches() {
        binding.textColorSwatches.removeAllViews()
        val section = getString(R.string.settings_text_color)
        textColors.forEach { color ->
            binding.textColorSwatches.addView(
                makeSwatch(color, color == textColor, swatchDescription(section, color)) {
                    textColor = it
                    persist()
                    renderTextSwatches()
                    updatePreview()
                }
            )
        }
        binding.textColorSwatches.addView(
            makeCustomSwatch(textColor !in textColors, section) {
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
        val section = getString(R.string.settings_bg_color)
        bgColors.forEach { color ->
            binding.bgColorSwatches.addView(
                makeSwatch(color, color == bgColor, swatchDescription(section, color)) {
                    bgColor = it
                    persist()
                    renderBgSwatches()
                    updatePreview()
                }
            )
        }
        binding.bgColorSwatches.addView(
            makeCustomSwatch(bgColor !in bgColors, section) {
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

    // ---------- 编辑器颜色（浅色/深色模式各自的背景色与文字色） ----------

    /** 渲染一行色板（预设色块 + 自定义取色按钮），点击即时保存并重绘选中边框 */
    private fun renderSwatchRow(
        container: LinearLayout,
        palette: List<Int>,
        selected: Int,
        pickerTitleRes: Int,
        section: String,
        onPick: (Int) -> Unit
    ) {
        container.removeAllViews()
        palette.forEach { color ->
            container.addView(
                makeSwatch(color, color == selected, swatchDescription(section, color)) {
                    onPick(it)
                    // 重绘整行，让选中边框立即同步
                    renderSwatchRow(container, palette, it, pickerTitleRes, section, onPick)
                }
            )
        }
        container.addView(
            makeCustomSwatch(selected !in palette, section) {
                showColorDialog(getString(pickerTitleRes), selected) { color ->
                    onPick(color)
                    renderSwatchRow(container, palette, color, pickerTitleRes, section, onPick)
                }
            }
        )
    }

    private fun renderEditorSwatches() {
        renderSwatchRow(
            binding.editorLightBgSwatches, bgColors, editorLightBg,
            R.string.color_picker_title_editor_light_bg,
            getString(R.string.settings_section_editor_light) + " " + getString(R.string.settings_bg_color)
        ) { editorLightBg = it; persist() }
        renderSwatchRow(
            binding.editorLightTextSwatches, textColors, editorLightText,
            R.string.color_picker_title_editor_light_text,
            getString(R.string.settings_section_editor_light) + " " + getString(R.string.settings_editor_text_color)
        ) { editorLightText = it; persist() }
        renderSwatchRow(
            binding.editorDarkBgSwatches, bgColors, editorDarkBg,
            R.string.color_picker_title_editor_dark_bg,
            getString(R.string.settings_section_editor_dark) + " " + getString(R.string.settings_bg_color)
        ) { editorDarkBg = it; persist() }
        renderSwatchRow(
            binding.editorDarkTextSwatches, textColors, editorDarkText,
            R.string.color_picker_title_editor_dark_text,
            getString(R.string.settings_section_editor_dark) + " " + getString(R.string.settings_editor_text_color)
        ) { editorDarkText = it; persist() }
    }

    /** 颜色 → 可读标签（无障碍朗读用） */
    private fun swatchDescription(section: String, color: Int): String {
        val label = if (color == Color.TRANSPARENT) {
            getString(R.string.color_none)
        } else {
            String.format(Locale.US, "#%06X", 0xFFFFFF and color)
        }
        return getString(R.string.a11y_swatch_description, section, label)
    }

    /**
     * 一个颜色方块；选中时显示高亮边框。
     *
     * 无障碍：48dp 触控目标、可聚焦、带可访问名称，并用 stateDescription 表达选中状态
     * （旧实现是裸 View，TalkBack 只能看到一堆没有名字的方块，且选中仅靠颜色区分）。
     */
    private fun makeSwatch(
        color: Int,
        selected: Boolean,
        description: String,
        onClick: (Int) -> Unit
    ): View {
        val swatch = View(this)
        swatch.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }
        swatch.contentDescription = description
        swatch.isFocusable = true
        swatch.isSelected = selected
        ViewCompat.setStateDescription(
            swatch,
            getString(if (selected) R.string.a11y_selected else R.string.a11y_not_selected)
        )

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

    /** "＋"自定义颜色按钮（同样补齐无障碍语义与 48dp 触控目标） */
    private fun makeCustomSwatch(active: Boolean, section: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = "＋"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.parseColor("#555555"))
            contentDescription = getString(R.string.a11y_custom_color, section)
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(if (active) 3 else 1),
                    if (active) Color.parseColor("#FF4081") else Color.parseColor("#BBBBBB"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }
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
        val dialogTextColor = themeAttrColor(android.R.attr.textColorPrimary)
        listOf("R", "G", "B").forEachIndexed { index, label ->
            val bar = SeekBar(this).apply {
                max = 255
                progress = rgb[index]
                contentDescription = label
            }
            val value = TextView(this).apply {
                text = String.format(Locale.getDefault(), "%d", rgb[index])
                setTextColor(dialogTextColor)
                gravity = Gravity.END
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = label
                    setTextColor(dialogTextColor)
                    layoutParams = LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(value, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            content.addView(row)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    rgb[index] = progress
                    value.text = String.format(Locale.getDefault(), "%d", progress)
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
        fillMarginDp = WidgetSettings.DEFAULT_FILL_MARGIN_DP
        editorLightBg = EditorSettings.DEFAULT_LIGHT_BG
        editorLightText = EditorSettings.DEFAULT_LIGHT_TEXT
        editorDarkBg = EditorSettings.DEFAULT_DARK_BG
        editorDarkText = EditorSettings.DEFAULT_DARK_TEXT
        persist()
        binding.fontSizeSeek.progress = (fontSp - 10).toInt()
        binding.bgAlphaSeek.progress = bgAlpha
        binding.fillMarginSeek.progress = fillMarginDp
        renderTextSwatches()
        renderBgSwatches()
        renderEditorSwatches()
        updatePreview()
    }

    // ---------- v8.2.0：备份 / 换机迁移 ----------

    /** 导出：把当前设备上的正文 + 设置写进用户选定的文件 */
    private fun writeBackupTo(uri: Uri) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    BackupCodec.encode(BackupStore.snapshot(this@SettingsActivity), System.currentTimeMillis())
                }.getOrElse { e ->
                    Log.w(TAG, "导出失败：生成备份内容时出错", e)
                    null
                }
            }
            if (json == null) {
                toast(getString(R.string.backup_export_failed))
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // "wt" = 截断后写入：用户选了已有文件时必须是覆盖，不能留下旧尾巴
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("openOutputStream 返回 null")
                }.onFailure { e -> Log.w(TAG, "导出失败：写入所选文件出错", e) }.isSuccess
            }
            toast(getString(if (ok) R.string.backup_exported else R.string.backup_export_failed))
        }
    }

    /** 读取用户选定的文件并解码；失败原因明确告诉用户（"不是备份"与"读不出来"是两回事） */
    private fun readBackupFrom(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("openInputStream 返回 null")
                }.onFailure { e -> Log.w(TAG, "恢复失败：无法读取所选文件", e) }.getOrNull()
            }
            if (text == null) {
                toast(getString(R.string.backup_import_unreadable))
                return@launch
            }
            when (val decoded = BackupCodec.decode(text)) {
                is BackupCodec.Decoded.Bad ->
                    toast(getString(R.string.backup_import_invalid, badReasonText(decoded.reason)))

                is BackupCodec.Decoded.Ok -> confirmRestore(decoded)
            }
        }
    }

    /**
     * 恢复前必须确认：这是**整机覆盖**（正文 + 外观 + WebDAV 设置），不可撤销。
     * 弹窗里把"备份正文多少字"摆出来，用户能据此判断自己选的是不是那份想要的备份。
     */
    private fun confirmRestore(decoded: BackupCodec.Decoded.Ok) {
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_import_confirm_title)
            .setMessage(
                getString(R.string.backup_import_confirm_message, decoded.backup.content.length)
            )
            .setPositiveButton(R.string.backup_import_confirm) { _, _ -> applyRestore(decoded) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyRestore(decoded: BackupCodec.Decoded.Ok) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                BackupStore.restore(this@SettingsActivity, decoded.backup)
            }
            if (!ok) {
                toast(getString(R.string.backup_import_failed))
                return@launch
            }
            toast(
                getString(
                    if (decoded.syncDropped) R.string.backup_imported_partial
                    else R.string.backup_imported
                )
            )
            // 编辑页里那份内容此刻已经过期：把恢复后的正文原样交回去，由它**同步**替换编辑器文本。
            // 不这样做的话，用户从本页返回后随手退出，就会拿恢复前的旧内容覆盖刚恢复的内容。
            setResult(
                RESULT_OK,
                Intent().putExtra(MainActivity.EXTRA_RESTORED_CONTENT, decoded.backup.content)
            )
            finish()
        }
    }

    /** 与本应用其它页面同款的一句话提示（短、不打扰，只有真正需要用户知道结果时才弹） */
    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun badReasonText(reason: BackupCodec.BadReason): String = getString(
        when (reason) {
            BackupCodec.BadReason.NOT_JSON -> R.string.backup_bad_not_json
            BackupCodec.BadReason.FOREIGN_APP -> R.string.backup_bad_foreign_app
            BackupCodec.BadReason.BAD_FORMAT -> R.string.backup_bad_format
            BackupCodec.BadReason.NEWER_FORMAT -> R.string.backup_bad_newer_format
            BackupCodec.BadReason.NO_CONTENT -> R.string.backup_bad_no_content
        }
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 从当前主题解析颜色属性（浅色/深色模式自动适配）。
     * 主题属性可能解析成 ColorStateList 资源而不是直接的 ARGB 整数，
     * 此时 `TypedValue.data` 不是颜色，需要按资源取默认色（旧实现直接用了 data）。
     */
    private fun themeAttrColor(attr: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return fallbackTextColor()
        val isColor = value.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            value.type <= TypedValue.TYPE_LAST_COLOR_INT
        if (isColor) return value.data
        if (value.resourceId != 0) {
            try {
                AppCompatResources.getColorStateList(this, value.resourceId)
                    ?.defaultColor
                    ?.let { return it }
            } catch (_: Exception) {
                // 不是 ColorStateList，走兜底
            }
        }
        return fallbackTextColor()
    }

    private fun fallbackTextColor(): Int =
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        ) Color.WHITE else Color.BLACK

    companion object {
        /** 日志 TAG：导出/恢复失败时 `adb logcat -s SettingsActivity` 能看到原因 */
        private const val TAG = "SettingsActivity"

        /** 备份文件的 MIME 类型（导出时用；导入时用通配类型，见 [importBackup]） */
        private const val BACKUP_MIME = "application/json"
    }
}
