package com.example.foldword

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

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

/** True when the phone has a working internet connection. */
fun Context.isOnline(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Opens Microsoft Word for the web in Chrome. Without internet it explains that Word Online needs a
 * connection and offers to try again. Nothing local is ever uploaded by this.
 */
fun Activity.openWordOnline(url: String = Config.WORD_URL) {
    if (isOnline()) {
        openInChromeApp(Uri.parse(url))
        return
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Internet needed")
        .setMessage("Microsoft Word Online needs an internet connection. Your offline documents keep working without one.")
        .setPositiveButton("Try again") { _, _ -> openWordOnline(url) }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Adds a one-tap "Word" icon to the home screen that opens Word Online in Chrome. */
fun Activity.pinWordShortcut() {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
        Toast.makeText(this, R.string.add_home_unsupported, Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(this, QuickOpenActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(QuickOpenActivity.EXTRA_TARGET, QuickOpenActivity.TARGET_WORD)
    val shortcut = ShortcutInfoCompat.Builder(this, "pinned_word")
        .setShortLabel(getString(R.string.shortcut_word_short))
        .setLongLabel(getString(R.string.shortcut_word_long))
        .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
    Toast.makeText(this, R.string.add_home_done, Toast.LENGTH_SHORT).show()
}
