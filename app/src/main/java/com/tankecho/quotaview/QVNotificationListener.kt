package com.tankecho.quotaview

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 诊断用: 读回自己已 post 通知的真实 flags, 判断系统是否真的提升了它 (FLAG_PROMOTED_ONGOING).
 * 需要用户在 系统设置→通知使用权 里授权.
 */
class QVNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != packageName) return
        val n = sbn.notification
        val promoted = n.flags and android.app.Notification.FLAG_PROMOTED_ONGOING != 0
        val ongoing = n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
        getSharedPreferences("qv", MODE_PRIVATE).edit()
            .putBoolean("island_promoted", promoted)
            .putBoolean("island_ongoing_readback", ongoing)
            .putLong("island_diag_ts", System.currentTimeMillis())
            .apply()
        android.util.Log.i("QVIsland", "readback: promoted=$promoted ongoing=$ongoing flags=${Integer.toBinaryString(n.flags)}")
    }
}
