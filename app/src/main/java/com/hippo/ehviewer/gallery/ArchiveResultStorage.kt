package com.hippo.ehviewer.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import java.io.File

/** Locations live in app preferences; portable result manifests contain no device paths or URIs. */
object ArchiveResultStorage {
    private const val PREFERENCES = "archive-result-parents"
    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    @JvmStatic fun isArchiveUri(value: String?) = value?.let {
        it.startsWith("content://") || it.startsWith("file://")
    } == true

    /** Only look up existing results while reading. Create the root on an explicit persistent write. */
    @JvmStatic @Synchronized fun directory(context: Context, archive: Uri, create: Boolean): UniFile? {
        val parent = parent(context, archive) ?: return null
        val name = UniFile.fromUri(context, archive)?.name ?: return null
        val directory = parent.findFile("$name.results")
        if (directory != null) return directory.takeIf { it.isDirectory }
        return if (create) parent.createDirectory("$name.results")?.takeIf { it.isDirectory } else null
    }

    @JvmStatic fun canWrite(context: Context, archive: Uri): Boolean =
        runCatching { parent(context, archive)?.canWrite() == true }.getOrDefault(false)

    internal fun parent(context: Context, archive: Uri): UniFile? {
        if (archive.scheme == "file") {
            val directory = archive.path?.let(::File)?.parentFile ?: return null
            return UniFile.fromFile(directory)?.takeIf { it.isDirectory }
        }
        val remembered = preferences(context).getString(archive.toString(), null)
        if (remembered != null) {
            val parent = runCatching { UniFile.fromTreeUri(context, Uri.parse(remembered)) }.getOrNull()
            if (parent != null && contains(context, parent, archive)) return parent
        }
        val id = runCatching { DocumentsContract.getDocumentId(archive) }.getOrNull() ?: return null
        // Reuse an existing tree grant, including when the pair was moved within that tree.
        for (grant in context.contentResolver.persistedUriPermissions) {
            if (!grant.isReadPermission || !UniFile.isTreeUri(grant.uri) || grant.uri.authority != archive.authority) continue
            val root = UniFile.fromTreeUri(context, grant.uri) ?: continue
            if (contains(context, root, archive)) return remember(context, archive, root)
            val parentId = runCatching {
                val document = DocumentsContract.buildDocumentUriUsingTree(grant.uri, id)
                DocumentsContract.findDocumentPath(context.contentResolver, document)?.path?.dropLast(1)?.lastOrNull()
            }.getOrNull() ?: if (archive.authority == "com.android.externalstorage.documents" && id.contains('/')) {
                id.substringBeforeLast('/')
            } else null
            if (parentId != null) {
                val parent = runCatching { UniFile.fromTreeUri(context,
                    DocumentsContract.buildDocumentUriUsingTree(grant.uri, parentId)) }.getOrNull()
                if (parent != null && contains(context, parent, archive)) return remember(context, archive, parent)
            }
        }
        return null
    }

    private fun remember(context: Context, archive: Uri, parent: UniFile): UniFile {
        preferences(context).edit().putString(archive.toString(), parent.uri.toString()).apply()
        return parent
    }

    /** Validate actual document IDs, never accept an unrelated directory with a same-named ZIP. */
    internal fun contains(context: Context, parent: UniFile, archive: Uri): Boolean = runCatching {
        if (parent.uri.authority != archive.authority) return@runCatching false
        val wanted = DocumentsContract.getDocumentId(archive)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent.uri,
            DocumentsContract.getDocumentId(parent.uri))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(0) == wanted) return@use true
            false
        } == true
    }.getOrDefault(false)

    internal fun bind(context: Context, archive: Uri, tree: Uri, flags: Int): Boolean = runCatching {
        val parent = UniFile.fromTreeUri(context, tree) ?: return@runCatching false
        if (!contains(context, parent, archive) || !parent.canWrite()) return@runCatching false
        val access = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (access != (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) return@runCatching false
        context.contentResolver.takePersistableUriPermission(tree, access)
        remember(context, archive, parent)
        true
    }.getOrDefault(false)
}
