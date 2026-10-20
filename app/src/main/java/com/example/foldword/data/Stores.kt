package com.example.foldword.data

import android.content.Context
import android.net.Uri
import com.example.foldword.doc.Json
import com.example.foldword.doc.SafeFiles
import java.io.File
import java.security.MessageDigest

/** A document the user opened recently. Only its address and name are stored, never its content. */
data class RecentDoc(val uri: String, val name: String, val format: String, val location: String, val time: Long)

class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("fold_word_recent", Context.MODE_PRIVATE)

    fun all(): List<RecentDoc> = try {
        Json.arr(Json.parse(prefs.getString(KEY, "[]") ?: "[]")).mapNotNull { o ->
            val m = Json.obj(o)
            val uri = Json.str(m, "uri", "")
            if (uri.isEmpty()) null
            else RecentDoc(uri, Json.str(m, "name", "Document"), Json.str(m, "format", "docx"),
                Json.str(m, "location", ""), Json.lng(m, "time", 0))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun add(doc: RecentDoc) = save(listOf(doc) + all().filter { it.uri != doc.uri })

    fun remove(uri: String) = save(all().filter { it.uri != uri })

    fun replace(oldUri: String, doc: RecentDoc) = save(listOf(doc) + all().filter { it.uri != oldUri && it.uri != doc.uri })

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(list: List<RecentDoc>) {
        val arr = list.take(MAX).map {
            linkedMapOf<String, Any>("uri" to it.uri, "name" to it.name, "format" to it.format,
                "location" to it.location, "time" to it.time)
        }
        prefs.edit().putString(KEY, Json.write(arr)).apply()
    }

    companion object {
        private const val KEY = "recent"
        private const val MAX = 40
    }
}

/** Information stored with a recovery draft. */
data class DraftMeta(
    val key: String,
    val uri: String?,
    val name: String,
    val format: String,
    val lost: List<String>,
    val styleIds: Map<String, String>,
    val savedAt: Long
)

/**
 * Recovery drafts and backups, kept in the app's private storage (not the cache, so Android does not
 * delete them). Drafts protect unsaved work if the app closes; backups keep the previous version of a
 * file before Fold Word replaces it.
 */
class DraftStore(context: Context) {
    private val drafts = File(context.filesDir, "drafts")
    val backups = File(context.filesDir, "backups")

    fun keyFor(uri: Uri): String = "f" + sha(uri.toString()).take(24)

    fun save(meta: DraftMeta, json: String, reuse: Map<String, ByteArray>?) {
        val m = linkedMapOf<String, Any?>(
            "key" to meta.key, "uri" to meta.uri, "name" to meta.name, "format" to meta.format,
            "lost" to meta.lost, "styleIds" to meta.styleIds, "savedAt" to meta.savedAt
        )
        val partsDir = File(drafts, meta.key + ".parts")
        if (reuse != null && !partsDir.exists()) {
            for ((name, bytes) in reuse) SafeFiles.writeAtomic(File(partsDir, name.replace('/', '_')), bytes)
        }
        SafeFiles.writeAtomic(File(drafts, meta.key + ".json"), json.toByteArray(Charsets.UTF_8))
        SafeFiles.writeAtomic(File(drafts, meta.key + ".meta"), Json.write(m).toByteArray(Charsets.UTF_8))
    }

    fun meta(key: String): DraftMeta? = try {
        val f = File(drafts, "$key.meta")
        if (!f.exists() || !File(drafts, "$key.json").exists()) null else parseMeta(String(SafeFiles.read(f), Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun json(key: String): String? = try {
        String(SafeFiles.read(File(drafts, "$key.json")), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun parts(key: String): Map<String, ByteArray>? {
        val dir = File(drafts, "$key.parts")
        val files = dir.listFiles() ?: return null
        // Stored names are part paths with "/" replaced by "_" (for example word_theme_theme1.xml).
        return files.associate { it.name.replace('_', '/') to it.readBytes() }
    }

    fun all(): List<DraftMeta> =
        (drafts.listFiles { f -> f.name.endsWith(".meta") } ?: emptyArray())
            .mapNotNull { meta(it.name.removeSuffix(".meta")) }
            .sortedByDescending { it.savedAt }

    fun delete(key: String) {
        File(drafts, "$key.json").delete()
        File(drafts, "$key.meta").delete()
        File(drafts, "$key.parts").deleteRecursively()
    }

    /** Keeps a copy of a file's previous version before it is replaced. Keeps the newest 20. */
    fun backup(name: String, bytes: ByteArray) {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        SafeFiles.writeAtomic(File(backups, "${System.currentTimeMillis()}-$safe"), bytes)
        val all = (backups.listFiles() ?: emptyArray()).sortedByDescending { it.name }
        all.drop(20).forEach { it.delete() }
    }

    fun listBackups(): List<File> = (backups.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.sortedByDescending { it.name }

    private fun parseMeta(s: String): DraftMeta {
        val m = Json.obj(Json.parse(s))
        val ids = Json.obj(m["styleIds"]).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        return DraftMeta(
            key = Json.str(m, "key", ""),
            uri = m["uri"] as? String,
            name = Json.str(m, "name", "Document"),
            format = Json.str(m, "format", "docx"),
            lost = Json.arr(m["lost"]).mapNotNull { it as? String },
            styleIds = ids,
            savedAt = Json.lng(m, "savedAt", 0)
        )
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The folder the user chose for "Offline documents" (a Storage Access Framework tree). */
class OfflineFolder(context: Context) {
    private val prefs = context.getSharedPreferences("fold_word_settings", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString("offline_tree", null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString("offline_tree", value?.toString()).apply()

    /** Folders added with Browse Phone, so they can be opened again quickly. */
    var browsed: List<String>
        get() = try { Json.arr(Json.parse(prefs.getString("browsed", "[]") ?: "[]")).mapNotNull { it as? String } } catch (e: Exception) { emptyList() }
        set(value) = prefs.edit().putString("browsed", Json.write(value.distinct().take(10))).apply()
}
