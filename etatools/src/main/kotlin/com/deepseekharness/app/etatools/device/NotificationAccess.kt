package com.deepseekharness.app.etatools.device

/**
 * 通知使用权与当前活动通知的只读访问契约。
 * 由宿主侧 NotificationListenerService 实现注入；工具层不直接依赖具体 Service 类。
 */
interface NotificationAccess {
    fun isEnabled(context: android.content.Context): Boolean
    fun currentNotifications(): Array<android.service.notification.StatusBarNotification>?
}
