package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything the wearer saved by voice: pending reminders and timers on top because
 * they are live and cancellable, notes below because they only wait to be read.
 *
 * The two Android permissions a reminder needs to actually ring are surfaced here as
 * cards that exist only while something is missing — a healthy phone never sees them.
 */
class AssistantProductivityActivity : Activity() {
    private val noteStore by lazy { AssistantNoteStore(applicationContext) }
    private val reminderStore by lazy { AssistantReminderStore(applicationContext) }
    private val reminderScheduler by lazy { androidReminderScheduler(applicationContext) }

    private lateinit var permissionSlot: LinearLayout
    private lateinit var remindersColumn: LinearLayout
    private lateinit var notesColumn: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        // Both permission flows leave for system UI and come back here.
        renderPermissions()
        renderLists()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        permissionSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        remindersColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        notesColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val content = NexusUi.contentColumn(this).apply {
            addView(
                NexusUi.cardBody(
                    this@AssistantProductivityActivity,
                    "Ask the glasses to remind you, start a timer, or take a note -- " +
                        "everything lands here, on this phone only.",
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantProductivityActivity, 18))
            addView(permissionSlot, NexusUi.block())
            addView(
                NexusUi.sectionRow(this@AssistantProductivityActivity, "Reminders & timers"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantProductivityActivity, 12))
            addView(remindersColumn, NexusUi.block())
            addView(BusTheme.gap(this@AssistantProductivityActivity, 28))
            addView(NexusUi.sectionRow(this@AssistantProductivityActivity, "Notes"), NexusUi.block())
            addView(BusTheme.gap(this@AssistantProductivityActivity, 12))
            addView(
                NexusUi.textButton(this@AssistantProductivityActivity, "Add note").apply {
                    setOnClickListener {
                        // No model call, no STT: this opens a bare typed field on the
                        // wearer's glasses (the same editable-card capability Relay's
                        // typed replies use), so it works even with no AI provider
                        // configured. It only works while Assistant is already open on
                        // the glasses -- there is no surface session before that.
                        if (!AssistantPluginService.requestNewNote()) {
                            toast("Open Assistant on the glasses first.")
                        }
                    }
                },
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantProductivityActivity, 8))
            addView(notesColumn, NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@AssistantProductivityActivity,
                    R.drawable.nexus_glyph_assistant,
                    "Notes & reminders",
                    "Saved by voice",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@AssistantProductivityActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        renderPermissions()
        renderLists()
    }

    // ------------------------------------------------------------------ permissions

    private fun renderPermissions() {
        permissionSlot.removeAllViews()
        val needsNotifications = !ReminderPermissions.notificationsGranted(this)
        val needsExact = !ReminderPermissions.canScheduleExact(this)
        if (!needsNotifications && !needsExact) return

        if (needsNotifications) {
            permissionSlot.addView(
                permissionCard(
                    body = "Without notification access, a reminder can only appear on the " +
                        "glasses HUD -- the phone stays silent.",
                    actionLabel = "Allow notifications",
                ) { requestNotificationsPermission() },
                NexusUi.block(),
            )
            permissionSlot.addView(BusTheme.gap(this, 12))
        }
        if (needsExact) {
            permissionSlot.addView(
                permissionCard(
                    body = "Android is delaying reminders to save battery. Allow exact " +
                        "alarms so they ring on time.",
                    actionLabel = "Allow exact timing",
                ) { openExactAlarmSettings() },
                NexusUi.block(),
            )
            permissionSlot.addView(BusTheme.gap(this, 12))
        }
        permissionSlot.addView(BusTheme.gap(this, 6))
    }

