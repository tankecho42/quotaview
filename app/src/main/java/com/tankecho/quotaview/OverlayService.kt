package com.tankecho.quotaview

import android.animation.ValueAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.res.Configuration
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.ClaudeApi
import com.tankecho.quotaview.data.DeepSeekBudgetLimits
import com.tankecho.quotaview.data.DeepSeekBudgetStatus
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.KimiApi
import com.tankecho.quotaview.data.MiniMaxApi
import com.tankecho.quotaview.data.ProviderStatus
import com.tankecho.quotaview.data.QuotaWindow
import com.tankecho.quotaview.data.meterWindows
import com.tankecho.quotaview.ui.DualRingView
import com.tankecho.quotaview.ui.ProviderIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三态额度悬浮窗.
 *
 * BAR    平常态: 贴边半透明胶囊长条 (颜色=健康度), 宽触摸热区, 可拖动, 松手吸边
 * RING   轻点长条: 长条消失, 圆环从吸附侧滑出; 圆环可拖动 + 吸边; 拖到屏幕边缘松手 → 滑回收缩
 * DETAIL 轻点圆环: 展开详情卡; 点卡外任意处 (scrim) 收回 RING
 * RING 8 秒无操作自动滑回边缘收起
 *
 * 数据: 每 5 分钟自动轮询刷新 (无需打开 App)
 */
class OverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var refreshJob: Job? = null
    private var shuttingDown = false
    private var currentWindow: QuotaWindow? = null
    private var lastUpdateAt = 0L

    private lateinit var wm: WindowManager
    private var barHost: FrameLayout? = null
    private var barShape: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var ringView: DualRingView? = null
    private var ringParams: WindowManager.LayoutParams? = null
    private var detailView: View? = null
    private var scrimView: View? = null

    private val dp: Float get() = resources.displayMetrics.density
    private val screenW: Int get() = resources.displayMetrics.widthPixels
    private val screenH: Int get() = resources.displayMetrics.heightPixels

    private val ringSize: Int get() = (52 * dp).toInt()
    private val barW: Int get() = (11 * dp).toInt()        // 视觉宽
    private val barH: Int get() = (48 * dp).toInt()        // 视觉高（较上一版缩短 1/3）
    private val hostW: Int get() = (30 * dp).toInt()       // 触摸热区 (加宽, 好按好拖)
    private val hostH: Int get() = (barH + (14 * dp).toInt())

    private val poll = object : Runnable {
        override fun run() {
            requestRefresh()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundQuiet()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post(poll)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_REFRESH -> requestRefresh()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        handler.removeCallbacksAndMessages(null)
        refreshJob?.cancel()
        scope.cancel()
        detachAll()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.removeCallbacks(ringTimeout)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            detachAll()
        } else {
            if (canShowOverlay() && barHost == null && ringView == null && detailView == null) attachBar()
            requestRefresh()
        }
    }

    // ---------- 前台服务 (静默载体, 保轮询存活) ----------

    private fun startForegroundQuiet() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val ch = NotificationChannel(CHANNEL_ID, "悬浮窗后台服务", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false); ch.setSound(null, null)
        nm.createNotificationChannel(ch)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("QuotaView 悬浮窗")
            .setContentText("正在后台刷新额度")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
                }
            }
            .build()
        startForeground(NOTI_ID, notification)
    }

    private fun canShowOverlay(): Boolean =
        !shuttingDown &&
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE &&
            android.provider.Settings.canDrawOverlays(this)

    // ---------- BAR: 贴边胶囊长条 (宽热区容器) ----------

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            width = w; height = h
            this.x = x; this.y = y
        }

    private fun attachBar() {
        if (!canShowOverlay()) return
        if (attachingBar) return   // 防重入 (并发 refreshData 双 attach)
        attachingBar = true
        try {
            detachBar()          // 确保旧的清干净再建
            attachBarInner()
        } finally { attachingBar = false }
    }

    private fun attachBarInner() {
        val p = getSharedPreferences("qv", MODE_PRIVATE)
        val defY = (screenH * 0.38f).toInt()
        // 关键: 旧版存的窄条坐标会把宽热区顶出屏 → 视觉条整个不可见. 强制夹回屏内
        // x 由吸附边决定: 要么贴左缘要么贴右缘 (bar 本来就该贴边)
        val side = p.getString("bar_side", "right") ?: "right"
        val savedX = if (side == "left") 0 else (screenW - hostW).coerceAtLeast(0)
        val savedY = p.getInt("bar_y", defY).coerceIn(0, (screenH - hostH).coerceAtLeast(0))
        val params = overlayParams(hostW, hostH, savedX, savedY)

        val shape = com.tankecho.quotaview.ui.BarView(this).apply {
            usedPercent = currentWindow?.usedPercent?.toFloat() ?: 0f
            healthColor = healthColor(currentWindow)
            attachRight = (side == "right")
        }
        val host = FrameLayout(this).apply {
            // 视觉条贴吸附侧: 右吸附靠右 (紧贴屏幕右缘), 左吸附靠左
            val g = if (side == "left") Gravity.START else Gravity.END
            addView(shape, FrameLayout.LayoutParams(barW, barH, g or Gravity.CENTER_VERTICAL))
        }
        host.setOnTouchListener { v, ev -> handleBarTouch(v as View, ev, params) }
        wm.addView(host, params)
        barHost = host; barShape = shape; barParams = params
    }

    private fun detachBar() {
        barHost?.let { runCatching { wm.removeView(it) } }
        barHost = null; barShape = null
    }

    private var attachingBar = false
    private var morphDownX = 0f; private var morphDownY = 0f
    private var morphBaseX = 0; private var morphBaseY = 0
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0
    private var moved = false

    private fun handleBarTouch(v: View, ev: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                startX = params.x; startY = params.y
                moved = false
                barShape?.alpha = 1f   // 按住提亮, 明确反馈
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (!moved && dx * dx + dy * dy > 225) {
                    moved = true
                    morphToRing(v, params, ev.rawX, ev.rawY)   // 拖动瞬间: bar 同窗口变圆环跟手
                }
                if (moved) {
                    params.x = (morphBaseX + (ev.rawX - morphDownX).toInt())
                        .coerceIn(-ringSize / 3, screenW - ringSize * 2 / 3)
                    params.y = (morphBaseY + (ev.rawY - morphDownY).toInt())
                        .coerceIn(0, screenH - ringSize)
                    runCatching { wm.updateViewLayout(v, params) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // tap → 弹独立圆环; 拖动(morph 圆环) → 吸边后变回 bar
                if (!moved) {
                    saveBarPos(params)
                    showRing(params.x < screenW / 2)
                } else {
                    snapMorphed(v, params)
                }
            }
        }
        return true
    }

    /** 拖动瞬间: bar 窗口原地 morph 成圆环 (swap 子view+改窗口尺寸), 手势流不断 */
    private fun morphToRing(host: View, params: WindowManager.LayoutParams, rawX: Float, rawY: Float) {
        morphDownX = rawX; morphDownY = rawY
        // morph 后窗口中心对齐手指: params.x/y 是新坐标系
        morphBaseX = params.x + hostW / 2 - ringSize / 2
        morphBaseY = params.y + hostH / 2 - ringSize / 2
        val fr = host as? FrameLayout ?: return
        fr.removeAllViews()
        fr.addView(buildRingView(), FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
        params.width = ringSize; params.height = ringSize
        params.x = morphBaseX.toInt(); params.y = morphBaseY.toInt()
        runCatching { wm.updateViewLayout(host, params) }
    }

    /** morph 圆环松手: 吸边动画, 结束后 morph 回 bar 形态 (原位) */
    private fun snapMorphed(host: View, params: WindowManager.LayoutParams) {
        val target = if (params.x + ringSize / 2 < screenW / 2) 0 else screenW - ringSize
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 160
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(host, params) }
            }
            doOnEnd {
                params.x = target
                morphBackToBar(host, params)
            }
            start()
        }
    }

    /** 圆环形态 morph 回 bar */
    private fun morphBackToBar(host: View, params: WindowManager.LayoutParams) {
        val fr = host as? FrameLayout ?: return
        // 锚点: bar 中心 = 圆环中心
        val anchorY = params.y + ringSize / 2
        val hostY = (anchorY - hostH / 2).coerceIn(0, (screenH - hostH).coerceAtLeast(0))
        val side = if (params.x < screenW / 2) "left" else "right"
        val hostX = if (side == "left") 0 else screenW - hostW
        val bv = com.tankecho.quotaview.ui.BarView(this@OverlayService).apply {
            usedPercent = currentWindow?.usedPercent?.toFloat() ?: 0f
            healthColor = healthColor(currentWindow)
            attachRight = (side == "right")
        }
        fr.removeAllViews()
        val g = if (side == "left") Gravity.START else Gravity.END
        fr.addView(bv, FrameLayout.LayoutParams(barW, barH, g or Gravity.CENTER_VERTICAL))
        params.width = hostW; params.height = hostH
        params.x = hostX; params.y = hostY
        runCatching { wm.updateViewLayout(host, params) }
        // 持久化新锚点
        getSharedPreferences("qv", MODE_PRIVATE).edit()
            .putString("bar_side", side).putInt("bar_x", hostX).putInt("bar_y", hostY).apply()
        barShape = bv
        barParams = params
    }

    private fun snapBar(v: View, params: WindowManager.LayoutParams) {
        val target = if (params.x + hostW / 2 < screenW / 2) 0 else screenW - hostW
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 160
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(v, params) }
            }
            doOnEnd {
                params.x = target
                runCatching { wm.updateViewLayout(v, params) }
                saveBarPos(params)
            }
            start()
        }
    }

    private fun saveBarPos(params: WindowManager.LayoutParams) {
        val side = if (params.x + hostW / 2 < screenW / 2) "left" else "right"
        getSharedPreferences("qv", MODE_PRIVATE).edit()
            .putString("bar_side", side)
            .putInt("bar_x", if (side == "left") 0 else screenW - hostW)
            .putInt("bar_y", params.y).apply()
    }

    // ---------- RING: 滑出圆环 (可拖动/吸边/拖到边缘收回) ----------

    private val ringTimeout = Runnable { hideRing() }

    private fun buildRingView(): DualRingView = DualRingView(this).apply {
        val win = currentWindow
        usedPercent = win?.usedPercent?.toFloat() ?: 0f
        timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
        ringColor = healthColor(win)
        bgColor = 0x66000000.toInt()   // 黑 40% 不透明 = 60% 透明 (按用户口径)
        val prov = getSharedPreferences("qv", MODE_PRIVATE).getString("ring_provider", "codex")!!
        iconRes = providerIcon(prov)
        centerText = if (iconRes == 0) providerInitial(prov) else null
    }

    private fun showRing(fromLeft: Boolean) {
        if (!canShowOverlay()) return
        if (ringView != null) return
        val bp = barParams ?: return
        // 统一锚点: ring 中心 = bar 视觉条中心 (host y + barH/2)
        val anchorY = bp.y + barH / 2
        val y = (anchorY - ringSize / 2).coerceIn(0, screenH - ringSize)
        val endX = if (fromLeft) 0 else screenW - ringSize
        val startHiddenX = if (fromLeft) -ringSize else screenW
        val params = overlayParams(ringSize, ringSize, startHiddenX, y)
        val v = buildRingView()
        v.setOnTouchListener { _, ev -> handleRingTouch(v, ev, params) }
        detachBar()   // 先移除长条再上 ring (顺序换: addView 前 bar 一定已清)
        wm.addView(v, params)
        ringView = v; ringParams = params
        ringShownAt = SystemClock.uptimeMillis()
        saveBarPos(bp)
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

    private var rDownX = 0f; private var rDownY = 0f
    private var rStartX = 0; private var rStartY = 0
    private var rMoved = false
    private var ringShownAt = 0L

    private fun handleRingTouch(v: View, ev: MotionEvent, params: WindowManager.LayoutParams): Boolean {
        // 新窗口可能收到展开长条那次手势残留的 DOWN/UP；防止一展开就误开详情。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            SystemClock.uptimeMillis() - ringShownAt < RING_TOUCH_DEBOUNCE_MS) {
            return false
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rDownX = ev.rawX; rDownY = ev.rawY
                rStartX = params.x; rStartY = params.y
                rMoved = false
                handler.removeCallbacks(ringTimeout)   // 拖动中不回收
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - rDownX; val dy = ev.rawY - rDownY
                if (dx * dx + dy * dy > 225) rMoved = true   // 15px 起算拖动 (抗手抖)
                params.x = rStartX + dx.toInt()
                params.y = (rStartY + dy.toInt()).coerceIn(0, screenH - ringSize)
                runCatching { wm.updateViewLayout(v, params) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - rDownX; val dy = ev.rawY - rDownY
                val realMoved = rMoved && (dx * dx + dy * dy > 100)
                if (!realMoved) {
                    showDetail()
                    return true
                }
                // 拖到屏幕最边缘 (左右各 8%) 才算收缩手势; 松手时以最终位置定锚点
                val atEdge = params.x < screenW * 0.08f || params.x + ringSize > screenW * 0.92f
                if (atEdge) {
                    saveAnchorFromRing(params)   // 收缩前先存锚点 → bar 出现在 ring 实际位置
                    hideRing()
                } else {
                    snapRing(v, params)
                    // 同步锚点: bar 将出现在 ring 吸边后的同高度
                    saveAnchorFromRing(params)
                    handler.postDelayed(ringTimeout, 8000)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.postDelayed(ringTimeout, 8000)
                return true
            }
        }
        return false
    }

    private fun snapRing(v: View, params: WindowManager.LayoutParams) {
        val target = if (params.x + ringSize / 2 < screenW / 2) 0 else screenW - ringSize
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 160
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(v, params) }
            }
            doOnEnd {
                params.x = target   // 强制精确贴边
                runCatching { wm.updateViewLayout(v, params) }
                saveAnchorFromRing(params)
            }
            start()
        }
    }

    /** ring 吸边后: 把锚点 (side+y) 写入 prefs, bar/ring 位置一体化 */
    private fun saveAnchorFromRing(params: WindowManager.LayoutParams) {
        val side = if (params.x + ringSize / 2 < screenW / 2) "left" else "right"
        // ring 中心 y = params.y + ringSize/2 → bar host y = anchorY - barH/2
        val anchorY = params.y + ringSize / 2
        val hostY = (anchorY - barH / 2).coerceIn(0, (screenH - hostH).coerceAtLeast(0))
        getSharedPreferences("qv", MODE_PRIVATE).edit()
            .putString("bar_side", side)
            .putInt("bar_x", if (side == "left") 0 else screenW - hostW)
            .putInt("bar_y", hostY).apply()
    }

    private fun hideRing() {
        if (shuttingDown) return
        val rv = ringView ?: run { if (barHost == null && canShowOverlay()) attachBar(); return }
        val p = ringParams ?: return
        val target = if (p.x < screenW / 2) -ringSize else screenW
        ValueAnimator.ofInt(p.x, target).apply {
            duration = 200
            addUpdateListener { a ->
                p.x = a.animatedValue as Int
                runCatching { wm.updateViewLayout(rv, p) }
            }
            doOnEnd {
                detachRing()
                if (canShowOverlay() && barHost == null) attachBar()
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
        if (!canShowOverlay()) return
        if (detailView != null) return
        handler.removeCallbacks(ringTimeout)
        val win = currentWindow
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val prov = prefs.getString("ring_provider", "codex")!!
        val provName = providerName(prov)
        val iconRes = providerIcon(prov)

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
            centerText = if (iconRes == 0) providerInitial(prov) else null
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
            else if (win.selectionKey.startsWith("budget_")) {
                "预算使用 ${win.usedPercent}% · DeepSeek 官方日账单"
            } else {
                "用量 ${win.usedPercent}% · 已过 ${win.timeElapsedPercent}% · PACE $paceText"
            }
            textSize = 12.5f; setTextColor(0xFF9BA1B0.toInt()); gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = if (lastUpdateAt > 0) "更新于 " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(lastUpdateAt)) + " · 每 5 分钟自动刷新" else "尚未获取数据"
            textSize = 11f; setTextColor(0xFF5C6270.toInt()); gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
        })
        card.setOnClickListener {
            // 点详情卡 → 进入主界面
            collapseDetail()
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            })
        }

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

        // ===== morph 动画: 从圆环位置/尺寸 → 展开到中央 =====
        val rp = ringParams
        if (rp != null) {
            // 圆环在屏幕上的中心 (mAttrs 坐标)
            val rcx = rp.x + ringSize / 2f
            val rcy = rp.y + ringSize / 2f
            // 卡片最终中心 (屏幕中心)
            val ccx = screenW / 2f
            val ccy = screenH / 2f
            val fromScale = ringSize.toFloat() / cardW
            card.scaleX = fromScale; card.scaleY = fromScale
            card.translationX = rcx - ccx
            card.translationY = rcy - ccy
            card.alpha = 0.6f
            card.animate()
                .scaleX(1f).scaleY(1f)
                .translationX(0f).translationY(0f)
                .alpha(1f)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun collapseDetail() {
        val card = detailView ?: return
        val rp = ringParams
        val scrim = scrimView
        detailView = null; scrimView = null
        if (rp == null) {
            runCatching { wm.removeView(card) }
            scrim?.let { runCatching { wm.removeView(it) } }
            ringView?.visibility = View.VISIBLE
            return
        }
        // 反向 morph: 卡片 → 圆环位置/尺寸, 结束后移除
        val rcx = rp.x + ringSize / 2f
        val rcy = rp.y + ringSize / 2f
        val ccx = screenW / 2f
        val ccy = screenH / 2f
        val cardW2 = (250 * dp).toInt()
        val toScale = ringSize.toFloat() / cardW2
        scrim?.let { sv ->
            sv.animate().alpha(0f).setDuration(200).withEndAction {
                runCatching { wm.removeView(sv) }   // scrim 自己负责移除自己
            }.start()
        }
        // 用 ValueAnimator 驱动 (不依赖 ViewPropertyAnimator 的结束帧行为),
        // 结束回调里先把卡片 GONE (窗口立即停渲染) 再 removeView — 杜绝最后一帧闪现
        val fromSx = card.scaleX; val fromSy = card.scaleY
        val fromTx = card.translationX; val fromTy = card.translationY
        val fromA = card.alpha
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = AccelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                card.scaleX = fromSx + (toScale - fromSx) * f
                card.scaleY = fromSy + (toScale - fromSy) * f
                card.translationX = fromTx + ((rcx - ccx) - fromTx) * f
                card.translationY = fromTy + ((rcy - ccy) - fromTy) * f
                card.alpha = fromA + (0.4f - fromA) * f
            }
            doOnEnd {
                card.visibility = View.GONE      // 先停止渲染
                runCatching { wm.removeView(card) }
                ringView?.visibility = View.VISIBLE
            }
            start()
        }
        handler.removeCallbacks(ringTimeout)
        handler.postDelayed(ringTimeout, 8000)
    }

    private fun detachAll() {
        detachBar(); detachRing()
        detailView?.let { runCatching { wm.removeView(it) } }; detailView = null
        scrimView?.let { runCatching { wm.removeView(it) } }; scrimView = null
    }

    // ---------- 数据 ----------

    /** 新刷新会取消旧刷新，避免快速切 provider 时慢响应覆盖新选择。 */
    private fun requestRefresh() {
        if (shuttingDown) return
        refreshJob?.cancel()
        refreshJob = scope.launch { refreshData() }
    }

    private fun healthColor(win: QuotaWindow?): Int = when {
        win == null -> 0xFF6E8BFF.toInt()
        win.selectionKey.startsWith("budget_") && win.usedPercent >= 100 -> 0xFFE5484D.toInt()
        win.selectionKey.startsWith("budget_") && win.usedPercent >= 80 -> 0xFFF5A524.toInt()
        win.selectionKey.startsWith("budget_") -> 0xFF46A758.toInt()
        win.pace == null -> 0xFF6E8BFF.toInt()
        win.pace!! > 1.5f -> 0xFFE5484D.toInt()
        win.pace!! > 1f -> 0xFFF5A524.toInt()
        else -> 0xFF46A758.toInt()
    }

    private suspend fun refreshData() {
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val st = withContext(Dispatchers.IO) {
            val prov = prefs.getString("ring_provider", "codex")!!
            runCatching { fetchRingStatus(prov, prefs) }.getOrNull()
        }

        val windows = st?.meterWindows().orEmpty()
        if (st == null || st.error != null || windows.isEmpty()) {
            // 数据失败时保留最后一次有效值；首次启动仍显示占位，不能让窗口消失。
            if (canShowOverlay()) {
                if (barHost == null && ringView == null && detailView == null) attachBar()
            }
            return
        }

        lastUpdateAt = System.currentTimeMillis()
        val winKey = prefs.getString("ring_window", "primary") ?: "primary"
        val win = windows.firstOrNull { it.selectionKey == winKey } ?: windows.firstOrNull()
        currentWindow = win

        if (canShowOverlay()) {
            // ring/detail 展示期间不重建 bar (bar 由 hideRing 收缩时重建)
            if (barHost == null && ringView == null && detailView == null) attachBar()
            (barShape as? com.tankecho.quotaview.ui.BarView)?.let {
                it.usedPercent = win?.usedPercent?.toFloat() ?: 0f
                it.healthColor = healthColor(win)
                it.invalidate()
            }
            ringView?.let {
                it.usedPercent = win?.usedPercent?.toFloat() ?: 0f
                it.timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
                it.ringColor = healthColor(win)
                it.invalidate()
            }
        }
    }

    private suspend fun fetchRingStatus(prov: String, prefs: android.content.SharedPreferences): ProviderStatus = when (prov) {
        "codex" -> CodexApi.fetch(
            prefs.getString("codex_token", "").orEmpty(),
            prefs.getString("codex_account", "").orEmpty(),
        )
        "glm" -> GlmApi.fetch(prefs.getString("glm_key", "").orEmpty())
        "kimi" -> KimiApi.fetch(prefs.getString("kimi_key", "").orEmpty())
        "claude" -> ClaudeApi.fetch(prefs.getString("claude_token", "").orEmpty())
        "minimax" -> MiniMaxApi.fetch(
            prefs.getString("minimax_key", "").orEmpty(),
            prefs.getString("minimax_region", "cn").orEmpty(),
        )
        "deepseek" -> DeepSeekBudgetStatus.fetch(
            prefs.getString("deepseek_key", "").orEmpty(),
            prefs.getString("deepseek_platform_token", "").orEmpty(),
            DeepSeekBudgetLimits.parse(
                prefs.getString("deepseek_budget_24h", ""),
                prefs.getString("deepseek_budget_7d", ""),
                prefs.getString("deepseek_budget_30d", ""),
            ),
        )
        else -> ProviderStatus(prov, providerName(prov), "?", emptyList(), updatedAt = 0)
    }

    private fun providerName(id: String): String = when (id) {
        "codex" -> "Codex"
        "glm" -> "GLM"
        "kimi" -> "Kimi Code"
        "claude" -> "Claude"
        "minimax" -> "MiniMax"
        "deepseek" -> "DeepSeek"
        else -> id
    }

    private fun providerIcon(id: String): Int = ProviderIcons.icon(id)

    private fun providerInitial(id: String): String = when (id) {
        "kimi" -> "K"
        "claude" -> "C"
        "minimax" -> "M"
        "deepseek" -> "D"
        else -> "?"
    }

    companion object {
        const val CHANNEL_ID = "qv_overlay"
        private const val LEGACY_CHANNEL_ID = "qv_island"
        const val NOTI_ID = 1001
        const val ACTION_STOP = "stop"
        const val ACTION_REFRESH = "refresh"
        private const val RING_TOUCH_DEBOUNCE_MS = 350L
    }
}

private inline fun ValueAnimator.doOnEnd(crossinline body: () -> Unit) =
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) { body() }
    })
