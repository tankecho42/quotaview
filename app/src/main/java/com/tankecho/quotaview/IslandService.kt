package com.tankecho.quotaview

import android.animation.ValueAnimator
import android.app.Notification
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
import android.widget.LinearLayout
import android.widget.TextView
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.QuotaWindow
import com.tankecho.quotaview.ui.DualRingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 灵动岛 v3: 屏幕边缘吸附悬浮圆环.
 * 平时: 44dp 半透明小圆环贴屏幕边缘 (外环=用量, 内环=时间), 不起眼.
 * 拖动: 松手自动吸附最近左右边缘.
 * 点击: 展开详情卡 (大圆环 + provider icon + 数字), 再点收回.
 */
class IslandService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var currentWindow: QuotaWindow? = null

    private lateinit var wm: WindowManager
    private var ringParams: WindowManager.LayoutParams? = null
    private var ringView: View? = null          // 收起态小圆环
    private var expandedView: View? = null      // 展开态详情卡
    private var expanded = false

    private val dp: Float get() = resources.displayMetrics.density
    private val ringSize: Int get() = (44 * dp).toInt()

    private val poll = object : Runnable {
        override fun run() {
            scope.launch { refreshData() }
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundQuiet()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post(poll)
        scope.launch { refreshData() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        ringView?.let { runCatching { wm.removeView(it) } }
        expandedView?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }

    // ---------- 通知 (前台服务静默载体) ----------

    private fun startForegroundQuiet() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "灵动岛", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false); ch.setSound(null, null)
        nm.createNotificationChannel(ch)
        startForeground(NOTI_ID, Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QuotaView 悬浮圆环运行中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .build())
    }

    // ---------- 悬浮圆环 ----------

    private fun ringParams(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = ringSize; height = ringSize
            this.x = x; this.y = y
        }

    private fun savedParams(): WindowManager.LayoutParams {
        val p = getSharedPreferences("qv", MODE_PRIVATE)
        val defY = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        val x = p.getInt("ring_x", resources.displayMetrics.widthPixels - ringSize)
        val y = p.getInt("ring_y", defY)
        return ringParams(x, y)
    }

    private fun buildRing(): View =
        DualRingView(this).apply {
            alpha = 0.45f
            usedPercent = currentWindow?.usedPercent?.toFloat() ?: 0f
            timeElapsedPercent = currentWindow?.timeElapsedPercent?.toFloat() ?: 0f
            ringColor = healthColor(currentWindow)
        }

    private fun attachRing() {
        detachRing()
        val params = savedParams()
        val view = buildRing()
        view.setOnTouchListener { v, ev -> handleRingTouch(v, ev, params) }
        wm.addView(view, params)
        ringParams = params
        ringView = view
    }

    private fun detachRing() {
        ringView?.let { runCatching { wm.removeView(it) } }
        ringView = null
    }

    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var moved = false

    private fun handleRingTouch(v: View, ev: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                startX = params.x; startY = params.y
                moved = false
                v.alpha = 0.9f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (dx * dx + dy * dy > 100) moved = true
                params.x = startX + dx.toInt()
                params.y = (startY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - ringSize)
                runCatching { wm.updateViewLayout(v, params) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.alpha = 0.45f
                if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                    savePos(params)
                    toggleExpanded()
                } else {
                    snapToEdge(v, params)
                }
            }
        }
        return true
    }

    /** 松手吸附最近左右边缘 (动画) */
    private fun snapToEdge(v: View, params: WindowManager.LayoutParams) {
        val w = resources.displayMetrics.widthPixels
        val target = if (params.x + ringSize / 2 < w / 2) 0 else w - ringSize
        val anim = ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(v, params) }
            }
            doOnEnd { savePos(params) }
            start()
        }
    }

    private fun savePos(params: WindowManager.LayoutParams) {
        getSharedPreferences("qv", MODE_PRIVATE).edit()
            .putInt("ring_x", params.x).putInt("ring_y", params.y).apply()
    }

    // ---------- 展开详情卡 ----------

    private fun toggleExpanded() {
        if (expanded) collapse() else expand()
    }

    private fun expand() {
        val win = currentWindow
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val prov = prefs.getString("ring_provider", "codex")!!
        val provName = if (prov == "codex") "Codex" else "GLM"
        val iconRes = if (prov == "codex") R.drawable.ic_openai else R.drawable.ic_zai

        val rp = ringParams ?: return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xEE101218.toInt()); cornerRadius = 22 * dp
                setStroke((1 * dp).toInt(), 0x33FFFFFF)
            }
        }
        val bigRing = DualRingView(this).apply {
            usedPercent = win?.usedPercent?.toFloat() ?: 0f
            timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
            ringColor = healthColor(win)
            this.iconRes = iconRes
        }
        card.addView(bigRing, LinearLayout.LayoutParams((140 * dp).toInt(), (140 * dp).toInt()))

        val pace = win?.pace
        val paceText = if (pace == null) "—" else "×%.2f".format(pace)
        card.addView(TextView(this).apply {
            text = if (win == null) "$provName · 加载中…" else "$provName · ${win.label}"
            textSize = 15f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (win == null) "等待数据到达…"
            else "用量 ${win.usedPercent}% · 时间已过 ${win.timeElapsedPercent}% · PACE $paceText"
            textSize = 12.5f; setTextColor(0xFF9BA1B0.toInt()); gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = (240 * dp).toInt(); height = WindowManager.LayoutParams.WRAP_CONTENT
            // 出现在圆环附近, 居中夹持
            val cardW = (240 * dp).toInt()
            x = (rp.x + ringSize / 2 - cardW / 2).coerceIn(8, resources.displayMetrics.widthPixels - cardW - 8)
            y = (rp.y - (60 * dp).toInt()).coerceIn(8, resources.displayMetrics.heightPixels / 2)
        }
        card.setOnClickListener { collapse() }
        wm.addView(card, params)
        expandedView = card
        expanded = true
        // 圆环暂时隐藏
        ringView?.visibility = View.INVISIBLE

        handler.postDelayed({ if (expanded) collapse() }, 8000)
    }

    private fun collapse() {
        expandedView?.let { runCatching { wm.removeView(it) } }
        expandedView = null
        expanded = false
        ringView?.visibility = View.VISIBLE
    }

    private fun healthColor(win: QuotaWindow?): Int = when {
        win?.pace == null -> 0xFF6E8BFF.toInt()
        win.pace!! > 1.5f -> 0xFFE5484D.toInt()
        win.pace!! > 1f -> 0xFFF5A524.toInt()
        else -> 0xFF46A758.toInt()
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
        } ?: run { if (ringView == null && android.provider.Settings.canDrawOverlays(this)) attachRing(); return }

        val winKey = prefs.getString("ring_window", "primary") ?: "primary"
        val win = st.windows.firstOrNull { labelToKey(it.label) == winKey } ?: st.windows.firstOrNull()
        currentWindow = win

        if (android.provider.Settings.canDrawOverlays(this)) {
            if (ringView == null) attachRing()
            (ringView as? DualRingView)?.let {
                it.usedPercent = win?.usedPercent?.toFloat() ?: 0f
                it.timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
                it.ringColor = healthColor(win)
                it.invalidate()
            }
        }
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

private inline fun ValueAnimator.doOnEnd(crossinline body: () -> Unit) =
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) { body() }
    })
