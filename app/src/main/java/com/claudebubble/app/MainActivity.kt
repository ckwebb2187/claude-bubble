package com.claudebubble.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Settings: permission, on/off, and the look editor with a live preview. */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_UPDATE = "update"
    }

    private lateinit var updateText: TextView
    private lateinit var updateButton: Button
    private var pendingRelease: Updater.Release? = null

    private lateinit var prefs: Prefs
    private lateinit var statusText: TextView
    private lateinit var permButton: Button
    private lateinit var toggleButton: Button
    private lateinit var preview: CompanionView
    private val refreshers = ArrayList<() -> Unit>()

    private val ink = Color.parseColor("#F2EFE9")
    private val muted = Color.parseColor("#9C978E")
    private val accent = Color.parseColor("#D97757")
    private val card = Color.parseColor("#1F1E1C")
    private val chipOff = Color.parseColor("#2C2A27")
    private val bg = Color.parseColor("#141311")

    private val tabs = linkedMapOf<String, LinearLayout>()
    private val tabViews = ArrayList<TextView>()
    private var tab = "look"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }

        // ----- Header: preview with the on/off switch under it -----
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Claude Bubble", 22f, ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("v${Updater.currentName(this@MainActivity)}", 12f, muted))
        })
        preview = CompanionView(this).apply { applyPrefs(prefs); restingOnRight = false }
        val stage = FrameLayout(this).apply {
            background = rounded(Color.parseColor("#0C0B0A"), 20)
            addView(preview, FrameLayout.LayoutParams(dp(124), dp(124), Gravity.CENTER))
            setOnClickListener { preview.tapped() }
            setOnLongClickListener { preview.petted(); true }
        }
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply { topMargin = dp(10) })

        statusText = text("", 13f, muted)
        toggleButton = button("Turn on", primary = true) {
            if (BubbleService.isRunning) BubbleService.stop(this) else turnOn()
            toggleButton.postDelayed({ refreshStatus() }, 250)
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(120), dp(40)) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), 0, 0)
            addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggleButton)
        })
        permButton = button("Allow display over other apps", primary = true) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        root.addView(permButton)

        // ----- Tabs -----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(card, 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        for ((key, label) in listOf("look" to "Look", "motion" to "Motion", "behave" to "Behavior", "more" to "More")) {
            val tv = TextView(this).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; tag = key
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { showTab(key) }
            }
            tabViews.add(tv)
            bar.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(4), dp(14), dp(14))
                background = rounded(card, 18)
            }
            tabs[key] = box
            root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }

        // Look
        tabs["look"]!!.apply {
            addView(label("Theme"))
            addView(swatches())
            addView(label("Core"))
            addView(chips(Looks.core, { prefs.core }) { prefs.core = it; preview.applyPrefs(prefs) })
            addView(label("Eyes"))
            addView(chips(Looks.eyes, { prefs.eyes }) { prefs.eyes = it; preview.applyPrefs(prefs) })
            val sizeLabel = label("Size  ${prefs.sizeDp} dp")
            addView(sizeLabel)
            addView(SeekBar(this@MainActivity).apply {
                max = 96 - 44
                progress = prefs.sizeDp - 44
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { sizeLabel.text = "Size  ${p + 44} dp" }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { prefs.sizeDp = progress + 44 }
                })
                refreshers.add { progress = prefs.sizeDp - 44; sizeLabel.text = "Size  ${prefs.sizeDp} dp" }
            })
            addView(button("Reset look and motion", primary = false) {
                prefs.resetLook(); preview.applyPrefs(prefs); refreshers.forEach { it() }
            })
        }

        // Motion
        tabs["motion"]!!.apply {
            addView(label("Personality"))
            addView(chips(Looks.persona, { prefs.persona }) { prefs.persona = it; preview.applyPrefs(prefs) })
            addView(label("Resting"))
            addView(chips(Looks.idle, { prefs.idleMotion }) { prefs.idleMotion = it; preview.applyPrefs(prefs) })
            addView(label("While you type or talk"))
            addView(chips(Looks.listen, { prefs.listenFx }) { prefs.listenFx = it; preview.applyPrefs(prefs); trial("listening") })
            addView(label("While Claude answers"))
            addView(chips(Looks.talk, { prefs.talkFx }) { prefs.talkFx = it; preview.applyPrefs(prefs); trial("talking") })
            addView(label("Blinking"))
            addView(chips(Looks.blink, { prefs.blink }) { prefs.blink = it; preview.applyPrefs(prefs) })
            addView(label("Chat window opens with"))
            addView(chips(Looks.window, { prefs.windowAnim }) { prefs.windowAnim = it })
            addView(label("Speed"))
            addView(chips(Looks.speed, { prefs.animSpeed }) { prefs.animSpeed = it; preview.applyPrefs(prefs) })
            addView(label("Try it"))
            addView(chips(listOf(Option("listening", "Listening"), Option("talking", "Answering")) + Looks.moods, { "" }) {
                if (it == "listening" || it == "talking") trial(it) else preview.tryMood(it)
            })
        }

        // Behavior
        tabs["behave"]!!.apply {
            addView(label("Tap does"))
            addView(chips(listOf(Option("window", "Floating chat"), Option("app", "Open the app"), Option("look", "Look at my screen")),
                { prefs.tapAction }) { prefs.tapAction = it })
            addView(label("App to open"))
            addView(chips(listOf(Option("duet", "Claude Duet"), Option("claude", "Claude")),
                { prefs.appTarget }) { prefs.appTarget = it })
            addView(switch("Start when the phone starts", prefs.startOnBoot) { prefs.startOnBoot = it })
            addView(switch("Fade when idle", prefs.fadeIdle) { prefs.fadeIdle = it })
            addView(button("Reset chat window size and spot", primary = false) {
                prefs.resetPanel()
                if (BubbleService.isRunning) startService(Intent(this@MainActivity, BubbleService::class.java).setAction(BubbleService.ACTION_RESET_PANEL))
            })
            addView(button("Sign out of the floating chat", primary = false) {
                if (BubbleService.isRunning) startService(Intent(this@MainActivity, BubbleService::class.java).setAction(BubbleService.ACTION_SIGN_OUT))
                else { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.WebStorage.getInstance().deleteAllData() }
            })
        }

        // More
        tabs["more"]!!.apply {
            addView(label("Updates"))
            updateText = text("Version ${Updater.currentName(this@MainActivity)}", 13f, muted)
            addView(updateText)
            updateButton = button("Check for updates", primary = false) { onUpdateButton() }
            addView(updateButton)
            addView(label("How it works"))
            addView(text(
                "• Tap: the floating chat (or what you set under Behavior).\n" +
                "• Hold: menu with Look at my screen, Open the app, Pet, Customize and Hide.\n" +
                "• Drag: move it; drop it on the X to hide it. Drag it while the chat is open to move the chat.\n" +
                "• Sign in to the chat with your email and the code Claude sends; Google's button doesn't work there.",
                13f, ink))
            addView(label("Alarms and calls come first"))
            addView(text(
                "The mic only runs while the chat is open and you tap its mic. Tucking the chat, the screen going off, " +
                "an alarm or a call stops all sound and the mic right away. Nothing listens in the background.",
                13f, ink))
        }

        showTab(tab)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
            // Keep the page clear of the status and navigation bars on edge-to-edge phones.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        })
    }

    /** Shows a listening or answering effect on the preview for a few seconds. */
    private fun trial(kind: String) {
        preview.activity = kind
        preview.removeCallbacks(endTrial)
        preview.postDelayed(endTrial, 3500)
    }
    private val endTrial = Runnable { preview.activity = "idle" }

    private fun showTab(key: String) {
        tab = key
        tabs.forEach { (k, v) -> v.visibility = if (k == key) View.VISIBLE else View.GONE }
        tabViews.forEach { tv ->
            val on = tv.tag == key
            tv.background = if (on) rounded(accent, 11) else null
            tv.setTextColor(if (on) Color.WHITE else muted)
            tv.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    override fun onResume() {
        super.onResume()
        preview.paused = false
        refreshStatus()
        if (pendingRelease == null) checkUpdate(quiet = !intent.getBooleanExtra(EXTRA_UPDATE, false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun checkUpdate(quiet: Boolean) {
        if (!quiet) updateText.text = "Checking…"
        Updater.check(this) { rel, msg ->
            pendingRelease = rel
            if (rel != null) {
                updateText.text = "Version ${Updater.currentName(this)}. $msg"
                updateButton.text = "Update to ${rel.versionName}"
            } else if (!quiet) {
                updateText.text = "Version ${Updater.currentName(this)}. $msg"
            }
        }
    }

    private fun onUpdateButton() {
        val rel = pendingRelease
        if (rel == null) { checkUpdate(quiet = false); return }
        if (!Updater.ensureAllowed(this)) {
            updateText.text = "Allow Claude Bubble to install updates, then come back and tap Update again."
            return
        }
        updateButton.isEnabled = false
        Updater.install(this, rel) { msg ->
            updateText.text = msg
            if (!msg.startsWith("Downloading")) updateButton.isEnabled = true
        }
    }

    override fun onPause() {
        super.onPause()
        preview.paused = true
    }

    private fun turnOn() {
        if (!Settings.canDrawOverlays(this)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        BubbleService.start(this)
    }

    private fun refreshStatus() {
        val allowed = Settings.canDrawOverlays(this)
        val running = BubbleService.isRunning
        statusText.text = when {
            !allowed -> "First, let the bubble show over other apps."
            running -> "The bubble is on."
            else -> "The bubble is off."
        }
        permButton.visibility = if (allowed) View.GONE else View.VISIBLE
        toggleButton.isEnabled = allowed
        toggleButton.alpha = if (allowed) 1f else 0.4f
        toggleButton.text = if (running) "Turn off" else "Turn on"
    }

    // ---------- small UI helpers ----------

    private fun section(root: LinearLayout, title: String): LinearLayout {
        root.addView(text(title, 17f, ink, bold = true).apply { setPadding(dp(4), dp(18), 0, dp(8)) })
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(card, 18)
        }
        root.addView(box)
        return box
    }

    private fun label(s: String) = text(s, 12f, muted, bold = true).apply { setPadding(0, dp(12), 0, dp(5)) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.WHITE else ink)
        background = rounded(if (primary) accent else chipOff, 14)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) }
    }

    private fun switch(s: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = s; textSize = 14f; setTextColor(ink); isChecked = checked
        setPadding(0, dp(8), 0, dp(2))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun chips(options: List<Option>, current: () -> String, onPick: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = options.map { opt ->
            TextView(this).apply {
                text = opt.label
                textSize = 13f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                tag = opt.key
                setOnClickListener { onPick(opt.key); refreshers.forEach { r -> r() } }
                row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
        }
        val paint = {
            val cur = current()
            views.forEach { v ->
                val on = v.tag == cur
                v.background = rounded(if (on) accent else chipOff, 20)
                v.setTextColor(if (on) Color.WHITE else ink)
            }
        }
        paint()
        refreshers.add(paint)
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun swatches(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(2)) }
        val cells = Themes.all.map { th ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val dot = View(this@MainActivity)
                addView(dot, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(text(th.label, 10f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
                tag = th to dot
                setOnClickListener { prefs.theme = th.key; preview.applyPrefs(prefs); refreshers.forEach { it() } }
                row.addView(this, LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        val paint = {
            cells.forEach { cell ->
                @Suppress("UNCHECKED_CAST")
                val pair = cell.tag as Pair<Theme, View>
                val (th, dot) = pair
                val colors = if (th.key == Themes.RAINBOW)
                    intArrayOf(Color.parseColor("#F472B6"), Color.parseColor("#FACC15"), Color.parseColor("#34D399"), Color.parseColor("#38BDF8"))
                else intArrayOf(th.primary, th.accent)
                dot.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                    shape = GradientDrawable.OVAL
                    if (prefs.theme == th.key) setStroke(dp(3), ink) else setStroke(dp(1), Color.parseColor("#33FFFFFF"))
                }
            }
        }
        paint()
        refreshers.add(paint)
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
