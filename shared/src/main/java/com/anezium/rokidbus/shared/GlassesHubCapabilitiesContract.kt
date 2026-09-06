package com.anezium.rokidbus.shared

import com.anezium.rokidbus.ink.InkWire
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class GlassesHubCapabilities(
    val protocolVersion: Int,
    val features: Int,
    val imageSurfaceVersion: Int,
    val pinSurfaceVersion: Int,
    val noticeSurfaceVersion: Int = 0,
    val activitySurfaceVersion: Int = 0,
    val inkSurfaceVersion: Int = 0,
    val editableSurfaceVersion: Int = 0,
    val maxImageBytes: Int,
    val versionName: String?,
    val setupComplete: Boolean = false,
    val setupFailureState: String = "",
    val setupFailureDiagnostic: String = "",
    val setupSessionId: String = "",
    val setupStage: String = "",
    val setupRunning: Boolean = false,
    val setupRequiresUserAction: Boolean = false,
    val setupSupportCode: String = "",
    val setupCompletionMode: String = "",
    val coreReady: Boolean = false,
    val maintenanceReady: Boolean = false,
    val ttsVersion: Int = 0,
)

/** Additive glasses-to-phone hub capabilities payload. Unknown fields remain ignorable. */
object GlassesHubCapabilitiesContract {
    const val VERSION = 1
    const val MAX_VERSION_NAME_CHARS = 80
    const val MAX_SETUP_FAILURE_STATE_CHARS = 96
    const val MAX_SETUP_FAILURE_DIAGNOSTIC_CHARS = 96
    const val MAX_SETUP_SESSION_ID_CHARS = 32
    const val MAX_SETUP_SUPPORT_CODE_CHARS = 12

    fun create(
        features: Int,
        imageSurfaceVersion: Int,
        pinSurfaceVersion: Int = 0,
        noticeSurfaceVersion: Int = 0,
        activitySurfaceVersion: Int = 0,
        inkSurfaceVersion: Int = 0,
        editableSurfaceVersion: Int = 0,
        maxImageBytes: Int,
        versionName: String?,
        setupComplete: Boolean = false,
        setupFailureState: String = "",
        setupFailureDiagnostic: String = "",
        setupSessionId: String = "",
        setupStage: String = "",
        setupRunning: Boolean = false,
        setupRequiresUserAction: Boolean = false,
        setupSupportCode: String = "",
        setupCompletionMode: String = "",
        coreReady: Boolean = false,
        maintenanceReady: Boolean = false,
        ttsVersion: Int = 0,
    ): GlassesHubCapabilities = GlassesHubCapabilities(
        protocolVersion = VERSION,
        features = features,
        imageSurfaceVersion = imageSurfaceVersion,
        pinSurfaceVersion = pinSurfaceVersion,
        noticeSurfaceVersion = noticeSurfaceVersion,
        activitySurfaceVersion = activitySurfaceVersion,
        inkSurfaceVersion = inkSurfaceVersion,
        editableSurfaceVersion = editableSurfaceVersion,
        maxImageBytes = maxImageBytes,
        versionName = normalizeVersionName(versionName),
        setupComplete = setupComplete,
        setupFailureState = normalizeFailureState(setupFailureState),
        setupFailureDiagnostic = normalizeFailureDiagnostic(setupFailureDiagnostic),
        setupSessionId = normalizeSessionId(setupSessionId),
        setupStage = SetupStage.normalize(setupStage),
        setupRunning = setupRunning,
        setupRequiresUserAction = setupRequiresUserAction,
        setupSupportCode = normalizeSupportCode(setupSupportCode),
        setupCompletionMode = SetupCompletionMode.normalize(setupCompletionMode),
        coreReady = coreReady,
        maintenanceReady = maintenanceReady,
        ttsVersion = ttsVersion,
    )

    fun toJson(capabilities: GlassesHubCapabilities): JSONObject = JSONObject()
        .put("version", capabilities.protocolVersion)
        .put("features", capabilities.features)
        .put("imageSurfaceVersion", capabilities.imageSurfaceVersion)
        .put("pinSurfaceVersion", capabilities.pinSurfaceVersion)
        .put("noticeSurfaceVersion", capabilities.noticeSurfaceVersion)
        .put("activitySurfaceVersion", capabilities.activitySurfaceVersion)
        .put("inkSurfaceVersion", capabilities.inkSurfaceVersion)
        .put("editableSurfaceVersion", capabilities.editableSurfaceVersion)
        .put("maxImageBytes", capabilities.maxImageBytes)
        .put("setupComplete", capabilities.setupComplete)
        .put("setupFailureState", capabilities.setupFailureState)
        .put("setupFailureDiagnostic", capabilities.setupFailureDiagnostic)
        .put("setupSessionId", normalizeSessionId(capabilities.setupSessionId))
        .put("setupStage", SetupStage.normalize(capabilities.setupStage))
        .put("setupRunning", capabilities.setupRunning)
        .put("setupRequiresUserAction", capabilities.setupRequiresUserAction)
        .put("setupSupportCode", normalizeSupportCode(capabilities.setupSupportCode))
        .put(
            "setupCompletionMode",
            SetupCompletionMode.normalize(capabilities.setupCompletionMode),
        )
        .put("coreReady", capabilities.coreReady)
        .put("maintenanceReady", capabilities.maintenanceReady)
        .put("ttsVersion", capabilities.ttsVersion)
        .also { payload ->
            capabilities.versionName?.let { payload.put("versionName", it) }
        }

