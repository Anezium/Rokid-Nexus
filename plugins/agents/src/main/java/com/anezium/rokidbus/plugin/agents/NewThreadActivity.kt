package com.anezium.rokidbus.plugin.agents

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A new session inside an anchored project: one prompt, one harness, and the
 * daemon does the launching. The session then lives on the board like any
 * other — this screen only lights the fuse.
 */
class NewThreadActivity : Activity() {
    private val configStore by lazy { AgentsConfigStore(applicationContext) }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var machineId: String
    private lateinit var machineName: String
    private lateinit var projectName: String
    private lateinit var projectPath: String
    private lateinit var promptField: EditText
    private lateinit var startClaude: Button
    private lateinit var startCodex: Button
    private lateinit var hint: TextView

    private var pendingRequestId: String? = null
    private var timeout: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        machineId = intent.getStringExtra(ComputerActivity.EXTRA_MACHINE_ID) ?: run { finish(); return }
        machineName = intent.getStringExtra(ComputerActivity.EXTRA_MACHINE_NAME) ?: machineId
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME) ?: run { finish(); return }
        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH) ?: run { finish(); return }
        buildUi()
        uiScope.launch {
            AgentsRuntime.store.threadStart.collectLatest { result ->
                if (result != null && result.requestId == pendingRequestId) {
                    pendingRequestId = null
                    timeout?.cancel()
                    onVerdict(result)
                }
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        promptField = NexusUi.field(this, "What should it work on?").apply {
            setSingleLine(false)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            setPadding(paddingLeft, 14, paddingRight, 14)
        }
        hint = NexusUi.rowSub(this, "").apply { visibility = View.GONE }
        startClaude = NexusUi.pillButton(this, "Start with Claude Code").apply {
            setOnClickListener { start(AgentProvider.CLAUDE) }
        }
        startCodex = NexusUi.outlinePillButton(this, "Start with Codex").apply {
            setOnClickListener { start(AgentProvider.CODEX) }
        }
        if (isCodexRemoteComputer(machineId)) {
            startClaude.visibility = View.GONE
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@NewThreadActivity,
                    "The session starts inside $projectPath and shows up on the " +
                        "board like any other.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@NewThreadActivity, 12))
            addView(promptField, NexusUi.block())
            addView(BusTheme.gap(this@NewThreadActivity, 12))
            if (!isCodexRemoteComputer(machineId)) {
                addView(startClaude, NexusUi.block())
                addView(BusTheme.gap(this@NewThreadActivity, 8))
            }
            addView(startCodex, NexusUi.block())
            addView(BusTheme.gap(this@NewThreadActivity, 8))
            addView(hint, NexusUi.block())
            addView(BusTheme.gap(this@NewThreadActivity, 24))
            addView(
                NexusUi.textButton(this@NewThreadActivity, "Remove this project", danger = true).apply {
                    setOnClickListener {
                        configStore.removeProject(machineId, projectPath)
                        toast("$projectName removed.")
                        finish()
                    }
                },
                NexusUi.block(),
            )
        }
        val root = NexusUi.fixedRoot(this).apply {
            addView(backHeader(), NexusUi.block())
            addView(
                NexusUi.screen(this@NewThreadActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    private fun backHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = NexusUi.dp(this@NewThreadActivity, 16)
        setPadding(pad, pad, pad, NexusUi.dp(this@NewThreadActivity, 8))
        addView(
            TextView(this@NewThreadActivity).apply {
                text = "‹"
                setTextColor(NexusUi.GREEN)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setPadding(0, 0, NexusUi.dp(this@NewThreadActivity, 14), NexusUi.dp(this@NewThreadActivity, 2))
                setOnClickListener { finish() }
            },
        )
        addView(
            LinearLayout(this@NewThreadActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(this@NewThreadActivity).apply {
                        text = projectName
                        setTextColor(NexusUi.INK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    },
                )
                addView(NexusUi.rowSub(this@NewThreadActivity, "New thread on $machineName"))
            },
        )
    }

    private fun start(provider: AgentProvider) {
        val prompt = promptField.text.toString().trim()
        if (prompt.isEmpty() && provider == AgentProvider.CLAUDE) {
            toast("Claude Code needs a prompt to start with.")
            return
        }
        if (isCodexRemoteComputer(machineId)) {
            val state = AgentsRuntime.store.connections.value[AgentProvider.CODEX]?.state
            if (state != ConnectionState.CONNECTED) {
                showHint("$machineName is not connected right now.")
                return
            }
        } else if (AgentsRuntime.store.linkMachine.value?.machineId != machineId) {
            showHint("$machineName is not connected right now.")
            return
        }
        val requestId = UUID.randomUUID().toString()
        pendingRequestId = requestId
        setBusy(true)
        showHint("Asking $machineName…")
        AgentsMonitorService.requestThreadStart(
            applicationContext,
            requestId,
            provider,
            projectPath,
            prompt,
            machineId,
        )
        timeout?.cancel()
        timeout = uiScope.launch {
            delay(VERDICT_TIMEOUT_MS)
            if (pendingRequestId == requestId) {
                pendingRequestId = null
                setBusy(false)
                showHint("$machineName did not answer. Check the link and try again.")
            }
        }
    }

    private fun onVerdict(result: ThreadStartResult) {
        setBusy(false)
        if (result.ok) {
            toast("Thread started — it will appear on the board.")
            finish()
        } else {
            showHint(result.error ?: "The computer could not start the session.")
        }
    }

    private fun setBusy(busy: Boolean) {
        startClaude.isEnabled = !busy
        startCodex.isEnabled = !busy
    }

    private fun showHint(message: String) {
        hint.text = message
        hint.visibility = View.VISIBLE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PROJECT_NAME = "projectName"
        const val EXTRA_PROJECT_PATH = "projectPath"
        private const val VERDICT_TIMEOUT_MS = 35_000L
    }
}
