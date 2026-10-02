package moe.hellowidget

import androidx.lifecycle.ViewModel

/**
 * 只做一件事：让 [UndoHistory] 在**配置变更**（旋转、深色模式重建、字体缩放等）之后依然存在。
 *
 * 放在 ViewModel 里的原因：编辑器内容与光标由系统在重建时自动恢复，
 * 如果撤回历史随 Activity 一起销毁，「重建后再也撤不回打开时的内容」就成了一个用户可见的漏洞。
 * 进程被系统回收后历史自然消失 —— 那已经是新的一次「打开应用」，撤回的下界也随之变成新内容。
 */
internal class UndoHistoryViewModel : ViewModel() {
    val history = UndoHistory()
}
