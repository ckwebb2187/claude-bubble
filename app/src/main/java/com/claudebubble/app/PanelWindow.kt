package com.claudebubble.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating chat: Claude Duet on the web (claude.ai/messages) in a window over your apps.
 * Pop it open from the bubble, tuck it away again; it stays loaded (and signed in) while tucked,
 * so an answer keeps coming in and the bubble keeps animating.
 *
 * It also watches the page so the bubble can react: you typing (or using your keyboard's mic),
 * and Claude answering.
 */
class PanelWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val prefs: Prefs,
    private val area: () -> Rect,
    private val topGap: () -> Int,
    private val listener: Listener,
) {
    interface Listener {
        /** "typing", "answering", "idle" or "done". */
        fun onActivity(state: String)
        fun onTuckRequested()
        fun onOpenApp()
    }

    private val main = Handler(Looper.getMainLooper())

    var expanded = false
        private set
    val isCreated get() = root != null

    private var root: PanelRoot? = null
    private var card: LinearLayout? = null
    private var web: WebView? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var lastState = "idle"

    private val answerTimeout = Runnable { setState("idle") }

    // ---------- Window ----------

    /** Adds the (hidden) window and starts loading Duet, so the first open is quick. Returns true if newly added. */
    fun ensureCreated(): Boolean {
        if (root != null) return false
        val r = PanelRoot(ctx)
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#1F1E1B")) }
            clipToOutline = true
        }
        c.addView(header(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        val w = buildWeb()
        c.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        r.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        r.addView(grip(), FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))

        // Keep the text box above the keyboard even if the system doesn't resize the window.
        r.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val ime = ins.getInsets(WindowInsets.Type.ime()).bottom
                c.setPadding(0, 0, 0, ime)
            }
            ins
        }

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
        root = r; card = c; web = w
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

    fun expand(bubbleOnRight: Boolean) {
        ensureCreated()
        val r = root ?: return
        val c = card ?: return
        expanded = true
        applyGeometry(update = false)
        lp.flags = shownFlags()
        lp.alpha = 1f
        runCatching { wm.updateViewLayout(r, lp) }
        runJs("window.__cbTucked=false;")
        c.pivotX = if (bubbleOnRight) lp.width.toFloat() else 0f
        c.pivotY = 0f
        c.animate().cancel()
        c.scaleX = 0.88f; c.scaleY = 0.88f
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
        web?.requestFocus()
    }

    /** Hides the window but keeps the page running, so answers keep streaming in. */
    fun collapse() {
        val r = root ?: return
        val c = card ?: return
        if (!expanded) return
        expanded = false
        hush()
        runCatching {
            ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(r.windowToken, 0)
        }
        c.animate().cancel()
        c.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(140).withEndAction {
            if (!expanded) {
                lp.flags = hiddenFlags()
                lp.alpha = 0f
                runCatching { wm.updateViewLayout(r, lp) }
            }
        }.start()
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        root?.let { runCatching { wm.removeView(it) } }
        web?.let { runCatching { it.stopLoading(); it.destroy() } }
        CookieManager.getInstance().flush()
        root = null; card = null; web = null
        expanded = false
    }

    /**
     * Silences the page: pauses any audio or video, stops speech, and blocks new playback
     * while the window is tucked away. Called on tuck, screen off, alarms, ringtones and calls.
     */
    fun hush() {
        runJs(HUSH_JS)
    }

    /** Forget the sign-in (clears this app's cookies and site storage). */
    fun signOut() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        web?.clearCache(true)
        web?.loadUrl(Config.WEB_URL)
    }

    // ---------- Geometry ----------

    private fun applyGeometry(update: Boolean = true) {
        val a = area()
        val margin = dp(8)
        val minW = min(dp(260), a.width())
        val minH = min(dp(300), a.height())
        var w = prefs.panelW; var h = prefs.panelH; var x = prefs.panelX; var y = prefs.panelY
        if (w <= 0 || h <= 0 || x < 0 || y < 0) {
            w = a.width() - margin * 2
            y = topGap() + dp(4)
            h = ((a.height() - y) * 0.8f).roundToInt()
            x = margin
        }
        w = w.coerceIn(minW, a.width())
        h = h.coerceIn(minH, a.height())
        x = x.coerceIn(0, max(0, a.width() - w))
        y = y.coerceIn(0, max(0, a.height() - h))
        lp.x = x; lp.y = y; lp.width = w; lp.height = h
        if (update) root?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    private fun saveGeometry() {
        prefs.panelX = lp.x; prefs.panelY = lp.y; prefs.panelW = lp.width; prefs.panelH = lp.height
    }

    /** Re-fit the window to the screen (after a rotation, say). */
    fun relayout() { if (root != null) applyGeometry() }

    fun resetGeometry() {
        prefs.resetPanel()
        if (root != null) applyGeometry()
    }

    // ---------- Header and grip ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun header(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            setBackgroundColor(Color.parseColor("#2A2925"))
        }
        bar.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(Color.parseColor("#55FFFFFF")) }
        }, LinearLayout.LayoutParams(dp(22), dp(4)).apply { marginEnd = dp(10) })
        bar.addView(TextView(ctx).apply {
            text = "Claude Duet"
            textSize = 14f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun btn(label: String, desc: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label
            contentDescription = desc
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E8E4DC"))
            setOnClickListener { onClick() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(dp(42), dp(42))) }
        btn("⟳", "Reload") { web?.reload() }
        btn("↗", "Open the app") { listener.onOpenApp() }
        btn("⌄", "Tuck away") { listener.onTuckRequested() }

        // Drag the header to move the window.
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    val a = area()
                    lp.x = (startX + (e.rawX - downX)).roundToInt().coerceIn(0, max(0, a.width() - lp.width))
                    lp.y = (startY + (e.rawY - downY)).roundToInt().coerceIn(0, max(0, a.height() - lp.height))
                    root?.let { runCatching { wm.updateViewLayout(it, lp) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> saveGeometry()
            }
            true
        }
        return bar
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun grip(): View {
        val g = TextView(ctx).apply {
            text = "◢"
            textSize = 16f
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(6), dp(4))
            setTextColor(Color.parseColor("#77FFFFFF"))
            contentDescription = "Resize"
        }
        var downX = 0f; var downY = 0f; var startW = 0; var startH = 0
        g.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startW = lp.width; startH = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    val a = area()
                    lp.width = (startW + (e.rawX - downX)).roundToInt().coerceIn(min(dp(240), a.width()), a.width() - lp.x)
                    lp.height = (startH + (e.rawY - downY)).roundToInt().coerceIn(min(dp(260), a.height()), a.height() - lp.y)
                    root?.let { runCatching { wm.updateViewLayout(it, lp) } }
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
        val w = WebView(ctx)
        w.setBackgroundColor(Color.parseColor("#1F1E1B"))
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
                if (isOurs(Uri.parse(url ?: "").host)) view.evaluateJavascript(WATCH_JS, null)
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
                // This app has no microphone or camera permission, so the page never gets them.
                request.deny()
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
            if (s !in setOf("typing", "answering", "idle", "done")) return
            main.post { setState(s) }
        }
    }

    private fun setState(s: String) {
        main.removeCallbacks(answerTimeout)
        if (s == "answering") main.postDelayed(answerTimeout, 8000) // the page pings while it's still answering
        if (s == lastState && s != "done") return
        lastState = if (s == "done") "idle" else s
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
        /** Pauses media and speech, and blocks new playback while tucked away. */
        private const val HUSH_JS = """
(function(){try{
  window.__cbTucked = true;
  document.querySelectorAll('audio,video').forEach(function(m){try{m.pause();}catch(e){}});
  if (window.speechSynthesis) { try{ speechSynthesis.cancel(); }catch(e){} }
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
