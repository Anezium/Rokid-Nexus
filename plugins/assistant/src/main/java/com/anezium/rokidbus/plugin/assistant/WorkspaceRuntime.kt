package com.anezium.rokidbus.plugin.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

internal object WorkspaceRuntime {
    @Volatile private var instance: WorkspaceController? = null

    suspend fun get(context: Context): WorkspaceController = withContext(Dispatchers.IO) {
        instance ?: synchronized(this@WorkspaceRuntime) {
            instance ?: WorkspaceController(
                WorkspaceStore(File(context.applicationContext.noBackupFilesDir, "assistant-workspace")),
                WorkspaceSafGateway(context.applicationContext.contentResolver),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                pdfReader = PdfBoxWorkspacePdfReader(context.applicationContext),
            ).also { instance = it }
        }
    }
}
