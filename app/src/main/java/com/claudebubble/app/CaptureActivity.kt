package com.claudebubble.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/**
 * Invisible screen that only asks Android for permission to capture the screen once,
 * then hands the answer to the bubble service.
 */
class CaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        val ask = if (Build.VERSION.SDK_INT >= 34) {
            // Capture the whole screen so you don't have to pick an app first.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        try {
            startActivityForResult(ask, 7)
        } catch (_: Exception) {
            send(RESULT_CANCELED, null)
            finish()
        }
    }

    @Deprecated("Activity result API kept simple on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        send(resultCode, data)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun send(resultCode: Int, data: Intent?) {
        val i = Intent(this, BubbleService::class.java)
            .setAction(BubbleService.ACTION_CAPTURE)
            .putExtra(BubbleService.EXTRA_RESULT_CODE, resultCode)
        if (data != null) i.putExtra(BubbleService.EXTRA_RESULT_DATA, data)
        startService(i)
    }
}
