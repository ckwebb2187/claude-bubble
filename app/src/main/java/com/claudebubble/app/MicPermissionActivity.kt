package com.claudebubble.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible screen that asks Android to allow the microphone for the floating chat's mic button
 * (a service can't ask by itself). If you allow it, the chat pops back open.
 */
class MicPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            done(true); return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        done(results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED)
    }

    private fun done(granted: Boolean) {
        if (granted) {
            Toast.makeText(this, "Mic allowed. Tap the mic in the chat again.", Toast.LENGTH_LONG).show()
            runCatching {
                startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_OPEN_PANEL))
            }
        } else {
            Toast.makeText(this, "No mic, so the chat's mic button stays off. Your keyboard's mic still works.", Toast.LENGTH_LONG).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
