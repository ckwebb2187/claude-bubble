package com.claudebubble.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Brings the bubble back after a reboot (if enabled) or after the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        val shouldStart = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> prefs.startOnBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> prefs.bubbleOn
            else -> false
        }
        if (!shouldStart || !Settings.canDrawOverlays(context)) return
        try {
            BubbleService.start(context)
        } catch (_: Exception) {
            // The system refused a background start; the user can turn it on from the app.
        }
    }
}
