package moe.hellowidget

import android.text.Editable
import android.text.TextWatcher

/**
 * 编辑器的「无限次撤回」历史（v7.7）。
 *
 * ## 设计要点
 *  - **只记录每一次文本变更本身**（替换起点、被删掉的旧文本、插入的长度），
 *    而不是保存整份文本快照。便签上限 1 万字符（v8.4.0），逐键快照会很快吃光内存；
 *    变更加起来的总量才是真正的编辑量，与文本长度无关。
 *  - **第 N 次编辑记录下来的 `removed` 正是那次编辑之前的文本**，因此把历史反向套用
 *    一遍，最后必然精确回到「打开应用（或系统重建恢复）时的内容」——这就是撤回次数不设
 *    上限（用户要求「无限次」）的下界，不需要额外保存初始快照。
 *  - 只记录**用户造成的**变更：程序化写入（读盘恢复、系统重建恢复）在监听器挂上之前完成，
 *    撤回本身产生的变更用 [applying] 标志排除（否则撤回会被记成一步新编辑，变成「重做」）。
 *
 * ## v8.4.0：内存紧凑化（撤回次数仍然无上限）
 * 旧实现每次编辑存一个 `Edit` 对象 + 两个 String（`removed` 与 `inserted`）。逐键编辑时
 * 大部分字段是空的，对象与 String 头的固定开销远大于数据本身。现在：
 *  - **不存 `inserted` 文本**，只存它的长度（撤回插入时按长度删除即可）；
 *  - 起点与长度放进 `IntArray`（无装箱），被删掉的文本放进 `ArrayList<String>`
 *    （插入类编辑的 `removed` 是空串，共用同一个空串实例，不产生新对象）。
 *
 * 一次按键从「对象 + 两个 String（约 150 字节）」降到「两个 int + 一个引用（约 16~24 字节）」，
 * 长会话的内存占用约降一个数量级；撤回的**次数**依旧不设上限，行为完全不变。
 * 数据上限由正文长度（[MainActivity.MAX_CONTENT_CHARS]，1 万字符）控制。
 *
 * ## 线程约定
 * 只在主线程使用：EditText 的文本变更回调与菜单点击都在主线程，无需额外同步。
 */
internal class UndoHistory : TextWatcher {

    /** 起点与「插入的长度」用原生数组存，避免每条记录一个对象的固定开销 */
    private var starts = IntArray(INITIAL_CAPACITY)
    private var insertedLengths = IntArray(INITIAL_CAPACITY)

    /** 被删掉的旧文本（插入类编辑这里是空串，共用 interned 空串，不额外分配） */
    private val removedTexts = ArrayList<String>(INITIAL_CAPACITY)

    /** 当前深度：starts / insertedLengths / removedTexts 的有效前缀长度 */
    private var depth = 0

    /** 正在反向套用历史：此时产生的文本变更不得再记进历史 */
    private var applying = false

    /** [beforeTextChanged] 里缓存的「本次被替换掉的旧文本」，交给 [onTextChanged] 入栈 */
    private var pendingRemoved = ""

    /** 是否还有可撤回的编辑（false = 内容已经回到刚打开时的样子） */
    val canUndo: Boolean get() = depth > 0

    /** 已记录的编辑步数（历史深度；仅供测试与调试观察） */
    val size: Int get() = depth

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
        if (applying || s == null) {
            pendingRemoved = ""
            return
        }
        // 此刻 s 还是「编辑前」的文本，count 是被替换掉的字符数。
        // 纯插入（count == 0）共用空串，不产生一个新的空 String 对象。
        pendingRemoved = if (count == 0) "" else s.subSequence(start, start + count).toString()
    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        if (applying || s == null) return
        if (before == 0 && count == 0) return // 纯标记/样式变更，不是文本编辑
        ensureCapacity(depth + 1)
        starts[depth] = start
        insertedLengths[depth] = count
        removedTexts.add(pendingRemoved)
        depth++
        pendingRemoved = ""
    }

    override fun afterTextChanged(s: Editable?) {}

    /**
     * 撤回一步：把最近一次编辑反向套用回 [editable]。
     *
     * 反向操作只有两步：先删掉那次编辑**插入**的字符（长度已知），再把当时**删掉**的文本
     * 放回去（文本已保存）。因此不需要保存"插入的文本"。
     *
     * @return 撤回后光标应处的位置（改动处末尾）；已经回到最初内容时返回 null，文本不变。
     */
    fun undo(editable: Editable): Int? {
        if (depth == 0) return null
        val index = depth - 1
        depth = index
        val start = starts[index]
        val insertedLength = insertedLengths[index]
        val removed = removedTexts.removeAt(index)
        applying = true
        try {
            editable.replace(start, start + insertedLength, removed)
        } finally {
            applying = false
        }
        return (start + removed.length).coerceIn(0, editable.length)
    }

    /** 按需扩容（×2），避免每个按键都换新数组 */
    private fun ensureCapacity(required: Int) {
        if (required <= starts.size) return
        val newSize = maxOf(required, starts.size * 2)
        starts = starts.copyOf(newSize)
        insertedLengths = insertedLengths.copyOf(newSize)
    }

    private companion object {
        /** 初始容量：一屏便签的常见编辑量，避免刚打开就扩容 */
        const val INITIAL_CAPACITY = 128
    }
}
