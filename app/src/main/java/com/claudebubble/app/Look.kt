package com.claudebubble.app

import android.graphics.Color

/** Color themes. Most are carried over from Nova Apex (primary + accent). */
data class Theme(val key: String, val label: String, val primary: Int, val accent: Int)

object Themes {
    private fun t(key: String, label: String, p: String, a: String) =
        Theme(key, label, Color.parseColor(p), Color.parseColor(a))

    /** "rainbow" is special: the companion cycles through hues. */
    const val RAINBOW = "rainbow"

    val all = listOf(
        t("clay", "Clay", "#E59372", "#B9553A"),
        t(RAINBOW, "Rainbow", "#F472B6", "#38BDF8"),
        t("cyan", "Cyan", "#38BDF8", "#6366F1"),
        t("violet", "Violet", "#A78BFA", "#EC4899"),
        t("ember", "Ember", "#FB923C", "#EF4444"),
        t("emerald", "Emerald", "#34D399", "#0EA5E9"),
        t("rose", "Rose", "#F472B6", "#F59E0B"),
        t("ice", "Ice", "#E0F2FE", "#38BDF8"),
        t("matrix", "Matrix", "#22C55E", "#A3E635"),
        t("gold", "Gold", "#FACC15", "#F97316"),
        t("crimson", "Crimson", "#F43F5E", "#7C3AED"),
        t("aqua", "Aqua", "#2DD4BF", "#3B82F6"),
        t("sunset", "Sunset", "#FB7185", "#FBBF24"),
        t("galaxy", "Galaxy", "#C084FC", "#22D3EE"),
        t("toxic", "Toxic", "#BEF264", "#10B981"),
        t("synth", "Synth", "#F0ABFC", "#22D3EE"),
        t("chrome", "Chrome", "#E5E7EB", "#64748B"),
        t("plasma", "Plasma", "#60A5FA", "#F472B6"),
        t("neonpink", "Neon pink", "#FF2D95", "#7C3AED"),
        t("midnight", "Midnight", "#818CF8", "#1E40AF"),
        t("ocean", "Ocean", "#0EA5E9", "#14B8A6"),
        t("sakura", "Sakura", "#F9A8D4", "#FBCFE8"),
        t("cyber", "Cyber", "#FDE047", "#EC4899"),
        t("hud", "HUD", "#00E5FF", "#FFB703"),
        t("hologram", "Hologram", "#67E8F9", "#C084FC"),
        t("xmas", "Xmas", "#EF4444", "#16A34A"),
        t("candycane", "Candy cane", "#FFFFFF", "#DC2626"),
        t("evergreen", "Evergreen", "#22C55E", "#EAB308"),
        t("frost", "Frost", "#E0F2FE", "#93C5FD"),
    )

    fun byKey(key: String?): Theme = all.firstOrNull { it.key == key } ?: all[0]
}

/** A simple labelled option for the chip rows in settings. */
data class Option(val key: String, val label: String)

object Looks {
    val eyes = listOf(
        Option("capsule", "Capsule"), Option("round", "Round"), Option("cat", "Cat"),
        Option("heart", "Heart"), Option("star", "Star"), Option("pixel", "Pixel"),
        Option("visor", "Visor"), Option("moon", "Crescent"), Option("cyclops", "Cyclops"),
    )
    val blink = listOf(
        Option("normal", "Normal blink"), Option("rapid", "Rapid"),
        Option("sleepy", "Slow & sleepy"), Option("never", "Never"),
    )
    val persona = listOf(
        Option("quirky", "Quirky"), Option("calm", "Calm"), Option("off", "Face off"),
    )
    val core = listOf(
        Option("orb", "Glow orb"), Option("ring", "Halo ring"),
        Option("plasma", "Plasma"), Option("jelly", "Jelly"),
    )
    val idle = listOf(
        Option("float", "Float"), Option("bounce", "Bounce"), Option("breathe", "Breathe"),
        Option("sway", "Sway"), Option("orbit", "Drift"), Option("still", "Still"),
    )
    val listen = listOf(
        Option("ripples", "Ripples"), Option("wave", "Sound wave"), Option("glow", "Glow"), Option("orbit", "Orbiting dots"),
    )
    val talk = listOf(
        Option("sparkles", "Sparkles"), Option("equalizer", "Equalizer"), Option("spin", "Spin ring"), Option("shimmer", "Shimmer"),
    )
    val speed = listOf(Option("slow", "Slow"), Option("normal", "Normal"), Option("fast", "Fast"))
    val window = listOf(
        Option("grow", "Grow"), Option("pop", "Pop"), Option("unroll", "Unroll"), Option("fade", "Fade"),
    )
    val moods = listOf(
        Option("happy", "Happy"), Option("love", "Love"), Option("surprised", "Surprised"),
        Option("starry", "Starry"), Option("smug", "Smug"), Option("confused", "Confused"),
        Option("dizzy", "Dizzy"), Option("sleep", "Sleepy"),
    )
}
