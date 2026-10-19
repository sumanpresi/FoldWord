package com.example.foldword

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat

/**
 * Opens a link in the user's browser with a Chrome Custom Tab. Custom Tabs share the browser's
 * own sign-in session, so the user is not asked to sign in again. The app never sees their password.
 */
fun Context.openInBrowser(uri: Uri) {
    try {
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(ContextCompat.getColor(this, R.color.brand_blue_dark))
            .build()
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(colors)
            .setShareState(CustomTabsIntent.SHARE_STATE_ON)
            .build()
            .launchUrl(this, uri)
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e2: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open this link.", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Opens a link in the full Chrome app. Chrome's menu there has "Desktop site", which makes Word for
 * the web show its full ribbon editor. Chrome remembers that choice per site. Falls back to a Custom Tab
 * (or any browser) when Chrome is not installed.
 */
fun Context.openInChromeApp(uri: Uri) {
    val chrome = Intent(Intent.ACTION_VIEW, uri)
        .setPackage("com.android.chrome")
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(chrome)
    } catch (e: ActivityNotFoundException) {
        openInBrowser(uri)
    }
}
