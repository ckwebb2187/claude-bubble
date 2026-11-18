package com.claudebubble.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The floating companion: a glowing orb with a face, drawn on a Canvas.
 * Looks and personality follow Nova Apex: themes, eye styles, blink styles,
 * quirky/calm personality, moods, emotes and a jelly squash when touched.
 */
class CompanionView(context: Context) : View(context) {

    // ----- Look -----
    var theme: Theme = Themes.byKey("clay"); set(v) { field = v; invalidate() }
    var eyeStyle = "capsule"
    var blinkStyle = "normal"
    var persona = "quirky"
    var coreStyle = "orb"

    /** Which screen edge the companion rests on (it glances toward the middle). */
    var restingOnRight = true

    /** Pause animation (screen off, hidden). */
    var paused = false
        set(v) { field = v; if (!v) { lastFrame = 0L; invalidate() } }

    /**
     * What's going on in the floating chat:
     * "listening" while you type or talk into it, "talking" while Claude answers, else "idle".
     */
    var activity = "idle"
        set(v) {
            if (field == v) return
            field = v
            idle = 0f
            if (v != "idle" && mood == "sleep") setMood("normal", 0f)
            if (v == "listening") { tLookX = if (restingOnRight) -0.6f else 0.6f; tLookY = -0.25f }
            invalidate()
        }

    /** A small dot: Claude finished an answer while the chat was tucked away. */
    var unread = false
        set(v) { field = v; invalidate() }

    /** Claude just finished answering. */
    fun answered() {
        idle = 0f
        setMood("happy", 1.4f)
        if (quirky) emote("✓")
        hop(0.6f)
    }

    fun applyPrefs(p: Prefs) {
        theme = Themes.byKey(p.theme)
        eyeStyle = p.eyes
        blinkStyle = p.blink
        persona = p.persona
        coreStyle = p.core
        invalidate()
    }

    // ----- Personality state -----
    private var mood = "normal"
    private var moodT = 0f            // seconds left; <= 0 with sleep means "until woken"
    private var q = 0f; private var vq = 0f          // jelly squash
    private var hopY = 0f; private var vhop = 0f     // little hop
    private var lookX = 0f; private var lookY = 0f   // where the eyes point (-1..1)
    private var tLookX = 0f; private var tLookY = 0f
    private var blink = 0f; private var nextBlink = 2f
    private var blush = 0f
    private var scared = false
    private var idle = 0f
    private var nextGlance = 3f
    private var t = 0f
    private var lastFrame = 0L
    private var hueShift = 0f
    private var tilt = 0f              // head tilt in degrees (leans in while listening)

    private data class Emote(val ch: String, var x: Float, var y: Float, var life: Float)
    private val emotes = ArrayList<Emote>()

    private val quirky get() = persona == "quirky"
    private val faceOn get() = persona != "off"

    // ----- Public reactions (called by the bubble service and the preview) -----

    fun touchDown() {
        idle = 0f
        if (mood == "sleep") { setMood("surprised", 0.8f); emote("!"); hop(1f) }
        vq -= 0.22f
    }

    fun tapped() {
        idle = 0f
        setMood("surprised", 0.35f)
        if (quirky) hop(0.8f)
        vq += 0.25f
    }

    fun petted() {
        idle = 0f
        setMood("love", 2.2f)
        blush = 1f
        emote("♥"); emote("♥")
        if (quirky) emote("★")
        hop(1f)
    }

    /** Called while dragging: dx, dy is the direction of travel in pixels. */
    fun dragging(dx: Float, dy: Float) {
        idle = 0f
        val len = max(1f, kotlin.math.hypot(dx, dy))
        tLookX = (dx / len).coerceIn(-1f, 1f)
        tLookY = (dy / len).coerceIn(-1f, 1f)
        if (mood == "sleep") setMood("normal", 0f)
    }

    fun shaken() {
        setMood("dizzy", 2.2f)
        if (quirky) emote("@")
    }

    fun landed() {
        vq += 0.3f
        tLookX = if (restingOnRight) -0.5f else 0.5f
        tLookY = 0f
    }

    fun setScared(on: Boolean) {
        if (on == scared) return
        scared = on
        if (on) { setMood("surprised", 30f); if (quirky) emote("!") } else if (mood == "surprised") setMood("normal", 0f)
    }

