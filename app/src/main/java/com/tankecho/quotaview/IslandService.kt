package com.tankecho.quotaview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.QuotaWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 灵动岛 v2: Android 16 Live Updates (Notification.ProgressStyle).
 * ColorOS 16 流体云自动提升为摄像头附近原生岛.
 *
 * 映射: 额度用量 → ProgressStyle 进度条 (Segment: 已用/剩余)
 *       时间进度 → setWhen/时间轴 (Points: 起点已过/终点回血)
 */
class IslandService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var currentWindow: QuotaWindow? = null

    private val poll = object : Runnable {
        override fun run() {
            scope.launch { refreshData() }
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundLiveUpdate()
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch { refreshData() }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun startForegroundLiveUpdate() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "灵动岛", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
        // 先占位, 数据到达后更新为 ProgressStyle
        startForeground(NOTI_ID, buildNotification(null))
    }

    private fun buildNotification(win: QuotaWindow?): Notification {
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val prov = prefs.getString("ring_provider", "codex")!!
        val provName = if (prov == "codex") "Codex" else "GLM"

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$provName · 灵动岛")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setFlag(Notification.FLAG_ONGOING_EVENT, true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )

        if (win == null) {
            return builder.setContentText("加载中…").build()
        }

        val usedPct = win.usedPercent.coerceIn(0, 100)
        val timePct = win.timeElapsedPercent.coerceIn(0, 100)

        builder.setContentText("${win.label} · 用量 ${usedPct}% · 已过 ${timePct}%")

        if (android.os.Build.VERSION.SDK_INT >= 36) {
            // Android 16+: ProgressStyle → ColorOS 流体云 / 原生 Live Update
            val style = Notification.ProgressStyle()
                .setProgress(usedPct)
                .setProgressSegments(listOf(
                    Notification.ProgressStyle.Segment(usedPct),
                    Notification.ProgressStyle.Segment(100 - usedPct),
                ))
                .setProgressPoints(listOf(
                    Notification.ProgressStyle.Point(timePct),
                ))
                .setStyledByProgress(true)
            builder.setStyle(style)
        }
        return builder.build()
    }

    private suspend fun refreshData() {
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val st = withContext(Dispatchers.IO) {
            val prov = prefs.getString("ring_provider", "codex")!!
            runCatching {
                if (prov == "codex") CodexApi.fetch(prefs.getString("codex_token", "").orEmpty(), prefs.getString("codex_account", "").orEmpty())
                else GlmApi.fetch(prefs.getString("glm_key", "").orEmpty())
            }.getOrNull()
        } ?: return

        val winKey = prefs.getString("ring_window", "primary") ?: "primary"
        val win = st.windows.firstOrNull { labelToKey(it.label) == winKey } ?: st.windows.firstOrNull()
        currentWindow = win

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, buildNotification(win))
    }

    private fun labelToKey(label: String): String = when {
        label.startsWith("MCP") -> "mcp"
        label.contains("周") -> "week"
        else -> "primary"
    }

    companion object {
        const val CHANNEL_ID = "qv_island"
        const val NOTI_ID = 1001
        const val ACTION_STOP = "stop"
    }
}
