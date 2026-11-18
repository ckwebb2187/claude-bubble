package com.claudebubble.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object ClaudeLauncher {

    /** Packages to try, in order, for the app the user picked (Duet first by default). */
    fun packages(context: Context): List<String> =
        if (Prefs(context).appTarget == "claude") listOf(Config.CLAUDE_PACKAGE, Config.DUET_PACKAGE)
        else listOf(Config.DUET_PACKAGE, Config.CLAUDE_PACKAGE)

    /** Name shown in menus for the app the user picked. */
    fun label(context: Context): String =
        if (Prefs(context).appTarget == "claude") "Claude app" else "Claude Duet app"

    /** Opens Claude Duet (or the regular Claude app, if that's the pick). Falls back to the other app, then the web. */
    fun open(context: Context) {
        for (pkg in packages(context)) {
            val appIntent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                context.startActivity(appIntent)
                return
            } catch (_: Exception) {
                // Try the next one.
            }
        }
        openWeb(context, Config.WEB_URL)
    }

    fun openWeb(context: Context, url: String) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(web)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Couldn't open Claude or a browser", Toast.LENGTH_SHORT).show()
        }
    }
}
