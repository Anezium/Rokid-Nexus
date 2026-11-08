package com.anezium.rokidbus.phone

import android.content.Context
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ComposerToolContext
import com.anezium.rokidbus.shared.ComposerToolContract
import com.anezium.rokidbus.shared.EditableSurfaceContract
import com.anezium.rokidbus.shared.GlyphContract
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.ForegroundSurfacePathPolicy
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import org.json.JSONArray
import org.json.JSONObject

class PhonePluginRegistry(
    context: Context,
    private val sendEnvelope: (BusEnvelope) -> String?,
    private val logger: (String) -> Unit,
    private val catalogProvider: (() -> PluginCatalog)? = null,
    private val externalController: ExternalPluginController? = null,
    private val journal: PluginBusJournal? = null,
    glyphReader: ((PhonePluginPrincipal) -> List<GlyphContract.CustomGlyph>)? = null,
    /** The page version an approved entry provides; 0 for plugins without pages. */
    private val pageVersion: (PluginCatalogEntry) -> Int = { it.principal?.descriptor?.pageSessionVersion ?: 0 },
    /**
     * Whether the glasses list a page provider that declares no full app (bundle v7). Older
     * glasses read every entry as launchable, so such an entry is withheld from them.
     */
    private val pagesOnlyEntries: () -> Boolean = { false },
) {
    private val glyphReader = glyphReader ?: PluginGlyphReader(context, logger)::read

    fun handleRemote(envelope: BusEnvelope): Boolean {
        when (envelope.path) {
            BusPaths.LAUNCHER_OPEN -> {
                val pluginId = envelope.payload.optString("pluginId")
                    .ifBlank { envelope.payload.optString("id") }
                // A composer tool's open names the page it came from; a context that is malformed or
                // names another provider's page refuses the whole open rather than opening blind.
                val hasContext = envelope.payload.has(ComposerToolContract.FIELD_CONTEXT)
                val context = ComposerToolContract.parse(envelope.payload.opt(ComposerToolContract.FIELD_CONTEXT))
                    ?.takeIf { pluginId.isNotBlank() && it.isOwnedBy(pluginId) }
                val badContext = hasContext && context == null
                val opened = !badContext && open(pluginId, context)
                recordRemote(
                    pluginId = pluginId.ifBlank { null },
                    category = PluginBusJournal.Category.LAUNCHER,
                    path = envelope.path,
                    verdict = if (opened) PluginBusJournal.Verdict.OK else PluginBusJournal.Verdict.REJECTED,
                    reason = when {
                        opened -> null
                        pluginId.isBlank() -> "MISSING_PLUGIN_ID"
                        badContext -> "BAD_CONTEXT"
                        else -> "OPEN_FAILED"
                    },
                )
                return opened
            }
            BusPaths.SURFACE_INPUT -> {
                handleSurfaceInput(envelope.payload)
                return true
            }
            BusPaths.SURFACE_TEXT_COMMITTED -> {
                handleSurfaceTextCommitted(envelope.payload)
                return true
            }
        }

        return false
    }

    /**
     * Gate for surfaces pushed by external plugins on their own initiative: only the
     * foreground plugin may draw on the HUD. On an idle HUD a show adopts the sender
     * as the foreground plugin; anything pushed while another plugin holds the HUD is
     * rejected so a background auto-open (lyrics) cannot steal the display.
     */
    fun allowExternalSurface(principal: PhonePluginPrincipal, path: String): Boolean {
        val controller = externalController ?: return true
        val externalActive = controller.activeId()
        if (externalActive == principal.descriptor.id) return true
        if (externalActive != null) return false
        if (!ForegroundSurfacePathPolicy.isShow(path)) return false
        return controller.adopt(principal)
    }

    /**
     * The Applications rows the glasses list: approved plugins that launch, plus page providers
     * without a full app when [includePagesOnly]. The launcher list and the favourites picker
     * both read this, so the owner can only pin what the glasses can show.
     */
    fun launcherApplications(includePagesOnly: Boolean = pagesOnlyEntries()): List<PhoneLauncherApplication> =
        catalog().entries.mapNotNull { entry ->
            if (entry.state != PluginCatalogState.ENABLED) return@mapNotNull null
            val id = entry.id ?: return@mapNotNull null
            val pages = pageVersion(entry)
            // An enabled provider with `LAUNCHABLE=false` is an Applications entry whose row opens its pages.
            if (!entry.launchable && !(includePagesOnly && pages > 0)) return@mapNotNull null
            PhoneLauncherApplication(
                id = id,
                displayName = entry.displayName,
                launchable = entry.launchable,
                pageVersion = pages,
                iconKey = entry.iconKey,
                packageName = entry.principal?.packageName,
                iconDrawableResId = entry.iconDrawableResId,
            )
        }

    fun syncLauncherList() {
        val catalog = catalog()
        send(
            BusPaths.LAUNCHER_LIST,
            JSONObject().put(
                "plugins",
                JSONArray().also { array ->
                    launcherApplications().forEach { entry ->
                        array.put(
                            JSONObject()
                                .put("id", entry.id)
                                .put("displayName", entry.displayName)
                                .apply { entry.iconKey?.let { put("iconKey", it) } }
                                .apply {
                                    // Absent means no pages, which is what an old glasses hub assumes anyway.
                                    entry.pageVersion.takeIf { it > 0 }?.let {
                                        put(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION, it)
                                    }
                                    if (!entry.launchable) put(FIELD_LAUNCHABLE, false)
                                },
                        )
                    }
                },
            ),
        )
        catalog.entries.forEach { entry ->
            val principal = entry.principal
                ?.takeIf { entry.state == PluginCatalogState.ENABLED }
                ?: return@forEach
            val glyphs = glyphReader(principal)
            if (glyphs.isEmpty()) return@forEach
            send(
                BusPaths.LAUNCHER_GLYPHS,
                JSONObject()
                    .put("pluginId", principal.descriptor.id)
                    .put(
                        "glyphs",
                        JSONArray().also { array ->
                            glyphs.forEach { glyph ->
                                array.put(
                                    JSONObject()
                                        .put("name", glyph.name)
                                        .put("pathData", glyph.pathData),
                                )
                            }
                        },
                    ),
            )
        }
    }

    fun close() {
        externalController?.closeAll("hub_destroyed")
    }

    fun openFromPhone(pluginId: String): Boolean = open(pluginId)

    private fun open(pluginId: String, context: ComposerToolContext? = null): Boolean {
        val entry = catalog().entry(pluginId)?.takeIf { it.launchable } ?: return false
        val external = entry.principal ?: return false
        val request = if (context != null) {
            ExternalPluginOpenRequest(type = PluginOpenTypes.COMPOSER_TOOL, context = ComposerToolContract.encode(context))
        } else {
            ExternalPluginOpenRequest()
        }
        return externalController?.open(external, request) == true
    }

    private fun handleSurfaceTextCommitted(payload: JSONObject) {
        val committed = EditableSurfaceContract.parseCommitted(payload) ?: return
        // Neither an explicit `ownerPluginId` nor a "plugin:local" surfaceId is
        // actually threaded onto the wire for an ordinary (non-Ink) external
        // card today (see withExternalSurfaceMetadata) — only Ink surfaces get
        // that. An editable card can only ever be the one foreground external
        // surface, so whoever currently holds that slot is unambiguously its
        // owner.
        val ownerPluginId = payload.optString("ownerPluginId")
            .ifBlank {
                committed.surfaceId.substringBefore(':')
                    .takeIf { ':' in committed.surfaceId }
                    .orEmpty()
            }
            .ifBlank { externalController?.activeId().orEmpty() }
        logger(
            "surface text-committed owner=$ownerPluginId surfaceId=${committed.surfaceId} " +
                "cancelled=${committed.cancelled} textLen=${committed.text.length} " +
                "activeId=${externalController?.activeId()}",
        )
        if (ownerPluginId.isBlank()) return
        val delivered = externalController?.textCommitted(
            ownerPluginId,
            committed.surfaceId,
            committed.text,
            committed.cancelled,
        ) == true
        logger("surface text-committed delivered=$delivered")
        recordRemote(
            pluginId = ownerPluginId,
            category = PluginBusJournal.Category.INPUT,
            path = BusPaths.SURFACE_TEXT_COMMITTED,
            verdict = if (delivered) PluginBusJournal.Verdict.OK else PluginBusJournal.Verdict.REJECTED,
            reason = if (delivered) null else "NO_ACTIVE_PLUGIN",
        )
    }

    private fun handleSurfaceInput(payload: JSONObject) {
        val surfaceId = payload.optString("surfaceId")
        val keyCode = payload.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN)
        val action = payload.optInt("action", KeyEvent.ACTION_DOWN)
        val ownerPluginId = payload.optString("ownerPluginId")
            .ifBlank { surfaceId.substringBefore(':').takeIf { ':' in surfaceId }.orEmpty() }
        val localSurfaceId = payload.optString("localSurfaceId")
            .ifBlank { surfaceId.substringAfter(':', surfaceId) }
        if (ownerPluginId.isNotBlank() &&
            externalController?.input(ownerPluginId, localSurfaceId, keyCode, action) == true
        ) {
            recordRemote(
                pluginId = ownerPluginId,
                category = PluginBusJournal.Category.INPUT,
                path = BusPaths.SURFACE_INPUT,
                verdict = PluginBusJournal.Verdict.OK,
            )
            return
        }
        recordRemote(
            pluginId = ownerPluginId.ifBlank { null },
            category = PluginBusJournal.Category.INPUT,
            path = BusPaths.SURFACE_INPUT,
            verdict = PluginBusJournal.Verdict.REJECTED,
            reason = "NO_ACTIVE_PLUGIN",
        )
    }

    private fun recordRemote(
        pluginId: String?,
        category: PluginBusJournal.Category,
        path: String,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    ) {
        val target = journal ?: return
        if (!target.enabled.get()) return
        try {
            target.record(
                pluginId = pluginId,
                category = category,
                direction = PluginBusJournal.Direction.GLASSES_TO_HUB,
                path = path,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect plugin dispatch.
        }
    }

    fun catalog(): PluginCatalog = catalogProvider?.invoke()
        ?: PluginCatalog.build(emptyList()) { PluginGrantState.Pending }

    private fun send(path: String, payload: JSONObject) {
        val error = sendEnvelope(BusEnvelope(path = path, payload = payload))
        if (error != null) logger("plugin send failed path=$path code=$error")
    }

    companion object {
        /** Absent means launchable; false marks a pages-only Applications entry (bundle v7). */
        const val FIELD_LAUNCHABLE = "launchable"
    }
}
