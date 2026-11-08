package com.anezium.rokidbus.plugin.meta

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusPluginIcons
import com.anezium.rokidbus.client.ui.NexusUi
import java.io.File

/**
 * The plugin's settings entry: one row per service. It opens no engine itself, so
 * nothing connects until the owner opens a service page; the state each row shows is
 * what that page last reported in this process, see [MetaPresence].
 */
class MetaMessagesActivity : Activity() {
    private var historySub: TextView? = null

    private fun historyLabel() =
        "The latest ${MetaSettings.historyLimit(this)} messages, older ones load when a conversation opens"

    private val rows = ArrayList<ServiceRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val content = NexusUi.contentColumn(this, topDp = 14)

        content.addView(NexusUi.sectionRow(this, "Services", "${MetaAccount.entries.count { it.open != null } + 1} available"), NexusUi.block())
        content.addView(BusTheme.gap(this, 10))
        val services = NexusUi.card(this).apply { setPadding(0, 0, 0, 0) }
        rows += ServiceRow(WHATSAPP_ID, "WhatsApp", "Link this phone as a companion device", available = true) {
            startActivity(Intent(this, WhatsAppSettingsActivity::class.java))
        }
        for (account in MetaAccount.entries) {
            val available = account.open != null
            rows += ServiceRow(account.id, account.title, "Sign in to your account", available) {
                startActivity(Intent(this, AccountSettingsActivity::class.java)
                    .putExtra(AccountSettingsActivity.EXTRA_ACCOUNT, account.id))
            }
        }
        rows.forEachIndexed { index, row ->
            if (index > 0) services.addView(MetaUi.rowDivider(this))
            services.addView(row.view)
        }
        content.addView(services, NexusUi.block())

        // The one place the plugin states what it is; the service pages do not repeat it.
        MetaUi.section(this, content, "Before you start")
        content.addView(NexusUi.card(this).apply {
            addView(NexusUi.cardTitle(context, "Unofficial clients"))
            addView(NexusUi.cardBody(context,
                "Meta Messages talks to WhatsApp, Instagram and Messenger the way their own apps do, " +
                    "but it is not made by Meta. Meta may sign this device out, or restrict or ban an account " +
                    "that uses third-party apps. Each service connects only while its page is open; " +
                    "nothing runs in the background."),
                NexusUi.block().apply { topMargin = NexusUi.dp(context, 6) })
        }, NexusUi.block())

        MetaUi.section(this, content, "On the glasses")
        content.addView(NexusUi.card(this).apply {
            setPadding(0, 0, 0, 0)
            val row = MetaUi.actionRow(context, "Messages per conversation", historyLabel()) {
                val choices = MetaSettings.HISTORY_CHOICES
                android.app.AlertDialog.Builder(this@MetaMessagesActivity)
                    .setTitle("Messages per conversation")
                    .setSingleChoiceItems(choices.map { "$it messages" }.toTypedArray(),
                        choices.indexOf(MetaSettings.historyLimit(this@MetaMessagesActivity))) { dialog, which ->
                        MetaSettings.setHistoryLimit(this@MetaMessagesActivity, choices[which])
                        historySub?.text = historyLabel()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            // rowText is title, gap, sub: the sub line is the one this row rewrites.
            historySub = (row.getChildAt(0) as LinearLayout).getChildAt(2) as TextView
            addView(row)
        }, NexusUi.block())

        MetaUi.section(this, content, "Plugin")
        content.addView(NexusUi.card(this).apply {
            setPadding(0, 0, 0, 0)
            addView(MetaUi.actionRow(context, "Open-source licenses", "The libraries this plugin is built with") {
                startActivity(Intent(this@MetaMessagesActivity, LicensesActivity::class.java))
            })
        }, NexusUi.block().apply { bottomMargin = NexusUi.dp(this@MetaMessagesActivity, 8) })
        content.addView(NexusUi.uninstallCard(this, "Meta Messages") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }, NexusUi.block())

        setContentView(NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@MetaMessagesActivity, NexusPluginIcons.drawableFor("chat", "meta-messages"),
                "Meta Messages", "WhatsApp · Instagram · Messenger"), NexusUi.block())
            addView(NexusUi.screen(this@MetaMessagesActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onStart() {
        super.onStart()
        // A service page may have signed in or forgotten an account since this screen was last shown.
        rows.forEach { it.render() }
    }


    /** One service: brand mark, name, the last known state, a dot, and a chevron or a "soon" tag. */
    private inner class ServiceRow(
        private val id: String,
        title: String,
        private val invitation: String,
        private val available: Boolean,
        onOpen: () -> Unit,
    ) {
        private val sub: TextView
        private val dot: View
        val view: LinearLayout = LinearLayout(this@MetaMessagesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = title
            setPadding(NexusUi.dp(context, 15), NexusUi.dp(context, 14), NexusUi.dp(context, 15), NexusUi.dp(context, 14))
            background = NexusUi.pressed(context, android.graphics.Color.TRANSPARENT, 15)
            isClickable = available
            isFocusable = available
            if (available) setOnClickListener { onOpen() }
            addView(MetaUi.serviceTile(context, title).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = NexusUi.dp(context, 14)
            })
            sub = NexusUi.rowSub(context, "")
            dot = NexusUi.dot(context).apply {
                layoutParams = LinearLayout.LayoutParams(NexusUi.dp(context, 6), NexusUi.dp(context, 6)).apply {
                    marginEnd = NexusUi.dp(context, 8)
                }
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(NexusUi.rowTitle(context, title))
                addView(BusTheme.gap(context, 4))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(dot)
                    addView(sub, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (available) addView(NexusUi.chevron(context))
            else addView(NexusUi.metaLabel(context, "Soon", NexusUi.INK4))
            if (!available) alpha = 0.55f
        }

        fun render() {
            if (!available) {
                sub.text = "Coming next"
                dot.visibility = View.GONE
                return
            }
            dot.visibility = View.VISIBLE
            when (val state = MetaPresence.get(id)) {
                is MetaPresence.State.Present -> {
                    sub.text = state.label
                    NexusUi.setDotColor(dot, NexusUi.GREEN)
                }
                MetaPresence.State.Absent -> {
                    sub.text = invitation
                    NexusUi.setDotColor(dot, NexusUi.INK3)
                }
                MetaPresence.State.Unknown -> {
                    // A fresh process has not met the service page yet; a saved session still counts.
                    val saved = MetaAccount.byId(id)?.let { File(File(noBackupFilesDir, it.directory), it.sessionFile).isFile } == true
                    sub.text = if (saved) "Signed in" else invitation
                    NexusUi.setDotColor(dot, if (saved) NexusUi.GREEN else NexusUi.INK4)
                }
            }
        }
    }

    companion object {
        /** WhatsApp has no [MetaAccount] entry; this is its key in [MetaPresence]. */
        const val WHATSAPP_ID = "whatsapp"
    }
}
