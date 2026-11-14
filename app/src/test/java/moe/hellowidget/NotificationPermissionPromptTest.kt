package moe.hellowidget

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v8.5.5 首次启动通知权限检查的纯逻辑测试。
 *
 * 这里只测「该不该问 / 能不能请求 / 去哪个设置页」这三类可确定性判断；
 * 真正的弹窗与权限请求由 [MainActivityNotificationPromptTest] 在 Activity 生命周期里验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPermissionPromptTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val notificationManager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        app.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(notificationManager).setNotificationsEnabled(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun notificationsEnabled_doesNotNeedPrompt() {
        assertTrue(NotificationPermissionPrompt.notificationsEnabled(app))
        assertFalse(NotificationPermissionPrompt.hasBeenAsked(app))
    }

    @Test
    fun notificationsDisabled_needsPrompt() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        assertFalse(NotificationPermissionPrompt.notificationsEnabled(app))
        assertFalse("首次启动尚未询问", NotificationPermissionPrompt.hasBeenAsked(app))
    }

    @Test
    fun markAsked_isPersistedAndStopsAsking() {
        NotificationPermissionPrompt.markAsked(app)

        assertTrue(NotificationPermissionPrompt.hasBeenAsked(app))
    }

    @Test
    fun api33_permissionDenied_canRequestRuntimePermission() {
        assertTrue(NotificationPermissionPrompt.canRequestRuntimePermission(app))

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse(NotificationPermissionPrompt.canRequestRuntimePermission(app))
    }

    @Test
    @Config(sdk = [32])
    fun api32_cannotRequestRuntimePermission() {
        // API 32 及以下没有 POST_NOTIFICATIONS 运行时权限，只能去系统设置
        assertFalse(NotificationPermissionPrompt.canRequestRuntimePermission(app))
    }

    @Test
    fun openNotificationSettings_api26Plus_opensAppNotificationSettings() {
        NotificationPermissionPrompt.openNotificationSettings(app)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        val intent = started!!
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue("从非 Activity Context 启动必须带 NEW_TASK", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    @Config(sdk = [24])
    fun openNotificationSettings_api24_opensAppDetailsSettings() {
        NotificationPermissionPrompt.openNotificationSettings(app)

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        val intent = started!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
    }
}
