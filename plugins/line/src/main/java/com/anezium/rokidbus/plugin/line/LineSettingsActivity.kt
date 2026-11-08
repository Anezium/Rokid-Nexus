package com.anezium.rokidbus.plugin.line

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.anezium.rokidbus.client.ui.NexusUi

class LineSettingsActivity : Activity() {
    private val owner = Any()
    private lateinit var status: TextView
    private lateinit var pin: TextView
    private lateinit var qr: ImageView
    private lateinit var link: Button
    private lateinit var openLine: Button
    private lateinit var cancel: Button
    private lateinit var forget: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = NexusUi.contentColumn(this)
        status = NexusUi.cardTitle(this, "Checking LINE…")
        content.addView(status, NexusUi.block())
        content.addView(NexusUi.cardBody(this,
            "Unofficial LINE client. LINE may restrict accounts using third-party clients. " +
                "This connection uses LINE's Chrome session and can replace an existing LINE Chrome login. " +
                "This prototype reads recent chats and sends text with Letter Sealing; media and calls are not supported."), NexusUi.block())
        content.addView(NexusUi.cardBody(this,
            "Tap Link account, then try Open LINE to approve on this phone. If LINE cannot open the link, " +
                "open LINE Messages on your glasses and scan its QR code with LINE on the phone. " +
                "A second screen can also scan the QR shown below. Enter the PIN in LINE when asked. Pairing expires after 3 minutes."), NexusUi.block())
        pin = NexusUi.cardTitle(this, "").apply { setTextIsSelectable(false) }
        content.addView(pin, NexusUi.block())
        qr = ImageView(this).apply { adjustViewBounds = true; contentDescription = "LINE account pairing QR code" }
        content.addView(qr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexusUi.dp(this, 280)))
        link = NexusUi.outlinePillButton(this, "Link account").apply {
            setOnClickListener {
                isEnabled = false
                if (runCatching { startForegroundService(Intent(this@LineSettingsActivity, LinePairingService::class.java)) }.isFailure) {
                    status.text = "Pairing could not start; keep Nexus visible and try again"
                    isEnabled = true
                }
            }
        }
        content.addView(link, NexusUi.block())
        openLine = NexusUi.outlinePillButton(this, "Open LINE").apply {
            setOnClickListener {
                val uri = runCatching { Uri.parse(LineSession.login.url) }.getOrNull()
                val allowed = uri?.scheme == "line" || (uri?.scheme == "https" && uri.host == "line.me")
                if (!allowed || runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("jp.naver.line.android"))
                }.isFailure) status.text = "Open LINE Messages on the glasses and scan its QR from LINE on the phone"
            }
        }
        content.addView(openLine, NexusUi.block())
        cancel = NexusUi.textButton(this, "Cancel pairing").apply {
            setOnClickListener { stopService(Intent(this@LineSettingsActivity, LinePairingService::class.java)); LineSession.abortLogin() }
        }
        content.addView(cancel, NexusUi.block())
        forget = NexusUi.textButton(this, "Forget this connection", danger = true).apply {
            setOnClickListener {
                AlertDialog.Builder(this@LineSettingsActivity).setTitle("Forget LINE connection?")
                    .setMessage("Remove this plugin's saved session and keys. No messages are deleted. " +
                        "You can also revoke the Chrome session from LINE's logged-in devices.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Forget") { _, _ ->
                        stopService(Intent(this@LineSettingsActivity, LinePairingService::class.java))
                        LineSession.forget(this@LineSettingsActivity) { ok ->
                            if (ok) render() else status.text = "Could not forget the connection; close LINE pages and try again"
                        }
                    }.show()
            }
        }
        content.addView(forget, NexusUi.block())
        content.addView(NexusUi.textButton(this, "Open-source licenses").apply {
            setOnClickListener {
                val notice = assets.open("licenses/NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val text = NexusUi.cardBody(this@LineSettingsActivity, notice).apply { setTextIsSelectable(true) }
                AlertDialog.Builder(this@LineSettingsActivity).setTitle("Open-source licenses")
                    .setView(ScrollView(this@LineSettingsActivity).apply { addView(text) })
                    .setPositiveButton("Close", null).show()
            }
        }, NexusUi.block())
        val root = NexusUi.fixedRoot(this)
        root.addView(NexusUi.pluginHeader(this, NexusUi.iconTile(this, "L", 48), "LINE Messages", "Personal account · text prototype", ::finish))
        root.addView(NexusUi.screen(this, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onStart() { super.onStart(); LineSession.acquire(this, owner, ::render) }
    override fun onStop() { qr.setImageDrawable(null); pin.text = ""; LineSession.release(owner); super.onStop() }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val login = LineSession.login
        val pairing = login.phase in setOf("starting", "qr", "pin")
        val linked = LineSession.snapshot.state in setOf("offline", "connecting", "ready")
        status.text = when (login.phase) {
            "starting" -> "Getting your QR code…"
            "qr" -> "Approve the connection in LINE"
            "pin" -> "Enter this PIN in LINE"
            "failed" -> "Pairing failed or expired · try again"
            "linked" -> "Account linked · open LINE Messages on the glasses"
            else -> if (linked) "Account linked" else LinePages.status(LineSession.snapshot.state)
        }
        pin.text = login.pin
        val bytes = login.qr
        qr.setImageBitmap(bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) })
        qr.visibility = if (bytes != null) View.VISIBLE else View.GONE
        link.isEnabled = !pairing && LineSession.snapshot.state == "signed_out"
        link.visibility = if (linked || LineSession.snapshot.state == "failed") View.GONE else View.VISIBLE
        openLine.visibility = if (login.phase == "qr") View.VISIBLE else View.GONE
        cancel.visibility = if (pairing) View.VISIBLE else View.GONE
        forget.visibility = if (linked || LineSession.snapshot.state == "failed") View.VISIBLE else View.GONE
    }
}
