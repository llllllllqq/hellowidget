package moe.hellowidget

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 首次启动时的通知权限检查（v8.5.5）。
 *
 * 为什么需要它：Android 13（API 33）起 `POST_NOTIFICATIONS` 是运行时权限，新安装的应用
 * **默认没有通知权限** —— 同步照常能跑，但通知栏里看不到失败提醒。用户要求「首次启动 app 时
 * 检查通知权限是否正常，没有就主动申请」，因此这里只在**全新启动且从未询问过**时走一次。
 *
 * 官方依据（都来自 `android docs`）：
 *  - 《Notification runtime permission》
 *    （`kb://android/develop/ui/compose/notifications/notification-permission`）：
 *    Android 13+ 用运行时权限控制非豁免通知；targetSdk 33+ 时应用自己决定弹窗时机，
 *    「Use this opportunity to explain to users why the app needs this permission」；
 *    拒绝后应优雅降级。还明确前台服务通知也受该权限管辖（拒绝后只在 Task Manager 可见）。
 *  - 《Requesting App Permissions》
 *    （`kb://android/training/permissions/requesting`）：
 *    先 `checkSelfPermission`，需要解释时 `shouldShowRequestPermissionRationale`，
 *    再用 `ActivityResultContracts.RequestPermission` 请求；拒绝后不要反复打扰。
 *  - 《Notification runtime permission》的 “Check whether your app can send notifications”：
 *    发通知前用 `NotificationManager.areNotificationsEnabled()` 判断用户是否允许了通知。
 *
 * 边界：
 *  - API 32 及以下没有这个运行时权限，用户只可能在系统设置里关掉通知 —— 只能引导去设置；
 *  - 权限被永久拒绝（「不再询问」）后 `launch()` 不会再弹系统窗，也只能引导去设置；
 *  - 这里只检查**应用级**通知开关，不逐个检查渠道：`webdav_sync` 渠道是同步第一次真正
 *    上传时才创建的，首次启动时它还不存在（刻意保持启动零副作用）。
 */
internal object NotificationPermissionPrompt {

    /** `hello_prefs` 里的「已经检查/询问过一次」标记：跨进程重启保留，避免每次启动都打扰。 */
    private const val KEY_ASKED = "notification_permission_asked"

    fun hasBeenAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ASKED, false)

    /** 在真正展示询问 UI 之前调用：即使用户点「暂不」或直接返回，也不再自动问第二次。 */
    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** 与 `SyncNotifier.hasNotificationPermission` 的区别：这里还包含用户在系统设置里关掉通知的情况。 */
    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** API 33+ 且运行时权限尚未授予 —— 此时可以拉起系统权限弹窗；否则只能去系统设置。 */
    fun canRequestRuntimePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

    /**
     * 是否已经被系统标记为「不再询问」。
     *
     * **只能在权限请求的回调里调用**：在第一次请求之前 `shouldShowRequestPermissionRationale`
     * 同样返回 false（系统还不知道用户会怎么选），拿它当「永久拒绝」会误判。
     *
     * 返回 true 时说明 `launch()` 已经不会再弹系统窗，唯一出路是引导用户去设置页手动开启。
     */
    fun isPermanentlyDenied(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            )

    /**
     * 打开本应用的通知设置。
     *
     * API 26+ 用「应用通知设置」页（直达渠道列表）；API 24–25 没有这个页面，只能打开
     * 应用详情页，用户再从那里进通知。两个 Intent 都用 `runCatching` 包住：
     * 个别 ROM 可能没有对应 Activity，提示失败不该让应用崩溃。
     */
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
}