    fun lookingAtScreen() {
        idle = 0f
        setMood("starry", 1.6f)
        emote("👁")
    }

    /** Try a mood from the settings screen. */
    fun tryMood(m: String) {
        idle = 0f
        when (m) {
            "love" -> petted()
            "dizzy" -> shaken()
            "sleep" -> { setMood("sleep", 0f); emote("z") }
            "confused" -> { setMood("confused", 2f); emote("?") }
            "happy" -> { setMood("happy", 2f); emote("♪"); hop(0.8f) }
            else -> setMood(m, 2f)
        }
        invalidate()
    }

    private fun setMood(m: String, secs: Float) { mood = m; moodT = secs }
    private fun hop(strength: Float) { if (hopY <= 0.01f) { vhop = 2.4f * strength; vq -= 0.12f } }
    private fun emote(ch: String) {
        if (emotes.size < 6) emotes.add(Emote(ch, (Random.nextFloat() - 0.5f) * 0.6f, 0f, 1f))
    }

    // ----- Animation loop -----

    private fun step(dt: Float) {
        t += dt
        hueShift = (hueShift + dt * 40f) % 360f

        // springs
        vq += (-q * 60f - vq * 7f) * dt; q += vq * dt * 1.2f
        q = q.coerceIn(-0.35f, 0.35f)
        if (hopY > 0f || vhop > 0f) {
            vhop -= 9f * dt; hopY += vhop * dt
            if (hopY <= 0f) { hopY = 0f; vhop = 0f; vq += 0.18f }
        }

        // eyes ease toward their target
        lookX += (tLookX - lookX) * min(1f, dt * 8f)
        lookY += (tLookY - lookY) * min(1f, dt * 8f)

        // blinking
        nextBlink -= dt
        if (nextBlink <= 0f) {
            blink = if (blinkStyle == "never") 0f else 1f
            nextBlink = when (blinkStyle) {
                "rapid" -> 0.6f + Random.nextFloat() * 1.2f
                "sleepy" -> 1.8f + Random.nextFloat() * 2.5f
                "never" -> 5f
                else -> 2f + Random.nextFloat() * 4f
            }
        }
        blink = max(0f, blink - dt * (if (blinkStyle == "sleepy") 2.5f else 7f))
        blush = max(0f, blush - dt * 0.4f)

        if (moodT > 0f) { moodT -= dt; if (moodT <= 0f && mood != "sleep") mood = "normal" }

        emotes.removeAll { it.y -= dt * 0.9f; it.life -= dt * 0.8f; it.life <= 0f }

        // lean in while listening
        val tiltTarget = if (activity == "listening") (if (restingOnRight) -8f else 8f) else 0f
        tilt += (tiltTarget - tilt) * min(1f, dt * 5f)

        // idle life
        idle += dt
        if (activity != "idle") idle = 0f
        if (!faceOn) return
        if (activity == "listening") {
            // attentive: eyes stay on the chat, with tiny movements
            nextGlance -= dt
            if (nextGlance <= 0f) {
                tLookX = (if (restingOnRight) -0.6f else 0.6f) + (Random.nextFloat() - 0.5f) * 0.2f
                tLookY = -0.25f + (Random.nextFloat() - 0.5f) * 0.15f
                nextGlance = 0.8f + Random.nextFloat()
            }
            return
        }
        if (activity == "talking" && quirky && Random.nextFloat() < dt * 0.25f) emote(listOf("♪", "✦").random())
        if (quirky && idle > 75f && mood != "sleep") setMood("sleep", 0f)
        if (mood == "sleep") {
            tLookX = 0f; tLookY = 0.3f
            if (Random.nextFloat() < dt * 0.5f) emote("z")
            return
        }
        nextGlance -= dt
        if (nextGlance <= 0f && !scared) {
            val home = if (restingOnRight) -0.45f else 0.45f
            if (Random.nextFloat() < (if (quirky) 0.6f else 0.3f)) {
                tLookX = (Random.nextFloat() * 2f - 1f) * 0.9f
                tLookY = (Random.nextFloat() * 2f - 1f) * 0.6f
            } else { tLookX = home; tLookY = 0f }
            nextGlance = if (quirky) 1.5f + Random.nextFloat() * 3f else 3f + Random.nextFloat() * 5f
            if (quirky && Random.nextFloat() < 0.08f) { hop(0.5f); emote(listOf("♪", "✦", "?").random()) }
        }
    }

