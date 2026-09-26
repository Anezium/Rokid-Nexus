package com.anezium.rokidbus.glasses

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

internal object RemoteInputImeProvisioner {
    fun ensureConfigured(context: Context): Boolean = configure(context, replaceSelected = false)

    /**
     * The owner's explicit choice from the phone: unlike [ensureConfigured], this replaces a
     * keyboard another app selected, since that is the whole point of asking.
     */
    fun selectNexus(context: Context): Boolean = configure(context, replaceSelected = true)

    fun canConfigure(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun selectedMethod(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

    fun nexusComponent(context: Context): String =
        ComponentName(context, NexusRemoteInputMethodService::class.java).flattenToShortString()

    private fun configure(context: Context, replaceSelected: Boolean): Boolean {
        if (!canConfigure(context)) return false

        val component = nexusComponent(context)
        return runCatching {
            val resolver = context.contentResolver
            val enabled = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_INPUT_METHODS,
            )
            val updated = enabledMethodsWithNexus(enabled, component)
            if (updated != enabled) {
                check(
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_INPUT_METHODS,
                        updated,
                    ),
                )
            }

            val current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val select = if (replaceSelected) {
                !isNexus(current, component)
            } else {
                shouldSelectNexus(current, component)
            }
            if (select) {
                check(
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD,
                        component,
                    ),
                )
            }
            true
        }.getOrDefault(false)
    }

    internal fun enabledMethodsWithNexus(current: String?, component: String): String {
        val methods = current.orEmpty()
            .split(':')
            .filterTo(linkedSetOf()) { it.isNotBlank() }
        methods += component
        return methods.joinToString(":")
    }

    internal fun shouldSelectNexus(current: String?, component: String): Boolean =
        current.isNullOrBlank() || current == component

    /** The setting may hold the "pkg/.Class" shorthand or the fully qualified form. */
    internal fun isNexus(method: String?, component: String): Boolean =
        !method.isNullOrBlank() && expanded(method) == expanded(component)

    internal fun methodPackage(method: String?): String? =
        method?.takeIf { '/' in it }?.substringBefore('/')?.takeIf(String::isNotBlank)

    private fun expanded(method: String): String {
        val pkg = method.substringBefore('/')
        val cls = method.substringAfter('/')
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else method
    }
}
