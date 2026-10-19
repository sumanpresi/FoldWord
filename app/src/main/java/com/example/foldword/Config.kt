package com.example.foldword

import android.net.Uri

/** Central settings. Review this file before every release. */
object Config {
    /** The official Microsoft Word for the web address. */
    const val WORD_URL = "https://word.cloud.microsoft/"

    /**
     * Hosts the beta WebView may load. Everything else opens in the system browser.
     * Add a host only after testing shows sign-in needs it. Never add a host you do not control or recognise.
     */
    private val ALLOWED_HOSTS = setOf(
        "word.cloud.microsoft",
        "login.microsoftonline.com",
        "login.live.com",
        "account.live.com",
        "aadcdn.msauth.net",
        "aadcdn.msftauth.net",
        "onedrive.live.com"
    )

    const val PREFS = "fold_word"
    const val KEY_RECENT = "recent_url"

    /** True when the host is an allowlisted one or a subdomain of one. */
    fun isAllowed(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return ALLOWED_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Keeps only scheme, host and path of a URL. Query strings and fragments are dropped,
     * because they can contain sign-in tokens or session values.
     */
    fun sanitize(uri: Uri): String {
        val path = uri.encodedPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        return Uri.Builder()
            .scheme(uri.scheme)
            .encodedAuthority(uri.encodedAuthority)
            .encodedPath(path)
            .build()
            .toString()
    }
}
