package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AgentsSettingsActivity : Activity() {
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var agentdEnabled: Switch
    private lateinit var agentdConnection: TextView
    private lateinit var agentdDot: View
    private lateinit var batteryStatus: TextView
    private lateinit var batteryDot: View
    private lateinit var batteryGrant: Button
    private lateinit var batteryAppInfo: Button
    private lateinit var batteryHint: TextView
    private lateinit var computersList: LinearLayout

    /** The machine whose row the wearer tapped open to reach its Forget. */
    private var expandedMachineId: String? = null
    private var liveLink: LinkMachine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadConfig()
        observeState()
        if (configStore.load().shouldMonitor) {
            AgentsMonitorService.reconcile(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        renderComputers()
        renderBatteryState()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        agentdEnabled = NexusUi.switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                configStore.saveAgentd(configStore.load().agentd, checked)
                AgentsMonitorService.reconcile(applicationContext)
            }
        }
        agentdDot = NexusUi.dot(this)
        agentdConnection = NexusUi.statusLine(this).apply { text = "DISCONNECTED" }
        batteryDot = NexusUi.dot(this)
        batteryStatus = NexusUi.statusLine(this)
        batteryGrant = NexusUi.textButton(this, "Let Agents keep running").apply {
            setOnClickListener {
                // Re-arm before leaving: the wearer is about to change something,
                // and the question worth answering next is whether it held, not
                // that it was once broken.
                configStore.clearMonitorSuspension()
                openBatteryExemptionRequest(this@AgentsSettingsActivity)
            }
        }
        // The phone's own battery screen is where some ROMs keep the decision
        // that actually binds. Its high-power manager cannot be opened directly
        // — it is guarded by a system permission — so App info is the nearest
        // door a plugin is allowed to open.
        batteryAppInfo = NexusUi.textButton(this, "Open App info").apply {
            setOnClickListener {
                configStore.clearMonitorSuspension()
                openAppInfo(this@AgentsSettingsActivity)
            }
        }
        batteryHint = NexusUi.cardBody(this, "")
        computersList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AgentsSettingsActivity,
                    "Mission control for Claude Code, Codex, and OpenClaw sessions. " +
                        "Agents never notifies this phone — when a session needs you, " +
                        "it says so on your glasses.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AgentsSettingsActivity, 18))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Monitoring"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(monitoringCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 22))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Computers"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(computersCard(), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 24))
            addView(NexusUi.sectionRow(this@AgentsSettingsActivity, "Plugin"), NexusUi.block())
            addView(BusTheme.gap(this@AgentsSettingsActivity, 10))
            addView(
                NexusUi.uninstallCard(this@AgentsSettingsActivity, "Agents") {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                },
                NexusUi.block(),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@AgentsSettingsActivity,
                    NexusPluginIcons.drawableFor("terminal"),
                    "Agents",
                    "Coding-agent mission control · v${BuildConfig.VERSION_NAME}",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@AgentsSettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun monitoringCard() = NexusUi.card(this).apply {
        addView(
            NexusUi.switchRow(
                this@AgentsSettingsActivity,
                "Monitor sessions",
                "Claude Code, Codex, and OpenClaw",
                agentdEnabled,
            ),
            NexusUi.block(),
        )
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(connectionRow(this@AgentsSettingsActivity, agentdDot, agentdConnection), NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 12))
        addView(connectionRow(this@AgentsSettingsActivity, batteryDot, batteryStatus), NexusUi.block())
        addView(BusTheme.gap(this@AgentsSettingsActivity, 8))
        addView(batteryGrant, NexusUi.block())
        addView(batteryAppInfo, NexusUi.block())
        addView(batteryHint, NexusUi.block())
    }

    private fun computersCard() = NexusUi.card(this).apply {
        addView(computersList, NexusUi.block())
        addView(NexusUi.divider(this@AgentsSettingsActivity))
        addView(
            NexusUi.rowTitle(this@AgentsSettingsActivity, "+ Add a computer").apply {
                setTextColor(NexusUi.GREEN)
                setPadding(0, NexusUi.dp(this@AgentsSettingsActivity, 10), 0, NexusUi.dp(this@AgentsSettingsActivity, 4))
                setOnClickListener {
                    startActivity(Intent(this@AgentsSettingsActivity, AddComputerActivity::class.java))
                }
            },
            NexusUi.block(),
        )
    }

    /**
     * One row per computer this phone trusts. Tapping a row trades its chevron
     * for Forget, so removing a machine takes two deliberate taps and a
     * misplaced one costs nothing.
     */
    private fun renderComputers() {
        if (!::computersList.isInitialized) return
        computersList.removeAllViews()
        val machines = configStore.trustedMachines()
        val paired = configStore.load().agentd
        if (machines.isEmpty() && paired == null) {
            computersList.addView(
                NexusUi.rowSub(this, "No computer linked yet — add one below"),
                NexusUi.block(),
            )
            return
        }
        machines.forEachIndexed { index, machine ->
            if (index > 0) computersList.addView(BusTheme.gap(this, 4))
            val connected = liveLink?.machineId == machine.machineId
            val sub = when {
                connected && liveLink?.overTailnet == true -> "Connected · over Tailscale"
                connected -> "Connected · same Wi-Fi"
                else -> lastSeenText(machine.lastSeenAtMs)
            }
            val projects = configStore.projects(machine.machineId).size
            computersList.addView(
                machineNavRow(
                    title = machine.name,
                    sub = if (projects > 0) {
                        "$sub · $projects ${if (projects == 1) "project" else "projects"}"
                    } else {
                        sub
                    },
                    dotColor = if (connected) NexusUi.GREEN else NexusUi.INK3,
                ) {
                    startActivity(
                        Intent(this, ComputerActivity::class.java)
                            .putExtra(ComputerActivity.EXTRA_MACHINE_ID, machine.machineId)
                            .putExtra(ComputerActivity.EXTRA_MACHINE_NAME, machine.name),
                    )
                },
                NexusUi.block(),
            )
        }
        if (paired != null) {
            if (machines.isNotEmpty()) computersList.addView(BusTheme.gap(this, 4))
            computersList.addView(
                machineRow(
                    title = paired.name,
                    sub = "Paired by hand · ${paired.host}:${paired.port}",
                    dotColor = NexusUi.INK3,
                    expanded = expandedMachineId == PAIRED_ROW_ID,
                    onToggle = {
                        expandedMachineId = if (expandedMachineId == PAIRED_ROW_ID) null else PAIRED_ROW_ID
                        renderComputers()
                    },
                    onForget = {
                        configStore.saveAgentd(null, enabled = agentdEnabled.isChecked)
                        AgentsMonitorService.reconcile(applicationContext)
                        expandedMachineId = null
                        renderComputers()
                        toast("${paired.name} forgotten.")
                    },
                ),
                NexusUi.block(),
            )
        }
    }

    private fun machineNavRow(
        title: String,
        sub: String,
        dotColor: Int,
        onOpen: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, NexusUi.dp(this@AgentsSettingsActivity, 6), 0, NexusUi.dp(this@AgentsSettingsActivity, 6))
        setOnClickListener { onOpen() }
        val dot = NexusUi.dot(this@AgentsSettingsActivity)
        NexusUi.setDotColor(dot, dotColor)
        val dotSize = NexusUi.dp(this@AgentsSettingsActivity, 8)
        addView(
            dot,
            LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = NexusUi.dp(this@AgentsSettingsActivity, 10)
            },
        )
        addView(
            LinearLayout(this@AgentsSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@AgentsSettingsActivity, title))
                addView(NexusUi.rowSub(this@AgentsSettingsActivity, sub))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.chevron(this@AgentsSettingsActivity))
    }

    private fun machineRow(
        title: String,
        sub: String,
        dotColor: Int,
        expanded: Boolean,
        onToggle: () -> Unit,
        onForget: () -> Unit,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, NexusUi.dp(this@AgentsSettingsActivity, 6), 0, NexusUi.dp(this@AgentsSettingsActivity, 6))
        setOnClickListener { onToggle() }
        val dot = NexusUi.dot(this@AgentsSettingsActivity)
        NexusUi.setDotColor(dot, dotColor)
        val dotSize = NexusUi.dp(this@AgentsSettingsActivity, 8)
        addView(
            dot,
            LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = NexusUi.dp(this@AgentsSettingsActivity, 10)
            },
        )
        addView(
            LinearLayout(this@AgentsSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@AgentsSettingsActivity, title))
                addView(NexusUi.rowSub(this@AgentsSettingsActivity, sub))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (expanded) {
            addView(
                NexusUi.rowTitle(this@AgentsSettingsActivity, "Forget").apply {
                    setTextColor(NexusUi.DANGER)
                    setPadding(NexusUi.dp(this@AgentsSettingsActivity, 12), 0, 0, 0)
                    setOnClickListener { onForget() }
                },
            )
        } else {
            addView(NexusUi.chevron(this@AgentsSettingsActivity))
        }
    }

    private fun loadConfig() {
        agentdEnabled.isChecked = configStore.load().agentdEnabled
        renderComputers()
        renderBatteryState()
    }

    /**
     * Granted is the quiet case, so the ask disappears once it is answered —
     * the same shape the Tailscale prerequisite uses on the Add a computer
     * screen. There is no callback when the wearer returns from the system
     * dialog, which is why onResume re-reads it.
     */
    private fun renderBatteryState() {
        val granted = batteryExemptionGranted(this)
        // Observation outranks the flag. Some phones keep the real decision in
        // their own power manager and leave the platform's answer at false
        // forever, so a green row on a phone that demonstrably froze this app
        // would be the screen lying about the one thing it is here to report.
        val suspended = configStore.monitorWasSuspended()
        batteryStatus.text = when {
            suspended -> "THIS PHONE HAS FROZEN AGENTS"
            granted -> "ALLOWED TO KEEP RUNNING"
            else -> "ANDROID MAY FREEZE THIS APP"
        }
        NexusUi.setDotColor(
            batteryDot,
            if (granted && !suspended) NexusUi.GREEN else NexusUi.AMBER,
        )
        batteryGrant.visibility = if (granted && !suspended) View.GONE else View.VISIBLE
        batteryAppInfo.visibility = batteryGrant.visibility
        batteryHint.text = if (suspended) {
            "Agents was frozen while monitoring, so sessions went unwatched and a " +
                "computer waiting on you was answered back at its own keyboard. " +
                "Allowing it above may not be enough on this phone: open App info, " +
                "then under battery let it use power in the background. The warning " +
                "clears when you act on it and comes back only if this happens again."
        } else {
            "Agents shows nothing in the phone's shade, so Android may treat it as " +
                "idle and freeze it. Frozen, it stops seeing your sessions and a " +
                "computer waiting on you is answered back at the keyboard instead of " +
                "on your glasses."
        }
    }

    private fun observeState() {
        uiScope.launch {
            AgentsRuntime.store.connections.collectLatest { states ->
                renderMonitoring(states.getValue(AgentProvider.CLAUDE))
            }
        }
        uiScope.launch {
            AgentsRuntime.store.sessions.collectLatest {
                renderMonitoring(AgentsRuntime.store.connections.value.getValue(AgentProvider.CLAUDE))
            }
        }
        uiScope.launch {
            AgentsRuntime.store.linkMachine.collectLatest {
                liveLink = it
                renderComputers()
            }
        }
        uiScope.launch {
            AgentsRuntime.linkedMachines.collect { renderComputers() }
        }
    }

    /**
     * The status answers what the glasses are seeing, not just whether a socket
     * is up: session counts while the link works, the link's own state while it
     * does not.
     */
    private fun renderMonitoring(state: ProviderConnectionState) {
        if (state.state != ConnectionState.CONNECTED) {
            applyConnectionState(agentdDot, agentdConnection, state, authFailure = "LINK REFUSED")
            return
        }
        val sessions = AgentsRuntime.store.sessions.value
            .filter { it.provider in AgentProvider.AGENTD_PROVIDERS }
        val needsYou = sessions.count { it.status == AgentStatus.NEEDS_YOU }
        val running = sessions.count { it.status == AgentStatus.WORKING }
        val idle = sessions.count { it.status == AgentStatus.IDLE }
        val parts = buildList {
            if (needsYou > 0) add(if (needsYou == 1) "1 NEEDS YOU" else "$needsYou NEED YOU")
            add("$running RUNNING")
            add("$idle IDLE")
        }
        agentdConnection.text = "WATCHING · ${parts.joinToString(" · ")}"
        NexusUi.setDotColor(agentdDot, if (needsYou > 0) NexusUi.AMBER else NexusUi.GREEN)
    }

    private fun applyConnectionState(
        dot: View,
        label: TextView,
        state: ProviderConnectionState,
        authFailure: String,
    ) {
        label.text = state.displayText(authFailure)
        NexusUi.setDotColor(
            dot,
            when (state.state) {
                ConnectionState.CONNECTED -> NexusUi.GREEN
                ConnectionState.CONNECTING -> NexusUi.AMBER
                ConnectionState.AUTH_FAILED -> NexusUi.DANGER
                ConnectionState.DISCONNECTED -> NexusUi.INK3
            },
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** List-row id for the hand-paired daemon entry, which has no machineId. */
        const val PAIRED_ROW_ID = "paired-daemon"
    }
}

internal fun ProviderConnectionState.displayText(authFailure: String): String {
    val label = if (state == ConnectionState.AUTH_FAILED) authFailure else state.wireValue.uppercase()
    val visibleDetail = detail?.takeIf {
        it.isNotBlank() && !it.equals(label, ignoreCase = true)
    }
    return visibleDetail?.let { "$label · $it" } ?: label
}