    fun parse(payload: JSONObject): GlassesHubCapabilities = GlassesHubCapabilities(
        protocolVersion = payload.optInt("version", 0),
        features = payload.optInt("features", 0),
        imageSurfaceVersion = payload.optInt("imageSurfaceVersion", 0),
        pinSurfaceVersion = payload.optInt("pinSurfaceVersion", 0),
        noticeSurfaceVersion = payload.optInt("noticeSurfaceVersion", 0),
        activitySurfaceVersion = payload.optInt("activitySurfaceVersion", 0),
        inkSurfaceVersion = payload.optInt("inkSurfaceVersion", 0),
        editableSurfaceVersion = payload.optInt("editableSurfaceVersion", 0),
        maxImageBytes = payload.optInt("maxImageBytes", 0),
        versionName = normalizeVersionName(payload.optString("versionName", "")),
        setupComplete = payload.optBoolean("setupComplete", false),
        setupFailureState = normalizeFailureState(payload.optString("setupFailureState", "")),
        setupFailureDiagnostic = normalizeFailureDiagnostic(
            payload.optString("setupFailureDiagnostic", ""),
        ),
        setupSessionId = normalizeSessionId(payload.optString("setupSessionId", "")),
        setupStage = SetupStage.normalize(payload.optString("setupStage", "")),
        setupRunning = payload.optBoolean("setupRunning", false),
        setupRequiresUserAction = payload.optBoolean("setupRequiresUserAction", false),
        setupSupportCode = normalizeSupportCode(payload.optString("setupSupportCode", "")),
        setupCompletionMode = SetupCompletionMode.normalize(
            payload.optString("setupCompletionMode", ""),
        ),
        coreReady = payload.optBoolean("coreReady", false),
        maintenanceReady = payload.optBoolean("maintenanceReady", false),
        ttsVersion = payload.optInt("ttsVersion", 0),
    )

    fun supportsInkSurface(capabilities: GlassesHubCapabilities): Boolean =
        capabilities.protocolVersion == VERSION &&
            capabilities.features and BusCapabilityBits.INK_SURFACE != 0 &&
            capabilities.inkSurfaceVersion == InkWire.VERSION

    fun supportsEditableSurface(capabilities: GlassesHubCapabilities): Boolean =
        capabilities.protocolVersion == VERSION &&
            capabilities.features and BusCapabilityBits.EDITABLE_SURFACE != 0 &&
            capabilities.editableSurfaceVersion == EditableSurfaceContract.VERSION

    fun effectiveStage(capabilities: GlassesHubCapabilities): String =
        SetupStage.normalize(capabilities.setupStage).ifBlank {
            when {
                capabilities.setupComplete -> SetupStage.COMPLETE
                capabilities.setupFailureState.isNotBlank() -> SetupStage.FAILED
                else -> SetupStage.UNKNOWN
            }
        }

    fun deriveSetupSupportCode(sessionId: String?): String {
        val normalizedSessionId = normalizeSessionId(sessionId)
        if (normalizedSessionId.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(normalizedSessionId.toByteArray(Charsets.UTF_8))
            .take(4)
            .joinToString("") { byte -> "%02X".format(Locale.ROOT, byte.toInt() and 0xff) }
    }

    private fun normalizeVersionName(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_NAME_CHARS }

    private fun normalizeFailureState(value: String?): String = value
        .orEmpty()
        .trim()
        .take(MAX_SETUP_FAILURE_STATE_CHARS)

    /** Defense in depth for data that a phone UI may display or persist later. */
    private fun normalizeFailureDiagnostic(value: String?): String = redactSensitiveSetupText(value)
        .replace(DIAGNOSTIC_WHITESPACE, " ")
        .trim()
        .take(MAX_SETUP_FAILURE_DIAGNOSTIC_CHARS)

    private fun normalizeSessionId(value: String?): String = value
        .orEmpty()
        .trim()
        .takeIf { SESSION_ID.matches(it) }
        .orEmpty()

    private fun normalizeSupportCode(value: String?): String = redactSensitiveSetupText(value)
        .trim()
        .uppercase(Locale.ROOT)
        .takeIf { it.length <= MAX_SETUP_SUPPORT_CODE_CHARS && SUPPORT_CODE.matches(it) }
        .orEmpty()

    private fun redactSensitiveSetupText(value: String?): String = value
        .orEmpty()
        .replace(STANDALONE_PAIRING_CODE, "......")
        .replace(IPV4_LITERAL, "")

    private val STANDALONE_PAIRING_CODE = Regex("""\b\d{6}\b""")
    private val IPV4_LITERAL = Regex("""\d+\.\d+\.\d+\.\d+""")
    private val DIAGNOSTIC_WHITESPACE = Regex("""\s+""")
    private val SESSION_ID = Regex("""[0-9a-f]{1,$MAX_SETUP_SESSION_ID_CHARS}""")
    private val SUPPORT_CODE = Regex("""[A-Z0-9-]+""")
}
