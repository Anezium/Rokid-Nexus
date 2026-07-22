package com.anezium.rokidbus.glasses

import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Performs only the six rapid Build-number activations requested by the manual setup UI. */
internal class SelfArmDeveloperOptionsEnabler(
    private val service: RokidBusAccessibilityService,
    private val handler: Handler,
) {
    private var active = false
    private var successfulTaps = 0
    private var activationChecks = 0
    private var deadlineAt = 0L
    private val stepRunnable = Runnable(::step)

    fun start(): Boolean {
        stop()
        active = true
        successfulTaps = 0
        activationChecks = 0
        deadlineAt = SystemClock.uptimeMillis() + TIMEOUT_MS
        if (!SelfArmManualSettingsLauncher.open(
                service.applicationContext,
                SelfArmManualTarget.ENABLE_DEVELOPER_OPTIONS,
            )
        ) {
            active = false
            return false
        }
        schedule(INITIAL_SCREEN_DELAY_MS)
        return true
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!active || event == null) return
        schedule(EVENT_SETTLE_MS)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(stepRunnable)
    }

    private fun step() {
        if (!active) return
        if (SystemClock.uptimeMillis() > deadlineAt) {
            finish(false)
            return
        }
        val root = AccessibilityWindowRoots.getNavigationRoot(service)
        if (root == null) {
            schedule(RETRY_DELAY_MS)
            return
        }
        if (successfulTaps >= SelfArmDeveloperOptionsTapPolicy.REQUIRED_TAPS) {
            if (SelfArmWirelessAdbController.areDeveloperOptionsUsable(service)) {
                finish(true)
                return
            }
            if (successfulTaps >= SelfArmDeveloperOptionsTapPolicy.MAX_TAPS) {
                finish(false)
                return
            }
            if (activationChecks < ACTIVATION_CHECKS_BEFORE_COMPATIBILITY_TAP) {
                activationChecks++
                schedule(ACTIVATION_CHECK_DELAY_MS)
                return
            }
        }
        val target = findBuildNumber(root)
        if (target == null || !clickNode(target)) {
            schedule(RETRY_DELAY_MS)
            return
        }
        successfulTaps++
        log("Manual developer enable tap=$successfulTaps/${SelfArmDeveloperOptionsTapPolicy.REQUIRED_TAPS}")
        schedule(
            if (successfulTaps >= SelfArmDeveloperOptionsTapPolicy.REQUIRED_TAPS) {
                ACTIVATION_CHECK_DELAY_MS
            } else {
                SelfArmDeveloperOptionsTapPolicy.TAP_INTERVAL_MS
            },
        )
    }

    private fun findBuildNumber(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val clickableByIdentifier = findFirst(root) { node ->
            node !== root &&
                node.isVisibleToUser &&
                node.isClickable &&
                SelfArmSettingsTextMatcher.containsBuildIdentifier(
                    subtreeText(node),
                    Build.DISPLAY.orEmpty(),
                    Build.ID.orEmpty(),
                )
        }
        if (clickableByIdentifier != null) return clickableByIdentifier
        val valueByIdentifier = findFirst(root) { node ->
            node !== root &&
                node.isVisibleToUser &&
                SelfArmSettingsTextMatcher.containsBuildIdentifier(
                    rawText(node),
                    Build.DISPLAY.orEmpty(),
                    Build.ID.orEmpty(),
                )
        }
        if (valueByIdentifier != null) return valueByIdentifier
        return findFirst(root) { node ->
            node !== root &&
                node.isVisibleToUser &&
                SelfArmSettingsTextMatcher.containsAny(
                    rawText(node),
                    "build number",
                    "numero de build",
                    "numero de version",
                    "software version",
                    "numero de compilacion",
                    "numero de compilacao",
                    "build-nummer",
                )
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
        }
        return false
    }

    private fun findFirst(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            findFirst(node.getChild(index), predicate)?.let { return it }
        }
        return null
    }

    private fun subtreeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val parts = ArrayList<String>()
        fun collect(current: AccessibilityNodeInfo?) {
            if (current == null || parts.size >= MAX_SUBTREE_PARTS) return
            rawText(current).takeIf(String::isNotBlank)?.let(parts::add)
            for (index in 0 until current.childCount) collect(current.getChild(index))
        }
        collect(node)
        return parts.joinToString(" ")
    }

    private fun rawText(node: AccessibilityNodeInfo?): String =
        node?.text?.toString()?.trim().orEmpty().ifBlank {
            node?.contentDescription?.toString()?.trim().orEmpty()
        }

    private fun finish(success: Boolean) {
        active = false
        handler.removeCallbacks(stepRunnable)
        log(
            if (success) {
                "Manual developer enable completed taps=$successfulTaps"
            } else {
                "Manual developer enable could not find Build number"
            },
        )
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(stepRunnable)
        handler.postDelayed(stepRunnable, delayMs)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val INITIAL_SCREEN_DELAY_MS = 650L
        const val EVENT_SETTLE_MS = 80L
        const val RETRY_DELAY_MS = 250L
        const val ACTIVATION_CHECK_DELAY_MS = 220L
        const val ACTIVATION_CHECKS_BEFORE_COMPATIBILITY_TAP = 2
        const val MAX_SUBTREE_PARTS = 32
    }
}

internal object SelfArmDeveloperOptionsTapPolicy {
    const val REQUIRED_TAPS = 6
    const val MAX_TAPS = 7
    const val TAP_INTERVAL_MS = 140L

    fun isCompatibilityTap(successfulTaps: Int): Boolean = successfulTaps >= REQUIRED_TAPS
}
