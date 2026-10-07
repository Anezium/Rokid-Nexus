package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle

/** Process-local authority for starts; the exported plugin Binder never exposes this token. */
internal object HubCommandIntents {
    private const val EXTRA_AUTHORITY = "com.anezium.rokidbus.phone.COMMAND_AUTHORITY"
    private val authority = Binder()

    fun create(context: Context): Intent = Intent(context, BusHubService::class.java).apply {
        putExtras(Bundle().apply { putBinder(EXTRA_AUTHORITY, authority) })
    }

    // Binder preserves object identity on an IPC round trip. A token from a dead process
    // cannot authorize a command in a new process; callers must retry that operation.
    fun isTrusted(intent: Intent): Boolean =
        runCatching { intent.extras?.getBinder(EXTRA_AUTHORITY) === authority }.getOrDefault(false)
}
