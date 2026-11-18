package com.claudebubble.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Foreground service that draws the floating Claude companion over other apps.
 * Tap = pop open the floating Claude Duet chat (or open the app, or look at the screen).
 * Hold = quick menu. Drag = move; it snaps to the nearest side. Drag onto the X = turn off.
 * Shake it while dragging and it gets dizzy.
 */
class BubbleService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val ACTION_STOP = "com.claudebubble.app.action.STOP"
        const val ACTION_LOOK = "com.claudebubble.app.action.LOOK"
        const val ACTION_CAPTURE = "com.claudebubble.app.action.CAPTURE"
        const val ACTION_SIGN_OUT = "com.claudebubble.app.action.SIGN_OUT"
        const val ACTION_RESET_PANEL = "com.claudebubble.app.action.RESET_PANEL"
        private const val UPDATE_CHANNEL_ID = "updates"
        private const val UPDATE_NOTIF_ID = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 1

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BubbleService::class.java))
        }

        /** Turns the bubble off because the user asked to. */
        fun stop(context: Context) {
            Prefs(context).bubbleOn = false
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var companion: CompanionView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var target: FrameLayout? = null
    private var targetIcon: ImageView? = null
    private var overTarget = false
    private var snapAnimator: ValueAnimator? = null
    private var menu: View? = null
    private var capturing = false

    private var panel: PanelWindow? = null
    private val panelOpen get() = panel?.expanded == true
    private var guard: AudioGuard? = null
    private var bubbleMove: ValueAnimator? = null

    private val updateCheck = object : Runnable {
        override fun run() {
            checkForUpdate()
            main.postDelayed(this, 12 * 60 * 60 * 1000L)
        }
    }
    private val orbSize get() = dp(prefs.sizeDp)
    private val windowSize get() = (orbSize * 1.32f).roundToInt()
    private val pad get() = (windowSize - orbSize) / 2
    private val edgeGap get() = dp(2)

    private val fadeRunnable = Runnable { companion?.animate()?.alpha(0.5f)?.setDuration(400)?.start() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val off = i.action == Intent.ACTION_SCREEN_OFF
            companion?.paused = off
            if (off) panel?.hush()   // no sound from the chat with the screen off
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        goForeground(withProjection = false)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        isRunning = true
        prefs.bubbleOn = true
        addBubble()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        })
        // Alarms, ringtones and calls always win: silence the chat the moment one starts.
        guard = AudioGuard(this) { panel?.hush() }.also { runCatching { it.start() } }
        main.postDelayed(updateCheck, 20_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.bubbleOn = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LOOK -> lookAtScreen()
            ACTION_CAPTURE -> onCaptureAnswer(intent)
            ACTION_SIGN_OUT -> panel?.signOut()
            ACTION_RESET_PANEL -> panel?.resetGeometry()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        runCatching { prefs.sp.unregisterOnSharedPreferenceChangeListener(this) }
        runCatching { unregisterReceiver(screenReceiver) }
        snapAnimator?.cancel()
        bubbleMove?.cancel()
        closeMenu()
        removeTarget()
        guard?.stop()
        panel?.destroy()
        panel = null
        companion?.let { runCatching { wm.removeView(it) } }
        companion = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        companion?.post {
            placeFromPrefs()
            if (panelOpen) moveBubbleToTop()
            panel?.relayout()
        }
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        if (key !in Prefs.LOOK_KEYS) return
        val view = companion ?: return
        view.applyPrefs(prefs)
        if (key == Prefs.KEY_SIZE) {
            bubbleParams.width = windowSize
            bubbleParams.height = windowSize
            placeFromPrefs()
        }
        if (key == Prefs.KEY_FADE) wake()
    }

    // ---------- Notification ----------

    private fun goForeground(withProjection: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val look = PendingIntent.getService(
            this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_LOOK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_look), look).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()

        when {
            Build.VERSION.SDK_INT >= 34 -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(NOTIF_ID, notification, type)
            }
            Build.VERSION.SDK_INT >= 29 && withProjection ->
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- Bubble ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val view = CompanionView(this).apply {
            contentDescription = getString(R.string.app_name)
            applyPrefs(prefs)
        }
        bubbleParams = WindowManager.LayoutParams(
            windowSize, windowSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        companion = view
        placeFromPrefs(update = false)
        wm.addView(view, bubbleParams)
        view.setOnTouchListener(BubbleTouch())
        view.scaleX = 0f
        view.scaleY = 0f
        view.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator()).start()
        wake()
    }

    /** Full opacity now; fade again later if that's turned on. */
    private fun wake() {
        main.removeCallbacks(fadeRunnable)
        companion?.animate()?.alpha(1f)?.setDuration(120)?.start()
        if (prefs.fadeIdle) main.postDelayed(fadeRunnable, Config.FADE_AFTER_SECONDS * 1000L)
    }

    private fun placeFromPrefs(update: Boolean = true) {
        val area = usableArea()
        bubbleParams.x = if (prefs.onRight) area.width() - windowSize + pad - edgeGap else -pad + edgeGap
        bubbleParams.x = bubbleParams.x.coerceIn(0, (area.width() - windowSize).coerceAtLeast(0))
        val maxY = (area.height() - windowSize).coerceAtLeast(0)
        bubbleParams.y = (prefs.yFraction * maxY).roundToInt().coerceIn(0, maxY)
        companion?.restingOnRight = prefs.onRight
        if (update) companion?.let { runCatching { wm.updateViewLayout(it, bubbleParams) } }
    }

    private fun snapToEdge() {
        val view = companion ?: return
        val area = usableArea()
        val toRight = bubbleParams.x + windowSize / 2 > area.width() / 2
        val endX = (if (toRight) area.width() - windowSize else 0)
        val maxY = (area.height() - windowSize).coerceAtLeast(0)
        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(bubbleParams.x, endX).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bubbleParams.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(view, bubbleParams) }
            }
            start()
        }
        prefs.onRight = toRight
        if (!panelOpen) prefs.yFraction = if (maxY == 0) 0f else bubbleParams.y.toFloat() / maxY
        view.restingOnRight = toRight
        view.landed()
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private var held = false
        // shake detection
        private var lastDir = 0
        private var flips = 0
        private var flipStart = 0L
        private var travel = 0f

        private val holdRunnable = Runnable {
            if (!dragging) {
                held = true
                companion?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showMenu()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val c = v as CompanionView
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    closeMenu()
                    wake()
                    downX = e.rawX; downY = e.rawY
                    lastX = e.rawX; lastY = e.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    dragging = false
                    held = false
                    flips = 0; lastDir = 0; travel = 0f
                    c.touchDown()
                    main.postDelayed(holdRunnable, longPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !held && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(holdRunnable)
                        showTarget()
                    }
                    if (dragging) {
                        bubbleParams.x = (startX + dx).roundToInt()
                        bubbleParams.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, bubbleParams) }
                        val mx = e.rawX - lastX; val my = e.rawY - lastY
                        c.dragging(mx, my)
                        detectShake(c, mx)
                        lastX = e.rawX; lastY = e.rawY
                        updateTargetHover(e.rawX, e.rawY, c)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(holdRunnable)
                    when {
                        dragging -> endDrag(c)
                        held -> { /* menu is open */ }
                        else -> {
                            c.tapped()
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            when (prefs.tapAction) {
                                "look" -> lookAtScreen()
                                "app" -> openApp()
                                else -> togglePanel()
                            }
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(holdRunnable)
                    if (dragging) endDrag(c)
                }
            }
            return true
        }

        private fun detectShake(c: CompanionView, mx: Float) {
            if (abs(mx) < dp(2)) return
            travel += abs(mx)
            val dir = if (mx > 0) 1 else -1
            val now = System.currentTimeMillis()
            if (lastDir != 0 && dir != lastDir && travel > dp(18)) {
                if (flips == 0 || now - flipStart > 1500) { flips = 0; flipStart = now }
                flips++
                travel = 0f
                if (flips >= 4) { c.shaken(); flips = 0 }
            }
            lastDir = dir
        }

        private fun endDrag(c: CompanionView) {
            dragging = false
            c.setScared(false)
            if (overTarget) dismiss() else { removeTarget(); snapToEdge() }
        }
    }

    // ---------- Hold menu ----------

    private fun showMenu() {
        closeMenu()
        val c = companion ?: return
        val area = usableArea()
        val onRight = bubbleParams.x + windowSize / 2 > area.width() / 2
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#F21F1E1B"))
            }
            elevation = dp(8).toFloat()
        }
        fun item(label: String, action: () -> Unit) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(11), dp(18), dp(11))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.TRANSPARENT) }
                setOnClickListener { closeMenu(); action() }
            })
        }
        item(if (panelOpen) "⌄   Tuck chat away" else "💬   Floating chat") { togglePanel() }
        item("👁   Look at my screen") { lookAtScreen() }
        item("📱   Open ${ClaudeLauncher.label(this)}") { openApp() }
        item("♥   Pet") { c.petted() }
        item("🎨   Customize") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        item("✕   Hide bubble") { dismiss() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            x = if (onRight) bubbleParams.x - box.measuredWidth - dp(4) else bubbleParams.x + windowSize + dp(4)
            x = x.coerceIn(0, (area.width() - box.measuredWidth).coerceAtLeast(0))
            y = (bubbleParams.y + windowSize / 2 - box.measuredHeight / 2)
                .coerceIn(0, (area.height() - box.measuredHeight).coerceAtLeast(0))
        }
        box.setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false }
        box.alpha = 0f
        box.scaleX = 0.9f; box.scaleY = 0.9f
        runCatching { wm.addView(box, lp) }.onFailure { return }
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        menu = box
    }

    private fun closeMenu() {
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    // ---------- Look at my screen ----------

    private fun lookAtScreen() {
        if (capturing) return
        capturing = true
        closeMenu()
        if (panelOpen) panel?.collapse()
        // Ask while the bubble is still showing (Android lets apps with a visible bubble open screens),
        // then hide it so it stays out of the screenshot.
        try {
            startActivity(Intent(this, CaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        } catch (e: Exception) {
            capturing = false
            Toast.makeText(this, "Couldn't start the screen capture", Toast.LENGTH_SHORT).show()
            return
        }
        main.postDelayed({ if (capturing) companion?.visibility = View.INVISIBLE }, 150)
        // Safety net: if nothing comes back, show the bubble again.
        main.postDelayed({ if (capturing) endCapture(null) }, 60_000)
    }

    private fun onCaptureAnswer(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != android.app.Activity.RESULT_OK || data == null) { endCapture(null); return }
        try {
            goForeground(withProjection = true)
        } catch (e: Exception) {
            Toast.makeText(this, "Android didn't allow the screen capture", Toast.LENGTH_SHORT).show()
            endCapture(null); return
        }
        // Let the permission pop-up finish closing before the shot.
        main.postDelayed({
            val projection = runCatching {
                getSystemService(MediaProjectionManager::class.java)!!.getMediaProjection(code, data)
            }.getOrNull()
            if (projection == null) { endCapture(null); return@postDelayed }
            ScreenGrabber(this).grab(projection) { file -> endCapture(file) }
        }, 450)
    }

    private fun endCapture(file: java.io.File?) {
        if (!capturing) return
        capturing = false
        runCatching { goForeground(withProjection = false) }
        companion?.visibility = View.VISIBLE
        wake()
        if (file != null) {
            companion?.lookingAtScreen()
            val p = panel
            if (prefs.tapAction == "window" && p != null && p.isCreated) {
                // Into the floating chat, as if pasted; if the page won't take it, send it to the app.
                p.attachScreenshot(file) { ok ->
                    if (ok) expandPanel() else ScreenGrabber.shareToClaude(this, file)
                }
            } else {
                ScreenGrabber.shareToClaude(this, file)
            }
        }
    }

    // ---------- Floating chat ----------

    private fun togglePanel() { if (panelOpen) tuckPanel() else expandPanel() }

    private fun openApp() {
        if (panelOpen) tuckPanel()
        ClaudeLauncher.open(this)
    }

    private fun expandPanel() {
        val p = panel ?: PanelWindow(this, wm, prefs, ::usableArea, { windowSize }, panelListener).also { panel = it }
        if (p.ensureCreated()) {
            // The chat window was just added on top; put the bubble back above it.
            companion?.let { v -> runCatching { wm.removeView(v); wm.addView(v, bubbleParams) } }
        }
        companion?.unread = false
        main.removeCallbacks(fadeRunnable)
        companion?.animate()?.alpha(1f)?.setDuration(120)?.start()
        moveBubbleToTop()
        p.expand(prefs.onRight)
    }

    private fun tuckPanel() {
        panel?.collapse()
        companion?.activity = "idle"
        moveBubble(homeY())
        wake()
    }

    private fun homeY(): Int {
        val maxY = (usableArea().height() - windowSize).coerceAtLeast(0)
        return (prefs.yFraction * maxY).roundToInt().coerceIn(0, maxY)
    }

    /** Like Messenger: the bubble sits at the top while its chat is open. */
    private fun moveBubbleToTop() = moveBubble(0)

    private fun moveBubble(toY: Int) {
        val view = companion ?: return
        bubbleMove?.cancel()
        bubbleMove = ValueAnimator.ofInt(bubbleParams.y, toY).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bubbleParams.y = it.animatedValue as Int
                runCatching { wm.updateViewLayout(view, bubbleParams) }
            }
            start()
        }
    }

    private val panelListener = object : PanelWindow.Listener {
        override fun onActivity(state: String) {
            val c = companion ?: return
            when (state) {
                "typing" -> c.activity = "listening"
                "answering" -> c.activity = "talking"
                "done" -> {
                    c.activity = "idle"
                    c.answered()
                    if (!panelOpen) { c.unread = true; wake() }
                }
                else -> c.activity = "idle"
            }
        }
        override fun onTuckRequested() { if (panelOpen) tuckPanel() }
        override fun onOpenApp() = openApp()
    }

    // ---------- Updates ----------

    private fun checkForUpdate() {
        Updater.check(this) { rel, _ ->
            if (rel == null) return@check
            val nm = getSystemService(NotificationManager::class.java)!!
            nm.createNotificationChannel(NotificationChannel(UPDATE_CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                this, 3, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_UPDATE, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(UPDATE_NOTIF_ID, Notification.Builder(this, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Claude Bubble ${rel.versionName} is ready")
                .setContentText("Tap to update")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    // ---------- Dismiss target ----------

    private fun showTarget() {
        if (target != null) return
        val size = dp(64)
        val icon = ImageView(this).apply { setImageResource(R.drawable.dismiss_target) }
        val frame = FrameLayout(this).apply {
            addView(icon, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            alpha = 0f
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }
        runCatching { wm.addView(frame, lp) }.onFailure { return }
        frame.animate().alpha(1f).setDuration(150).start()
        target = frame
        targetIcon = icon
        overTarget = false
    }

    private fun updateTargetHover(rawX: Float, rawY: Float, c: CompanionView) {
        val icon = targetIcon ?: return
        if (icon.width == 0) return
        val loc = IntArray(2)
        icon.getLocationOnScreen(loc)
        val cx = loc[0] + icon.width / 2f
        val cy = loc[1] + icon.height / 2f
        val near = hypot(rawX - cx, rawY - cy) < dp(Config.DISMISS_RADIUS_DP)
        if (near != overTarget) {
            overTarget = near
            val s = if (near) 1.25f else 1f
            icon.animate().scaleX(s).scaleY(s).setDuration(120).start()
            c.setScared(near)
            if (near) c.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun removeTarget() {
        target?.let { runCatching { wm.removeView(it) } }
        target = null
        targetIcon = null
        overTarget = false
    }

    private fun dismiss() {
        val view = companion
        removeTarget()
        if (view == null) {
            stop(this)
            return
        }
        view.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180)
            .withEndAction { stop(this) }
            .start()
    }

    // ---------- Helpers ----------

    /** The part of the screen the bubble can sit in, excluding status and navigation bars. */
    private fun usableArea(): Rect {
        return if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            Rect(0, 0, m.bounds.width() - ins.left - ins.right, m.bounds.height() - ins.top - ins.bottom)
        } else {
            val dm = resources.displayMetrics
            val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val status = if (statusId > 0) resources.getDimensionPixelSize(statusId) else 0
            Rect(0, 0, dm.widthPixels, dm.heightPixels - status)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
