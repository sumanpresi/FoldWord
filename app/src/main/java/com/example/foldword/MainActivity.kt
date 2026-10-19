package com.example.foldword

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Launch screen: one large "Open Word" button, plus a shortcut to the last page visited. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(Config.PREFS, MODE_PRIVATE)

        // Primary path: Microsoft's own page, in the browser, with its own sign-in.
        findViewById<MaterialButton>(R.id.btnOpenWord).setOnClickListener {
            openInBrowser(Uri.parse(Config.WORD_URL))
        }

        // Beta path: Word inside this app. Sign-in may fail here; see docs/TEST_CHECKLIST.md.
        findViewById<MaterialButton>(R.id.btnOpenBeta).setOnClickListener {
            startActivity(
                Intent(this, WordWebActivity::class.java)
                    .putExtra(WordWebActivity.EXTRA_URL, recentUrl() ?: Config.WORD_URL)
            )
        }

        findViewById<MaterialButton>(R.id.btnContinue).setOnClickListener {
            openInBrowser(Uri.parse(recentUrl() ?: Config.WORD_URL))
        }

        findViewById<MaterialButton>(R.id.btnForget).setOnClickListener {
            prefs.edit().remove(Config.KEY_RECENT).apply()
            refreshRecent()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecent()
    }

    private fun recentUrl(): String? = prefs.getString(Config.KEY_RECENT, null)

    private fun refreshRecent() {
        val card = findViewById<MaterialCardView>(R.id.cardRecent)
        val url = recentUrl()
        card.visibility = if (url == null) View.GONE else View.VISIBLE
        // Show only the path, which is what the user recognises (for example, a document name).
        findViewById<TextView>(R.id.txtRecent).text = url?.let { Uri.parse(it).path ?: "" } ?: ""
    }
}
