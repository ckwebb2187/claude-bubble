package com.claudebubble.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer Claude Bubble and installs it.
 * Android shows its own "Update" prompt; you tap it once. Updates only install
 * if they're signed with the same key as the app already on the phone.
 */
object Updater {

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())

    fun currentCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Looks up the latest release. Calls back on the main thread with a newer release, or null and a reason. */
    fun check(ctx: Context, onResult: (Release?, String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val result: Pair<Release?, String> = try {
                val conn = (URL("https://api.github.com/repos/${Config.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 15000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "ClaudeBubble-updater")
                }
                val code = conn.responseCode
                if (code == 404) null to "No updates published yet."
                else if (code != 200) null to "Couldn't check for updates (GitHub said $code)."
                else {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val assets = json.optJSONArray("assets")
                    var best: Release? = null
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        val m = Regex("""^ClaudeBubble-(.+)-code(\d+)\.apk$""").find(name) ?: continue
                        val url = a.optString("browser_download_url")
                        if (!url.startsWith("https://github.com/${Config.UPDATE_REPO}/releases/download/")) continue
                        val r = Release(m.groupValues[2].toInt(), m.groupValues[1], url, a.optLong("size"))
                        if (best == null || r.versionCode > best.versionCode) best = r
                    }
                    when {
                        best == null -> null to "The latest release has no app file."
                        best.versionCode <= currentCode(app) -> null to "You're up to date (${currentName(app)})."
                        else -> best to "Version ${best.versionName} is ready."
                    }
                }
            } catch (e: Exception) {
                null to "Couldn't check for updates. Are you online?"
            }
            main.post { onResult(result.first, result.second) }
        }.start()
    }

    /** True if Android lets this app install updates; otherwise opens the setting to allow it. */
    fun ensureAllowed(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return false
    }

    /** Downloads the release and hands it to Android's installer. Progress text comes back on the main thread. */
    fun install(ctx: Context, rel: Release, progress: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val dir = File(app.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val apk = File(dir, "update.apk")
                var conn = URL(rel.apkUrl).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "ClaudeBubble-updater")
                conn.connectTimeout = 20000; conn.readTimeout = 30000
                val total = if (rel.size > 0) rel.size else conn.contentLengthLong
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L; var lastPct = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct && pct % 10 == 0) { lastPct = pct; main.post { progress("Downloading… $pct%") } }
                            }
                        }
                    }
                }
                main.post { progress("Opening the installer…") }

                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite("base.apk", 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java), flags)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                main.post { progress("The update didn't download. Try again in a bit.") }
            }
        }.start()
    }
}
