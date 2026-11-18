package com.claudebubble.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * Invisible screen that opens Android's file picker for the floating chat
 * (a service can't open the picker itself), then hands the files back to the page.
 */
class FilePickActivity : Activity() {

    companion object {
        private var pending: ValueCallback<Array<Uri>>? = null
        private var pickIntent: Intent? = null

        fun start(activityContext: android.content.Context, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
            pending?.onReceiveValue(null)
            pending = callback
            pickIntent = runCatching { params.createIntent() }.getOrNull()
                ?: Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                pickIntent!!.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            return try {
                activityContext.startActivity(Intent(activityContext, FilePickActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                true
            } catch (_: Exception) {
                pending = null; false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val i = pickIntent
        if (i == null) { deliver(null); finish(); return }
        try {
            startActivityForResult(Intent.createChooser(i, "Choose a file"), 9)
        } catch (_: Exception) {
            deliver(null); finish()
        }
    }

    @Deprecated("Activity result API kept simple on purpose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        var uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        val clip = data?.clipData
        if (resultCode == RESULT_OK && clip != null && clip.itemCount > 1) {
            uris = Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        deliver(uris)
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        // Closed without picking: tell the page so its file button works again.
        if (isFinishing && pending != null && !changingConfigurations()) deliver(null)
        super.onDestroy()
    }

    private fun changingConfigurations() = isChangingConfigurations

    private fun deliver(uris: Array<Uri>?) {
        pending?.onReceiveValue(uris)
        pending = null
        pickIntent = null
    }
}
