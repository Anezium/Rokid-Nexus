package com.anezium.rokidbus.plugin.relay

import android.app.Activity
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class RelaySettingsActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val settings by lazy { RelaySettings(this) }
    private lateinit var content: LinearLayout
    private var unobserveData: (() -> Unit)? = null
    private var unobserveDiagnostics: (() -> Unit)? = null
    private var unobserveHarness: (() -> Unit)? = null
    private var harnessExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        content = NexusUi.contentColumn(this)
        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@RelaySettingsActivity,
                    R.drawable.nexus_glyph_relay,
                    "Relay",
                    "Voice replies to phone notifications",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@RelaySettingsActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        CompanionDeviceCoordinator.startObserving(this)
        unobserveData = observeData { main.post(::render) }
        unobserveDiagnostics = RelayDiagnostics.observe { main.post(::render) }
        unobserveHarness = FakeNotificationHarness.observe { main.post(::render) }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    @Deprecated("The companion device chooser still returns through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CompanionDeviceCoordinator.COMPANION_REQUEST_CODE) {
            CompanionDeviceCoordinator.handleAssociationResult(this, resultCode, data)
            render()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CompanionDeviceCoordinator.handleBluetoothPermissionResult(
            this,
            requestCode,
            grantResults,
            ::showTransientMessage,
        )
    }

    override fun onStop() {
        unobserveData?.invoke()
        unobserveData = null
        unobserveDiagnostics?.invoke()
        unobserveDiagnostics = null
        unobserveHarness?.invoke()
        unobserveHarness = null
        super.onStop()
    }

    private fun render() {
        if (!::content.isInitialized) return
        content.removeAllViews()

        content.addView(NexusUi.sectionRow(this, "Access"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(notificationAccessCard(), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        content.addView(companionLinkCard(), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                title = "Relay notifications",
                subtitle = "Nothing is forwarded while this is off",
                checked = settings.enabled(),
            ) { enabled ->
                settings.setEnabled(enabled)
                NotificationControl.refreshFromSettings()
            },
            NexusUi.block(),
        )

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Notification handling"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            switchCard(
                "Hide message text on the glasses",
                "The band names the sender · tap Show to read",
                settings.hideNoticeText(),
            ) { enabled -> settings.setHideNoticeText(enabled) },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Hide previews in the inbox",
                "The list names senders · open a conversation to read",
                settings.hideInboxPreviews(),
            ) { enabled -> settings.setHideInboxPreviews(enabled) },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Image previews",
                "Off by default · 512 px / 64 KiB maximum",
                settings.imagePreviewsEnabled(),
            ) { enabled ->
                settings.setImagePreviewsEnabled(enabled)
                NotificationControl.refreshFromSettings()
            },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(messageLimitCard(), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Pause while phone screen is on",
                "The next post or listener refresh resumes capture",
                settings.pauseWhilePhoneScreenOn(),
            ) { enabled -> settings.setPauseWhilePhoneScreenOn(enabled) },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Clear after reply",
                "Requests source-notification removal three times",
                settings.clearAfterReply(),
            ) { enabled -> settings.setClearAfterReply(enabled) },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Black out behind notifications",
                "The glasses show only the notification",
                settings.noticeBackdrop(),
            ) { enabled -> settings.setNoticeBackdrop(enabled) },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(noticeDisplayTimeCard(), NexusUi.block())
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Longer for long messages",
                "Adds time per character, up to 45 seconds",
                settings.noticeScalesWithLength(),
            ) { enabled -> settings.setNoticeScalesWithLength(enabled) },
            NexusUi.block(),
        )
        content.addView(BusTheme.gap(this, 8))
        content.addView(
            switchCard(
                "Read notifications aloud",
                "The glasses speak the message when it arrives",
                settings.readAloud(),
            ) { enabled -> settings.setReadAloud(enabled) },
            NexusUi.block(),
        )

        content.addView(BusTheme.gap(this, 24))
        // Folded by default, and folded again every time this screen opens. It
        // is a development tool living in a shipped app: useful to whoever knows
        // it is there, and no invitation for anyone else to post themselves
        // messages they never received.
        content.addView(
            NexusUi.sectionRow(
                this,
                "Test harness",
                if (harnessExpanded) "hide" else "show",
            ).apply { setOnClickListener { harnessExpanded = !harnessExpanded; render() } },
            NexusUi.block(),
        )
        if (harnessExpanded) {
            content.addView(BusTheme.gap(this, 10))
            content.addView(harnessCard(), NexusUi.block())
        }

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Diagnostics"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(diagnosticsCard(), NexusUi.block())

        content.addView(BusTheme.gap(this, 24))
        content.addView(NexusUi.sectionRow(this, "Plugin"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        content.addView(
            NexusUi.uninstallCard(this, "Relay") {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
            },
            NexusUi.block(),
        )
    }

    private fun notificationAccessCard(): LinearLayout = NexusUi.card(this).apply {
        val granted = hasNotificationAccess()
        addView(NexusUi.cardTitle(this@RelaySettingsActivity, "Notification access"))
        addView(BusTheme.gap(this@RelaySettingsActivity, 5))
        addView(
            NexusUi.cardBody(
                this@RelaySettingsActivity,
                if (granted) "Granted. Relay can inspect repliable notifications."
                else "Required to discover notifications and retain their live reply actions.",
            ),
        )
        addView(BusTheme.gap(this@RelaySettingsActivity, 10))
        addView(
            NexusUi.outlinePillButton(
                this@RelaySettingsActivity,
                if (granted) "Review access" else "Grant access",
            ).apply {
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun companionLinkCard(): LinearLayout = NexusUi.card(this).apply {
        val linked = CompanionDeviceCoordinator.hasAssociation(this@RelaySettingsActivity)
        addView(NexusUi.cardTitle(this@RelaySettingsActivity, "Show messages Android hides"))
        addView(BusTheme.gap(this@RelaySettingsActivity, 5))
        addView(
            NexusUi.cardBody(
                this@RelaySettingsActivity,
                CompanionLinkCardContent.body(linked, Build.VERSION.SDK_INT),
            ),
        )
        if (!linked) {
            addView(BusTheme.gap(this@RelaySettingsActivity, 10))
            addView(
                NexusUi.outlinePillButton(
                    this@RelaySettingsActivity,
                    "LINK ›",
                ).apply {
                    setOnClickListener {
                        CompanionDeviceCoordinator.requestAssociation(
                            this@RelaySettingsActivity,
                            ::showTransientMessage,
                        )
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun diagnosticsCard(): LinearLayout = NexusUi.card(this).apply {
        val snapshot = RelayDiagnostics.snapshot(this@RelaySettingsActivity)
        addView(NexusUi.cardTitle(this@RelaySettingsActivity, "Relay health"))
        addView(BusTheme.gap(this@RelaySettingsActivity, 5))
        addView(
            NexusUi.cardBody(
                this@RelaySettingsActivity,
                "Capture, repair, guardian, and companion state. The copied history contains no message or device data.",
            ),
        )
        addView(NexusUi.divider(this@RelaySettingsActivity))
        addView(diagnosticsRow(
            "Notification access",
            if (snapshot.notificationAccessGranted) "Granted" else "Not granted",
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Listener",
            if (snapshot.listenerConnected) {
                "Connected ${formatWallTime(snapshot.listenerConnectedSinceWallMs)} · gen ${snapshot.listenerConnectGeneration}"
            } else {
                "Disconnected · gen ${snapshot.listenerConnectGeneration}"
            },
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Last disconnect",
            formatWallTime(snapshot.lastListenerDisconnectedWallMs),
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Last raw callback",
            formatWallTime(snapshot.lastRawNotificationPostedWallMs),
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Last accepted capture",
            formatWallTime(snapshot.lastAcceptedCaptureWallMs),
        ))
        addView(NexusUi.divider(this@RelaySettingsActivity))
        addView(diagnosticsRow(
            "Guardian bound",
            yesNo(snapshot.guardianBound),
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Last repair",
            "${formatWallTime(snapshot.lastRepairAttemptWallMs)} · ${formatState(snapshot.lastRepairResult.toString())}",
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Last process exit",
            formatProcessExitReason(snapshot.lastProcessExitReason),
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Force-stopped before start",
            snapshot.forceStoppedBeforeStart?.let(::yesNo) ?: "Not recorded",
        ))
        addView(NexusUi.divider(this@RelaySettingsActivity))
        addView(diagnosticsRow(
            "Linked",
            yesNo(CompanionDeviceCoordinator.hasAssociation(this@RelaySettingsActivity)),
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Observation registered",
            "${yesNo(snapshot.companionObservationRegistered)} · " +
                formatState(snapshot.companionObservationPath.toString()),
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 9))
        addView(diagnosticsRow(
            "Companion service bound",
            yesNo(snapshot.companionServiceBound),
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 12))
        addView(
            NexusUi.outlinePillButton(
                this@RelaySettingsActivity,
                "Copy diagnostics",
            ).apply {
                setOnClickListener {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText(
                            "Relay diagnostics",
                            RelayDiagnostics.export(this@RelaySettingsActivity),
                        ),
                    )
                    showTransientMessage("Diagnostics copied")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun diagnosticsRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            NexusUi.rowLabel(this@RelaySettingsActivity, label),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            NexusUi.rowValue(this@RelaySettingsActivity).apply {
                text = value
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f),
        )
    }

    private fun formatWallTime(wallTimeMs: Long): String {
        if (wallTimeMs <= 0L) return "Never"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wallTimeMs))
    }

    private fun formatProcessExitReason(reason: Int?): String {
        if (reason == null) return "Not recorded"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            when (reason) {
                ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> return "Package state change"
                ApplicationExitInfo.REASON_PACKAGE_UPDATED -> return "Package updated"
            }
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            reason == ApplicationExitInfo.REASON_FREEZER
        ) {
            return "Freezer"
        }
        return when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "Unknown"
            ApplicationExitInfo.REASON_EXIT_SELF -> "Self exit"
            ApplicationExitInfo.REASON_SIGNALED -> "Signal"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory"
            ApplicationExitInfo.REASON_CRASH -> "Crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource use"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested"
            ApplicationExitInfo.REASON_USER_STOPPED -> "User stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Dependency died"
            ApplicationExitInfo.REASON_OTHER -> "Other"
            else -> "Reason $reason"
        }
    }

    private fun formatState(state: String): String =
        state.lowercase(Locale.US).replace('_', ' ')

    private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

    private fun showTransientMessage(message: String) {
        main.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun switchCard(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): LinearLayout = NexusUi.card(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@RelaySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@RelaySettingsActivity, title))
                addView(BusTheme.gap(this@RelaySettingsActivity, 4))
                addView(NexusUi.rowSub(this@RelaySettingsActivity, subtitle))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(Switch(this@RelaySettingsActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        })
    }

    private fun messageLimitCard(): LinearLayout = NexusUi.card(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@RelaySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@RelaySettingsActivity, "Messages per thread"))
                addView(BusTheme.gap(this@RelaySettingsActivity, 4))
                addView(NexusUi.rowSub(this@RelaySettingsActivity, "Newest messages survive the 1024-char cap"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(stepButton("−") { changeMessageLimit(-1) })
        addView(NexusUi.rowValue(this@RelaySettingsActivity).apply {
            text = settings.messagesPerThread().toString()
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(NexusUi.dp(this@RelaySettingsActivity, 42), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(stepButton("+") { changeMessageLimit(1) })
    }

    private fun stepButton(label: String, onClick: () -> Unit): Button =
        NexusUi.textButton(this, label).apply { setOnClickListener { onClick() } }

    private fun changeMessageLimit(delta: Int) {
        settings.setMessagesPerThread(settings.messagesPerThread() + delta)
        NotificationControl.refreshFromSettings()
        render()
    }

    private fun noticeDisplayTimeCard(): LinearLayout = NexusUi.card(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@RelaySettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(this@RelaySettingsActivity, "Message display time"))
                addView(BusTheme.gap(this@RelaySettingsActivity, 4))
                addView(NexusUi.rowSub(this@RelaySettingsActivity, "Seconds before the band leaves · 2 to 45"))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val seconds = settings.noticeDisplaySeconds()
        val field = NexusUi.field(this@RelaySettingsActivity, "3").apply {
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            filters = arrayOf(InputFilter.LengthFilter(2))
            setText(seconds.toString())
            setSelection(text.length)
            // Commit on DONE and on focus loss. Parsing happens once per commit,
            // and the field is reset to the stored (coerced) value, so the wearer
            // sees what was kept. render() is not called here: it rebuilds the
            // whole screen and would drop focus mid-edit.
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    commitNoticeDisplaySeconds(this)
                    val imm = getSystemService(InputMethodManager::class.java)
                    imm?.hideSoftInputFromWindow(windowToken, 0)
                    clearFocus()
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) commitNoticeDisplaySeconds(this)
            }
        }
        addView(
            field,
            LinearLayout.LayoutParams(NexusUi.dp(this@RelaySettingsActivity, 64), ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(NexusUi.rowValue(this@RelaySettingsActivity).apply { text = "s" })
    }

    private fun commitNoticeDisplaySeconds(field: EditText) {
        val parsed = field.text?.toString()?.toIntOrNull()
        if (parsed != null) {
            settings.setNoticeDisplaySeconds(parsed)
        }
        field.setText(settings.noticeDisplaySeconds().toString())
    }

    private fun harnessCard(): LinearLayout = NexusUi.card(this).apply {
        val snapshot = FakeNotificationHarness.snapshot()
        addView(
            NexusUi.cardBody(
                this@RelaySettingsActivity,
                "Posts a real MessagingStyle notification with a mutable RemoteInput, so the listener " +
                    "captures it exactly as it captures any other app's. No second phone needed.",
            ),
        )
        addView(BusTheme.gap(this@RelaySettingsActivity, 10))
        addView(buttonRow(
            "Post thread" to {
                ensureCanPost() && FakeNotificationHarness.resetAndPost(this@RelaySettingsActivity)
            },
            "Append" to {
                ensureCanPost() && FakeNotificationHarness.appendAndPost(this@RelaySettingsActivity)
            },
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 8))
        addView(buttonRow(
            // A second conversation, from someone else: the inbox is a list, and
            // a list of one cannot show selection moving or two rows being told
            // apart.
            "Second thread" to {
                ensureCanPost() && FakeNotificationHarness.postSecondThread(this@RelaySettingsActivity)
            },
            "Attach image" to {
                settings.setImagePreviewsEnabled(true)
                ensureCanPost() && FakeNotificationHarness.attachImageAndPost(this@RelaySettingsActivity)
            },
        ))
        addView(BusTheme.gap(this@RelaySettingsActivity, 8))
        addView(
            harnessButton(
                // A code-shaped thread; see postCodeThread for why it does not prove anything.
                "Code thread" to {
                    ensureCanPost() && FakeNotificationHarness.postCodeThread(this@RelaySettingsActivity)
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(BusTheme.gap(this@RelaySettingsActivity, 8))
        addView(
            harnessButton(
                // More threads than the glasses viewport holds, so list
                // windowing has something real to chase.
                "Eight threads" to {
                    ensureCanPost() && FakeNotificationHarness.postCrowd(this@RelaySettingsActivity)
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(BusTheme.gap(this@RelaySettingsActivity, 8))
        addView(
            harnessButton(
                "Open access" to {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(BusTheme.gap(this@RelaySettingsActivity, 10))
        addView(NexusUi.rowSub(
            this@RelaySettingsActivity,
            buildString {
                append("messages=${snapshot.messageCount} · image=${if (snapshot.imageAttached) "yes" else "no"}")
                val reply = snapshot.deliveredReply
                if (reply != null) append(" · reply received (${reply.length} chars)")
                if (!hasNotificationAccess()) append(" · grant listener access")
            },
        ).apply { maxLines = 3 })
        snapshot.deliveredReply?.let { reply ->
            addView(BusTheme.gap(this@RelaySettingsActivity, 8))
            addView(NexusUi.cardBody(this@RelaySettingsActivity, reply))
        }
    }

    private fun buttonRow(
        first: Pair<String, () -> Unit>,
        second: Pair<String, () -> Unit>,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(harnessButton(first), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(harnessButton(second), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = NexusUi.dp(this@RelaySettingsActivity, 8)
        })
    }

    private fun harnessButton(spec: Pair<String, () -> Unit>): Button =
        NexusUi.outlinePillButton(this, spec.first).apply {
            setOnClickListener {
                val injected = spec.second.invoke().let { true }
                if (!injected) return@setOnClickListener
                main.postDelayed(::render, 100L)
            }
        }

    /**
     * The harness posts a real notification, so on Android 13+ it needs the
     * post grant or the system drops it before the listener is ever called.
     * Asked for here, at the press, rather than at startup: Relay does not
     * notify the wearer of anything, and a permission dialog on first launch
     * for a feature only the owner uses would be a lie about what it wants.
     */
    private fun ensureCanPost(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        return granted
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it.packageName == packageName }
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    companion object {
        private val dataListeners = CopyOnWriteArrayList<() -> Unit>()

        internal fun notifyDataChanged() {
            dataListeners.forEach { listener -> runCatching { listener() } }
        }

        private fun observeData(listener: () -> Unit): () -> Unit {
            dataListeners += listener
            return { dataListeners.remove(listener) }
        }
    }
}
