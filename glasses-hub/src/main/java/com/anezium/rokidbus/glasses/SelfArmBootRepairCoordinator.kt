package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.anezium.rokidbus.shared.GlassesRepairContract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Revives the command bridge on the boots where nothing else can.
 *
 * The background re-arm ([AccessibilityRearmWatcher]) fires on radio edges, and this ROM boots
 * with Wi-Fi off, so after a reboot the edge never comes by itself. The only tool that turns
 * Wi-Fi on without the (dead) bridge is the Settings automation, and that walks across whatever
 * the wearer is looking at — so it runs at most once per boot under the owner's standing consent,
 * plus at most once per explicit "repair now" from the phone. Everything after the radio is up
 * belongs to the existing machinery: the Wi-Fi edge re-arms the bridge in the background, and
 * this class only watches for that arm to land so it can hand the radio back.
 */
internal object SelfArmBootRepairCoordinator {
    /** Not a bridge_liveness: reason — the owner asked, so re-pairing escalation is allowed. */
    internal const val OWNER_REPAIR_REASON = "boot_repair:owner_request"

    private val handler = Handler(Looper.getMainLooper())
    private val repairRunning = AtomicBoolean(false)
    private var bootCheckRunnable: Runnable? = null

    fun onAccessibilityServiceConnected(context: Context) {
        val appContext = context.applicationContext
        bootCheckRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            bootCheckRunnable = null
            maybeRunBootRepair(appContext)
        }
        bootCheckRunnable = runnable
        handler.postDelayed(runnable, SelfArmBootRepairPolicy.CONNECT_SETTLE_DELAY_MS)
    }

    /**
     * The phone's "repair now". Runs the same repair as the boot path, popup included when the
     * radio is down, and always answers [onResult] exactly once with a
     * [GlassesRepairContract] result code. Not throttled by the boot latch: the owner pressed a
     * button and is watching. [GlassesRepairContract.MODE_PAIR] skips the arm and asks the
     * glasses to pair themselves again.
     */
    fun runOwnerRepair(
        context: Context,
        mode: String = GlassesRepairContract.MODE_REPAIR,
        onResult: (String) -> Unit,
    ) {
        val appContext = context.applicationContext
        handler.post { startOwnerRepair(appContext, mode, onResult) }
    }

    internal fun isRepairRunning(): Boolean = repairRunning.get()

    private fun maybeRunBootRepair(context: Context) {
        val blocker = SelfArmBootRepairPolicy.bootAttemptBlocker(
            autoRepairEnabled = SelfArmBootRepairStore.isAutoRepairEnabled(context),
            alreadyAttemptedThisBoot = SelfArmBootRepairStore.hasAttemptedThisBoot(context),
            bridgePresumedDead = SelfArmBridgeLivenessStore.presumedDead(context),
            wifiEnabled = SelfArmWirelessAdbController.isWifiEnabled(context),
            bootstrapComplete = SelfArmLocalAdbBootstrapper.isBootstrapComplete(context),
            setupSessionActive = SelfArmOnboardingStore.currentActiveSessionId(context).isNotBlank(),
            displayInteractive = isDisplayInteractive(context),
        )
        if (blocker != null) {
            log("Boot repair skipped trigger=boot blockedBy=$blocker")
            return
        }
        if (!repairRunning.compareAndSet(false, true)) {
            log("Boot repair skipped trigger=boot blockedBy=repair_already_running")
            return
        }
        // Claimed before the automation starts, not after it succeeds: a failed run is also this
        // boot's one attempt, and a hub restart mid-automation must not buy a second popup.
        SelfArmBootRepairStore.recordBootAttempt(context)
        log("Boot repair starting trigger=boot")
        val started = RokidBusAccessibilityService.requestRepairWifiEnable { wifiEnabled ->
            handler.post { onBootWifiAutomationFinished(context, wifiEnabled) }
        }
        if (!started) {
            repairRunning.set(false)
            log("Boot repair abandoned trigger=boot reason=service_not_live")
        }
    }

    private fun onBootWifiAutomationFinished(context: Context, wifiEnabled: Boolean) {
        if (!wifiEnabled) {
            // One attempt per boot: the reason is on record and nothing retries until the next.
            repairRunning.set(false)
            log("Boot repair abandoned trigger=boot reason=wifi_automation_failed")
            return
        }
        log("Boot repair Wi-Fi enabled trigger=boot; waiting for the background re-arm")
        awaitArmThenRestore(
            context = context,
            trigger = "boot",
            deadlineAt = SystemClock.uptimeMillis() + SelfArmBootRepairPolicy.ARM_WAIT_TIMEOUT_MS,
        )
    }

    /**
     * The arm itself belongs to the Wi-Fi-edge machinery; the observable fact that it landed is
     * the armed boot instant flipping to this boot, which is what recordArmed writes.
     */
    private fun awaitArmThenRestore(context: Context, trigger: String, deadlineAt: Long) {
        val armed = !SelfArmBridgeLivenessStore.presumedDead(context)
        if (!armed && SystemClock.uptimeMillis() < deadlineAt) {
            handler.postDelayed(
                { awaitArmThenRestore(context, trigger, deadlineAt) },
                SelfArmBootRepairPolicy.ARM_POLL_INTERVAL_MS,
            )
            return
        }
        log("Repair arm wait finished trigger=$trigger armed=$armed")
        restoreWifiIfOwed(context, trigger = trigger, bridgeArmed = armed) {
            repairRunning.set(false)
        }
    }

    private fun startOwnerRepair(context: Context, mode: String, onResult: (String) -> Unit) {
        if (!clearSetupSessionForOwner(context)) {
            onResult(GlassesRepairContract.RESULT_BUSY)
            return
        }
        if (!repairRunning.compareAndSet(false, true)) {
            log("Owner repair refused reason=repair_already_running")
            onResult(GlassesRepairContract.RESULT_BUSY)
            return
        }
        val before = SelfArmBridgeLivenessStore.helperRecord(context)
        val bootstrapComplete = SelfArmLocalAdbBootstrapper.isBootstrapComplete(context)
        val wifiWasOff = !SelfArmWirelessAdbController.isWifiEnabled(context)
        log(
            "Owner repair starting mode=$mode helperRecord=$before " +
                "bootstrapComplete=$bootstrapComplete wifiOff=$wifiWasOff",
        )
        if (mode == GlassesRepairContract.MODE_PAIR) {
            finishOwnerRepair(
                context,
                armed = false,
                bridgeRunning = false,
                before = before,
                requestPairing = true,
                onResult = onResult,
            )
            return
        }
        if (!wifiWasOff || !bootstrapComplete) {
            // With the radio up the arm can talk right now. Without a pairing of its own the arm
            // cannot run at all, and the pairing run brings the radio up itself — no popup here.
            runOwnerArm(context, before, wifiWasOff = false, onResult = onResult)
            return
        }
        if (SelfArmOwnerRepairPolicy.mayAnswerHealthyWithoutMeasuring(before)) {
            // Armed with its bridge this boot and nothing has failed since: the popup would only
            // prove what the record already says.
            finishOwnerRepair(
                context,
                armed = true,
                bridgeRunning = true,
                before = before,
                requestPairing = false,
                onResult = onResult,
            )
            return
        }
        val started = RokidBusAccessibilityService.requestRepairWifiEnable { wifiEnabled ->
            handler.post {
                if (!wifiEnabled) {
                    repairRunning.set(false)
                    log("Owner repair abandoned reason=wifi_automation_failed")
                    onResult(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE)
                } else {
                    runOwnerArm(context, before, wifiWasOff = true, onResult = onResult)
                }
            }
        }
        if (!started) {
            repairRunning.set(false)
            log("Owner repair abandoned reason=service_not_live")
            onResult(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE)
        }
    }

    /**
     * False when a run is genuinely in progress. A session nothing is driving any more is
     * dropped instead: answering busy to it forever is how an owner ended up with no way back.
     */
    private fun clearSetupSessionForOwner(context: Context): Boolean {
        val sessionId = SelfArmOnboardingStore.currentActiveSessionId(context)
        val disposition = SelfArmOwnerRepairPolicy.sessionDisposition(
            sessionActive = sessionId.isNotBlank(),
            sessionRunningWithValidLease = SelfArmOnboardingStore.snapshot(context).setupRunning,
            manualArmInProgress = SelfArmOnboardingStore.isManualArmInProgress(context),
        )
        return when (disposition) {
            SelfArmOwnerRepairPolicy.SessionDisposition.PROCEED -> true
            SelfArmOwnerRepairPolicy.SessionDisposition.BUSY -> {
                log("Owner repair refused reason=setup_session_active")
                false
            }
            SelfArmOwnerRepairPolicy.SessionDisposition.SUPERSEDE -> {
                log("Owner repair superseding idle setup session")
                SelfArmOnboardingStore.invalidateSession(context)
                true
            }
        }
    }

    private fun runOwnerArm(
        context: Context,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        wifiWasOff: Boolean,
        onResult: (String) -> Unit,
    ) {
        // Deliberately NOT a background-liveness reason: the owner pressed the button and is
        // watching, so a credential the daemon refuses may escalate to a fresh pairing request —
        // the one escalation SelfArmController.BRIDGE_LIVENESS_REASON_PREFIX forbids to re-arms
        // nobody asked for. restartWatchdog=false keeps the arm a `start`: a bridge that turns
        // out to be healthy is never killed to prove it.
        val accepted = SelfArmController.ensureWatchdog(
            context,
            reason = OWNER_REPAIR_REASON,
            restartWatchdog = false,
        ) { result ->
            handler.post { finishOwnerArm(context, before, wifiWasOff, result, onResult) }
        }
        if (!accepted) {
            // A background arm already holds the single-flight; report its outcome once it lands
            // instead of racing it with a second run. Only an arm on record for this boot counts:
            // no record at all is unknown, not landed.
            SelfArmController.runWhenIdle {
                handler.post {
                    val landed = SelfArmBridgeLivenessStore.armedBootInstantMillis(context) != null &&
                        !SelfArmBridgeLivenessStore.presumedDead(context)
                    val result = if (landed) {
                        SelfArmWatchdogEnsureResult.READY
                    } else {
                        SelfArmWatchdogEnsureResult.FAILED
                    }
                    finishOwnerArm(context, before, wifiWasOff, result, onResult)
                }
            }
        }
    }

    private fun finishOwnerArm(
        context: Context,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        wifiWasOff: Boolean,
        result: SelfArmWatchdogEnsureResult,
        onResult: (String) -> Unit,
    ) {
        val armed = result == SelfArmWatchdogEnsureResult.READY
        // A successful arm has just put its own measurement on record, bridge included.
        val bridgeRunning = armed && SelfArmBridgeLivenessStore.armedBridgeRunning(context)
        log("Owner repair arm finished result=$result bridge=$bridgeRunning wifiWasOff=$wifiWasOff")
        val complete = {
            finishOwnerRepair(
                context = context,
                armed = armed,
                bridgeRunning = bridgeRunning,
                before = before,
                // Read after the arm: a refused credential may have just been dropped.
                requestPairing = !armed && !SelfArmLocalAdbBootstrapper.isBootstrapComplete(context),
                onResult = onResult,
            )
        }
        if (wifiWasOff) {
            // Restore before answering: released too early, a second "repair now" could open the
            // popup while this thread is still turning the radio off under it.
            restoreWifiIfOwed(context, trigger = "owner", bridgeArmed = armed, onDone = complete)
        } else {
            complete()
        }
    }

    private fun finishOwnerRepair(
        context: Context,
        armed: Boolean,
        bridgeRunning: Boolean,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        requestPairing: Boolean,
        onResult: (String) -> Unit,
    ) {
        val pairing = if (requestPairing) {
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = true)
        } else {
            null
        }
        val verdict = SelfArmOwnerRepairPolicy.verdict(armed, bridgeRunning, before, pairing)
        log("Owner repair verdict=$verdict pairing=${pairing?.logValue ?: "not_requested"}")
        repairRunning.set(false)
        onResult(verdict)
    }

    private fun restoreWifiIfOwed(
        context: Context,
        trigger: String,
        bridgeArmed: Boolean,
        onDone: () -> Unit,
    ) {
        val shouldRestore = SelfArmBootRepairPolicy.shouldRestoreWifi(
            wifiWasOffBeforeRepair = true,
            wifiEnabledNow = SelfArmWirelessAdbController.isWifiEnabled(context),
            bridgePresumedDead = !bridgeArmed,
            wifiHubOwned = GlassesHub.isWifiHubOwned(),
            setupSessionActive = SelfArmOnboardingStore.currentActiveSessionId(context).isNotBlank(),
            mediaSyncSessionActive = MediaSyncEngine.isSessionActive(),
            cameraSessionActive = GlassesHub.isCameraSessionActive(),
        )
        if (!shouldRestore) {
            log("Repair Wi-Fi restore skipped trigger=$trigger armed=$bridgeArmed")
            onDone()
            return
        }
        Thread {
            // The bridge first: it is both the proof the repair worked and the quiet way back.
            // Its fallback still never drives Settings — a radio that refuses to go down stays up.
            val viaBridge = runCatching { SelfArmCommandBridgeClient.setWifiEnabled(context, false) }
                .onFailure { logError("Repair Wi-Fi restore bridge call failed", it) }
                .getOrDefault(false)
            val applied = viaBridge || SelfArmController.setWifiEnabled(context, false)
            log("Repair Wi-Fi restore trigger=$trigger applied=$applied viaBridge=$viaBridge")
            handler.post(onDone)
        }.apply {
            name = "RokidNexusRepairWifiRestore"
            isDaemon = true
            start()
        }
    }

    private fun isDisplayInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true
}
