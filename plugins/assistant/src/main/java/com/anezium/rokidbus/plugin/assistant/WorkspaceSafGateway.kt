package com.anezium.rokidbus.plugin.assistant

import android.content.ContentResolver
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.coroutines.resumeWithException

internal class WorkspaceSafGateway(private val resolver: ContentResolver) : WorkspaceDocumentGateway {
    override fun hasReadGrant(treeUri: String): Boolean = isLocalWorkspaceTree(treeUri) &&
        resolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isReadPermission }

    override fun persistReadGrant(treeUri: String, returnedFlags: Int) {
        require(isLocalWorkspaceTree(treeUri) && returnedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        resolver.takePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun releaseReadGrant(treeUri: String) {
        if (isLocalWorkspaceTree(treeUri)) {
            try {
                resolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Revoked grants are already released.
            }
        }
    }

    override suspend fun root(treeUri: String): WorkspaceEntry {
        val tree = Uri.parse(treeUri)
        return metadata(treeUri, DocumentsContract.getTreeDocumentId(tree))
    }

    override suspend fun children(treeUri: String, documentId: String): List<WorkspaceEntry> = query {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(treeUri), documentId)
        readEntries(uri, it, WorkspaceLimits.MAX_ENTRIES)
    }

    override suspend fun metadata(treeUri: String, documentId: String): WorkspaceEntry = query {
        readEntries(documentUri(treeUri, documentId), it, 1).singleOrNull() ?: throw FileNotFoundException()
    }

    override suspend fun open(treeUri: String, documentId: String): InputStream = query { signal ->
        val descriptor = resolver.openFileDescriptor(documentUri(treeUri, documentId), "r", signal)
            ?: throw FileNotFoundException()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    override fun observe(treeUri: String, onChange: () -> Unit): AutoCloseable? {
        if (!isLocalWorkspaceTree(treeUri)) return null
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        return try {
            val tree = Uri.parse(treeUri)
            resolver.registerContentObserver(tree, true, observer)
            resolver.registerContentObserver(DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree)), true, observer)
            AutoCloseable { resolver.unregisterContentObserver(observer) }
        } catch (_: Exception) {
            resolver.unregisterContentObserver(observer)
            null
        }
    }

    private fun documentUri(treeUri: String, documentId: String): Uri {
        require(isLocalWorkspaceTree(treeUri))
        return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), documentId)
    }

    private fun readEntries(uri: Uri, signal: CancellationSignal, maxEntries: Int): List<WorkspaceEntry> {
        val cursor = resolver.query(uri, PROJECTION, null, null, null, signal) ?: error("workspace_query_failed")
        return cursor.use {
            if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
                it.extras.containsKey(DocumentsContract.EXTRA_ERROR)
            ) error("workspace_query_incomplete")
            buildList {
                while (it.moveToNext()) {
                    if (size >= maxEntries) throw WorkspaceCheckLimitException()
                    val name = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).orEmpty()
                    val mime = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
                    val flags = it.optionalLong(DocumentsContract.Document.COLUMN_FLAGS) ?: 0L
                    add(WorkspaceEntry(
                        documentId = it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
                        name = name, modifiedAtMs = it.optionalLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                        sizeBytes = it.optionalLong(DocumentsContract.Document.COLUMN_SIZE),
                        directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        virtual = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT.toLong() != 0L,
                    ))
                }
            }
        }
    }

    private suspend fun <T> query(block: (CancellationSignal) -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                signal.throwIfCanceled()
                continuation.resume(block(signal), onCancellation = { _, value, _ ->
                    if (value is Closeable) runCatching { value.close() }
                })
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }

    private fun Cursor.optionalLong(column: String): Long? {
        val position = getColumnIndex(column)
        return if (position < 0 || isNull(position)) null else getLong(position)
    }

    companion object {
        private val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS)
    }
}
