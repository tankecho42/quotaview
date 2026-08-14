package com.tankecho.quotaview

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 诊断用: 读回自己已 post 通知的真实 flags, 判断系统是否真的提升了它 (FLAG_PROMOTED_ONGOING).
 * 需要用户在 系统设置→通知使用权 里授权.
 */
class QVNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 授权后立即读回当前已存在的通知, 不用等服务下次 post
        activeNotifications?.forEach { handle(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName != packageName) return
        val n = sbn.notification
        val promoted = n.flags and android.app.Notification.FLAG_PROMOTED_ONGOING != 0
        val ongoing = n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
        val promotable = runCatching { n.hasPromotableCharacteristics() }.getOrDefault(false)
        getSharedPreferences("qv", MODE_PRIVATE).edit()
            .putBoolean("island_promoted", promoted)
            .putBoolean("island_promotable", promotable)
            .putBoolean("island_ongoing_readback", ongoing)
            .putLong("island_diag_ts", System.currentTimeMillis())
            .apply()
        android.util.Log.i("QVIsland", "readback: promoted=$promoted promotable=$promotable ongoing=$ongoing flags=${Integer.toBinaryString(n.flags)}")
    }
}
