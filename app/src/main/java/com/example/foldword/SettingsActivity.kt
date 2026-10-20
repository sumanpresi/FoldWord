package com.example.foldword

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.foldword.data.DocumentAccess
import com.example.foldword.data.DraftStore
import com.example.foldword.data.OfflineFolder
import com.example.foldword.data.RecentStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors

/** Settings, privacy explanation, backups and the online-only extras. */
class SettingsActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var drafts: DraftStore
    private var backupToExport: File? = null

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            DocumentAccess.takePermission(this, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            OfflineFolder(this).treeUri = tree
            refresh()
        }
    }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { target ->
        val file = backupToExport ?: return@registerForActivityResult
        backupToExport = null
        if (target == null) return@registerForActivityResult
        io.execute {
            val result = runCatching { DocumentAccess.writeBytes(this, target, file.readBytes()) }
            runOnUiThread {
                Toast.makeText(this, result.fold({ getString(R.string.backup_exported) }, { DocumentAccess.friendly(it) }), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        drafts = DraftStore(this)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.setFolder).setOnClickListener { pickFolder.launch(OfflineFolder(this).treeUri) }
        findViewById<MaterialButton>(R.id.setBeta).setOnClickListener {
            startActivity(Intent(this, WordWebActivity::class.java).putExtra(WordWebActivity.EXTRA_URL, Config.WORD_URL))
        }
        findViewById<MaterialButton>(R.id.setPin).setOnClickListener { pinWordShortcut() }
        findViewById<MaterialButton>(R.id.setRibbonTip).setOnClickListener {
            getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit().putBoolean(Config.KEY_RIBBON_TIP_DONE, false).apply()
            Toast.makeText(this, R.string.ribbon_tip_back, Toast.LENGTH_SHORT).show()
        }
        findViewById<MaterialButton>(R.id.setClearRecent).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_recent_title)
                .setMessage(R.string.clear_recent_body)
                .setPositiveButton(R.string.clear) { _, _ -> RecentStore(this).clear(); refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<MaterialButton>(R.id.setBackups).setOnClickListener { chooseBackup() }
        findViewById<MaterialButton>(R.id.setClearDrafts).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_drafts_title)
                .setMessage(R.string.clear_drafts_body)
                .setPositiveButton(R.string.m_delete) { _, _ -> drafts.all().forEach { drafts.delete(it.key) }; refresh() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        findViewById<TextView>(R.id.setVersion).text = getString(R.string.about_version,
            packageManager.getPackageInfo(packageName, 0).versionName ?: "")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val tree: Uri? = OfflineFolder(this).treeUri
        findViewById<TextView>(R.id.setFolderText).text =
            if (tree == null) getString(R.string.folder_not_set) else DocumentAccess.locationLabel(tree)
        findViewById<TextView>(R.id.setStorageText).text =
            getString(R.string.storage_summary, drafts.all().size, drafts.listBackups().size, RecentStore(this).all().size)
    }

    private fun chooseBackup() {
        val files = drafts.listBackups()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.no_backups, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = files.map { f ->
            val time = f.name.substringBefore('-').toLongOrNull() ?: 0L
            f.name.substringAfter('-') + " — " + android.text.format.DateFormat.format("d MMM, HH:mm", time)
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.backups_title)
            .setItems(labels) { _, which ->
                backupToExport = files[which]
                exportBackup.launch(files[which].name.substringAfter('-'))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
