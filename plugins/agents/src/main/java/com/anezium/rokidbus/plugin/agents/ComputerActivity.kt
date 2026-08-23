package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * One linked computer: its state, the project folders anchored on it, and the
 * only place its trust can be revoked. Projects are what the glasses flow
 * will offer when a session is started from the HUD.
 */
class ComputerActivity : Activity() {
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var machineId: String
    private lateinit var machineName: String
    private lateinit var statusDot: View
    private lateinit var statusLine: TextView
    private lateinit var projectsList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        machineId = intent.getStringExtra(EXTRA_MACHINE_ID) ?: run { finish(); return }
        machineName = intent.getStringExtra(EXTRA_MACHINE_NAME) ?: machineId
        buildUi()
        uiScope.launch {
            AgentsRuntime.store.linkMachine.collectLatest { renderStatus(it) }
        }
        uiScope.launch {
            AgentsRuntime.store.connections.collectLatest { renderStatus(AgentsRuntime.store.linkMachine.value) }
        }
    }

    override fun onResume() {
        super.onResume()
        renderProjects()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        statusDot = NexusUi.dot(this)
        statusLine = NexusUi.statusLine(this)
        projectsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(connectionRow(this@ComputerActivity, statusDot, statusLine), NexusUi.block())
            addView(BusTheme.gap(this@ComputerActivity, 18))
            addView(NexusUi.sectionRow(this@ComputerActivity, "Projects"), NexusUi.block())
            addView(BusTheme.gap(this@ComputerActivity, 10))
            addView(projectsCard(), NexusUi.block())
            addView(BusTheme.gap(this@ComputerActivity, 24))
            addView(
                NexusUi.textButton(this@ComputerActivity, "Forget this computer", danger = true).apply {
                    setOnClickListener {
                        AgentsMonitorService.forgetMachine(applicationContext, machineId)
                        toast("$machineName forgotten.")
                        finish()
                    }
                },
                NexusUi.block(),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(backHeader(), NexusUi.block())
            addView(
                NexusUi.screen(this@ComputerActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun backHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = NexusUi.dp(this@ComputerActivity, 16)
        setPadding(pad, pad, pad, NexusUi.dp(this@ComputerActivity, 8))
        addView(
            TextView(this@ComputerActivity).apply {
                text = "‹"
                setTextColor(NexusUi.GREEN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, NexusUi.dp(this@ComputerActivity, 14), NexusUi.dp(this@ComputerActivity, 2))
                setOnClickListener { finish() }
            },
        )
        addView(
            TextView(this@ComputerActivity).apply {
                text = machineName
                setTextColor(NexusUi.INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            },
        )
    }

    private fun projectsCard() = NexusUi.card(this).apply {
        addView(projectsList, NexusUi.block())
        addView(NexusUi.divider(this@ComputerActivity))
        if (!machineId.startsWith(DirectComputer.ID_PREFIX)) {
            addView(
                NexusUi.rowTitle(this@ComputerActivity, "+ Add a project").apply {
                    setTextColor(NexusUi.GREEN)
                    setPadding(0, NexusUi.dp(this@ComputerActivity, 10), 0, NexusUi.dp(this@ComputerActivity, 4))
                    setOnClickListener {
                        startActivity(
                            Intent(this@ComputerActivity, ProjectPickerActivity::class.java)
                                .putExtra(EXTRA_MACHINE_ID, machineId)
                                .putExtra(EXTRA_MACHINE_NAME, machineName),
                        )
                    }
                },
                NexusUi.block(),
            )
        }
    }

    private fun renderStatus(link: LinkMachine?) {
        if (machineId.startsWith(DirectComputer.ID_PREFIX)) {
            val state = AgentsRuntime.store.connections.value[AgentProvider.CODEX]
            when (state?.state) {
                ConnectionState.CONNECTED -> {
                    statusLine.text = "CONNECTED · APP-SERVER"
                    NexusUi.setDotColor(statusDot, NexusUi.GREEN)
                }
                ConnectionState.CONNECTING -> {
                    statusLine.text = "CONNECTING"
                    NexusUi.setDotColor(statusDot, NexusUi.AMBER)
                }
                ConnectionState.AUTH_FAILED -> {
                    statusLine.text = state.displayText("REJECTED")
                    NexusUi.setDotColor(statusDot, NexusUi.DANGER)
                }
                else -> {
                    val machine = configStore.listedComputers().firstOrNull { it.machineId == machineId }
                    statusLine.text = lastSeenText(machine?.lastSeenAtMs).uppercase()
                    NexusUi.setDotColor(statusDot, NexusUi.INK3)
                }
            }
            return
        }
        val connected = link?.machineId == machineId
        if (connected) {
            statusLine.text =
                if (link?.overTailnet == true) "CONNECTED · OVER TAILSCALE" else "CONNECTED · SAME WI-FI"
            NexusUi.setDotColor(statusDot, NexusUi.GREEN)
        } else {
            val machine = configStore.trustedMachines().firstOrNull { it.machineId == machineId }
            statusLine.text = lastSeenText(machine?.lastSeenAtMs).uppercase()
            NexusUi.setDotColor(statusDot, NexusUi.INK3)
        }
    }

    private fun renderProjects() {
        projectsList.removeAllViews()
        val projects = configStore.projects(machineId)
        if (projects.isEmpty()) {
            projectsList.addView(
                NexusUi.rowSub(
                    this,
                    if (machineId.startsWith(DirectComputer.ID_PREFIX)) {
                        "Threads come from the app-server. No project folder is required."
                    } else {
                        "No project anchored on this computer yet."
                    },
                ),
                NexusUi.block(),
            )
            return
        }
        projects.forEachIndexed { index, project ->
            if (index > 0) projectsList.addView(BusTheme.gap(this, 4))
            projectsList.addView(projectRow(project), NexusUi.block())
        }
    }

    private fun projectRow(project: AgentProject) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, NexusUi.dp(this@ComputerActivity, 6), 0, NexusUi.dp(this@ComputerActivity, 6))
        setOnClickListener {
            startActivity(
                Intent(this@ComputerActivity, NewThreadActivity::class.java)
                    .putExtra(EXTRA_MACHINE_ID, machineId)
                    .putExtra(EXTRA_MACHINE_NAME, machineName)
                    .putExtra(NewThreadActivity.EXTRA_PROJECT_NAME, project.name)
                    .putExtra(NewThreadActivity.EXTRA_PROJECT_PATH, project.path),
            )
        }
        addView(
            LinearLayout(this@ComputerActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@ComputerActivity, project.name))
                addView(NexusUi.rowSub(this@ComputerActivity, project.path))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(NexusUi.chevron(this@ComputerActivity))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_MACHINE_ID = "machineId"
        const val EXTRA_MACHINE_NAME = "machineName"
    }
}
