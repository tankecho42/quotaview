package com.tankecho.quotaview

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 诊断用: 读回 Live Updates 通知 (id=1002) 在系统手中的真实状态.
 * 只跟踪 LIVE_NOTI_ID — 前台服务载体通知 (id=1001) 无 ProgressStyle, 会污染结果.
 */
class QVNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications?.forEach { handle(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        if (Build.VERSION.SDK_INT < 36) return
        if (sbn.packageName != packageName) return
        if (sbn.id != IslandService.LIVE_NOTI_ID) return   // 只看 Live 通知
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
        android.util.Log.i("QVIsland", "readback(live): promoted=$promoted promotable=$promotable ongoing=$ongoing flags=${Integer.toBinaryString(n.flags)}")
    }
}
