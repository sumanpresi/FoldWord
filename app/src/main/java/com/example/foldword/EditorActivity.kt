package com.example.foldword

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.example.foldword.data.DocumentAccess
import com.example.foldword.data.DraftMeta
import com.example.foldword.data.DraftStore
import com.example.foldword.data.OfflineFolder
import com.example.foldword.data.RecentDoc
import com.example.foldword.data.RecentStore
import com.example.foldword.doc.DocJson
import com.example.foldword.doc.DocModel
import com.example.foldword.doc.Formats
import com.example.foldword.doc.Json
import com.example.foldword.doc.SavePolicy
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Fold Word's offline editor. Works entirely without internet: documents are read from and written
 * to files the user chose, through Android's file picker. Unsaved work is kept in a recovery draft.
 */
class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_NEW_FORMAT = "new_format"
        const val EXTRA_DRAFT_KEY = "draft_key"

        private const val EDITOR_URL = "https://appassets.androidplatform.net/assets/editor/editor.html"
        private const val AUTOSAVE_MS = 20_000L

        fun open(context: Context, uri: Uri) =
            context.startActivity(Intent(context, EditorActivity::class.java).putExtra(EXTRA_URI, uri.toString()))

        fun newDocument(context: Context, format: Formats) =
            context.startActivity(Intent(context, EditorActivity::class.java).putExtra(EXTRA_NEW_FORMAT, format.name))

        fun openDraft(context: Context, key: String) =
            context.startActivity(Intent(context, EditorActivity::class.java).putExtra(EXTRA_DRAFT_KEY, key))
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var drafts: DraftStore
    private lateinit var recents: RecentStore
    private lateinit var web: WebView
    private lateinit var toolbar: MaterialToolbar

    // Current document
    private var uri: Uri? = null
    private var name = "Untitled"
    private var location = "Not saved yet"
    private var format = Formats.DOCX
    private var lost: Set<String> = emptySet()
    private var reuse: Map<String, ByteArray>? = null
    private var styleIds: Map<String, String> = emptyMap()
    private var canWrite = false
    private var canRename = false
    private var canDelete = false
    private var sizeBytes = -1L
    private var draftKey = ""
    private var dirty = false
    private var replaceConfirmed = false
    private var lastSaved = 0L
    private var words = 0
    private var chars = 0
    private var editorReady = false
    private var pendingJson: String? = null
    private var saving = false
    private var pendingSaveAs: Formats? = null
    private var afterSave: (() -> Unit)? = null

    /** Save As with the Fold Word folder offered first. */
    private val createDoc = registerForActivityResult(object : ActivityResultContract<Pair<String, Formats>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, Formats>): Intent {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second.mimeType)
                .putExtra(Intent.EXTRA_TITLE, input.first)
            OfflineFolder(context).treeUri?.let { tree ->
                try {
                    i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)))
                } catch (ignored: Exception) {}
            }
            return i
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == RESULT_OK) intent?.data else null
    }) { target ->
        val fmt = pendingSaveAs ?: format
        pendingSaveAs = null
        if (target == null) {
            afterSave = null
            return@registerForActivityResult
        }
        DocumentAccess.takePermission(this, target,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        writeTo(target, fmt, overwrite = false)
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        drafts = DraftStore(this)
        recents = RecentStore(this)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        web = findViewById(R.id.editorWeb)
        setupEditor()
        setupFindBar()
        updatePanel(resources.configuration)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findViewById<View>(R.id.findBar).visibility == View.VISIBLE) { closeFind(); return }
                confirmLeave { finish() }
            }
        })

        val restoreKey = savedInstanceState?.getString("draftKey")
        when {
            // The system closed the app in the background: bring the work back silently.
            restoreKey != null && drafts.meta(restoreKey) != null -> {
                replaceConfirmed = savedInstanceState?.getBoolean("replaceConfirmed") ?: false
                pendingSaveAs = savedInstanceState?.getString("pendingSaveAs")?.let { Formats.valueOf(it) }
                restoreDraft(restoreKey, announce = getString(R.string.banner_restored_auto))
            }
            intent.getStringExtra(EXTRA_DRAFT_KEY) != null -> restoreDraft(intent.getStringExtra(EXTRA_DRAFT_KEY)!!, null)
            intent.getStringExtra(EXTRA_URI) != null -> openUri(Uri.parse(intent.getStringExtra(EXTRA_URI)))
            intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_EDIT -> {
                val data = intent.data
                if (data == null) finish() else openUri(data)
            }
            else -> {
                val fmt = intent.getStringExtra(EXTRA_NEW_FORMAT)?.let { runCatching { Formats.valueOf(it) }.getOrNull() } ?: Formats.DOCX
                startNew(fmt, savedInstanceState?.getString("draftKey"))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draftKey", draftKey)
        outState.putBoolean("replaceConfirmed", replaceConfirmed)
        pendingSaveAs?.let { outState.putString("pendingSaveAs", it.name) }
    }

    override fun onPause() {
        super.onPause()
        if (dirty) saveDraft()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web.destroy()
        io.shutdown()
        super.onDestroy()
    }

    /** Folding and unfolding do not restart the screen; the document list just appears or hides. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePanel(newConfig)
    }

    private var panelWanted = true

    private fun updatePanel(config: Configuration) {
        val wide = config.screenWidthDp >= 720
        val show = wide && panelWanted
        findViewById<View>(R.id.sidePanel).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.sideDivider).visibility = if (show) View.VISIBLE else View.GONE
        if (show) fillSidePanel()
    }

    // ------------------------------------------------------------------ editor (local WebView)

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupEditor() {
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        with(web.settings) {
            javaScriptEnabled = true          // the editor itself is JavaScript, shipped inside the app
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            databaseEnabled = false
            blockNetworkImage = true
            setGeolocationEnabled(false)
            textZoom = 100
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)
                    // Anything that is not the app's own editor file is refused: the editor never goes online.
                    ?: WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        web.addJavascriptInterface(Bridge(), "FoldBridge")
        web.loadUrl(EDITOR_URL)
    }

    /** Messages from the editor page. Called on a background thread, so each one hops to the UI thread. */
    inner class Bridge {
        @JavascriptInterface fun onReady(arg: String) = main.post { editorReady = true; pendingJson?.let { loadIntoEditor(it) } }
        @JavascriptInterface fun onDirty(arg: String) = main.post { setDirty(true) }
        @JavascriptInterface fun onSaveShortcut(arg: String) = main.post { save() }
        @JavascriptInterface fun onFindShortcut(arg: String) = main.post { showFind() }
        @JavascriptInterface fun onStats(arg: String) = main.post {
            try {
                val m = Json.obj(Json.parse(arg))
                words = Json.num(m, "words", 0)
                chars = Json.num(m, "chars", 0)
                updateStatus()
            } catch (ignored: Exception) {}
        }
    }

    private fun loadIntoEditor(json: String) {
        if (!editorReady) {
            pendingJson = json
            return
        }
        pendingJson = null
        val literal = StringBuilder()
        Json.quote(json, literal)
        web.evaluateJavascript("FW.load($literal, false)", null)
    }

    /** Gets the current document from the editor as JSON. */
    private fun serialize(callback: (String?) -> Unit) {
        if (!editorReady) {
            callback(pendingJson)
            return
        }
        web.evaluateJavascript("FW.serialize()") { result ->
            callback(try { Json.parse(result) as? String } catch (e: Exception) { null })
        }
    }

    // ------------------------------------------------------------------ opening

    private fun startNew(fmt: Formats, existingKey: String?) {
        uri = null
        format = fmt
        name = fmt.fileName("Untitled")
        location = getString(R.string.not_saved_yet)
        draftKey = existingKey ?: "n" + UUID.randomUUID().toString().replace("-", "").take(20)
        loadIntoEditor(DocJson.toJson(DocModel.Document().also { it.blocks.add(DocModel.Paragraph()) }))
        refreshTitle()
    }

    private fun openUri(target: Uri) {
        setStatus(getString(R.string.status_opening))
        io.execute {
            try {
                DocumentAccess.requireContentUri(target)
                val info = DocumentAccess.info(this, target)
                val fmt = Formats.detect(info.name, info.mime)
                    ?: throw java.io.IOException(unsupportedMessage(info.name))
                if (fmt == Formats.PDF) {
                    main.post { PdfActivity.open(this, target); finish() }
                    return@execute
                }
                val bytes = DocumentAccess.readBytes(this, target)
                val loaded = fmt.read(bytes)
                val json = DocJson.toJson(loaded.document)
                main.post {
                    uri = target
                    format = fmt
                    name = info.name
                    location = info.location
                    canWrite = info.canWrite
                    canRename = info.canRename
                    canDelete = info.canDelete
                    sizeBytes = info.size
                    lost = loaded.lostFeatures
                    reuse = loaded.reusableParts
                    styleIds = loaded.styleIds ?: emptyMap()
                    draftKey = drafts.keyFor(target)
                    recents.add(RecentDoc(target.toString(), name, fmt.extension, location, System.currentTimeMillis()))
                    val draft = drafts.meta(draftKey)
                    if (draft != null) offerDraft(draft, json) else showOpened(json)
                }
            } catch (e: Exception) {
                main.post { showOpenError(target, e) }
            }
        }
    }

    private fun unsupportedMessage(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".doc") -> "Old Word .doc files can't be opened offline. Open it in Word Online, or save it as .docx first."
            lower.endsWith(".rtf") || lower.endsWith(".odt") -> "RTF and ODT files are not supported yet. Supported: .docx, .txt, .md, and .pdf (view only)."
            else -> "Fold Word can't open this type of file. Supported: .docx, .txt, .md, and .pdf (view only)."
        }
    }

    private fun showOpened(json: String) {
        loadIntoEditor(json)
        setDirty(false)
        lastSaved = 0
        showBanner(when {
            lost.isNotEmpty() -> getString(R.string.banner_lost, lost.joinToString("\n• ", prefix = "• "))
            !canWrite -> getString(R.string.banner_read_only)
            else -> null
        })
        refreshTitle()
    }

    private fun offerDraft(draft: DraftMeta, fileJson: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.draft_found_title)
            .setMessage(getString(R.string.draft_found_body, DateFormat.format("d MMM, HH:mm", draft.savedAt)))
            .setCancelable(false)
            .setPositiveButton(R.string.restore) { _, _ -> restoreDraft(draft.key, getString(R.string.banner_restored)) }
            .setNegativeButton(R.string.discard) { _, _ ->
                drafts.delete(draft.key)
                showOpened(fileJson)
            }
            .show()
    }

    private fun restoreDraft(key: String, announce: String?) {
        val meta = drafts.meta(key)
        val json = drafts.json(key)
        if (meta == null || json == null) {
            Toast.makeText(this, R.string.draft_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        draftKey = key
        format = runCatching { Formats.valueOf(meta.format.uppercase()) }.getOrNull()
            ?: Formats.values().firstOrNull { it.extension == meta.format } ?: Formats.DOCX
        name = meta.name
        lost = meta.lost.toSet()
        styleIds = meta.styleIds
        reuse = drafts.parts(key)
        uri = meta.uri?.let { Uri.parse(it) }
        location = getString(R.string.not_saved_yet)
        loadIntoEditor(json)
        setDirty(true)
        uri?.let { u ->
            io.execute {
                val info = runCatching { DocumentAccess.info(this, u) }.getOrNull()
                main.post {
                    if (info != null) {
                        location = info.location; canWrite = info.canWrite
                        canRename = info.canRename; canDelete = info.canDelete; sizeBytes = info.size
                    }
                    refreshTitle()
                }
            }
        }
        val parts = listOfNotNull(announce, if (lost.isNotEmpty()) getString(R.string.banner_lost, lost.joinToString("\n• ", prefix = "• ")) else null)
        showBanner(parts.joinToString("\n\n").ifEmpty { null })
        refreshTitle()
    }

    private fun showOpenError(target: Uri, e: Exception) {
        setStatus("")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cant_open)
            .setMessage(DocumentAccess.friendly(e))
            .setPositiveButton(R.string.remove_from_recent) { _, _ -> recents.remove(target.toString()); finish() }
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------------ saving

    private fun save() {
        if (saving) return
        when (SavePolicy.decide(uri != null, format.canSave, lost, canWrite, replaceConfirmed)) {
            SavePolicy.Action.CHOOSE_LOCATION -> saveAs(format)
            SavePolicy.Action.SAVE_COPY_ONLY -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.keep_original_title)
                .setMessage(getString(R.string.keep_original_body, lost.joinToString("\n• ", prefix = "• ")))
                .setPositiveButton(R.string.save_copy) { _, _ -> saveAs(format) }
                .setNegativeButton(R.string.cancel) { _, _ -> afterSave = null }
                .show()
            SavePolicy.Action.READ_ONLY -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.read_only_title)
                .setMessage(R.string.read_only_body)
                .setPositiveButton(R.string.save_copy) { _, _ -> saveAs(format) }
                .setNegativeButton(R.string.cancel) { _, _ -> afterSave = null }
                .show()
            SavePolicy.Action.CONFIRM_REPLACE -> MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.replace_title, name))
                .setMessage(getString(R.string.replace_body, location))
                .setPositiveButton(R.string.replace) { _, _ -> replaceConfirmed = true; uri?.let { writeTo(it, format, overwrite = true) } }
                .setNeutralButton(R.string.save_copy) { _, _ -> saveAs(format) }
                .setNegativeButton(R.string.cancel) { _, _ -> afterSave = null }
                .show()
            SavePolicy.Action.OVERWRITE -> uri?.let { writeTo(it, format, overwrite = true) }
        }
    }

    /** Lets the user pick a format, warns about anything it can't store, then asks where to save. */
    private fun saveAs(preselected: Formats) {
        val choices = arrayOf(Formats.DOCX, Formats.TXT, Formats.MARKDOWN)
        var picked = choices.indexOf(preselected).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_as_title)
            .setSingleChoiceItems(choices.map { "${it.label} (.${it.extension})" }.toTypedArray(), picked) { _, w -> picked = w }
            .setPositiveButton(R.string.next) { _, _ -> checkFormatThenPick(choices[picked]) }
            .setNegativeButton(R.string.cancel) { _, _ -> afterSave = null }
            .show()
    }

    private fun checkFormatThenPick(fmt: Formats) {
        serialize { json ->
            if (json == null) { saveFailed(java.io.IOException(getString(R.string.editor_not_ready))); return@serialize }
            val problems = try { fmt.unsupportedIn(DocJson.fromJson(json)) } catch (e: Exception) { emptyList<String>() }
            val suggested = if (uri != null) SavePolicy.copyName(name, fmt) else fmt.fileName(name)
            if (problems.isEmpty()) {
                pendingSaveAs = fmt
                createDoc.launch(suggested to fmt)
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.format_loses_title, fmt.label))
                    .setMessage(getString(R.string.format_loses_body, problems.joinToString("\n• ", prefix = "• ")))
                    .setPositiveButton(R.string.save_anyway) { _, _ -> pendingSaveAs = fmt; createDoc.launch(suggested to fmt) }
                    .setNegativeButton(R.string.cancel) { _, _ -> afterSave = null }
                    .show()
            }
        }
    }

    private fun writeTo(target: Uri, fmt: Formats, overwrite: Boolean) {
        saving = true
        setStatus(getString(R.string.status_saving))
        serialize { json ->
            if (json == null) { saving = false; saveFailed(java.io.IOException(getString(R.string.editor_not_ready))); return@serialize }
            val reuseNow = if (fmt == Formats.DOCX) reuse else null
            val ids = styleIds
            val oldName = name
            io.execute {
                try {
                    val doc = DocJson.fromJson(json)
                    val bytes = fmt.write(doc, reuseNow, ids)  // fully built before the file is touched
                    if (overwrite) {
                        // Keep the previous version inside Fold Word before replacing it.
                        val previous = runCatching { DocumentAccess.readBytes(this, target) }.getOrNull()
                        if (previous != null) drafts.backup(oldName, previous)
                    }
                    DocumentAccess.writeBytes(this, target, bytes)
                    val info = DocumentAccess.info(this, target)
                    main.post { saveSucceeded(target, fmt, info, bytes.size.toLong()) }
                } catch (e: Exception) {
                    main.post { saving = false; saveFailed(e) }
                }
            }
        }
    }

    private fun saveSucceeded(target: Uri, fmt: Formats, info: DocumentAccess.FileInfo, size: Long) {
        saving = false
        val oldKey = draftKey
        val oldUri = uri
        uri = target
        format = fmt
        name = info.name
        location = info.location
        canWrite = true // the user just chose this location for saving
        canRename = info.canRename
        canDelete = info.canDelete
        sizeBytes = if (info.size >= 0) info.size else size
        if (oldUri != target) {
            // A fresh copy contains only what Fold Word can represent, so later saves can update it in place.
            lost = emptySet()
            replaceConfirmed = true
            if (fmt != Formats.DOCX) reuse = null
        }
        drafts.delete(oldKey)
        draftKey = drafts.keyFor(target)
        lastSaved = System.currentTimeMillis()
        web.evaluateJavascript("FW.markSaved()", null)
        setDirty(false)
        recents.add(RecentDoc(target.toString(), name, fmt.extension, location, lastSaved))
        showBanner(null)
        refreshTitle()
        Toast.makeText(this, getString(R.string.saved_to, name, location), Toast.LENGTH_SHORT).show()
        afterSave?.let { afterSave = null; it() }
    }

    private fun saveFailed(e: Exception) {
        saveDraft()
        afterSave = null
        updateStatus()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_failed)
            .setMessage(DocumentAccess.friendly(e) + "\n\n" + getString(R.string.save_failed_kept))
            .setPositiveButton(R.string.save_copy) { _, _ -> saveAs(format) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private val autosave = Runnable { if (dirty) saveDraft(); scheduleAutosave() }

    private fun scheduleAutosave() {
        main.removeCallbacks(autosave)
        if (dirty) main.postDelayed(autosave, AUTOSAVE_MS)
    }

    /** Writes a recovery draft to the app's private storage. Never uploads anything. */
    private fun saveDraft() {
        val key = draftKey.ifEmpty { return }
        val meta = DraftMeta(key, uri?.toString(), name, format.name, lost.toList(), styleIds, System.currentTimeMillis())
        val parts = reuse
        serialize { json ->
            if (json == null) return@serialize
            io.execute { runCatching { drafts.save(meta, json, parts) } }
        }
    }

    // ------------------------------------------------------------------ leaving safely

    private fun confirmLeave(then: () -> Unit) {
        if (!dirty) { then(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_body)
            .setPositiveButton(R.string.m_save) { _, _ -> afterSave = then; save() }
            .setNeutralButton(R.string.keep_draft) { _, _ -> saveDraft(); then() }
            .setNegativeButton(R.string.discard) { _, _ ->
                drafts.delete(draftKey)
                dirty = false
                then()
            }
            .show()
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.m_rename)?.isEnabled = uri != null && canRename
        menu.findItem(R.id.m_delete)?.isEnabled = uri != null && canDelete
        menu.findItem(R.id.m_share)?.isEnabled = uri != null
        menu.findItem(R.id.m_panel)?.isVisible = resources.configuration.screenWidthDp >= 720
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.m_save -> save()
            R.id.m_save_as -> saveAs(format)
            R.id.m_undo -> web.evaluateJavascript("FW.undo()", null)
            R.id.m_redo -> web.evaluateJavascript("FW.redo()", null)
            R.id.m_find -> showFind()
            R.id.m_info -> showInfo()
            R.id.m_rename -> rename()
            R.id.m_delete -> delete()
            R.id.m_share -> share()
            R.id.m_upload -> upload()
            R.id.m_online -> confirmLeaveForOnline()
            R.id.m_panel -> { panelWanted = !panelWanted; updatePanel(resources.configuration) }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showInfo() {
        val lines = mutableListOf(
            getString(R.string.info_name, name),
            getString(R.string.info_location, location),
            getString(R.string.info_format, format.label),
            getString(R.string.info_words, words, chars),
            getString(R.string.info_mode)
        )
        if (sizeBytes >= 0) lines.add(getString(R.string.info_size, android.text.format.Formatter.formatShortFileSize(this, sizeBytes)))
        if (lastSaved > 0) lines.add(getString(R.string.info_saved, DateFormat.format("d MMM yyyy, HH:mm", lastSaved)))
        lines.add(if (dirty) getString(R.string.status_unsaved) else getString(R.string.status_saved))
        if (lost.isNotEmpty()) lines.add("\n" + getString(R.string.info_lost, lost.joinToString("\n• ", prefix = "• ")))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.m_info)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun rename() {
        val target = uri ?: return
        val input = EditText(this).apply {
            setText(name)
            setSelection(0, name.substringBeforeLast('.').length)
            inputType = EditorInfo.TYPE_CLASS_TEXT
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.m_rename)
            .setView(input.also { it.setPadding(48, 24, 48, 24) })
            .setPositiveButton(R.string.m_rename) { _, _ ->
                val newName = format.fileName(input.text.toString())
                io.execute {
                    try {
                        val newUri = DocumentAccess.rename(this, target, newName)
                        val info = DocumentAccess.info(this, newUri)
                        main.post {
                            val oldKey = draftKey
                            uri = newUri
                            name = info.name
                            location = info.location
                            draftKey = drafts.keyFor(newUri)
                            if (dirty) { drafts.delete(oldKey); saveDraft() }
                            recents.replace(target.toString(), RecentDoc(newUri.toString(), name, format.extension, location, System.currentTimeMillis()))
                            refreshTitle()
                        }
                    } catch (e: Exception) {
                        main.post { toastLong(DocumentAccess.friendly(e)) }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete() {
        val target = uri ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_title, name))
            .setMessage(getString(R.string.delete_body, location))
            .setPositiveButton(R.string.m_delete) { _, _ ->
                io.execute {
                    try {
                        DocumentAccess.delete(this, target)
                        main.post {
                            recents.remove(target.toString())
                            drafts.delete(draftKey)
                            dirty = false
                            toastLong(getString(R.string.deleted, name))
                            finish()
                        }
                    } catch (e: Exception) {
                        main.post { toastLong(DocumentAccess.friendly(e)) }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Sends the saved file to another app the user picks (for example email or the OneDrive app). */
    private fun share() {
        val target = uri ?: return
        if (dirty) toastLong(getString(R.string.share_saved_version))
        val send = Intent(Intent.ACTION_SEND)
            .setType(format.mimeType)
            .putExtra(Intent.EXTRA_STREAM, target)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.m_share)))
    }

    /** Explains clearly what uploading means, and only then hands off to OneDrive. */
    private fun upload() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.upload_title)
            .setMessage(R.string.upload_body)
            .setPositiveButton(R.string.upload_share) { _, _ ->
                if (uri == null || dirty) {
                    afterSave = { share() }
                    toastLong(getString(R.string.upload_save_first))
                    save()
                } else share()
            }
            .setNeutralButton(R.string.upload_web) { _, _ -> openWordOnline(Config.ONEDRIVE_URL) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmLeaveForOnline() {
        if (!dirty) { openWordOnline(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.online_switch_title)
            .setMessage(R.string.online_switch_body)
            .setPositiveButton(R.string.m_save) { _, _ -> afterSave = { openWordOnline() }; save() }
            .setNeutralButton(R.string.keep_draft) { _, _ -> saveDraft(); openWordOnline() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ find

    private fun setupFindBar() {
        val text = findViewById<EditText>(R.id.findText)
        val count = findViewById<TextView>(R.id.findCount)
        web.setFindListener { active, matches, done ->
            if (done) count.text = if (matches == 0) getString(R.string.find_none) else "${active + 1}/$matches"
        }
        text.setOnEditorActionListener { _, _, _ -> web.findAllAsync(text.text.toString()); true }
        text.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { web.findAllAsync(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<MaterialButton>(R.id.findNext).setOnClickListener { web.findNext(true) }
        findViewById<MaterialButton>(R.id.findPrev).setOnClickListener { web.findNext(false) }
        findViewById<MaterialButton>(R.id.findClose).setOnClickListener { closeFind() }
    }

    private fun showFind() {
        findViewById<View>(R.id.findBar).visibility = View.VISIBLE
        val t = findViewById<EditText>(R.id.findText)
        t.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(t, 0)
    }

    private fun closeFind() {
        web.clearMatches()
        findViewById<View>(R.id.findBar).visibility = View.GONE
    }

    // ------------------------------------------------------------------ side panel (unfolded screen)

    private fun fillSidePanel() {
        val list = findViewById<LinearLayout>(R.id.sideList)
        list.removeAllViews()
        val items = recents.all().take(25)
        if (items.isEmpty()) {
            list.addView(sideRow(getString(R.string.no_recent), "") {})
            return
        }
        for (r in items) {
            val current = r.uri == uri?.toString()
            list.addView(sideRow(r.name, r.location, current) {
                if (!current) confirmLeave {
                    open(this, Uri.parse(r.uri))
                    finish()
                }
            })
        }
    }

    private fun sideRow(title: String, subtitle: String, current: Boolean = false, onClick: () -> Unit): View {
        val v = layoutInflater.inflate(R.layout.row_doc, null, false)
        v.findViewById<TextView>(R.id.rowTitle).text = title
        v.findViewById<TextView>(R.id.rowSubtitle).apply {
            text = subtitle
            visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }
        v.isSelected = current
        if (current) v.setBackgroundResource(R.drawable.row_selected)
        v.setOnClickListener { onClick() }
        return v
    }

    // ------------------------------------------------------------------ status

    private fun setDirty(value: Boolean) {
        dirty = value
        updateStatus()
        scheduleAutosave()
    }

    private fun refreshTitle() {
        toolbar.title = name
        toolbar.subtitle = location
        invalidateOptionsMenu()
        if (findViewById<View>(R.id.sidePanel).visibility == View.VISIBLE) fillSidePanel()
        updateStatus()
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.statusSave).text = text
    }

    private fun updateStatus() {
        findViewById<TextView>(R.id.statusWords).text = getString(R.string.status_words, words)
        setStatus(when {
            saving -> getString(R.string.status_saving)
            dirty -> getString(R.string.status_unsaved)
            lastSaved > 0 -> getString(R.string.status_saved_at, DateFormat.format("HH:mm", lastSaved))
            uri == null -> getString(R.string.not_saved_yet)
            else -> getString(R.string.status_saved)
        })
    }

    private fun showBanner(text: String?) {
        findViewById<TextView>(R.id.banner).apply {
            this.text = text ?: ""
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun toastLong(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
