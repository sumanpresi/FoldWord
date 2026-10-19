package com.example.foldword

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Beta: Word for the web inside a WebView.
 *
 * Security notes:
 * - Only allowlisted HTTPS hosts load here (see Config). Anything else opens in the browser.
 * - Third-party cookies are off. Microsoft's own first-party session cookies are kept.
 * - File access is off. Cleartext HTTP is blocked by the network security config.
 * - The app never reads, injects into, or stores page content, cookies, or tokens.
 */
class WordWebActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "start_url"
    }

    private lateinit var web: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Lets Word's "Upload" buttons open the phone's file picker.
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray())
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_word_web)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        progress = findViewById(R.id.progress)
        status = findViewById(R.id.txtStatus)
        web = findViewById(R.id.webView)

        with(web.settings) {
            javaScriptEnabled = true // Word for the web cannot run without JavaScript.
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)                 // Needed for Microsoft's own sign-in session.
            setAcceptThirdPartyCookies(web, false) // Blocks cross-site tracking cookies.
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && Config.isAllowed(uri.host)) return false
                openInBrowser(uri) // Leave the app for any host not on the allowlist.
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                status.visibility = View.GONE
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                url?.let { saveRecent(Uri.parse(it)) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    status.text = getString(R.string.load_error)
                    status.visibility = View.VISIBLE
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.setProgressCompat(newProgress, true)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    filePicker.launch(arrayOf("*/*"))
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.setDownloadListener { url, _, _, _, _ ->
            // Word's exports often use blob: links, which this WebView cannot save reliably.
            if (Uri.parse(url).scheme == "https") openInBrowser(Uri.parse(url))
            else Toast.makeText(this, getString(R.string.download_blob), Toast.LENGTH_LONG).show()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        val restored = savedInstanceState?.let { web.restoreState(it) }
        if (restored == null) loadStart()
    }

    private fun loadStart() {
        val requested = intent.getStringExtra(EXTRA_URL)?.let { Uri.parse(it) }
        val target = if (requested != null && requested.scheme == "https" && Config.isAllowed(requested.host)) {
            requested.toString()
        } else {
            Config.WORD_URL
        }
        web.loadUrl(target)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_web, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            web.reload()
            true
        }
        R.id.action_browser -> {
            openInBrowser(Uri.parse(web.url ?: Config.WORD_URL))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun saveRecent(uri: Uri) {
        if (uri.scheme != "https" || uri.host != "word.cloud.microsoft") return
        getSharedPreferences(Config.PREFS, MODE_PRIVATE)
            .edit()
            .putString(Config.KEY_RECENT, Config.sanitize(uri))
            .apply()
    }

    override fun onDestroy() {
        // Recommended by Android: detach and destroy the WebView to avoid leaking memory.
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
