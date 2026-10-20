package com.example.foldword

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.foldword.data.DocumentAccess
import com.example.foldword.data.RecentStore
import com.example.foldword.doc.DocModel
import com.example.foldword.doc.Formats
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * Lists the documents in a folder the user granted with the Android folder picker, including
 * subfolders. Used for "Browse phone" and for the "Offline documents" folder.
 */
class BrowseActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TREE = "tree"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, tree: Uri, title: String) = context.startActivity(
            Intent(context, BrowseActivity::class.java).putExtra(EXTRA_TREE, tree.toString()).putExtra(EXTRA_TITLE, title)
        )
    }

    private data class Entry(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int) {
        val isFolder get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val adapter = RowAdapter()
    private lateinit var tree: Uri
    private lateinit var toolbar: MaterialToolbar
    private val stack = mutableListOf<Pair<String, String>>() // (documentId, name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        tree = Uri.parse(intent.getStringExtra(EXTRA_TREE) ?: run { finish(); return })
        val rootName = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.browse_phone)
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { finish(); return }
        stack.add(rootId to rootName)
        savedInstanceState?.getStringArrayList("stackIds")?.let { ids ->
            val names = savedInstanceState?.getStringArrayList("stackNames") ?: arrayListOf()
            if (ids.size == names.size && ids.isNotEmpty()) { stack.clear(); ids.indices.forEach { stack.add(ids[it] to names[it]) } }
        }
        val list = findViewById<RecyclerView>(R.id.browseList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) { stack.removeAt(stack.size - 1); load() } else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        load() // also refreshes after returning from the editor
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("stackIds", ArrayList(stack.map { it.first }))
        outState.putStringArrayList("stackNames", ArrayList(stack.map { it.second }))
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun currentDocUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, stack.last().first)

    private fun load() {
        toolbar.title = stack.last().second
        toolbar.subtitle = DocumentAccess.locationLabel(currentDocUri())
        val message = findViewById<TextView>(R.id.browseMessage)
        message.visibility = View.GONE
        val parentId = stack.last().first
        io.execute {
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS
                )
                val out = mutableListOf<Entry>()
                contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        out.add(Entry(c.getString(0), name, c.getString(2) ?: "", if (c.isNull(3)) -1 else c.getLong(3),
                            if (c.isNull(4)) 0 else c.getLong(4), if (c.isNull(5)) 0 else c.getInt(5)))
                    }
                }
                val sorted = out.sortedWith(compareBy<Entry>({ !it.isFolder }, { it.name.lowercase() }))
                main.post { show(sorted) }
            } catch (e: SecurityException) {
                main.post { showMessage(getString(R.string.browse_lost_access)) }
            } catch (e: Exception) {
                main.post { showMessage(DocumentAccess.friendly(e)) }
            }
        }
    }

    private fun showMessage(text: String) {
        adapter.rows = emptyList()
        findViewById<TextView>(R.id.browseMessage).apply { this.text = text; visibility = View.VISIBLE }
    }

    private fun show(entries: List<Entry>) {
        if (entries.isEmpty()) { showMessage(getString(R.string.browse_empty)); return }
        adapter.rows = entries.map { e ->
            val fmt = if (e.isFolder) null else Formats.detect(e.name, e.mime)
            val date = if (e.modified > 0) DateFormat.format("d MMM yyyy", e.modified).toString() else ""
            val size = if (e.size >= 0 && !e.isFolder) android.text.format.Formatter.formatShortFileSize(this, e.size) else ""
            val subtitle = when {
                e.isFolder -> getString(R.string.folder)
                fmt == null -> getString(R.string.not_supported_short)
                else -> listOf(fmt.label, size, date).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            Row(
                title = e.name,
                subtitle = subtitle,
                badge = if (e.isFolder) badgeFor("folder") else badgeFor(fmt?.extension ?: e.name.substringAfterLast('.', "?")),
                enabled = e.isFolder || fmt != null,
                onClick = { openEntry(e, fmt) },
                onLongClick = if (e.isFolder) null else ({ entryMenu(e, fmt) })
            )
        }
    }

    private fun openEntry(e: Entry, fmt: Formats?) {
        if (e.isFolder) { stack.add(e.docId to e.name); load(); return }
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
        when {
            fmt == Formats.PDF -> PdfActivity.open(this, uri)
            fmt != null -> EditorActivity.open(this, uri)
            else -> Toast.makeText(this, R.string.not_supported_long, Toast.LENGTH_LONG).show()
        }
    }

    private fun entryMenu(e: Entry, fmt: Formats?) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.docId)
        val canRename = e.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
        val canDelete = e.flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
        val labels = mutableListOf(getString(R.string.open))
        if (canRename) labels.add(getString(R.string.m_rename))
        if (canDelete) labels.add(getString(R.string.m_delete))
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    getString(R.string.open) -> openEntry(e, fmt)
                    getString(R.string.m_rename) -> renameEntry(e, uri)
                    getString(R.string.m_delete) -> deleteEntry(e, uri)
                }
            }
            .show()
    }

    private fun renameEntry(e: Entry, uri: Uri) {
        val input = EditText(this).apply { setText(e.name); setSelection(0, e.name.substringBeforeLast('.').length) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.m_rename)
            .setView(input.also { it.setPadding(48, 24, 48, 24) })
            .setPositiveButton(R.string.m_rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                io.execute {
                    val result = runCatching { DocumentAccess.rename(this, uri, newName) }
                    main.post {
                        result.onSuccess { newUri ->
                            val recents = RecentStore(this)
                            recents.all().firstOrNull { it.uri == uri.toString() }?.let {
                                recents.replace(it.uri, it.copy(uri = newUri.toString(), name = newName))
                            }
                            load()
                        }.onFailure { Toast.makeText(this, DocumentAccess.friendly(it), Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteEntry(e: Entry, uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_title, e.name))
            .setMessage(getString(R.string.delete_body, DocumentAccess.locationLabel(uri)))
            .setPositiveButton(R.string.m_delete) { _, _ ->
                io.execute {
                    val result = runCatching { DocumentAccess.delete(this, uri) }
                    main.post {
                        result.onSuccess { RecentStore(this).remove(uri.toString()); load() }
                            .onFailure { Toast.makeText(this, DocumentAccess.friendly(it), Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.new_here).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(0, 2, 0, R.string.refresh)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> chooseFormat(this) { fmt -> askNameThenCreate(fmt) }
            2 -> load()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** Creates a new document directly in this folder, saves an empty valid file, then opens it. */
    private fun askNameThenCreate(fmt: Formats) {
        val input = EditText(this).apply { setText(getString(R.string.untitled)); selectAll() }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_named, fmt.label))
            .setView(input.also { it.setPadding(48, 24, 48, 24) })
            .setPositiveButton(R.string.create) { _, _ ->
                val fileName = fmt.fileName(input.text.toString())
                val parent = currentDocUri()
                io.execute {
                    try {
                        val created = DocumentsContract.createDocument(contentResolver, parent, fmt.mimeType, fileName)
                            ?: throw java.io.IOException(getString(R.string.create_failed))
                        val empty = DocModel.Document().also { it.blocks.add(DocModel.Paragraph()) }
                        DocumentAccess.writeBytes(this, created, fmt.write(empty, null, null))
                        main.post { EditorActivity.open(this, created) }
                    } catch (e: Exception) {
                        main.post { Toast.makeText(this, DocumentAccess.friendly(e), Toast.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

/** Asks which kind of new document to create. */
fun chooseFormat(context: Context, then: (Formats) -> Unit) {
    val choices = arrayOf(Formats.DOCX, Formats.TXT, Formats.MARKDOWN)
    val labels = arrayOf(
        context.getString(R.string.new_docx),
        context.getString(R.string.new_txt),
        context.getString(R.string.new_md)
    )
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.new_document)
        .setItems(labels) { _, which -> then(choices[which]) }
        .setNegativeButton(R.string.cancel, null)
        .show()
}
