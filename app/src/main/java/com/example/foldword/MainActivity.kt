package com.example.foldword

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.foldword.data.DocumentAccess
import com.example.foldword.data.DraftStore
import com.example.foldword.data.OfflineFolder
import com.example.foldword.data.RecentStore
import com.example.foldword.doc.Formats
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Fold Word home screen. Offline actions (new, open, browse, offline folder) and the online
 * Microsoft Word button live side by side; on the unfolded screen the document list sits on the right.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recents: RecentStore
    private lateinit var drafts: DraftStore
    private lateinit var folder: OfflineFolder
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            DocumentAccess.takePermission(this, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            openDocument(uri, null)
        }
    }

    private val browseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            DocumentAccess.takePermission(this, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folder.browsed = listOf(tree.toString()) + folder.browsed
            BrowseActivity.open(this, tree, DocumentAccess.locationLabel(tree).substringAfterLast(" › "))
        }
    }

    private val chooseOfflineFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            DocumentAccess.takePermission(this, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folder.treeUri = tree
            BrowseActivity.open(this, tree, getString(R.string.offline_documents))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        recents = RecentStore(this)
        drafts = DraftStore(this)
        folder = OfflineFolder(this)

        click(R.id.btnNew) { chooseFormat(this) { fmt -> EditorActivity.newDocument(this, fmt) } }
        click(R.id.btnOpen) { openFile.launch(Formats.pickerMimeTypes()) }
        click(R.id.btnBrowse) { browsePhone() }
        click(R.id.btnOffline) { openOfflineFolder() }
        click(R.id.btnWordOnline) { openWordOnline() }
        click(R.id.btnOneDrive) { openWordOnline(Config.ONEDRIVE_URL) }
        click(R.id.btnAddHome) { pinWordShortcut() }
        click(R.id.btnSettings) { startActivity(Intent(this, SettingsActivity::class.java)) }
        click(R.id.btnRibbonOpen) { openWordOnline() }
        click(R.id.btnRibbonDone) {
            getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit().putBoolean(Config.KEY_RIBBON_TIP_DONE, true).apply()
            refreshRibbonTip()
        }
        click(R.id.btnClearRecent) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_recent_title)
                .setMessage(R.string.clear_recent_body)
                .setPositiveButton(R.string.clear) { _, _ -> recents.clear(); refreshRecent() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun click(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener { action() }

    override fun onStart() {
        super.onStart()
        // Keep the Online / No internet label current while the home screen is visible.
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { refreshNetwork() } }
            override fun onLost(network: Network) { runOnUiThread { refreshNetwork() } }
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) { runOnUiThread { refreshNetwork() } }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (ignored: Exception) {}
    }

    override fun onStop() {
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshNetwork()
        refreshRecent()
        refreshRibbonTip()
    }

    private fun refreshNetwork() {
        val online = isOnline()
        findViewById<TextView>(R.id.netChip).apply {
            text = getString(if (online) R.string.net_online else R.string.net_offline)
            setBackgroundResource(if (online) R.drawable.chip_online else R.drawable.chip_offline)
            setTextColor(getColor(if (online) R.color.chip_online_ink else R.color.chip_offline_ink))
        }
        findViewById<MaterialButton>(R.id.btnWordOnline).text =
            getString(if (online) R.string.open_word_online else R.string.open_word_online_offline)
    }

    private fun refreshRibbonTip() {
        val done = getSharedPreferences(Config.PREFS, MODE_PRIVATE).getBoolean(Config.KEY_RIBBON_TIP_DONE, false)
        findViewById<MaterialCardView>(R.id.cardRibbon).visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun refreshRecent() {
        val list = findViewById<LinearLayout>(R.id.recentList)
        list.removeAllViews()
        val rows = mutableListOf<Row>()
        for (d in drafts.all()) {
            rows.add(Row(
                title = d.name,
                subtitle = getString(R.string.unsaved_draft, DateUtils.getRelativeTimeSpanString(d.savedAt)),
                badge = "✎",
                onClick = { EditorActivity.openDraft(this, d.key) },
                onLongClick = {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(d.name)
                        .setMessage(R.string.draft_delete_body)
                        .setPositiveButton(R.string.m_delete) { _, _ -> drafts.delete(d.key); refreshRecent() }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            ))
        }
        val draftUris = drafts.all().mapNotNull { it.uri }.toSet()
        for (r in recents.all().filter { it.uri !in draftUris }.take(30)) {
            rows.add(Row(
                title = r.name,
                subtitle = listOf(r.location, DateUtils.getRelativeTimeSpanString(r.time).toString()).filter { it.isNotEmpty() }.joinToString(" · "),
                badge = badgeFor(r.format),
                onClick = { openDocument(Uri.parse(r.uri), r.format) },
                onLongClick = {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(r.name)
                        .setItems(arrayOf(getString(R.string.open), getString(R.string.remove_from_recent))) { _, w ->
                            if (w == 0) openDocument(Uri.parse(r.uri), r.format) else { recents.remove(r.uri); refreshRecent() }
                        }
                        .show()
                }
            ))
        }
        for (row in rows) {
            val v = layoutInflater.inflate(R.layout.row_doc, list, false)
            bindRow(v, row)
            list.addView(v)
        }
        findViewById<View>(R.id.recentEmpty).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnClearRecent).visibility = if (recents.all().isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openDocument(uri: Uri, knownFormat: String?) {
        val fmt = if (knownFormat == "pdf") Formats.PDF else null
        if (fmt == Formats.PDF || (knownFormat == null && contentResolver.getType(uri) == "application/pdf")) {
            PdfActivity.open(this, uri)
        } else {
            EditorActivity.open(this, uri)
        }
    }

    private fun browsePhone() {
        val saved = folder.browsed.mapNotNull { s ->
            val u = Uri.parse(s)
            if (contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }) u else null
        }
        if (saved.isEmpty()) { browseFolder.launch(null); return }
        val labels = saved.map { DocumentAccess.locationLabel(it) } + getString(R.string.choose_another_folder)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.browse_phone)
            .setItems(labels.toTypedArray()) { _, w ->
                if (w < saved.size) BrowseActivity.open(this, saved[w], labels[w].substringAfterLast(" › "))
                else browseFolder.launch(null)
            }
            .show()
    }

    private fun openOfflineFolder() {
        val tree = folder.treeUri
        val stillAllowed = tree != null && contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (tree != null && stillAllowed) {
            BrowseActivity.open(this, tree, getString(R.string.offline_documents))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offline_folder_title)
            .setMessage(R.string.offline_folder_body)
            .setPositiveButton(R.string.choose_folder) { _, _ -> chooseOfflineFolder.launch(null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
