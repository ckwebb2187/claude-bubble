package com.claudebubble.app

object Config {
    /** Claude Duet's Android app (its Google Play listing, by Anthropic PBC). */
    const val DUET_PACKAGE = "com.anthropic.aibo"

    /** The regular Claude Android app. */
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    /** Claude Duet on the web. The floating chat window loads this, and it's the last fallback. */
    const val WEB_URL = "https://claude.ai/messages"

    /** Text sent along with a screenshot when it goes to an app instead of the floating chat. */
    const val LOOK_PROMPT =
        "Take a look at my screen. What am I looking at, and is there anything useful you can tell me or help with?"

    /**
     * Where updates come from: the latest GitHub release of this repository.
     * A release needs an APK named like ClaudeBubble-1.1-code2.apk (code = versionCode).
     * Android only installs it over this app if it's signed with the same key.
     */
    const val UPDATE_REPO = "ckwebb2187/claude-bubble"

    /** Default orb size in dp (you can change it in the app). */
    const val BUBBLE_SIZE_DP = 60

    /** How close (dp) your finger must get to the X to dismiss the bubble. */
    const val DISMISS_RADIUS_DP = 72

    /** Seconds without a touch before the bubble fades (when "Fade when idle" is on). */
    const val FADE_AFTER_SECONDS = 6
}
