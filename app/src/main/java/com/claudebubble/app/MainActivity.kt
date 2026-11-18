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

    private val ink = Color.parseColor("#1F1E1B")
    private val muted = Color.parseColor("#6B6862")
    private val accent = Color.parseColor("#D97757")
    private val card = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }

        root.addView(text("Claude Bubble", 26f, ink, bold = true))
        root.addView(text("A little companion that floats over your apps. Tap it to pop open Claude Duet, hold it for more.", 14f, muted).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        // ----- Preview -----
        preview = CompanionView(this).apply { applyPrefs(prefs); restingOnRight = false }
        val stage = FrameLayout(this).apply {
            background = rounded(Color.parseColor("#1F1E1B"), 22)
            addView(preview, FrameLayout.LayoutParams(dp(150), dp(150), Gravity.CENTER))
            setOnClickListener { preview.tapped() }
            setOnLongClickListener { preview.petted(); true }
        }
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
        root.addView(text("Tap the preview to poke it, hold to pet it.", 12f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(10))
        })

        // ----- On / off -----
        val power = section(root, "Bubble")
        statusText = text("", 14f, muted)
        power.addView(statusText)
        permButton = button("Allow display over other apps", primary = true) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        power.addView(permButton)
        toggleButton = button("Turn bubble on", primary = true) {
            if (BubbleService.isRunning) BubbleService.stop(this) else turnOn()
            toggleButton.postDelayed({ refreshStatus() }, 250)
        }
        power.addView(toggleButton)
        power.addView(text("Tap does", 13f, muted, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        power.addView(chips(listOf(Option("window", "Floating chat"), Option("app", "Open the app"), Option("look", "Look at my screen")),
            { prefs.tapAction }) { prefs.tapAction = it })
        power.addView(text("App to open", 13f, muted, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        power.addView(chips(listOf(Option("duet", "Claude Duet"), Option("claude", "Claude")),
            { prefs.appTarget }) { prefs.appTarget = it })
        power.addView(switch("Turn on when the phone starts", prefs.startOnBoot) { prefs.startOnBoot = it })
        power.addView(switch("Fade when idle", prefs.fadeIdle) { prefs.fadeIdle = it })

        // ----- Look -----
        val look = section(root, "Look")
        look.addView(label("Theme"))
        look.addView(swatches())
        look.addView(label("Core"))
        look.addView(chips(Looks.core, { prefs.core }) { prefs.core = it; preview.applyPrefs(prefs) })
        look.addView(label("Eyes"))
        look.addView(chips(Looks.eyes, { prefs.eyes }) { prefs.eyes = it; preview.applyPrefs(prefs) })
        look.addView(label("Blinking"))
        look.addView(chips(Looks.blink, { prefs.blink }) { prefs.blink = it; preview.applyPrefs(prefs) })
        look.addView(label("Size"))
        val sizeVal = text("${prefs.sizeDp} dp", 13f, muted)
        look.addView(sizeVal)
        look.addView(SeekBar(this).apply {
            max = 96 - 44
            progress = prefs.sizeDp - 44
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { sizeVal.text = "${p + 44} dp" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.sizeDp = progress + 44 }
            })
            refreshers.add { progress = prefs.sizeDp - 44; sizeVal.text = "${prefs.sizeDp} dp" }
        })

        // ----- Personality -----
        val pers = section(root, "Personality")
        pers.addView(chips(Looks.persona, { prefs.persona }) { prefs.persona = it; preview.applyPrefs(prefs) })
        pers.addView(text("Quirky glances around, hops, gets dizzy when you shake it and dozes off when left alone. Calm keeps it chill.", 13f, muted).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        pers.addView(label("Try a mood"))
        pers.addView(chips(Looks.moods, { "" }) { preview.tryMood(it) })
        pers.addView(button("Reset look", primary = false) {
            prefs.resetLook(); preview.applyPrefs(prefs); refreshers.forEach { it() }
        })

        // ----- Floating chat -----
        val chat = section(root, "Floating chat")
        chat.addView(text(
            "Claude Duet on the web, in a window over your apps. Drag its top bar to move it, the corner to resize, " +
            "and tap ⌄, Back or the bubble to tuck it away. It stays signed in and keeps going while tucked: " +
            "the bubble's mouth moves while Claude answers, and a dot shows when an answer is waiting.\n\n" +
            "Signing in: use your email and the code Claude sends. Google's sign-in button doesn't work inside an app window.",
            14f, ink))
        chat.addView(button("Reset window size and spot", primary = false) {
            prefs.resetPanel()
            if (BubbleService.isRunning) startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_RESET_PANEL))
        })
        chat.addView(button("Sign out of the floating chat", primary = false) {
            if (BubbleService.isRunning) startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_SIGN_OUT))
            else { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.WebStorage.getInstance().deleteAllData() }
        })

        // ----- Alarm safety -----
        val safe = section(root, "Alarms and calls come first")
        safe.addView(text(
            "The mic only works while the floating chat is open and you tap its mic button. It lets go of the mic " +
            "the moment you tuck the chat away, the screen turns off, or an alarm or call starts, and it never listens " +
            "in the background. The chat can't play sound unless you tap something, " +
            "goes quiet when you tuck it away or the screen turns off, and stops any sound the moment an alarm, " +
            "ringtone or call starts.",
            14f, ink))

        // ----- Updates -----
        val upd = section(root, "Updates")
        updateText = text("Version ${Updater.currentName(this)}", 14f, muted)
        upd.addView(updateText)
        updateButton = button("Check for updates", primary = false) { onUpdateButton() }
        upd.addView(updateButton)

        // ----- Help -----
        val help = section(root, "How it works")
        help.addView(text(
            "• Tap: pop open the floating chat (or open the app, or look at your screen, if you set it that way).\n" +
            "• Hold: menu with Floating chat, Look at my screen, Open the app, Pet, Customize and Hide.\n" +
            "• Drag: move it. It snaps to the nearest side. Drag it onto the X to hide it.\n" +
            "• Look at my screen: Android asks to allow a capture, then the screenshot goes into the floating chat " +
            "(or the app), ready for your question.",
            14f, ink))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#F5F2EC"))
            addView(root)
        })
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
        toggleButton.text = if (running) "Turn bubble off" else "Turn bubble on"
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

    private fun label(s: String) = text(s, 13f, muted, bold = true).apply { setPadding(0, dp(12), 0, dp(6)) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.WHITE else ink)
        background = rounded(if (primary) accent else Color.parseColor("#ECE7DF"), 14)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
    }

    private fun switch(s: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = s; textSize = 15f; setTextColor(ink); isChecked = checked
        setPadding(0, dp(10), 0, dp(4))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun chips(options: List<Option>, current: () -> String, onPick: (String) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = options.map { opt ->
            TextView(this).apply {
                text = opt.label
                textSize = 14f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                tag = opt.key
                setOnClickListener { onPick(opt.key); refreshers.forEach { r -> r() } }
                row.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            }
        }
        val paint = {
            val cur = current()
            views.forEach { v ->
                val on = v.tag == cur
                v.background = rounded(if (on) accent else Color.parseColor("#F0ECE5"), 20)
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
                addView(dot, LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(text(th.label, 11f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
                tag = th to dot
                setOnClickListener { prefs.theme = th.key; preview.applyPrefs(prefs); refreshers.forEach { it() } }
                row.addView(this, LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT))
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
                    if (prefs.theme == th.key) setStroke(dp(3), ink) else setStroke(dp(1), Color.parseColor("#22000000"))
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
