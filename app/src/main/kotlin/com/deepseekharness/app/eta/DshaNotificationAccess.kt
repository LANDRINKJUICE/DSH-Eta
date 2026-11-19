package com.deepseekharness.app.eta

import android.content.Context
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.deepseekharness.app.etatools.device.NotificationAccess

/**
 * Eta [NotificationAccess] 的 DSHA 宿主实现（第一版：只读能力探测）。
 *
 * 当前 DSHA 快照未声明任何 NotificationListenerService（AndroidManifest.xml 中无
 * BIND_NOTIFICATION_LISTENER_SERVICE），因此本实现只如实报告「本包未被授予通知监听权」，
 * currentNotifications() 返回 null。注意 EtatoolsService.create 目前没有 notificationAccess
 * 入参，本类第一版不会被注入，仅作为接口落地与后续扩展点。
 */
class DshaNotificationAccess : NotificationAccess {

    override fun isEnabled(context: Context): Boolean {
        val ownPackage = context.packageName
        val enabled = try {
            Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty()
        } catch (throwable: Throwable) {
            ""
        }
        if (enabled.split(':').any { it.startsWith("$ownPackage/") || it == ownPackage }) {
            return true
        }
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? android.app.NotificationManager
            manager?.enabledNotificationListeners?.any { it.packageName == ownPackage } == true
        } catch (throwable: Throwable) {
            false
        }
    }

    override fun currentNotifications(): Array<StatusBarNotification>? = null

    companion object {
        private const val ENABLED_LISTENERS = "enabled_notification_listeners"
    }
}
