package com.tankecho.quotaview

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 灵动岛 v4: 三态悬浮窗.
 *
 * BAR    平常态: 贴边半透明长条 (颜色=健康度), 可拖动, 松手吸附左右边缘
 * RING   轻点长条: 长条消失, 圆环从吸附侧滑出 (外环=用量, 内环=时间, 中心=provider icon)
 * DETAIL 轻点圆环: 展开详情卡; 点卡外任意处 (scrim) 收回 RING
 * RING 8 秒无操作自动滑回边缘收起, 长条重现
 *
 * 数据: 每 5 分钟自动轮询刷新 (无需打开 App)
 */
class IslandService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var currentWindow: QuotaWindow? = null
    private var lastUpdateAt = 0L

    private lateinit var wm: WindowManager
    private var barView: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var ringView: DualRingView? = null
    private var ringParams: WindowManager.LayoutParams? = null
    private var detailView: View? = null
    private var scrimView: View? = null

    private val dp: Float get() = resources.displayMetrics.density
    private val ringSize: Int get() = (52 * dp).toInt()
    private val barW: Int get() = (6 * dp).toInt()
    private val barH: Int get() = (110 * dp).toInt()

    private val poll = object : Runnable {
        override fun run() {
            scope.launch { refreshData() }
            handler.postDelayed(this, 5 * 60 * 1000L)   // 5 分钟自动刷新
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
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH -> scope.launch { refreshData() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        detachAll()
        super.onDestroy()
    }

    // ---------- 前台服务 (静默载体, 保轮询存活) ----------

    private fun startForegroundQuiet() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false); ch.setSound(null, null)
        nm.createNotificationChannel(ch)
        startForeground(NOTI_ID, Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QuotaView 悬浮窗运行中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .build())
    }

    // ---------- BAR: 贴边长条 ----------

    private fun barParams(x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = barW; height = barH
            this.x = x; this.y = y
        }

    private fun savedBarParams(): WindowManager.LayoutParams {
        val p = getSharedPreferences("qv", MODE_PRIVATE)
        val defY = (resources.displayMetrics.heightPixels * 0.38f).toInt()
        val x = p.getInt("bar_x", resources.displayMetrics.widthPixels - barW)
        val y = p.getInt("bar_y", defY)
        return barParams(x, y)
    }

    private fun barColor(): Int = (healthColor(currentWindow) and 0x00FFFFFF) or 0x66000000

    private fun attachBar() {
        detachBar()
        val params = savedBarParams()
        val v = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = barW.toFloat()
                setColor(barColor())
            }
        }
        v.setOnTouchListener { t, ev -> handleBarTouch(t, ev, params) }
        wm.addView(v, params)
        barParams = params
        barView = v
    }

    private fun detachBar() {
        barView?.let { runCatching { wm.removeView(it) } }
        barView = null
    }

    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var moved = false

    private fun handleBarTouch(v: View, ev: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                startX = params.x; startY = params.y
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (dx * dx + dy * dy > 144) moved = true
                params.x = startX + dx.toInt()
                params.y = (startY + dy.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - barH)
                runCatching { wm.updateViewLayout(v, params) }
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    saveBarPos(params)
                    showRing(params.x < resources.displayMetrics.widthPixels / 2)
                } else snapBar(v, params)
            }
        }
        return true
    }

    private fun snapBar(v: View, params: WindowManager.LayoutParams) {
        val w = resources.displayMetrics.widthPixels
        val target = if (params.x + barW / 2 < w / 2) 0 else w - barW
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 160
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(v, params) }
            }
            doOnEnd { saveBarPos(params) }
            start()
        }
    }

    private fun saveBarPos(params: WindowManager.LayoutParams) {
        getSharedPreferences("qv", MODE_PRIVATE).edit()
            .putInt("bar_x", params.x).putInt("bar_y", params.y).apply()
    }

    // ---------- RING: 滑出圆环 ----------

    private val ringTimeout = Runnable { hideRing() }

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

    private fun buildRingView(): DualRingView = DualRingView(this).apply {
        val win = currentWindow
        usedPercent = win?.usedPercent?.toFloat() ?: 0f
        timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
        ringColor = healthColor(win)
        val prov = getSharedPreferences("qv", MODE_PRIVATE).getString("ring_provider", "codex")!!
        iconRes = if (prov == "codex") R.drawable.ic_openai else R.drawable.ic_zai
    }

    private fun showRing(fromLeft: Boolean) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (ringView != null) return
        val w = resources.displayMetrics.widthPixels
        val bp = barParams ?: return
        val y = (bp.y + barH / 2 - ringSize / 2)
            .coerceIn(0, resources.displayMetrics.heightPixels - ringSize)
        val endX = if (fromLeft) 0 else w - ringSize
        val startHiddenX = if (fromLeft) -ringSize else w
        val params = ringParams(startHiddenX, y)
        val v = buildRingView()
        var rDownX = 0f; var rDownY = 0f
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { rDownX = ev.rawX; rDownY = ev.rawY; true }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - rDownX; val dy = ev.rawY - rDownY
                    if (dx * dx + dy * dy < 900) showDetail()
                    true
                }
                else -> false
            }
        }
        wm.addView(v, params)
        ringView = v; ringParams = params
        barView?.visibility = View.GONE
        ValueAnimator.ofInt(startHiddenX, endX).apply {
            duration = 240
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(v, params) }
            }
            start()
        }
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, 8000)
    }

    private fun hideRing() {
        val rv = ringView ?: run { barView?.visibility = View.VISIBLE; return }
        val p = ringParams ?: return
        val w = resources.displayMetrics.widthPixels
        val target = if (p.x < w / 2) -ringSize else w
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 200
            addUpdateListener { a ->
                p.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(rv, p) }
            }
            doOnEnd {
                detachRing()
                barView?.visibility = View.VISIBLE
            }
            start()
        }
    }

    private fun detachRing() {
        ringView?.let { runCatching { wm.removeView(it) } }
        ringView = null
    }

    // ---------- DETAIL: 详情卡 (点外部自动收回) ----------

    private fun showDetail() {
        if (detailView != null) return
        handler.removeCallbacks(ringTimeout)
        val win = currentWindow
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val prov = prefs.getString("ring_provider", "codex")!!
        val provName = if (prov == "codex") "Codex" else "GLM"
        val iconRes = if (prov == "codex") R.drawable.ic_openai else R.drawable.ic_zai

        // 1. 全屏 scrim: 捕捉"详情之外"的点击 (先加 = 底层)
        val scrim = View(this)
        val sp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        scrim.setOnTouchListener { _, _ -> collapseDetail(); true }
        runCatching { wm.addView(scrim, sp) }
        scrimView = scrim

        // 2. 详情卡
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt())
            background = GradientDrawable().apply {
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
        card.addView(bigRing, LinearLayout.LayoutParams((150 * dp).toInt(), (150 * dp).toInt()))

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
            else "用量 ${win.usedPercent}% · 已过 ${win.timeElapsedPercent}% · PACE $paceText"
            textSize = 12.5f; setTextColor(0xFF9BA1B0.toInt()); gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (lastUpdateAt > 0) "更新于 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastUpdateAt)) + " · 每 5 分钟自动刷新" else "尚未获取数据"
            textSize = 11f; setTextColor(0xFF5C6270.toInt()); gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        card.setOnClickListener { }   // 吞掉卡上点击, 不穿透 scrim

        val rp = ringParams
        val cardW = (250 * dp).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            width = cardW; height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        runCatching { wm.addView(card, params) }
        detailView = card
        ringView?.visibility = View.INVISIBLE
    }

    private fun collapseDetail() {
        detailView?.let { runCatching { wm.removeView(it) } }
        detailView = null
        scrimView?.let { runCatching { wm.removeView(it) } }
        scrimView = null
        ringView?.visibility = View.VISIBLE
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, 8000)
    }

    private fun detachAll() {
        detachBar(); detachRing()
        detailView?.let { runCatching { wm.removeView(it) } }; detailView = null
        scrimView?.let { runCatching { wm.removeView(it) } }; scrimView = null
    }

    // ---------- 数据 ----------

    private fun healthColor(win: QuotaWindow?): Int = when {
        win?.pace == null -> 0xFF6E8BFF.toInt()
        win.pace!! > 1.5f -> 0xFFE5484D.toInt()
        win.pace!! > 1f -> 0xFFF5A524.toInt()
        else -> 0xFF46A758.toInt()
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

        lastUpdateAt = System.currentTimeMillis()
        val winKey = prefs.getString("ring_window", "primary") ?: "primary"
        val win = st.windows.firstOrNull { labelToKey(it.label) == winKey } ?: st.windows.firstOrNull()
        currentWindow = win

        if (android.provider.Settings.canDrawOverlays(this)) {
            if (barView == null) attachBar()
            // 长条颜色 = 健康度
            barView?.background = GradientDrawable().apply {
                cornerRadius = barW.toFloat()
                setColor(barColor())
            }
            // 圆环实时数据
            ringView?.let {
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
        const val LIVE_NOTI_ID = 1002
        const val ACTION_STOP = "stop"
        const val ACTION_REFRESH = "refresh"
    }
}

private inline fun ValueAnimator.doOnEnd(crossinline body: () -> Unit) =
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) { body() }
    })
