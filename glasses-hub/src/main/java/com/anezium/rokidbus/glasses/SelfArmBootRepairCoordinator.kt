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
        recordWifiDebt(context)
        val started = RokidBusAccessibilityService.requestRepairWifiEnable { wifiEnabled ->
            handler.post { onBootWifiAutomationFinished(context, wifiEnabled) }
        }
        if (!started) {
            log("Boot repair abandoned trigger=boot reason=service_not_live")
            restoreWifiIfOwed(context, trigger = "boot", repairEnabledWifi = false) {
                repairRunning.set(false)
            }
        }
    }

    private fun onBootWifiAutomationFinished(context: Context, wifiEnabled: Boolean) {
        if (!wifiEnabled) {
            // One attempt per boot: the reason is on record and nothing retries until the next.
            log("Boot repair abandoned trigger=boot reason=wifi_automation_failed")
            // A half-finished automation may still have left the radio on.
            restoreWifiIfOwed(context, trigger = "boot", repairEnabledWifi = false) {
                repairRunning.set(false)
            }
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
        restoreWifiIfOwed(context, trigger = trigger, repairEnabledWifi = true) {
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
            runOwnerArm(context, before, wifiUpAt = null, attempt = 1, onResult = onResult)
            return
        }
        // Owed from here on, whatever the arm finds: the wearer had this radio off.
        recordWifiDebt(context)
        if (SelfArmOwnerRepairPolicy.mayTryBridgeBeforePopup(before)) {
            // The record only says the bridge was armed this boot, not that it still runs. A
            // bridge that brings the radio up is alive and spares the popup; the arm then
            // measures the helpers for real.
            Thread {
                val viaBridge = runCatching { SelfArmCommandBridgeClient.setWifiEnabled(context, true) }
                    .onFailure { logError("Owner repair bridge Wi-Fi call failed", it) }
                    .getOrDefault(false)
                log("Owner repair bridge Wi-Fi enable answered=$viaBridge")
                handler.post {
                    if (viaBridge) {
                        awaitReadinessThenArm(context, before, onResult)
                    } else {
                        startOwnerRepairWifiPopup(context, before, onResult)
                    }
                }
            }.apply {
                name = "RokidNexusRepairBridgeProbe"
                isDaemon = true
                start()
            }
            return
        }
        startOwnerRepairWifiPopup(context, before, onResult)
    }

    private fun startOwnerRepairWifiPopup(
        context: Context,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        onResult: (String) -> Unit,
    ) {
        val started = RokidBusAccessibilityService.requestRepairWifiEnable { wifiEnabled ->
            handler.post {
                if (!wifiEnabled) {
                    log("Owner repair abandoned reason=wifi_automation_failed")
                    abandonOwnerRepair(context, onResult)
                } else {
                    awaitReadinessThenArm(context, before, onResult)
                }
            }
        }
        if (!started) {
            log("Owner repair abandoned reason=service_not_live")
            abandonOwnerRepair(context, onResult)
        }
    }

    /** A half-finished automation may still have left the radio on. */
    private fun abandonOwnerRepair(context: Context, onResult: (String) -> Unit) {
        restoreWifiIfOwed(context, trigger = "owner", repairEnabledWifi = false) {
            repairRunning.set(false)
            onResult(GlassesRepairContract.RESULT_WIFI_UNAVAILABLE)
        }
    }

    /**
     * The radio answering "on" is not the arm's cue: the network and adbd's TLS port come
     * seconds later, and an arm that starts before them only measures their absence.
     */
    private fun awaitReadinessThenArm(
        context: Context,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        onResult: (String) -> Unit,
    ) {
        val wifiUpAt = SystemClock.uptimeMillis()
        Thread {
            val readiness = runCatching {
                SelfArmOwnerRepairPolicy.awaitArmReadiness(
                    networkReady = { SelfArmWirelessAdbController.isWifiNetworkReady(context) },
                    enableWirelessDebugging = {
                        SelfArmWirelessAdbController.ensureEnabledForMaintenance(context)
                    },
                    readTlsPort = SelfArmWirelessAdbController::readWirelessPort,
                    nowMs = SystemClock::uptimeMillis,
                    sleep = Thread::sleep,
                )
            }.onFailure { logError("Owner repair Wi-Fi readiness wait failed", it) }
                .getOrDefault(SelfArmOwnerRepairPolicy.ArmReadiness.NO_NETWORK)
            log(
                "Owner repair Wi-Fi readiness=${readiness.logValue} " +
                    "waitedMs=${SystemClock.uptimeMillis() - wifiUpAt}",
            )
            handler.post { runOwnerArm(context, before, wifiUpAt, attempt = 1, onResult = onResult) }
        }.apply {
            name = "RokidNexusRepairWifiReadiness"
            isDaemon = true
            start()
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
            manualArmLeased = SelfArmOnboardingStore.isManualArmLeased(context),
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

    /** [wifiUpAt] is when this repair brought Wi-Fi up, or null when it found the radio on. */
    private fun runOwnerArm(
        context: Context,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        wifiUpAt: Long?,
        attempt: Int,
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
            handler.post { onOwnerArmFinished(context, before, wifiUpAt, attempt, result, onResult) }
        }
        if (!accepted) {
            // A background arm already holds the single-flight; report its outcome once it lands
            // instead of racing it with a second run. Only an arm on record for this boot counts:
            // no record at all is unknown, not landed, and a run that failed did not land.
            SelfArmController.runWhenIdle {
                handler.post {
                    val landed = SelfArmBridgeLivenessStore.armedBootInstantMillis(context) != null &&
                        !SelfArmBridgeLivenessStore.presumedDead(context) &&
                        !SelfArmBridgeLivenessStore.armFailedSinceArmed(context)
                    // Not landed is not a measurement of ours: that arm's reason is not on record.
                    val result = if (landed) SelfArmWatchdogEnsureResult.READY else null
                    onOwnerArmFinished(context, before, wifiUpAt, attempt, result, onResult)
                }
            }
        }
    }

    private fun onOwnerArmFinished(
        context: Context,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        wifiUpAt: Long?,
        attempt: Int,
        result: SelfArmWatchdogEnsureResult?,
        onResult: (String) -> Unit,
    ) {
        val retry = SelfArmOwnerRepairPolicy.mayRetryArm(
            result = result,
            attempt = attempt,
            msSinceWifiUp = wifiUpAt?.let { SystemClock.uptimeMillis() - it },
            bootstrapComplete = SelfArmLocalAdbBootstrapper.isBootstrapComplete(context),
        )
        if (retry) {
            log("Owner repair arm retry result=${result ?: "not_landed"} nextAttempt=${attempt + 1}")
            handler.postDelayed(
                { runOwnerArm(context, before, wifiUpAt, attempt + 1, onResult) },
                SelfArmOwnerRepairPolicy.UNREACHABLE_RETRY_DELAY_MS,
            )
            return
        }
        finishOwnerArm(
            context = context,
            before = before,
            wifiBroughtUp = wifiUpAt != null,
            result = result ?: SelfArmWatchdogEnsureResult.FAILED,
            onResult = onResult,
        )
    }

    private fun finishOwnerArm(
        context: Context,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        wifiBroughtUp: Boolean,
        result: SelfArmWatchdogEnsureResult,
        onResult: (String) -> Unit,
    ) {
        val armed = result == SelfArmWatchdogEnsureResult.READY
        // A successful arm has just put its own measurement on record, bridge included.
        val bridgeRunning = armed && SelfArmBridgeLivenessStore.armedBridgeRunning(context)
        // Measured by the same arm before it started anything: a record that says alive is no
        // proof the helper was still running when the owner asked.
        val helperWasRunning = if (armed) {
            SelfArmBridgeLivenessStore.armedHelperWasRunning(context)
        } else {
            null
        }
        log(
            "Owner repair arm finished result=$result bridge=$bridgeRunning " +
                "helperWasRunning=${helperWasRunning ?: "unknown"} wifiWasOff=$wifiBroughtUp",
        )
        val complete = {
            finishOwnerRepair(
                context = context,
                armed = armed,
                bridgeRunning = bridgeRunning,
                before = before,
                helperRunningBeforeArm = helperWasRunning,
                // Read after the arm: a refused credential may have just been dropped.
                requestPairing = !armed && !SelfArmLocalAdbBootstrapper.isBootstrapComplete(context),
                sessionUnreachable = result == SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE,
                onResult = onResult,
            )
        }
        // Restore before answering: released too early, a second "repair now" could open the
        // popup while this thread is still turning the radio off under it. Owed whether or not
        // the arm worked; a debt an earlier repair could not settle is settled here too.
        restoreWifiIfOwed(context, trigger = "owner", repairEnabledWifi = wifiBroughtUp, onDone = complete)
    }

    private fun finishOwnerRepair(
        context: Context,
        armed: Boolean,
        bridgeRunning: Boolean,
        before: SelfArmOwnerRepairPolicy.HelperRecord,
        requestPairing: Boolean,
        onResult: (String) -> Unit,
        helperRunningBeforeArm: Boolean? = null,
        sessionUnreachable: Boolean = false,
    ) {
        val pairing = if (requestPairing) {
            SelfArmLocalAdbBootstrapper.requestSelfPairing(context, owner = true)
        } else {
            null
        }
        val verdict = SelfArmOwnerRepairPolicy.verdict(
            armed = armed,
            bridgeRunning = bridgeRunning,
            before = before,
            pairing = pairing,
            helperRunningBeforeArm = helperRunningBeforeArm,
            sessionUnreachable = sessionUnreachable,
        )
        log("Owner repair verdict=$verdict pairing=${pairing?.logValue ?: "not_requested"}")
        repairRunning.set(false)
        onResult(verdict)
    }

    private fun recordWifiDebt(context: Context) {
        if (!SelfArmRepairWifiObligationStore.recordBeforeEnable(context)) {
            // This run still restores from memory; only a hub death before then would forget it.
            log("Repair Wi-Fi debt record failed")
        }
    }

    /**
     * Settles the Wi-Fi debt on every way a repair ends. [repairEnabledWifi] covers this run even
     * if its durable record failed; the record covers a run that died before getting here.
     */
    private fun restoreWifiIfOwed(
        context: Context,
        trigger: String,
        repairEnabledWifi: Boolean,
        onDone: () -> Unit,
    ) {
        val action = SelfArmBootRepairPolicy.restoreWifi(
            repairOwesWifiOff = repairEnabledWifi || SelfArmRepairWifiObligationStore.isOwed(context),
            wifiEnabledNow = SelfArmWirelessAdbController.isWifiEnabled(context),
            wifiHubOwned = GlassesHub.isWifiHubOwned(),
            setupSessionActive = SelfArmOnboardingStore.currentActiveSessionId(context).isNotBlank(),
            mediaSyncSessionActive = MediaSyncEngine.isSessionActive(),
            cameraSessionActive = GlassesHub.isCameraSessionActive(),
        )
        when (action) {
            SelfArmBootRepairPolicy.WifiRestore.NOT_OWED -> {
                onDone()
                return
            }
            SelfArmBootRepairPolicy.WifiRestore.SETTLED -> {
                SelfArmRepairWifiObligationStore.clear(context)
                log("Repair Wi-Fi restore trigger=$trigger result=already_off")
                onDone()
                return
            }
            SelfArmBootRepairPolicy.WifiRestore.DEFERRED -> {
                // Left on record: the hub's reconciliation settles it once that owner lets go.
                if (repairEnabledWifi) SelfArmRepairWifiObligationStore.recordBeforeEnable(context)
                log("Repair Wi-Fi restore deferred trigger=$trigger owedKept=true")
                onDone()
                return
            }
            SelfArmBootRepairPolicy.WifiRestore.DISABLE -> Unit
        }
        Thread {
            // The bridge first: it is the quiet way back. Its fallback still never drives
            // Settings — a radio that refuses to go down stays up, its debt kept on record.
            val viaBridge = runCatching { SelfArmCommandBridgeClient.setWifiEnabled(context, false) }
                .onFailure { logError("Repair Wi-Fi restore bridge call failed", it) }
                .getOrDefault(false)
            val applied = viaBridge || SelfArmController.setWifiEnabled(context, false)
            val observedOff = applied && awaitWifiOff(context)
            if (observedOff) {
                SelfArmRepairWifiObligationStore.clear(context)
            } else {
                SelfArmRepairWifiObligationStore.recordBeforeEnable(context)
            }
            log(
                "Repair Wi-Fi restore trigger=$trigger applied=$applied viaBridge=$viaBridge " +
                    "observedOff=$observedOff owedKept=${!observedOff}",
            )
            handler.post(onDone)
        }.apply {
            name = "RokidNexusRepairWifiRestore"
            isDaemon = true
            start()
        }
    }

    private fun awaitWifiOff(context: Context): Boolean {
        val deadline = SystemClock.uptimeMillis() + WIFI_OFF_OBSERVE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (!SelfArmWirelessAdbController.isWifiEnabled(context)) return true
            try {
                Thread.sleep(WIFI_OFF_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !SelfArmWirelessAdbController.isWifiEnabled(context)
    }

    private fun isDisplayInteractive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private const val WIFI_OFF_OBSERVE_TIMEOUT_MS = 5_000L
    private const val WIFI_OFF_POLL_MS = 200L
}
