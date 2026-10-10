package com.anezium.rokidbus.plugin.assistant

import android.provider.DocumentsContract
import java.net.URI

internal fun isWorkspaceTreeUri(uri: String): Boolean = runCatching {
    val value = URI(uri)
    uri.length <= 2_048 && value.scheme == "content" &&
        Regex("[A-Za-z0-9._-]+").matches(value.rawAuthority.orEmpty()) &&
        value.rawUserInfo == null && value.port == -1 &&
        value.rawQuery == null && value.rawFragment == null &&
        Regex("/tree/[^/]+").matches(value.rawPath.orEmpty())
}.getOrDefault(false)

internal fun isPlatformWorkspaceTree(uri: String): Boolean = isWorkspaceTreeUri(uri) &&
    URI(uri).rawAuthority == "com.android.externalstorage.documents"

internal data class WorkspaceProviderRoot(val rootId: String, val documentId: String, val flags: Int?) {
    val localOnly: Boolean get() = flags != null && flags >= 0 &&
        flags and DocumentsContract.Root.FLAG_LOCAL_ONLY != 0
}

internal fun workspaceFolderIsLocal(uri: String, treeDocumentId: String,
    readRoots: () -> List<WorkspaceProviderRoot>, isChild: (String) -> Boolean,
): Boolean {
    if (!isWorkspaceTreeUri(uri)) return false
    if (isPlatformWorkspaceTree(uri)) return true
    return try {
        val roots = readRoots()
        if (roots.isEmpty() || roots.size > WorkspaceLimits.MAX_PROVIDER_ROOTS ||
            roots.any { it.rootId.isBlank() || it.documentId.isBlank() ||
                it.rootId.length > 1_024 || it.documentId.length > 1_024 } ||
            roots.map { it.rootId }.distinct().size != roots.size
        ) return false
        val exact = roots.filter { it.documentId == treeDocumentId }
        if (exact.isNotEmpty()) return exact.size == 1 && exact.single().localOnly
        if (roots.all { it.localOnly }) return true
        roots.filter { it.localOnly }.count { isChild(it.documentId) } == 1
    } catch (_: Exception) {
        false
    }
}
