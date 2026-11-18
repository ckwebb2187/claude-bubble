package com.claudebubble.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/** Takes a single screenshot with an approved MediaProjection, then lets it go. */
class ScreenGrabber(private val ctx: Context) {

    fun grab(projection: MediaProjection, onDone: (File?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val (w, h, dpi) = screenSize()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null
        var finished = false

        fun finish(file: File?) {
            if (finished) return
            finished = true
            runCatching { display?.release() }
            runCatching { reader.close() }
            runCatching { projection.stop() }
            onDone(file)
        }

        // Android 14+ insists on a callback before capturing.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { finish(null) } }
        }, main)

        var first: android.media.Image? = null
        fun use(img: android.media.Image) {
            val file = try {
                val plane = img.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * w
                val wide = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                wide.copyPixelsFromBuffer(plane.buffer)
                save(Bitmap.createBitmap(wide, 0, 0, w, h))
            } catch (_: Exception) { null } finally { img.close() }
            finish(file)
        }

        reader.setOnImageAvailableListener({ r ->
            if (first != null || finished) return@setOnImageAvailableListener
            first = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            // The very first frame can be stale; wait a moment and prefer a newer one if it comes.
            main.postDelayed({
                if (finished) return@postDelayed
                val newer = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (newer != null) { first?.close(); use(newer) } else use(first!!)
            }, 180)
        }, main)

        display = projection.createVirtualDisplay(
            "claude-bubble-look", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main
        )
        // Give up if nothing arrives.
        main.postDelayed({ if (!finished) { first?.close(); finish(null) } }, 4000)
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)!!
        val dpi = ctx.resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dpi)
        }
    }

    private fun save(bmp: Bitmap): File {
        val dir = ShotProvider.dir(ctx)
        dir.listFiles()?.forEach { it.delete() } // keep only the newest screenshot
        val f = File(dir, "screen-${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f
    }

    companion object {
        /** Opens the screenshot in Claude Duet or Claude (or the share sheet if neither takes it). */
        fun shareToClaude(ctx: Context, file: File) {
            val uri = ShotProvider.uriFor(file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, Config.LOOK_PROMPT)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            send.clipData = ClipData.newRawUri("screenshot", uri)

            for (pkg in ClaudeLauncher.packages(ctx)) {
                val toApp = Intent(send).setPackage(pkg)
                if (toApp.resolveActivity(ctx.packageManager) != null) {
                    try { ctx.startActivity(toApp); return } catch (_: Exception) { }
                }
            }
            val chooser = Intent.createChooser(send, "Send screenshot to…")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(chooser)
        }
    }
}