    // ----- Drawing -----

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()
    private val rect = RectF()

    private fun colors(): Pair<Int, Int> {
        if (theme.key != Themes.RAINBOW) return theme.primary to theme.accent
        val p = Color.HSVToColor(floatArrayOf(hueShift, 0.55f, 1f))
        val a = Color.HSVToColor(floatArrayOf((hueShift + 140f) % 360f, 0.75f, 0.95f))
        return p to a
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        if (!paused) step(dt)

        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f / 1.32f          // orb radius; the rest is room for glow and emotes
        val cx = w / 2f
        val bob = when {
            mood == "sleep" -> sin(t * 1.3f) * r * 0.04f
            activity == "talking" -> sin(t * 6.5f) * r * 0.035f
            activity == "listening" -> sin(t * 3.2f) * r * 0.025f
            else -> sin(t * 2f) * r * 0.035f
        }
        val cy = h / 2f + bob - hopY * r * 0.12f
        val (p, a) = colors()

        // aura
        val pulseSpeed = when {
            mood == "sleep" -> 1.2f
            activity == "talking" -> 7f
            activity == "listening" -> 4f
            else -> 2.6f
        }
        val pulse = 0.85f + 0.15f * sin(t * pulseSpeed)
        glowPaint.shader = RadialGradient(cx, cy, r * 1.3f,
            intArrayOf(withAlpha(a, (150 * pulse).toInt()), withAlpha(a, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 1.3f, glowPaint)

        // listening: soft ripples coming in toward the orb, like it's taking in what you say
        if (activity == "listening") {
            ringPaint.shader = null
            ringPaint.strokeWidth = r * 0.05f
            for (i in 0 until 2) {
                val ph = ((t * 0.8f + i * 0.5f) % 1f)
                ringPaint.color = withAlpha(Color.WHITE, (110 * ph).toInt())
                canvas.drawCircle(cx, cy, r * (1.32f - ph * 0.26f), ringPaint)
            }
        }

        // body with jelly squash (anchored at the bottom)
        canvas.save()
        if (abs(tilt) > 0.05f) canvas.rotate(tilt, cx, cy + r)
        val sx = 1f + q; val sy = 1f - q
        canvas.scale(sx, sy, cx, cy + r)
        when (coreStyle) {
            "jelly" -> drawJelly(canvas, cx, cy, r, p, a)
            else -> {
                bodyPaint.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
                    intArrayOf(lighten(p, 0.45f), p, a), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, r, bodyPaint)
            }
        }
        if (coreStyle == "plasma") drawPlasma(canvas, cx, cy, r, p, a)
        if (coreStyle == "ring") drawRing(canvas, cx, cy, r, a)

        // glossy highlight
        hiPaint.alpha = 90
        rect.set(cx - r * 0.55f, cy - r * 0.78f, cx + r * 0.05f, cy - r * 0.42f)
        canvas.save(); canvas.rotate(-25f, rect.centerX(), rect.centerY()); canvas.drawOval(rect, hiPaint); canvas.restore()

        if (faceOn) drawFace(canvas, cx, cy, r, p)
        canvas.restore()

        // emotes float above
        emotePaint.textSize = r * 0.62f
        for (e in emotes) {
            emotePaint.color = Color.WHITE
            emotePaint.alpha = (255 * e.life.coerceIn(0f, 1f)).toInt()
            emotePaint.setShadowLayer(r * 0.12f, 0f, 0f, withAlpha(Color.BLACK, (160 * e.life).toInt()))
            canvas.drawText(e.ch, cx + e.x * r + r * 0.55f, cy - r * 0.55f + e.y * r, emotePaint)
        }

        // new answer waiting in the tucked-away chat
        if (unread) {
            val dx = if (restingOnRight) -r * 0.72f else r * 0.72f
            eyePaint.color = Color.WHITE
            canvas.drawCircle(cx + dx, cy - r * 0.72f, r * 0.24f, eyePaint)
            eyePaint.color = Color.parseColor("#F43F5E")
            canvas.drawCircle(cx + dx, cy - r * 0.72f, r * 0.18f, eyePaint)
        }

        if (!paused) {
            // Smooth while something is happening, gentler when idle.
            val busy = abs(q) > 0.01f || abs(vq) > 0.05f || hopY > 0f || emotes.isNotEmpty() || blink > 0f ||
                abs(lookX - tLookX) > 0.01f || abs(lookY - tLookY) > 0.01f || theme.key == Themes.RAINBOW ||
                activity != "idle" || abs(tilt) > 0.05f
            if (busy) postInvalidateOnAnimation() else postInvalidateDelayed(66)
        }
    }

    private fun drawRing(c: Canvas, cx: Float, cy: Float, r: Float, a: Int) {
        ringPaint.strokeWidth = r * 0.09f
        ringPaint.shader = SweepGradient(cx, cy, intArrayOf(withAlpha(Color.WHITE, 0), withAlpha(Color.WHITE, 220), withAlpha(a, 0)), null)
        c.save(); c.rotate((t * 140f) % 360f, cx, cy)
        rect.set(cx - r * 1.08f, cy - r * 1.08f, cx + r * 1.08f, cy + r * 1.08f)
        c.drawArc(rect, 0f, 300f, false, ringPaint)
        c.restore()
        ringPaint.shader = null
    }

    private fun drawPlasma(c: Canvas, cx: Float, cy: Float, r: Float, p: Int, a: Int) {
        for (i in 0 until 4) {
            val ang = t * (0.7f + i * 0.23f) + i * 1.7f
            val bx = cx + cos(ang) * r * 0.45f
            val by = cy + sin(ang * 1.3f) * r * 0.45f
            bodyPaint.shader = RadialGradient(bx, by, r * 0.55f,
                intArrayOf(withAlpha(if (i % 2 == 0) lighten(p, 0.5f) else a, 120), withAlpha(a, 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(bx, by, r * 0.55f, bodyPaint)
        }
    }

    private fun drawJelly(c: Canvas, cx: Float, cy: Float, r: Float, p: Int, a: Int) {
        path.reset()
        val n = 48
        for (i in 0..n) {
            val ang = (i.toFloat() / n) * 2f * PI.toFloat()
            val wob = 1f + 0.045f * sin(ang * 3f + t * 3f) + 0.03f * sin(ang * 5f - t * 2.2f)
            val x = cx + cos(ang) * r * wob; val y = cy + sin(ang) * r * wob
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        bodyPaint.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.5f,
            intArrayOf(lighten(p, 0.5f), withAlpha(p, 235), withAlpha(a, 235)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, bodyPaint)
    }

    private fun drawFace(c: Canvas, cx: Float, cy: Float, r: Float, p: Int) {
        val ink = if (luminance(p) > 0.45f) Color.parseColor("#1B1A18") else Color.WHITE
        eyePaint.color = ink
        strokePaint.color = ink
        strokePaint.strokeWidth = r * 0.085f

        val ex = cx + lookX * r * 0.16f
        val ey = cy - r * 0.08f + lookY * r * 0.12f
        val gap = r * 0.36f
        val ew = r * 0.17f
        val eh = r * 0.34f * (1f - blink * 0.9f) * (if (activity == "listening") 1.15f else 1f)

        when {
            mood == "happy" -> {
                for (s in listOf(-1, 1)) arcEye(c, ex + s * gap, ey + r * 0.04f, ew * 1.2f, up = true)
            }
            mood == "sleep" -> for (s in listOf(-1, 1)) arcEye(c, ex + s * gap, ey + r * 0.06f, ew * 1.2f, up = false)
            mood == "dizzy" -> for (s in listOf(-1, 1)) spiral(c, ex + s * gap, ey, ew * 1.3f, if (s < 0) t * 8f else -t * 8f)
            mood == "starry" -> for (s in listOf(-1, 1)) star(c, ex + s * gap, ey, ew * 1.5f, Color.parseColor("#FDE047"))
            mood == "love" -> for (s in listOf(-1, 1)) heart(c, ex + s * gap, ey, ew * 1.5f, Color.parseColor("#F43F5E"))
            mood == "surprised" -> for (s in listOf(-1, 1)) {
                eyePaint.color = ink; c.drawCircle(ex + s * gap, ey, ew * 1.25f, eyePaint)
                hiPaint.alpha = 230; c.drawCircle(ex + s * gap - ew * 0.35f, ey - ew * 0.4f, ew * 0.38f, hiPaint)
            }
            else -> styledEyes(c, ex, ey, gap, ew, eh, ink)
        }
        // smug / confused tweaks: lids
        if (mood == "smug") {
            eyePaint.color = lighten(p, 0.1f)
            for (s in listOf(-1, 1)) c.drawRect(ex + s * gap - ew * 1.6f, ey - eh, ex + s * gap + ew * 1.6f, ey - eh * 0.05f, eyePaint)
        }

        // blush
        if (blush > 0.01f || mood == "happy" || mood == "love") {
            val ba = (130 * max(blush, if (mood == "happy" || mood == "love") 0.7f else 0f)).toInt()
            blushPaint.color = withAlpha(Color.parseColor("#FB7185"), ba)
            for (s in listOf(-1, 1)) { rect.set(cx + s * r * 0.55f - r * 0.14f, cy + r * 0.14f, cx + s * r * 0.55f + r * 0.14f, cy + r * 0.26f); c.drawOval(rect, blushPaint) }
        }

        // mouth
        val mx = cx + lookX * r * 0.1f
        val my = cy + r * 0.32f + lookY * r * 0.06f
        strokePaint.strokeWidth = r * 0.07f
        path.reset()
        if (activity == "talking" && mood != "sleep" && mood != "dizzy") {
            // mouth moves while Claude answers
            val open = (0.2f + 0.8f * abs(sin(t * 9f)) * (0.65f + 0.35f * sin(t * 2.3f))).coerceIn(0.1f, 1f)
            eyePaint.color = ink
            rect.set(mx - r * 0.13f, my - r * (0.02f + 0.07f * open), mx + r * 0.13f, my + r * (0.02f + 0.07f * open))
            c.drawOval(rect, eyePaint)
            return
        }
        if (activity == "listening" && mood == "normal") {
            eyePaint.color = ink
            rect.set(mx - r * 0.07f, my - r * 0.035f, mx + r * 0.07f, my + r * 0.045f)
            c.drawOval(rect, eyePaint)
            return
        }
        when (mood) {
            "surprised" -> { eyePaint.color = ink; c.drawCircle(mx, my + r * 0.02f, r * 0.08f, eyePaint) }
            "happy", "love", "starry" -> { rect.set(mx - r * 0.18f, my - r * 0.12f, mx + r * 0.18f, my + r * 0.12f); c.drawArc(rect, 15f, 150f, false, strokePaint) }
            "sleep" -> { eyePaint.color = ink; c.drawCircle(mx, my, r * 0.045f, eyePaint) }
            "dizzy", "confused" -> {
                path.moveTo(mx - r * 0.16f, my)
                path.quadTo(mx - r * 0.08f, my - r * 0.07f, mx, my)
                path.quadTo(mx + r * 0.08f, my + r * 0.07f, mx + r * 0.16f, my)
                c.drawPath(path, strokePaint)
            }
            "smug" -> { path.moveTo(mx - r * 0.12f, my + r * 0.02f); path.quadTo(mx + r * 0.05f, my + r * 0.06f, mx + r * 0.16f, my - r * 0.06f); c.drawPath(path, strokePaint) }
            else -> { rect.set(mx - r * 0.11f, my - r * 0.07f, mx + r * 0.11f, my + r * 0.07f); c.drawArc(rect, 25f, 130f, false, strokePaint) }
        }
    }

    private fun styledEyes(c: Canvas, ex: Float, ey: Float, gap: Float, ew: Float, eh: Float, ink: Int) {
        eyePaint.color = ink
        val confusedScale = if (mood == "confused") 1.25f else 1f
        when (eyeStyle) {
            "visor" -> {
                rect.set(ex - gap - ew * 1.4f, ey - eh * 0.35f, ex + gap + ew * 1.4f, ey + eh * 0.35f)
                c.drawRoundRect(rect, eh * 0.35f, eh * 0.35f, eyePaint)
                hiPaint.alpha = 200
                val sweep = ((t * 0.6f) % 1f)
                c.drawCircle(rect.left + rect.width() * sweep, ey, eh * 0.18f, hiPaint)
                return
            }
            "cyclops" -> {
                c.drawCircle(ex, ey, ew * 1.6f * (1f - blink * 0.9f).coerceAtLeast(0.12f), eyePaint)
                hiPaint.alpha = 230; c.drawCircle(ex - ew * 0.5f, ey - ew * 0.5f, ew * 0.45f, hiPaint)
                return
            }
        }
        for (s in listOf(-1, 1)) {
            val x = ex + s * gap
            val sc = if (s > 0) confusedScale else 1f
            val hh = eh * sc; val ww = ew * sc
            when (eyeStyle) {
                "round" -> { rect.set(x - ww * 1.15f, ey - hh * 0.6f, x + ww * 1.15f, ey + hh * 0.6f); c.drawOval(rect, eyePaint) }
                "cat" -> {
                    path.reset(); path.moveTo(x - ww * 1.3f, ey); path.quadTo(x, ey - hh * 0.9f, x + ww * 1.3f, ey)
                    path.quadTo(x, ey + hh * 0.9f, x - ww * 1.3f, ey); c.drawPath(path, eyePaint)
                    eyePaint.color = Color.parseColor("#FDE047"); rect.set(x - ww * 0.18f, ey - hh * 0.4f, x + ww * 0.18f, ey + hh * 0.4f)
                    c.drawOval(rect, eyePaint); eyePaint.color = ink
                }
                "heart" -> heart(c, x, ey, ww * 1.45f * (1f - blink * 0.8f), ink)
                "star" -> star(c, x, ey, ww * 1.5f * (1f - blink * 0.8f), ink)
                "pixel" -> { val b = ww * 0.9f; rect.set(x - b, ey - hh * 0.5f, x + b, ey + hh * 0.5f); c.drawRect(rect, eyePaint) }
                "moon" -> {
                    rect.set(x - ww * 1.25f, ey - hh * 0.55f, x + ww * 1.25f, ey + hh * 0.55f)
                    c.drawArc(rect, 0f, 180f, true, eyePaint)
                }
                else -> { // capsule
                    rect.set(x - ww, ey - hh / 2f, x + ww, ey + hh / 2f)
                    c.drawRoundRect(rect, ww, ww, eyePaint)
                }
            }
            if (eyeStyle in setOf("capsule", "round", "pixel") && blink < 0.5f) {
                hiPaint.alpha = 220
                c.drawCircle(x - ww * 0.35f + lookX * ww * 0.2f, ey - hh * 0.22f, ww * 0.36f, hiPaint)
            }
        }
    }

    private fun arcEye(c: Canvas, x: Float, y: Float, w: Float, up: Boolean) {
        rect.set(x - w, y - w * 0.8f, x + w, y + w * 0.8f)
        if (up) c.drawArc(rect, 200f, 140f, false, strokePaint) else c.drawArc(rect, 20f, 140f, false, strokePaint)
    }

    private fun spiral(c: Canvas, x: Float, y: Float, rad: Float, rot: Float) {
        path.reset()
        val turns = 2.2f; val steps = 40
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            val ang = f * turns * 2f * PI.toFloat() + rot
            val rr = rad * f
            val px = x + cos(ang) * rr; val py = y + sin(ang) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val old = strokePaint.strokeWidth; strokePaint.strokeWidth = old * 0.7f
        c.drawPath(path, strokePaint); strokePaint.strokeWidth = old
    }

    private fun star(c: Canvas, x: Float, y: Float, rad: Float, color: Int) {
        path.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) rad else rad * 0.45f
            val ang = (i * 36f - 90f) * PI.toFloat() / 180f
            val px = x + cos(ang) * rr; val py = y + sin(ang) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        eyePaint.color = color; c.drawPath(path, eyePaint)
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(x, y + s * 0.8f)
        path.cubicTo(x - s * 1.4f, y - s * 0.1f, x - s * 0.6f, y - s * 1.1f, x, y - s * 0.35f)
        path.cubicTo(x + s * 0.6f, y - s * 1.1f, x + s * 1.4f, y - s * 0.1f, x, y + s * 0.8f)
        path.close()
        eyePaint.color = color; c.drawPath(path, eyePaint)
    }

    // ----- color helpers -----
    private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    private fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())
    private fun luminance(c: Int): Float {
        fun ch(v: Int): Float { val s = v / 255f; return if (s <= 0.03928f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f) }
        return 0.2126f * ch(Color.red(c)) + 0.7152f * ch(Color.green(c)) + 0.0722f * ch(Color.blue(c))
    }
}
