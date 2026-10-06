package moe.hellowidget

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.hellowidget.sync.SyncRetry

/**
 * v7.9：把「保存之后还有内容没传上去」这件事交给系统的那一条腿。
 *
 * [SyncRetry] 排入的持久化任务最终由系统在这里拉起进程执行一次同步：
 *  - 不显示任何通知（进度通知只属于 [SyncService] 那条前台服务路径），
 *    因此它只是"悄悄把欠的上传补上"，不会打扰用户；
 *  - 失败只按指数退避重试（退避与"哪些失败才值得重试"都在 [SyncRetry] 里）；
 *  - 与前台服务共用 [moe.hellowidget.sync.SyncManager] 的互斥锁，绝不并发 PUT。
 *
 * `onStartJob` 在主线程被调用，所以真正的同步放在 IO 协程里，并在做完后调用
 * [jobFinished]（`wantsReschedule = true` 表示请系统按退避再排一次）。
 */
class SyncRetryJobService : JobService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "系统重试任务开始执行（jobId=${params.jobId}）")
        scope.launch {
            val again = try {
                SyncRetry.runOnce(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 任何意外都按"还值得再试"处理：内容还在本地，不会丢
                Log.w(TAG, "系统重试任务异常", e)
                true
            }
            jobFinished(params, again)
        }
        return true // 还有异步工作在做，做完自己调 jobFinished
    }

    /**
     * 系统因为条件不再满足（例如没网了 / 需要让路给别的任务）停止本任务。
     * 返回 `true` = 请按退避重排：任务存在的唯一理由就是"还有内容没传上去"，
     * 而它只有在成功之后才会被 [SyncRetry.cancel] 撤销（内容没变时同步会直接短路成功）。
     */
    override fun onStopJob(params: JobParameters): Boolean {
        Log.i(TAG, "系统重试任务被系统停止（jobId=${params.jobId}），按退避重排")
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "SyncRetryJob"
    }
}
