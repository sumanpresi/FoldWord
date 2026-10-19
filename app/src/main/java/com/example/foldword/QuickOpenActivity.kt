package com.example.foldword

import android.app.Activity
import android.net.Uri
import android.os.Bundle

/**
 * Invisible trampoline for home-screen shortcuts: one tap opens Word (or OneDrive) straight in the
 * Chrome app, then this screen closes. It only ever opens Microsoft's fixed addresses from Config,
 * never a URL passed in from outside.
 */
class QuickOpenActivity : Activity() {

    companion object {
        const val EXTRA_TARGET = "target"
        const val TARGET_WORD = "word"
        const val TARGET_ONEDRIVE = "onedrive"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = when (intent?.getStringExtra(EXTRA_TARGET)) {
            TARGET_ONEDRIVE -> Config.ONEDRIVE_URL
            else -> Config.WORD_URL
        }
        openInChromeApp(Uri.parse(url))
        finish()
    }
}
