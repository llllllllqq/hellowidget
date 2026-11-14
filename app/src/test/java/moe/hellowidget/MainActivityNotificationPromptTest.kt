package moe.hellowidget

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/**
 * v8.5.5：首次启动真的会先说明、再申请通知权限。
 *
 * 用 Robolectric 的 Activity 生命周期断言三件事：
 *  1. 通知关闭 + 首次启动 → 出现说明框，点「开启通知」后真的发起了
 *     `POST_NOTIFICATIONS` 运行时请求（`ShadowActivity.lastRequestedPermission`）；
 *  2. 通知已经开启 → 不弹框（不能为了权限打扰本来就没问题的用户）；
 *  3. 已经问过 → 再次启动不重复弹框（「暂不」也不会变成每次启动都问）。
 *
 * 系统权限弹窗本身由 Android 绘制，JVM 里没有；这里验证的是「应用确实把请求交给了系统」。
 * 真机上「系统弹窗长什么样、用户点允许后通知是否出现」仍需要用户验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityNotificationPromptTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val notificationManager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(notificationManager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowDialog.reset()
    }

    @Test
    fun firstLaunch_notificationsOff_explainsThenRequestsPermission() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("首次启动且通知关闭时必须先显示说明框", dialog)
        val alert = dialog!!
        assertTrue("说明框必须可见", alert.isShowing)
        assertTrue("展示过就要落标记，不能每次启动都问", NotificationPermissionPrompt.hasBeenAsked(app))

        alert.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val request = shadowOf(activity).lastRequestedPermission
        assertNotNull("点「开启通知」必须真的把权限请求交给系统", request)
        assertArrayEquals(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            request!!.requestedPermissions
        )
    }

    @Test
    fun firstLaunch_notificationsOn_doesNotShowPrompt() {
        // 通知已经开启：即使用户从没被问过，也不该弹框打扰
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitEditorEnabled(controller.get())
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("通知可用时不应出现权限说明框", ShadowDialog.getLatestDialog())
        assertTrue("检查过就落标记，后续启动不再重复检查", NotificationPermissionPrompt.hasBeenAsked(app))
    }

    @Test
    fun secondLaunch_afterAsked_doesNotShowAgain() {
        shadowOf(notificationManager).setNotificationsEnabled(false)
        NotificationPermissionPrompt.markAsked(app)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitEditorEnabled(controller.get())
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("已经问过一次，再次启动不得重复弹框", ShadowDialog.getLatestDialog())
    }

    @Test
    @Config(sdk = [32])
    fun api32_notificationsOff_guidesToSystemSettings() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull(dialog)
        dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // API 32 没有运行时权限可申请，只能去系统设置；API 26+ 直达应用通知设置页
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started!!.action)
    }

    @Test
    fun api33_notificationsDisabledButPermissionGranted_guidesToSystemSettings() {
        // 运行时权限已经给了，但用户在系统里关掉了全部通知：再 launch() 不会有弹窗，只能去设置
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notificationManager).setNotificationsEnabled(false)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        awaitEditorEnabled(activity)
        shadowOf(Looper.getMainLooper()).idle()

        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull(dialog)
        dialog!!.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("必须打开系统通知设置页", started)
        val intent = started!!
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
