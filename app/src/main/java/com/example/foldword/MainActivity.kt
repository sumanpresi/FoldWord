package com.example.foldword

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Launch screen: one large "Open Word" button, OneDrive files, and a shortcut to the last page. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE)

        // Primary path: Microsoft's own editor in Chrome, with its own sign-in. Editing works here.
        findViewById<MaterialButton>(R.id.btnOpenWord).setOnClickListener {
            openInChromeApp(Uri.parse(Config.WORD_URL))
        }

        // Existing documents live in OneDrive; opening one there launches the Word editor in Chrome.
        findViewById<MaterialButton>(R.id.btnOpenOneDrive).setOnClickListener {
            openInChromeApp(Uri.parse(Config.ONEDRIVE_URL))
        }

        // One-tap home-screen icon that opens Word straight in Chrome.
        findViewById<MaterialButton>(R.id.btnAddHome).setOnClickListener { pinWordShortcut() }

        // Beta: Word inside this app. Sign-in works, but Microsoft blocks editing in embedded browsers.
        findViewById<MaterialButton>(R.id.btnOpenBeta).setOnClickListener {
            startActivity(
                Intent(this, WordWebActivity::class.java)
                    .putExtra(WordWebActivity.EXTRA_URL, Config.WORD_URL)
            )
        }

        findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener {
            openInChromeApp(Uri.parse(recentUrl() ?: Config.WORD_URL))
        }

        // One-time tip: how to get Word's full ribbon (Chrome "Desktop site" for Word).
        findViewById<MaterialButton>(R.id.btnRibbonOpen).setOnClickListener {
            openInChromeApp(Uri.parse(Config.WORD_URL))
        }
        findViewById<MaterialButton>(R.id.btnRibbonDone).setOnClickListener {
            prefs.edit().putBoolean(Config.KEY_RIBBON_TIP_DONE, true).apply()
            refreshRibbonTip()
        }

        findViewById<MaterialButton>(R.id.btnForget).setOnClickListener {
            prefs.edit().remove(Config.KEY_RECENT).apply()
            refreshRecent()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
        refreshRibbonTip()
    }

    private fun pinWordShortcut() {
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

    private fun refreshRibbonTip() {
        val done = prefs.getBoolean(Config.KEY_RIBBON_TIP_DONE, false)
        findViewById<MaterialCardView>(R.id.cardRibbon).visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun recentUrl(): String? = prefs.getString(Config.KEY_RECENT, null)

    private fun refreshRecent() {
        val card = findViewById<MaterialCardView>(R.id.cardRecent)
        val url = recentUrl()
        card.visibility = if (url == null) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.txtRecent).text = url?.let { Uri.parse(it).path ?: "" } ?: ""
    }
}
