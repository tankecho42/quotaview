package com.tankecho.quotaview

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.QuotaWindow
import com.tankecho.quotaview.ui.DualRingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 灵动岛: SYSTEM_ALERT_WINDOW 悬浮窗, 贴状态栏中央.
 * 左: provider icon · 右: 双同心圆环(外环额度/内环时间)
 * 拖动可临时移位, 点击打开 App.
 */
class IslandService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var island: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            scope.launch { refreshData() }
            handler.postDelayed(this, 5 * 60 * 1000L) // 5min
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithType()
        showIsland()
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        // 配置变化时立即刷新一次
        scope.launch { refreshData() }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        island?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---------- 前台服务通知(保活, 低存在感) ----------

    private fun startForegroundWithType() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "灵动岛", NotificationManager.IMPORTANCE_MIN)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
        val noti = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QuotaView 灵动岛运行中")
            .setOngoing(true)
            .build()
        startForeground(NOTI_ID, noti)
    }

    // ---------- 悬浮窗 ----------

    private fun showIsland() {
        val d = resources.displayMetrics.density
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)

        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((d * 6).toInt(), (d * 4).toInt(), (d * 10).toInt(), (d * 4).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                color = android.content.res.ColorStateList.valueOf(0xF2101218.toInt())
                cornerRadius = d * 22
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(if (prefs.getString("ring_provider", "codex") == "codex") R.drawable.ic_openai else R.drawable.ic_zai)
            layoutParams = LinearLayout.LayoutParams((d * 20).toInt(), (d * 20).toInt()).apply { rightMargin = (d * 6).toInt() }
        }
        val ring = DualRingView(this).apply {
            iconRes = 0
            centerText = null
        }
        ring.tag = RING_TAG
        pill.addView(icon)
        pill.addView(ring, LinearLayout.LayoutParams((d * 34).toInt(), (d * 34).toInt()))

        // 拖动 + 点击
        var downX = 0f; var downY = 0f; var startX = 0f; var startY = 0f; var moved = false
        pill.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = 0f; startY = 0f
                    moved = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(ev.rawX - downX) > 12 || kotlin.math.abs(ev.rawY - downY) > 12) moved = true
                    updatePosition(ev.rawX - downX, ev.rawY - downY); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }; true
                }
                else -> false
            }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (d * 3).toInt()
        }
        wm.addView(pill, lp)
        island = pill
    }

    private fun updatePosition(dx: Float, dy: Float) {
        island?.let { v ->
            runCatching {
                wm.updateViewLayout(v, (v.layoutParams as WindowManager.LayoutParams).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x += dx.toInt(); y += dy.toInt()
                })
            }
        }
    }

    // ---------- 数据 ----------

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
        val win: QuotaWindow? = st.windows.firstOrNull { labelToKey(it.label) == winKey } ?: st.windows.firstOrNull()
        val ring = island?.findViewWithTag<DualRingView>(RING_TAG) ?: return
        ring.usedPercent = win?.usedPercent?.toFloat() ?: 0f
        ring.timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
        ring.ringColor = when {
            win == null || win.pace == null -> 0xFF6E8BFF.toInt()
            win.pace!! > 1.5f -> 0xFFE5484D.toInt()
            win.pace!! > 1f -> 0xFFF5A524.toInt()
            else -> 0xFF6E8BFF.toInt()
        }
        ring.invalidate()
    }

    private fun labelToKey(label: String): String = when {
        label.startsWith("MCP") -> "mcp"
        label.contains("周") -> "week"
        else -> "primary"
    }

    companion object {
        const val CHANNEL_ID = "qv_island"
        const val NOTI_ID = 1001
        const val RING_TAG = "qv_ring"
        const val ACTION_STOP = "stop"
    }
}
