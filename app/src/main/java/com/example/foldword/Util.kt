package com.example.foldword

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens a link in the user's browser with a Chrome Custom Tab. Custom Tabs share the browser's
 * own sign-in session, so the user is not asked to sign in again. The app never sees their password.
 */
fun Context.openInBrowser(uri: Uri) {
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open this link.", Toast.LENGTH_LONG).show()
        }
    }
}
