package com.claudebubble.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating chat: Claude Duet on the web (claude.ai/messages) in a window over your apps.
 *
 * It's part of the bubble rather than a separate box: it grows out of the bubble, the bubble
 * docks into its top bar (same theme colors) and keeps reacting there, dragging the bubble
 * drags the whole window, and tapping the bubble folds the window back into it.
 * It stays loaded (and signed in) while tucked, so an answer keeps coming in and the bubble
 * keeps animating. Dark mode throughout.
 */
class PanelWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val area: () -> Rect,
    /** The bubble's drawn size and its window's size, in px. */
    private val orbSize: () -> Int,
    private val bubbleWindow: () -> Int,
    private val listener: Listener,
) {
    interface Listener {
        /** "typing", "answering", "idle" or "done". */
        fun onActivity(state: String)
        fun onTuckRequested()
        fun onOpenApp()
        /** The window moved or changed size: re-dock the bubble. */
        fun onPanelMoved()
        /** The page wants the mic (you tapped its mic button). Returns false if it mustn't have it now. */
        fun onMicWanted(): Boolean
        /** The page stopped using the mic. */
        fun onMicReleased()
        /** Android hasn't allowed the mic yet: ask (with the chat tucked so the question shows). */
        fun onMicPermissionNeeded()
    }

    private val main = Handler(Looper.getMainLooper())

    var expanded = false
        private set
    val isCreated get() = root != null

    private var root: PanelRoot? = null
    private var card: LinearLayout? = null
    private var bar: LinearLayout? = null
    private var subtitle: TextView? = null
    private var web: WebView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var lastState = "idle"
    private var dockRight = true
    private var theme: Theme = Themes.byKey(prefs.theme)
    private var reveal: Animator? = null
    private var imeInset = 0
    private val visRect = Rect()
    private val loc = IntArray(2)

    private val answerTimeout = Runnable { setState("idle") }

    /** Room around the card for its shadow. */
    private val pad get() = dp(12)
    private val headerH get() = dp(56)
    /** Gap between the card's edge and the docked bubble. */
    private val dockInset get() = dp(10)

    // ---------- Window ----------

    /** Adds the (hidden) window and starts loading Duet, so the first open is quick. Returns true if newly added. */
    fun ensureCreated(): Boolean {
        if (root != null) return false
        val r = PanelRoot(ctx).apply { setPadding(pad, pad, pad, pad); clipToPadding = false }
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            elevation = dp(9).toFloat()
        }
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar = b
        c.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerH))
        val w = buildWeb()
        c.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        r.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        r.addView(grip().apply { translationZ = dp(10).toFloat() },
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))
        card = c
        buildHeader()
        paint()

        // Keep the text box above the keyboard even if the system doesn't resize the window.
        // Keep the message box and send button above the keyboard: the chat shrinks to the
        // space above it. Two ways of measuring, since overlay windows don't resize on their own.
        r.setOnApplyWindowInsetsListener { _, ins ->
            imeInset = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
            fitKeyboard()
            ins
        }
        r.viewTreeObserver.addOnGlobalLayoutListener { fitKeyboard() }

        lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            hiddenFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            alpha = 0f
        }
        applyGeometry(update = false)
        c.alpha = 0f
        root = r; web = w
        wm.addView(r, lp)
        w.loadUrl(Config.WEB_URL)
        return true
    }

    private fun hiddenFlags() =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun shownFlags() =
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    /** Which side of the window the bubble docks on (its own side of the screen). */
    fun setDock(right: Boolean, force: Boolean = false) {
        if (!force && right == dockRight && (bar?.childCount ?: 0) > 0) return
        dockRight = right
        buildHeader()
        paint()
    }

    /** Matches the window's top bar and edge glow to the bubble's theme. */
    fun applyTheme(t: Theme) {
        theme = t
        paint()
    }

    /** Where the bubble's window goes so the bubble sits in the top bar: its top-left, in px. */
    fun dockPoint(): Pair<Int, Int> {
        val (cx, cy) = dockCenterOnScreen()
        val win = bubbleWindow()
        return (cx - win / 2) to (cy - win / 2)
    }

    private fun dockCenterOnScreen(): Pair<Int, Int> {
        val (cx, cy) = dockCenterInCard()
        return (lp.x + pad + cx) to (lp.y + pad + cy)
    }

    /** The bubble's center relative to the card: the point the window grows out of. */
    private fun dockCenterInCard(): Pair<Int, Int> {
        val cardW = lp.width - pad * 2
        val half = orbSize() / 2
        val cx = if (dockRight) cardW - dockInset - half else dockInset + half
        return cx to headerH / 2
    }

    /** Grows the window out of the bubble. */
    fun expand(delayMs: Long = 0) {
        ensureCreated()
        val r = root ?: return
        expanded = true
        applyGeometry(update = false)
        lp.flags = shownFlags()
        lp.alpha = 1f
        runCatching { wm.updateViewLayout(r, lp) }
        runJs("window.__cbTucked=false;")
        main.postDelayed({ if (expanded) growIn() }, delayMs)
        web?.requestFocus()
    }

    private fun growIn() {
        val c = card ?: return
        if (c.width == 0 || !c.isAttachedToWindow) { c.post { if (expanded) growIn() }; return }
        val (ox, oy) = dockCenterInCard()
        reveal?.cancel()
        c.animate().cancel()
        c.pivotX = ox.toFloat(); c.pivotY = oy.toFloat()
        when (prefs.windowAnim) {
            "pop" -> {
                c.scaleX = 0.3f; c.scaleY = 0.3f; c.alpha = 0f
                c.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340)
                    .setInterpolator(OvershootInterpolator(1.3f)).start()
                return
            }
            "unroll" -> {
                c.scaleX = 0.92f; c.scaleY = 0.06f; c.alpha = 0.6f
                c.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320)
                    .setInterpolator(DecelerateInterpolator(1.8f)).start()
                return
            }
            "fade" -> {
                c.scaleX = 0.97f; c.scaleY = 0.97f; c.alpha = 0f
                c.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
                    .setInterpolator(DecelerateInterpolator()).start()
                return
            }
        }
        c.scaleX = 0.82f; c.scaleY = 0.82f
        c.alpha = 1f
        c.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(DecelerateInterpolator(1.6f)).start()
        val end = farthestCorner(c, ox, oy)
        reveal = runCatching {
            ViewAnimationUtils.createCircularReveal(c, ox, oy, orbSize() / 2f, end).apply {
                duration = 300
                interpolator = DecelerateInterpolator(1.4f)
                start()
            }
        }.getOrNull()
    }

    /** Folds the window back into the bubble; the page keeps running, so answers keep streaming in. */
    fun collapse() {
        val r = root ?: return
        val c = card ?: return
        if (!expanded) return
        expanded = false
        hush()
        runCatching {
            ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(r.windowToken, 0)
        }
        val hide = Runnable {
            if (!expanded) {
                c.alpha = 0f
                lp.flags = hiddenFlags()
                lp.alpha = 0f
                runCatching { wm.updateViewLayout(r, lp) }
            }
        }
        val (ox, oy) = dockCenterInCard()
        reveal?.cancel()
        c.animate().cancel()
        c.pivotX = ox.toFloat(); c.pivotY = oy.toFloat()
        when (prefs.windowAnim) {
            "pop" -> { c.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(200)
                .setInterpolator(AccelerateInterpolator(1.4f)).withEndAction(hide).start(); return }
            "unroll" -> { c.animate().scaleX(0.92f).scaleY(0.06f).alpha(0.4f).setDuration(220)
                .setInterpolator(AccelerateInterpolator(1.4f)).withEndAction(hide).start(); return }
            "fade" -> { c.animate().scaleX(0.97f).scaleY(0.97f).alpha(0f).setDuration(180)
                .setInterpolator(AccelerateInterpolator()).withEndAction(hide).start(); return }
        }
        c.animate().scaleX(0.82f).scaleY(0.82f).setDuration(210).start()
        val anim = runCatching {
            ViewAnimationUtils.createCircularReveal(c, ox, oy, farthestCorner(c, ox, oy), orbSize() / 2f).apply {
                duration = 210
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { hide.run() }
                })
                start()
            }
        }.getOrNull()
        reveal = anim
        if (anim == null) c.animate().alpha(0f).setDuration(180).withEndAction(hide).start()
    }

    /** Pads the chat's bottom by however much of it the keyboard covers. */
    private fun fitKeyboard() {
        val r = root ?: return
        val c = card ?: return
        var kb = 0
        if (expanded) {
            kb = imeInset
            if (Build.VERSION.SDK_INT >= 30) {
                r.rootWindowInsets?.let { kb = max(kb, it.getInsets(WindowInsets.Type.ime()).bottom) }
            }
            // What's actually visible on screen: the keyboard's top edge.
            r.getWindowVisibleDisplayFrame(visRect)
            r.getLocationOnScreen(loc)
            val covered = loc[1] + r.height - visRect.bottom
            if (covered > dp(90)) kb = max(kb, covered)
        }
        val want = max(0, kb - pad)
        if (c.paddingBottom != want) c.setPadding(0, 0, 0, want)
    }

    private fun farthestCorner(v: View, x: Int, y: Int): Float {
        val w = v.width.toFloat(); val h = v.height.toFloat()
        return max(max(hypot(x.toFloat(), y.toFloat()), hypot(w - x, y.toFloat())),
            max(hypot(x.toFloat(), h - y), hypot(w - x, h - y)))
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        reveal?.cancel()
        root?.let { runCatching { wm.removeView(it) } }
        web?.let { runCatching { it.stopLoading(); it.destroy() } }
        CookieManager.getInstance().flush()
        root = null; card = null; web = null; bar = null; subtitle = null
        expanded = false
    }

    /**
     * Silences the page: pauses any audio or video, stops speech, and blocks new playback
     * while the window is tucked away. Called on tuck, screen off, alarms, ringtones and calls.
     */
    fun hush() {
        runJs(HUSH_JS)
        listener.onMicReleased()
    }

    /** Forget the sign-in (clears this app's cookies and site storage). */
    fun signOut() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.loadUrl(Config.WEB_URL)
    }

    // ---------- Geometry ----------

    /** Highest the window may sit while the docked bubble stays fully on screen. */
    private fun minY(): Int = max(0, bubbleWindow() / 2 - pad - headerH / 2)

    private fun applyGeometry(update: Boolean = true) {
        val a = area()
        val margin = dp(2)
        val minW = min(dp(270), a.width())
        val minH = min(dp(320), a.height())
        val top = minY()
        var w = prefs.panelW; var h = prefs.panelH; var x = prefs.panelX; var y = prefs.panelY
        if (w <= 0 || h <= 0 || x < 0 || y < 0) {
            w = a.width() - margin * 2
            y = top + dp(4)
            h = ((a.height() - y) * 0.84f).roundToInt()
            x = margin
        }
        w = w.coerceIn(minW, a.width())
        h = h.coerceIn(minH, max(minH, a.height() - top))
        x = x.coerceIn(0, max(0, a.width() - w))
        y = y.coerceIn(top, max(top, a.height() - h))
        lp.x = x; lp.y = y; lp.width = w; lp.height = h
        if (update) root?.let { runCatching { wm.updateViewLayout(it, lp) } }
        listener.onPanelMoved()
    }

    private fun saveGeometry() {
        prefs.panelX = lp.x; prefs.panelY = lp.y; prefs.panelW = lp.width; prefs.panelH = lp.height
    }

    /** Moves the whole window (dragging the bubble or the top bar). */
    fun moveTo(x: Int, y: Int) {
        if (root == null) return
        val a = area()
        lp.x = x.coerceIn(0, max(0, a.width() - lp.width))
        lp.y = y.coerceIn(minY(), max(minY(), a.height() - lp.height))
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }
        listener.onPanelMoved()
    }

    val windowX get() = if (root == null) 0 else lp.x
    val windowY get() = if (root == null) 0 else lp.y

    fun saveWhere() { if (root != null) saveGeometry() }

    /** Re-fit the window to the screen (after a rotation, say). */
    fun relayout() { if (root != null) applyGeometry() }

    fun resetGeometry() {
        prefs.resetPanel()
        if (root != null) applyGeometry()
    }

    // ---------- Look: dark, in the bubble's colors ----------

    private fun mix(c: Int, toward: Int, f: Float): Int = Color.rgb(
        (Color.red(c) + (Color.red(toward) - Color.red(c)) * f).roundToInt(),
        (Color.green(c) + (Color.green(toward) - Color.green(c)) * f).roundToInt(),
        (Color.blue(c) + (Color.blue(toward) - Color.blue(c)) * f).roundToInt(),
    )

    private fun themeColors(): Pair<Int, Int> =
        if (theme.key == Themes.RAINBOW) Color.parseColor("#F472B6") to Color.parseColor("#38BDF8")
        else theme.primary to theme.accent

    private fun paint() {
        val c = card ?: return
        val (p, a) = themeColors()
        val ink = Color.parseColor("#141311")
        c.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(BODY)
            setStroke(dp(1), (mix(p, ink, 0.25f) and 0x00FFFFFF) or 0x8C000000.toInt())
        }
        // The top bar runs from the bubble's color on its side to the accent on the other.
        val near = mix(p, ink, 0.58f)
        val far = mix(a, ink, 0.66f)
        bar?.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (dockRight) intArrayOf(far, near) else intArrayOf(near, far)
        )
        root?.invalidate()
        themePage()
    }

    /** Colors the chat's message bubbles to match the bubble's theme, on a dark page. */
    private fun themePage() {
        val (p, a) = themeColors()
        val ink = Color.parseColor("#141311")
        fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)
        val u1 = hex(mix(p, ink, 0.45f)); val u2 = hex(mix(a, ink, 0.55f))
        val ai = hex(mix(a, Color.parseColor("#262624"), 0.86f))
        val link = hex(mix(p, Color.WHITE, 0.35f))
        runJs("window.__cbTheme && window.__cbTheme('$u1','$u2','$ai','$link');")
    }

    // ---------- Top bar and grip ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildHeader() {
        val b = bar ?: return
        b.removeAllViews()
        // Room for the docked bubble on its side; the buttons go on the other side.
        val dockRoom = dockInset * 2 + orbSize()
        val titles = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (dockRight) Gravity.END else Gravity.START
        }
        titles.addView(TextView(ctx).apply {
            text = "Claude Duet"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        val sub = TextView(ctx).apply {
            text = subtitleFor(lastState)
            textSize = 11.5f
            setTextColor(Color.parseColor("#C8FFFFFF"))
        }
        titles.addView(sub)
        subtitle = sub
        fun btn(label: String, desc: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            contentDescription = desc
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#F2EEE6"))
            setOnClickListener { onClick() }
        }
        val buttons = listOf(
            btn("⟳", "Reload") { web?.reload() },
            btn("↗", "Open the app") { listener.onOpenApp() },
            btn("⌄", "Tuck away") { listener.onTuckRequested() },
        )
        val titleLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (dockRight) {
            b.setPadding(dp(4), 0, 0, 0)
            buttons.reversed().forEach { b.addView(it, LinearLayout.LayoutParams(dp(40), dp(44))) }
            b.addView(titles, titleLp)
            b.addView(View(ctx), LinearLayout.LayoutParams(dockRoom, 1))
        } else {
            b.setPadding(0, 0, dp(4), 0)
            b.addView(View(ctx), LinearLayout.LayoutParams(dockRoom, 1))
            b.addView(titles, titleLp)
            buttons.forEach { b.addView(it, LinearLayout.LayoutParams(dp(40), dp(44))) }
        }

        // Drag the top bar to move the window (the bubble comes along).
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> moveTo((startX + (e.rawX - downX)).roundToInt(), (startY + (e.rawY - downY)).roundToInt())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveGeometry()
            }
            true
        }
    }

    private fun subtitleFor(s: String) = when (s) {
        "typing" -> "Listening…"
        "answering" -> "Answering…"
        else -> "Tap me to tuck away"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun grip(): View {
        val g = TextView(ctx).apply {
            text = "◢"
            textSize = 16f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(8), dp(6))
            setTextColor(Color.parseColor("#66FFFFFF"))
            contentDescription = "Resize"
        }
        var downX = 0f; var downY = 0f; var startW = 0; var startH = 0
        g.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startW = lp.width; startH = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    val a = area()
                    lp.width = (startW + (e.rawX - downX)).roundToInt().coerceIn(min(dp(250), a.width()), max(min(dp(250), a.width()), a.width() - lp.x))
                    lp.height = (startH + (e.rawY - downY)).roundToInt().coerceIn(min(dp(280), a.height()), max(min(dp(280), a.height()), a.height() - lp.y))
                    root?.let { runCatching { wm.updateViewLayout(it, lp) } }
                    listener.onPanelMoved()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveGeometry()
            }
            true
        }
        return g
    }

    // ---------- The web page ----------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun buildWeb(): WebView {
        // A dark theme on the WebView's context makes the page see prefers-color-scheme: dark,
        // so Claude shows its own dark theme.
        val w = WebView(ContextThemeWrapper(ctx, android.R.style.Theme_Material_NoActionBar))
        w.setBackgroundColor(BODY)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            // No sound without a tap, ever. (Part of keeping alarms and calls safe.)
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            textZoom = 100
            setGeolocationEnabled(false)
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Dark mode: the page's own dark theme first, and darken anything that has none.
            if (Build.VERSION.SDK_INT >= 33) {
                isAlgorithmicDarkeningAllowed = true
            } else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION") forceDark = WebSettings.FORCE_DARK_ON
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "ClaudeBubbleHost")

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val uri = req.url
                if (uri.scheme != "https" && uri.scheme != "http") {
                    openOutside(uri); return true
                }
                if (isOurs(uri.host)) return false
                // Google's sign-in page refuses embedded windows; everything else external opens in the browser.
                openOutside(uri)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (isOurs(Uri.parse(url ?: "").host)) {
                    view.evaluateJavascript(WATCH_JS, null)
                    view.evaluateJavascript(THEME_JS, null)
                    themePage()
                }
                if (!expanded) view.evaluateJavascript("window.__cbTucked=true;", null)
                CookieManager.getInstance().flush()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page crashed or Android reclaimed it: build a fresh one instead of taking the bubble down.
                main.post { rebuildWeb() }
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Only the mic, only for Claude's page, only while the chat is open. Never the camera.
                val wantsMic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources
                if (!wantsMic || !expanded || !isOurs(request.origin?.host)) { request.deny(); return }
                if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    request.deny()
                    listener.onMicPermissionNeeded()
                    return
                }
                if (!listener.onMicWanted()) { request.deny(); return }
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                return FilePickActivity.start(ctx, callback, params)
            }

            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                overlayDialog(message ?: "", withCancel = false, result); return true
            }

            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                overlayDialog(message ?: "", withCancel = true, result); return true
            }
        }
        w.setDownloadListener { url, _, _, _, _ -> openOutside(Uri.parse(url)) }
        return w
    }

    private fun rebuildWeb() {
        val c = card ?: return
        web?.let { old -> c.removeView(old); runCatching { old.destroy() } }
        val w = buildWeb()
        c.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        web = w
        w.loadUrl(Config.WEB_URL)
        setState("idle")
    }

    private fun isOurs(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "claude.ai" || h.endsWith(".claude.ai") ||
            h == "claude.com" || h.endsWith(".claude.com") ||
            h == "anthropic.com" || h.endsWith(".anthropic.com") ||
            h == "claudeusercontent.com" || h.endsWith(".claudeusercontent.com")
    }

    private fun openOutside(uri: Uri) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            listener.onTuckRequested()
        }.onFailure { Toast.makeText(ctx, "No app can open that link", Toast.LENGTH_SHORT).show() }
    }

    private fun overlayDialog(message: String, withCancel: Boolean, result: JsResult) {
        try {
            val b = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
            if (withCancel) b.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            val d = b.create()
            d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            d.show()
        } catch (_: Exception) {
            if (withCancel) result.cancel() else result.confirm()
        }
    }

    private fun runJs(js: String) {
        web?.evaluateJavascript(js, null)
    }

    // ---------- Screenshot into the chat ----------

    /**
     * Puts a screenshot into the chat's message box, as if you'd pasted it.
     * Calls [done] with true if the page took it, false if it should go to an app instead.
     */
    fun attachScreenshot(file: File, done: (Boolean) -> Unit) {
        val w = web
        if (w == null) { done(false); return }
        Thread {
            val b64 = runCatching {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, opts)
                var sample = 1
                while (max(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
                val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                val longest = max(bmp.width, bmp.height)
                val scaled = if (longest > 1600) {
                    val f = 1600f / longest
                    Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt(), (bmp.height * f).roundToInt(), true)
                } else bmp
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
            main.post {
                if (b64 == null || web !== w) { done(false); return@post }
                w.evaluateJavascript("window.__cbAttach ? window.__cbAttach('$b64','screen.jpg') : 'nohook'") { res ->
                    val r = res?.trim('"') ?: ""
                    done(r == "pasted" || r == "input")
                }
            }
        }.start()
    }

    // ---------- Page activity -> bubble ----------

    private inner class Bridge {
        @JavascriptInterface
        fun state(s: String) {
            if (s == "micoff") { main.post { listener.onMicReleased() }; return }
            if (s !in setOf("typing", "answering", "idle", "done")) return
            main.post { setState(s) }
        }
    }

    private fun setState(s: String) {
        main.removeCallbacks(answerTimeout)
        if (s == "answering") main.postDelayed(answerTimeout, 8000) // the page pings while it's still answering
        if (s == lastState && s != "done") return
        lastState = if (s == "done") "idle" else s
        subtitle?.text = subtitleFor(lastState)
        listener.onActivity(s)
    }

    // ---------- The root view: Back tucks the window away ----------

    private inner class PanelRoot(c: Context) : FrameLayout(c) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) listener.onTuckRequested()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    companion object {
        /** The chat's dark body color (matches Claude's dark theme). */
        private val BODY = Color.parseColor("#262624")

        /**
         * Themes the chat: keeps the page dark, and colors your messages (and Claude's, where the
         * page draws them as bubbles) in the bubble's theme. Finds the bubbles by how they look
         * (rounded, colored, sitting on one side), since the page's own class names change.
         */
        private const val THEME_JS = """
(function(){
  if (window.__cbThemeHooked) return; window.__cbThemeHooked = true;
  var st = document.createElement('style'); st.id = 'cb-theme';
  (document.head || document.documentElement).appendChild(st);
  var seen = new WeakSet(), cols = null, timer = null;

  function keepDark(){
    var h = document.documentElement;
    if (h.getAttribute('data-mode') === 'light') h.setAttribute('data-mode', 'dark');
    if (!h.classList.contains('dark')) h.classList.add('dark');
  }

  function css(){
    if (!cols) return;
    st.textContent =
      ':root{color-scheme:dark}' +
      'html,body{background-color:#262624}' +
      '.cb-ub{background:linear-gradient(135deg,' + cols.u1 + ',' + cols.u2 + ')!important;color:#fff!important;border-color:transparent!important}' +
      '.cb-ub *{color:inherit!important;background-color:transparent!important}' +
      '.cb-ab{background:' + cols.ai + '!important;border-color:transparent!important}' +
      '.cb-ub a,.cb-ab a,.font-claude-message a{color:' + cols.link + '!important}';
  }

  function transparent(bg){ return !bg || bg === 'transparent' || /rgba\([^)]*,\s*0\)$/.test(bg); }

  function scan(){
    timer = null;
    keepDark();
    var vw = window.innerWidth || document.documentElement.clientWidth;
    var bodyBg = getComputedStyle(document.body || document.documentElement).backgroundColor;
    // Known markers first.
    document.querySelectorAll('[data-testid="user-message"]').forEach(function(m){
      var el = m;
      for (var i = 0; i < 5 && el && el !== document.body; i++, el = el.parentElement) {
        var cs = getComputedStyle(el);
        if (!transparent(cs.backgroundColor) && cs.backgroundColor !== bodyBg) { el.classList.add('cb-ub'); break; }
      }
    });
    // Then anything shaped like a chat bubble.
    var els = document.querySelectorAll('main div, [role="log"] div, div[class*="message"]');
    for (var i = 0; i < els.length && i < 4000; i++) {
      var el = els[i];
      if (seen.has(el)) continue;
      if (el.closest('[contenteditable],form,nav,header,footer,[role="dialog"],[role="menu"]')) { seen.add(el); continue; }
      if (!(el.textContent || '').trim()) continue;
      var cs = getComputedStyle(el);
      if (transparent(cs.backgroundColor) || cs.backgroundColor === bodyBg || parseFloat(cs.borderTopLeftRadius) < 10) { seen.add(el); continue; }
      if (el.querySelector('input,textarea,button,[contenteditable],img,svg,video')) { seen.add(el); continue; }
      var r = el.getBoundingClientRect();
      if (r.width < 40 || r.height < 26 || r.width > vw * 0.88) continue;
      seen.add(el);
      if (r.right > vw * 0.86 && r.left > vw * 0.12) el.classList.add('cb-ub');
      else if (r.left < vw * 0.14 && r.right < vw * 0.88) el.classList.add('cb-ab');
    }
  }
  function soon(){ if (!timer) timer = setTimeout(scan, 500); }
  new MutationObserver(soon).observe(document.documentElement, {subtree:true, childList:true, attributes:true, attributeFilter:['data-mode','class']});
  window.addEventListener('resize', soon);

  window.__cbTheme = function(u1, u2, ai, link){ cols = {u1:u1, u2:u2, ai:ai, link:link}; css(); soon(); };
  keepDark();
})();
"""

        /** Pauses media and speech, and blocks new playback while tucked away. */
        private const val HUSH_JS = """
(function(){try{
  window.__cbTucked = true;
  document.querySelectorAll('audio,video').forEach(function(m){try{m.pause();}catch(e){}});
  if (window.speechSynthesis) { try{ speechSynthesis.cancel(); }catch(e){} }
  (window.__cbStreams || []).forEach(function(s){ try{ s.getTracks().forEach(function(t){ t.stop(); }); }catch(e){} });
  window.__cbStreams = [];
}catch(e){}})();
"""

        /**
         * Watches the page and tells the bubble what's happening:
         * typing (keys or keyboard voice typing in a text box), answering (new text streaming in
         * outside the text box), idle and done. Also blocks audio while the window is tucked.
         */
        private const val WATCH_JS = """
(function(){
  if (window.__cbHooked) return; window.__cbHooked = true;
  var H = window.ClaudeBubbleHost; if (!H) return;
  function send(s){ try{ H.state(s); }catch(e){} }

  // No sound while tucked away.
  try {
    var oPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function(){
      if (window.__cbTucked) { try{ this.pause(); }catch(e){} return Promise.reject(new DOMException('Tucked away','NotAllowedError')); }
      return oPlay.apply(this, arguments);
    };
    if (window.speechSynthesis) {
      var oSpeak = speechSynthesis.speak.bind(speechSynthesis);
      speechSynthesis.speak = function(u){ if (window.__cbTucked) return; return oSpeak(u); };
    }
  } catch(e){}

  // The page's mic: never while tucked; the bubble listens while it's on; let Android know when it's off.
  window.__cbStreams = window.__cbStreams || [];
  try {
    var md = navigator.mediaDevices;
    if (md && md.getUserMedia) {
      var oGUM = md.getUserMedia.bind(md);
      var micT = null;
      var micLive = function(){
        return window.__cbStreams.some(function(s){ return s.getAudioTracks().some(function(t){ return t.readyState === 'live'; }); });
      };
      var watchMic = function(){
        if (micT) return;
        send('typing');
        micT = setInterval(function(){
          if (micLive()) { send('typing'); return; }
          clearInterval(micT); micT = null;
          window.__cbStreams = [];
          send('idle'); send('micoff');
        }, 1500);
      };
      md.getUserMedia = function(c){
        if (window.__cbTucked) return Promise.reject(new DOMException('Tucked away', 'NotAllowedError'));
        return oGUM(c).then(function(s){ window.__cbStreams.push(s); watchMic(); return s; });
      };
    }
  } catch(e){}

  function editable(el){
    while (el && el.nodeType !== 1) el = el.parentNode;
    while (el) {
      if (el.isContentEditable || el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && /^(text|search|)$/i.test(el.type || ''))) return true;
      el = el.parentElement;
    }
    return false;
  }

  var typing = false, typingT = null, answering = false, ansT = null, pingT = 0;
  var burst = 0, burstStart = 0, burstLast = 0, quietUntil = Date.now() + 2500, lastHref = location.href;

  function typed(e){
    if (!editable(e.target)) return;
    clearTimeout(typingT);
    if (!typing) { typing = true; if (!answering) send('typing'); }
    typingT = setTimeout(function(){ typing = false; if (!answering) send('idle'); }, 2500);
  }
  ['input','keydown','compositionupdate'].forEach(function(t){ document.addEventListener(t, typed, true); });

  function noteText(n){
    var now = Date.now();
    if (location.href !== lastHref) { lastHref = location.href; quietUntil = now + 2000; burst = 0; }
    if (now < quietUntil || typing) return;
    if (now - burstLast > 1500) { burst = 0; burstStart = now; }
    burstLast = now; burst += n;
    if (!answering && burst > 30 && now - burstStart > 500) { answering = true; send('answering'); pingT = now; }
    if (answering) {
      if (now - pingT > 2500) { send('answering'); pingT = now; }
      clearTimeout(ansT);
      ansT = setTimeout(function(){ answering = false; burst = 0; send('done'); }, 2200);
    }
  }

  new MutationObserver(function(recs){
    var added = 0;
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i];
      if (editable(r.target)) continue;
      if (r.type === 'characterData') {
        added += Math.max(0, (r.target.data || '').length - (r.oldValue || '').length);
      } else {
        for (var j = 0; j < r.addedNodes.length; j++) {
          var nd = r.addedNodes[j];
          if (nd.nodeType === 3) added += nd.data.length;
          else if (nd.nodeType === 1 && nd.tagName !== 'SCRIPT' && nd.tagName !== 'STYLE') added += Math.min(400, (nd.textContent || '').length);
        }
      }
    }
    if (added > 0) noteText(added);
  }).observe(document.documentElement, {subtree:true, childList:true, characterData:true, characterDataOldValue:true});

  // Screenshot into the message box, as if pasted.
  window.__cbAttach = function(b64, name){
    try {
      var bin = atob(b64), arr = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
      var file = new File([arr], name, {type:'image/jpeg'});
      var dt = new DataTransfer(); dt.items.add(file);
      var ed = document.querySelector('[contenteditable="true"]') || document.querySelector('textarea');
      if (ed) {
        ed.focus();
        var ev = new ClipboardEvent('paste', {clipboardData: dt, bubbles: true, cancelable: true});
        ed.dispatchEvent(ev);
        if (ev.defaultPrevented) return 'pasted';
      }
      var inp = document.querySelector('input[type=file]');
      if (inp) { inp.files = dt.files; inp.dispatchEvent(new Event('change', {bubbles: true})); return 'input'; }
      return ed ? 'unhandled' : 'noeditor';
    } catch(e) { return 'error'; }
  };
})();
"""
    }
}
