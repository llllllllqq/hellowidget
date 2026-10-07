package moe.hellowidget

import android.text.Editable
import android.text.TextWatcher

/**
 * 编辑器的「无限次撤回」历史（v7.7）。
 *
 * ## 设计要点
 *  - **只记录每一次文本变更本身**（替换起点、被删掉的旧文本、插入的新文本），
 *    而不是保存整份文本快照。便签上限 10 万字符，逐键快照会迅速吃光内存；
 *    变更加起来的总量才是真正的编辑量，与文本长度无关。
 *  - **第 N 次编辑记录下来的 `removed` 正是那次编辑之前的文本**，因此把历史反向套用
 *    一遍，最后必然精确回到「打开应用（或系统重建恢复）时的内容」——这就是撤回次数不设
 *    上限（用户要求「无限次」）的下界，不需要额外保存初始快照。
 *  - 只记录**用户造成的**变更：程序化写入（读盘恢复、系统重建恢复）在监听器挂上之前完成，
 *    撤回本身产生的变更用 [applying] 标志排除（否则撤回会被记成一步新编辑，变成「重做」）。
 *
 * ## 线程约定
 * 只在主线程使用：EditText 的文本变更回调与菜单点击都在主线程，无需额外同步。
 */
internal class UndoHistory : TextWatcher {

    /** 一次文本变更：在 [start] 处把 [removed] 换成了 [inserted] */
    private class Edit(val start: Int, val removed: String, val inserted: String)

    private val edits = ArrayList<Edit>()

    /** 正在反向套用历史：此时产生的文本变更不得再记进历史 */
    private var applying = false

    /** [beforeTextChanged] 里缓存的「本次被替换掉的旧文本」，交给 [onTextChanged] 入栈 */
    private var pendingRemoved = ""

    /** 是否还有可撤回的编辑（false = 内容已经回到刚打开时的样子） */
    val canUndo: Boolean get() = edits.isNotEmpty()

    /** 已记录的编辑步数（历史深度；仅供测试与调试观察） */
    val size: Int get() = edits.size

    /**
     * v8.2.0：在**不计入历史**的状态下执行一次程序化写入（恢复备份时用它整份替换正文）。
     *
     * 为什么必须走这里、而不是"摘掉监听器再挂回去"：监听器的挂载时机由编辑页的异步读盘
     * 决定（读盘完成才挂），"摘掉再挂回"会在两条路径交叉时把同一个监听器挂上两次，
     * 于是每一步编辑被记两遍、撤回要按两次 —— 而那种时序只有在用户手速极快时才出现。
     * 复用 [applying] 标志则与"撤回自己产生的变更"共用同一条排除逻辑，没有时序假设。
     */
    fun <T> withoutRecording(block: () -> T): T = try {
        applying = true
        block()
    } finally {
        applying = false
    }

    /** 清空历史（恢复备份后，撤回的终点应当是"恢复后的内容"） */
    fun clear() {
        edits.clear()
        pendingRemoved = ""
    }

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        if (applying || s == null) {
            pendingRemoved = ""
            return
        }
        // 此刻 s 还是「编辑前」的文本，count 是被替换掉的字符数
        pendingRemoved = s.subSequence(start, start + count).toString()
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (applying || s == null) return
        if (before == 0 && count == 0) return // 纯标记/样式变更，不是文本编辑
        edits.add(Edit(start, pendingRemoved, s.subSequence(start, start + count).toString()))
        pendingRemoved = ""
    }

    override fun afterTextChanged(s: Editable?) {}

    /**
     * 撤回一步：把最近一次编辑反向套用回 [editable]。
     *
     * @return 撤回后光标应处的位置（改动处末尾）；已经回到最初内容时返回 null，文本不变。
     */
    fun undo(editable: Editable): Int? {
        if (edits.isEmpty()) return null
        val edit = edits.removeAt(edits.lastIndex)
        applying = true
        try {
            editable.replace(edit.start, edit.start + edit.inserted.length, edit.removed)
        } finally {
            applying = false
        }
        return (edit.start + edit.removed.length).coerceIn(0, editable.length)
    }
}