    private fun permissionCard(
        body: String,
        actionLabel: String,
        onAction: () -> Unit,
    ): LinearLayout =
        NexusUi.card(this).apply {
            addView(NexusUi.cardBody(this@AssistantProductivityActivity, body), NexusUi.block())
            addView(BusTheme.gap(this@AssistantProductivityActivity, 8))
            addView(
                LinearLayout(this@AssistantProductivityActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantProductivityActivity, actionLabel).apply {
                            setOnClickListener { onAction() }
                        },
                    )
                },
                NexusUi.block(),
            )
        }

    private fun requestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) renderPermissions()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.fromParts("package", packageName, null)),
            )
        }.onFailure { toast("Couldn't open the alarm settings.") }
    }

    // ------------------------------------------------------------------ lists

    /** Both stores are file reads; never let them land on the frame that is drawing. */
    private fun renderLists() {
        Thread {
            val reminders = runCatching { reminderStore.pending() }.getOrDefault(emptyList())
            val notes = runCatching { noteStore.notes() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showReminders(reminders)
                showNotes(notes)
            }
        }.start()
    }

    private fun showReminders(reminders: List<AssistantReminder>) {
        remindersColumn.removeAllViews()
        if (reminders.isEmpty()) {
            remindersColumn.addView(
                NexusUi.card(this).apply {
                    addView(
                        NexusUi.rowSub(
                            this@AssistantProductivityActivity,
                            "Nothing pending. Say “remind me to…” or " +
                                "“set a timer” on the glasses.",
                        ),
                    )
                },
                NexusUi.block(),
            )
            return
        }
        remindersColumn.addView(
            NexusUi.card(this).apply {
                reminders.sortedBy(AssistantReminder::epochMillis)
                    .forEachIndexed { index, reminder ->
                        if (index > 0) addView(NexusUi.divider(this@AssistantProductivityActivity))
                        addView(reminderRow(reminder), NexusUi.block())
                    }
            },
            NexusUi.block(),
        )
    }

    private fun reminderRow(reminder: AssistantReminder): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                0,
                NexusUi.dp(this@AssistantProductivityActivity, 4),
                0,
                NexusUi.dp(this@AssistantProductivityActivity, 4),
            )
            addView(
                LinearLayout(this@AssistantProductivityActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        NexusUi.rowLabel(this@AssistantProductivityActivity, reminder.label),
                        NexusUi.block(),
                    )
                    addView(
                        NexusUi.rowSub(
                            this@AssistantProductivityActivity,
                            reminderMeta(reminder),
                        ),
                        NexusUi.block(),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                NexusUi.textButton(
                    this@AssistantProductivityActivity,
                    "Cancel",
                    danger = true,
                ).apply {
                    setOnClickListener { confirmCancelReminder(reminder) }
                },
            )
        }

    private fun reminderMeta(reminder: AssistantReminder): String {
        val kind = if (reminder.kind == AssistantReminderKind.TIMER) "Timer" else "Reminder"
        return "$kind · ${fireTimeLabel(reminder.epochMillis)}"
    }

    /**
     * "Today 18:30", "Tomorrow 09:00", then full date -- the wearer set these in
     * spoken language, so the label answers "when will it ring?" at a glance.
     */
    private fun fireTimeLabel(epochMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val fireAt = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val today = LocalDate.now(zone)
        val time = fireAt.format(TIME_FORMAT)
        return when (fireAt.toLocalDate()) {
            today -> "today $time"
            today.plusDays(1) -> "tomorrow $time"
            else -> fireAt.format(DATE_FORMAT)
        }
    }

    private fun confirmCancelReminder(reminder: AssistantReminder) {
        val kind = if (reminder.kind == AssistantReminderKind.TIMER) "timer" else "reminder"
        confirm(
            title = "Cancel this $kind",
            body = "“${reminder.label}” will not ring.",
            confirmLabel = "Cancel it",
        ) {
            Thread {
                runCatching {
                    reminderStore.delete(reminder.id)
                    reminderScheduler.cancel(reminder.id)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    toast("Cancelled.")
                    renderLists()
                }
            }.start()
        }
    }

    private fun showNotes(notes: List<AssistantNote>) {
        notesColumn.removeAllViews()
        if (notes.isEmpty()) {
            notesColumn.addView(
                NexusUi.card(this).apply {
                    addView(
                        NexusUi.rowSub(
                            this@AssistantProductivityActivity,
                            "No notes yet. Say “take a note…” on the glasses.",
                        ),
                    )
                },
                NexusUi.block(),
            )
            return
        }
        notesColumn.addView(
            NexusUi.card(this).apply {
                notes.forEachIndexed { index, note ->
                    if (index > 0) addView(NexusUi.divider(this@AssistantProductivityActivity))
                    addView(noteRow(note), NexusUi.block())
                }
            },
            NexusUi.block(),
        )
    }

    private fun noteRow(note: AssistantNote): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Read note ${note.title}"
            background = NexusUi.pressed(this@AssistantProductivityActivity, Color.TRANSPARENT, 10)
            setPadding(
                0,
                NexusUi.dp(this@AssistantProductivityActivity, 4),
                0,
                NexusUi.dp(this@AssistantProductivityActivity, 4),
            )
            setOnClickListener { showNoteDialog(note) }
            addView(
                NexusUi.rowLabel(this@AssistantProductivityActivity, note.title),
                NexusUi.block(),
            )
            addView(
                NexusUi.rowSub(
                    this@AssistantProductivityActivity,
                    Instant.ofEpochMilli(note.createdAtMs)
                        .atZone(ZoneId.systemDefault())
                        .format(DATE_FORMAT),
                ),
                NexusUi.block(),
            )
        }

    private fun showNoteDialog(note: AssistantNote) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantProductivityActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantProductivityActivity, 18),
                NexusUi.dp(this@AssistantProductivityActivity, 18),
                NexusUi.dp(this@AssistantProductivityActivity, 18),
                NexusUi.dp(this@AssistantProductivityActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantProductivityActivity, note.title))
            addView(BusTheme.gap(this@AssistantProductivityActivity, 2))
            addView(
                NexusUi.rowSub(
                    this@AssistantProductivityActivity,
                    Instant.ofEpochMilli(note.createdAtMs)
                        .atZone(ZoneId.systemDefault())
                        .format(DATE_FORMAT),
                ),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@AssistantProductivityActivity, 14))
            addView(
                ScrollView(this@AssistantProductivityActivity).apply {
                    isVerticalScrollBarEnabled = false
                    addView(
                        NexusUi.cardBody(this@AssistantProductivityActivity, note.text),
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(BusTheme.gap(this@AssistantProductivityActivity, 14))
            addView(
                LinearLayout(this@AssistantProductivityActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantProductivityActivity, "Close").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(
                            this@AssistantProductivityActivity,
                            "Delete",
                            danger = true,
                        ).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                confirmDeleteNote(note)
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (resources.displayMetrics.heightPixels * 0.6f).toInt(),
        )
    }

    private fun confirmDeleteNote(note: AssistantNote) {
        confirm(
            title = "Delete this note",
            body = "“${note.title}” is removed from this phone. This cannot be undone.",
            confirmLabel = "Delete",
        ) {
            Thread {
                runCatching { noteStore.delete(note.id) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    toast("Note deleted.")
                    renderLists()
                }
            }.start()
        }
    }

    // ------------------------------------------------------------------ shared

    private fun confirm(
        title: String,
        body: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = NexusUi.bordered(
                this@AssistantProductivityActivity,
                NexusUi.PANEL,
                NexusUi.LINE2,
                16,
            )
            setPadding(
                NexusUi.dp(this@AssistantProductivityActivity, 18),
                NexusUi.dp(this@AssistantProductivityActivity, 18),
                NexusUi.dp(this@AssistantProductivityActivity, 18),
                NexusUi.dp(this@AssistantProductivityActivity, 14),
            )
            addView(NexusUi.cardTitle(this@AssistantProductivityActivity, title))
            addView(BusTheme.gap(this@AssistantProductivityActivity, 6))
            addView(NexusUi.cardBody(this@AssistantProductivityActivity, body), NexusUi.block())
            addView(BusTheme.gap(this@AssistantProductivityActivity, 14))
            addView(
                LinearLayout(this@AssistantProductivityActivity).apply {
                    gravity = Gravity.END
                    addView(
                        NexusUi.textButton(this@AssistantProductivityActivity, "Keep").apply {
                            setOnClickListener { dialog.dismiss() }
                        },
                    )
                    addView(
                        NexusUi.textButton(
                            this@AssistantProductivityActivity,
                            confirmLabel,
                            danger = true,
                        ).apply {
                            setOnClickListener {
                                dialog.dismiss()
                                onConfirm()
                            }
                        },
                    )
                },
                NexusUi.block(),
            )
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 41
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)
    }
}
