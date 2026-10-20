package com.example.foldword.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.foldword.doc.SafeFiles
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Reads and writes documents through Android's Storage Access Framework (content:// URIs only).
 * Fold Word never asks for broad storage permissions: the user picks each file or folder.
 */
object DocumentAccess {

    /** Largest file the offline editor will load into memory. */
    const val MAX_EDIT_BYTES = 50L * 1024 * 1024

    class FileInfo(
        val uri: Uri,
        val name: String,
        val mime: String?,
        val size: Long,
        val canWrite: Boolean,
        val canRename: Boolean,
        val canDelete: Boolean,
        val location: String
    )

    fun info(context: Context, uri: Uri): FileInfo {
        var name: String? = null
        var size = -1L
        var mime: String? = null
        var flags = -1
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    val mi = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (mi >= 0 && !c.isNull(mi)) mime = c.getString(mi)
                    val fi = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                    if (fi >= 0 && !c.isNull(fi)) flags = c.getInt(fi)
                }
            }
        } catch (e: Exception) {
            // Some providers refuse queries; fall back to what the URI itself tells us.
        }
        if (mime == null) mime = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
        val isDocument = DocumentsContract.isDocumentUri(context, uri)
        val hasWriteGrant = context.contentResolver.persistedUriPermissions.any {
            it.isWritePermission && (it.uri == uri || uri.toString().startsWith(it.uri.toString()))
        } || context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val canWrite = if (flags >= 0 && isDocument) {
            flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0 && hasWriteGrant
        } else hasWriteGrant
        return FileInfo(
            uri = uri,
            name = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document",
            mime = mime,
            size = size,
            canWrite = canWrite,
            canRename = flags >= 0 && isDocument && flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0,
            canDelete = flags >= 0 && isDocument && flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0,
            location = locationLabel(uri)
        )
    }

    /** Readable name for where a file lives, for example "Phone storage › Documents". */
    fun locationLabel(uri: Uri): String {
        val auth = uri.authority ?: return "This phone"
        return when (auth) {
            "com.android.externalstorage.documents" -> {
                val id = try {
                    if (DocumentsContract.isTreeUri(uri) && !uri.toString().contains("/document/")) {
                        DocumentsContract.getTreeDocumentId(uri)
                    } else DocumentsContract.getDocumentId(uri)
                } catch (e: Exception) { "" }
                val volume = id.substringBefore(':')
                val path = id.substringAfter(':', "").substringBeforeLast('/', "")
                val root = if (volume == "primary") "Phone storage" else "SD card"
                if (path.isEmpty()) root else "$root › " + path.replace("/", " › ")
            }
            "com.android.providers.downloads.documents" -> "Downloads"
            "com.android.providers.media.documents" -> "Phone media"
            "com.google.android.apps.docs.storage", "com.google.android.apps.docs.storage.legacy" -> "Google Drive"
            "com.microsoft.skydrive.content.StorageAccessProvider" -> "OneDrive app"
            "com.sec.android.app.myfiles.FileProvider" -> "My Files"
            else -> if (auth.contains("download")) "Downloads" else "Other app"
        }
    }

    @Throws(IOException::class)
    fun readBytes(context: Context, uri: Uri, limit: Long = MAX_EDIT_BYTES): ByteArray {
        requireContentUri(uri)
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw IOException("Fold Word no longer has permission to open this file. Open it again with Open File.", e)
        } catch (e: FileNotFoundException) {
            throw IOException("This file was moved, renamed or deleted.", e)
        } ?: throw IOException("This file could not be opened.")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("This file is larger than ${limit / (1024 * 1024)} MB and cannot be edited offline.")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }

    /**
     * Replaces the file's content. The bytes are fully prepared before the file is opened, so a
     * formatting error can never leave a half-written file. Afterwards the file is read back and
     * compared; a mismatch is reported so the caller can restore the backup.
     */
    @Throws(IOException::class)
    fun writeBytes(context: Context, uri: Uri, data: ByteArray) {
        requireContentUri(uri)
        val resolver = context.contentResolver
        try {
            val stream = try {
                resolver.openOutputStream(uri, "wt")
            } catch (e: IllegalArgumentException) {
                resolver.openOutputStream(uri, "rwt") // providers that do not accept "wt"
            } catch (e: FileNotFoundException) {
                if (e.message?.contains("mode", ignoreCase = true) == true) resolver.openOutputStream(uri, "rwt") else throw e
            } ?: throw IOException("This location cannot be written to.")
            stream.use {
                it.write(data)
                it.flush()
            }
        } catch (e: SecurityException) {
            throw IOException("Fold Word doesn't have permission to change this file. Use Save As to save a copy.", e)
        } catch (e: FileNotFoundException) {
            throw IOException("This file was moved or deleted. Use Save As to save your work.", e)
        } catch (e: IOException) {
            if (SafeFiles.isNoSpace(e)) throw SafeFiles.LowStorageException("The phone's storage is full. Free some space and save again.")
            throw e
        }
        if (data.size <= 20 * 1024 * 1024) {
            val back = try { readBytes(context, uri, data.size.toLong() + 1) } catch (e: IOException) { null }
            if (back != null && !back.contentEquals(data)) {
                throw IOException("The saved file did not match what was written. Your previous version was backed up.")
            }
        }
    }

    fun takePermission(context: Context, uri: Uri, flags: Int) {
        val f = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            context.contentResolver.takePersistableUriPermission(uri, f)
        } catch (e: SecurityException) {
            // Not every provider offers lasting access; the file still opens this time.
            if (f and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (ignored: SecurityException) {}
            }
        }
    }

    /** Renames a document. Returns the new URI (it can change), or throws with a readable message. */
    @Throws(IOException::class)
    fun rename(context: Context, uri: Uri, newName: String): Uri {
        try {
            return DocumentsContract.renameDocument(context.contentResolver, uri, newName)
                ?: throw IOException("This location does not allow renaming.")
        } catch (e: UnsupportedOperationException) {
            throw IOException("This location does not allow renaming.", e)
        } catch (e: SecurityException) {
            throw IOException("Fold Word doesn't have permission to rename this file.", e)
        } catch (e: IllegalStateException) {
            throw IOException("A file with that name may already exist.", e)
        }
    }

    @Throws(IOException::class)
    fun delete(context: Context, uri: Uri) {
        try {
            if (!DocumentsContract.deleteDocument(context.contentResolver, uri)) throw IOException("The file could not be deleted.")
        } catch (e: UnsupportedOperationException) {
            throw IOException("This location does not allow deleting.", e)
        } catch (e: SecurityException) {
            throw IOException("Fold Word doesn't have permission to delete this file.", e)
        }
    }

    fun requireContentUri(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("Only files chosen with the Android file picker can be opened.")
    }

    fun friendly(e: Throwable): String {
        val m = e.message
        return when {
            e is SafeFiles.LowStorageException -> m ?: "The phone's storage is full."
            m.isNullOrBlank() -> "Something went wrong with this file."
            else -> m
        }
    }
}
