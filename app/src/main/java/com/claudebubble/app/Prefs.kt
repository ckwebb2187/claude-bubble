package com.claudebubble.app

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)

    var startOnBoot: Boolean
        get() = sp.getBoolean("start_on_boot", false)
        set(v) = sp.edit().putBoolean("start_on_boot", v).apply()

    /** True while the user wants the bubble on (used to bring it back after an app update). */
    var bubbleOn: Boolean
        get() = sp.getBoolean("bubble_on", false)
        set(v) = sp.edit().putBoolean("bubble_on", v).apply()

    /** Which edge the bubble rests on. */
    var onRight: Boolean
        get() = sp.getBoolean("on_right", true)
        set(v) = sp.edit().putBoolean("on_right", v).apply()

    /** Vertical position as a fraction of the usable screen height (0 = top, 1 = bottom). */
    var yFraction: Float
        get() = sp.getFloat("y_fraction", 0.35f)
        set(v) = sp.edit().putFloat("y_fraction", v).apply()

    // ----- Look (changes apply to the bubble right away) -----

    var theme: String
        get() = sp.getString(KEY_THEME, "clay")!!
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    var eyes: String
        get() = sp.getString(KEY_EYES, "capsule")!!
        set(v) = sp.edit().putString(KEY_EYES, v).apply()

    var blink: String
        get() = sp.getString(KEY_BLINK, "normal")!!
        set(v) = sp.edit().putString(KEY_BLINK, v).apply()

    var persona: String
        get() = sp.getString(KEY_PERSONA, "quirky")!!
        set(v) = sp.edit().putString(KEY_PERSONA, v).apply()

    var core: String
        get() = sp.getString(KEY_CORE, "orb")!!
        set(v) = sp.edit().putString(KEY_CORE, v).apply()

    /** Diameter of the orb in dp. */
    var sizeDp: Int
        get() = sp.getInt(KEY_SIZE, Config.BUBBLE_SIZE_DP)
        set(v) = sp.edit().putInt(KEY_SIZE, v).apply()

    /** Fade to half-see-through after a while of not being touched. */
    var fadeIdle: Boolean
        get() = sp.getBoolean(KEY_FADE, false)
        set(v) = sp.edit().putBoolean(KEY_FADE, v).apply()

    /** What a plain tap does: "window" (floating chat), "app" (open the app) or "look" (look at my screen). */
    var tapAction: String
        get() = when (val v = sp.getString("tap_action", "window")!!) { "open" -> "window"; else -> v }
        set(v) = sp.edit().putString("tap_action", v).apply()

    /** Which app "Open app" launches: "duet" (Claude Duet) or "claude" (the regular Claude app). */
    var appTarget: String
        get() = sp.getString("app_target", "duet")!!
        set(v) = sp.edit().putString("app_target", v).apply()

    /** Where and how big the floating chat is, in pixels (-1 = not set yet, use the default). */
    var panelX: Int
        get() = sp.getInt("panel_x", -1)
        set(v) = sp.edit().putInt("panel_x", v).apply()
    var panelY: Int
        get() = sp.getInt("panel_y", -1)
        set(v) = sp.edit().putInt("panel_y", v).apply()
    var panelW: Int
        get() = sp.getInt("panel_w", -1)
        set(v) = sp.edit().putInt("panel_w", v).apply()
    var panelH: Int
        get() = sp.getInt("panel_h", -1)
        set(v) = sp.edit().putInt("panel_h", v).apply()

    fun resetPanel() {
        sp.edit().remove("panel_x").remove("panel_y").remove("panel_w").remove("panel_h").apply()
    }

    fun resetLook() {
        sp.edit().remove(KEY_THEME).remove(KEY_EYES).remove(KEY_BLINK).remove(KEY_PERSONA)
            .remove(KEY_CORE).remove(KEY_SIZE).remove(KEY_FADE).apply()
    }

    companion object {
        const val KEY_THEME = "theme"
        const val KEY_EYES = "eyes"
        const val KEY_BLINK = "blink"
        const val KEY_PERSONA = "persona"
        const val KEY_CORE = "core"
        const val KEY_SIZE = "size_dp"
        const val KEY_FADE = "fade_idle"
        val LOOK_KEYS = setOf(KEY_THEME, KEY_EYES, KEY_BLINK, KEY_PERSONA, KEY_CORE, KEY_SIZE, KEY_FADE)
    }
}
