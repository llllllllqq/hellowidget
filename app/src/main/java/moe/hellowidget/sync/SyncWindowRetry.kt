package moe.hellowidget.sync

/**
 * v8.0.3：**前台窗口内的有限次快速重试**策略。
 *
 * ## 为什么要有它（而不是"把通知挂久一点"）
 * 前台服务确实带来两样东西（官方《Power management resource limits》的 app-state 表：
 * 「进程在跑前台服务 ⇒ Network: 无限制」；《Service bindings and process states》：
 * FGS 进程的 `oom_score_adj` 是 0~200，而 cached 是 700+）。但**窗口的长度本身不值钱** ——
 * 值钱的是窗口里有没有事情在做。此前窗口正好等于一次上传的耗时：退出瞬间网络刚抖一下
 * （Wi-Fi 切蜂窝、刚息屏、DNS 抖动），那一次 PUT 失败，上传就整个交给系统兜底任务
 * （v8.1.0 起是 30 秒后；当时是 65 秒后），用户看到的是"退出了、没传上去、过一会儿才补上"。
 *
 * 现在这段窗口被用来**把那次抖动吃掉**：只要失败得足够早、且属于[暂时性失败][isTransient]，
 * 就在窗口内退避重试最多 [MAX_RETRIES] 次。重试仍失败才落回系统兜底任务（原有行为不变）。
 *
 * ## 与"永远挂着通知"的区别
 * 通知只在**真的在重试**时改变文案（`网络不稳，正在重试（1/2）…`），窗口长度依旧由工作决定：
 * 一次成功的同步窗口还是几百毫秒；只有连续失败时窗口才会长到几秒。
 * 这是本项目「最小提示」偏好的直接延续 —— 也避免了"有通知 = 有上传"这种新误判。
 *
 * ## 为什么只在这三条路径生效
 * 窗口内重试的全部依据是"我们正处在前台窗口里"（前台服务在跑，或进程内兜底那次仍在用户可见的转换中）。
 * **系统兜底任务（`SyncRetryJobService`）刻意不做窗口内重试**：它没有前台服务、不显示任何通知，
 * 而且它自己就有 60 秒起步的指数退避与 [SyncRetry.MAX_ATTEMPTS] 预算 ——
 * 在里面再放大请求次数只会让"传不上去的内容"更频繁地敲服务器。
 *
 * ## 边界（都与 shortService 的约 3 分钟上限有关）
 *  - [RETRY_DEADLINE_MS]：只有"失败得很早"才重试。晚失败（例如读超时 30 秒）说明链路确实慢或不通，
 *    把剩余时间交给兜底任务更划算，也不至于把服务推到系统时限附近。
 *  - [DELAYS_MS] 刻意很短：窗口存在的意义是"立刻把这次补上"，不是把等待搬进前台
 *    （真要等，兜底任务更合适，而且不占通知栏）。
 *  - 极端路径（PUT 超时 60s → MKCOL 超时 60s → PUT 再超时）**本身**就可能贴到 shortService 上限，
 *    这在本次改动之前就存在，由 `SyncService.onTimeout()` → `stopSelf()` 兜住，内容不丢
 *    （兜底任务接手）。窗口内重试不会把这个极端路径变得更常见：它的门槛是"早失败"。
 */
internal object SyncWindowRetry {

    /** 窗口内最多再试几次（不含第一次）。 */
    const val MAX_RETRIES = 2

    /**
     * 每次重试前的退避（第 1 次重试前 1 秒、第 2 次前 3 秒）。
     *
     * 取值只为一件事：给"刚切网 / 刚息屏 / DNS 抖动"这类瞬时状态一点恢复时间。
     * 再长就应该交给系统兜底任务 —— 那才是"等"该待的地方。
     */
    val DELAYS_MS = longArrayOf(1_000L, 3_000L)

    /**
     * 只有失败发生在同步开始后的这段时间内，才值得在窗口内重试。
     * 20 秒：覆盖"连接/握手/首包"这类早期失败，同时保证不会在接近窗口末尾时又发起一次可能很长的请求。
     */
    const val RETRY_DEADLINE_MS = 20_000L

    /**
     * @param retriesDone 已经用掉的窗口内重试次数
     * @param error 上一次尝试的失败原因（[isTransient] 决定它值不值得再试）
     * @param elapsedMs 这次同步从开始到现在的耗时
     */
    fun shouldRetry(retriesDone: Int, error: WebDavError, elapsedMs: Long): Boolean {
        if (retriesDone >= MAX_RETRIES) return false
        if (!error.isTransient) return false
        if (elapsedMs > RETRY_DEADLINE_MS) return false
        return true
    }

    /** 第 [retriesDone] 次重试（已用次数）之前要等多久 */
    fun delayBeforeNext(retriesDone: Int): Long =
        DELAYS_MS[retriesDone.coerceIn(0, DELAYS_MS.lastIndex)]
}
