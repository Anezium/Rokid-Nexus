package com.anezium.rokidbus.plugin.assistant

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.anezium.rokidbus.client.ui.BusTheme
import com.anezium.rokidbus.client.ui.NexusUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

internal object CodexChatGptAppReturnSignal {
    private val generation = AtomicLong(0L)

    fun signal() {
        generation.incrementAndGet()
    }

    fun snapshot(): Long = generation.get()
}

/**
 * The attempt the live process is waiting on. It survives a configuration
 * change, where the browser is still open on that very attempt, but not a
 * process death, after which the authorization code is gone for good.
 */
internal object CodexChatGptSignInSession {
    @Volatile
    var liveAttemptState: String? = null
}

class CodexChatGptRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CodexChatGptAppReturnSignal.signal()
        // The browser launches this activity inside its own task. Without
        // NEW_TASK the sign-in screen would be duplicated there with a fresh
        // OAuth attempt (and a doomed second bind of the callback port)
        // instead of resuming the instance the callback belongs to.
        startActivity(
            Intent(this, CodexChatGptSignInActivity::class.java)
                .setAction(CodexChatGptSignInActivity.ACTION_BROWSER_RETURN)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
        )
        finish()
    }
}

/**
 * Holds the plugin process in the foreground while the wearer is away in the
 * browser. Aggressive OEM reapers kill a backgrounded process within a couple
 * of minutes, and the localhost callback server dies with it -- the browser
 * then cannot deliver the authorization code and the sign-in strands.
 */
class CodexChatGptSignInHoldService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ChatGPT sign-in",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Assistant")
                .setContentText("Waiting for the ChatGPT sign-in in your browser")
                .setSmallIcon(
                    applicationInfo.icon.takeIf { it != 0 }
                        ?: com.anezium.rokidbus.client.R.drawable.ic_plugin_bus,
                )
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        private const val CHANNEL_ID = "assistant_chatgpt_sign_in"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, CodexChatGptSignInHoldService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CodexChatGptSignInHoldService::class.java))
        }
    }
}

class CodexChatGptSignInActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var attempt: CodexChatGptOAuth.AuthAttempt
    private lateinit var statusDot: android.view.View
    private lateinit var status: TextView
    private lateinit var browserButton: Button
    private var signInJob: Job? = null
    private var loopbackServer: CodexOAuthLoopbackServer? = null
    private var pendingTokens: CodexChatGptOAuthTokenBundle? = null
    private var didReceiveBrowserReturn = false
    private var appReturnGeneration = 0L
    private var recoveringInterruptedAttempt = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restored = savedInstanceState?.let(::restoreAttempt)
        attempt = restored ?: CodexChatGptOAuth.createLoginAttempt()
        appReturnGeneration = CodexChatGptAppReturnSignal.snapshot()
        buildUi()
        val store = CodexAuthStore(this)
        val coldBrowserReturn = intent?.action == ACTION_BROWSER_RETURN && savedInstanceState == null
        // A cold browser return carries no saved attempt; the one the dead
        // process started is the only candidate for having finished.
        val interrupted = when {
            coldBrowserReturn -> store.pendingChatGptSignIn()
            else -> restored
        }
        when {
            savedInstanceState == null && !coldBrowserReturn -> beginSignIn(openBrowser = true)
            store.isChatGptSignInCompleted(interrupted?.state) && store.oauthTokens() != null -> {
                // The exchange landed before the process went away; only the
                // screen that would have said so is new.
                showStatus(NexusUi.GREEN, "Signed in. Returning to Assistant...")
                didReceiveBrowserReturn = true
                setResult(RESULT_OK)
                finish()
            }
            restored != null && restored.state == CodexChatGptSignInSession.liveAttemptState ->
                // Recreated in the same process (a configuration change): the
                // browser is already open on this attempt, so only the callback
                // server has to come back. Opening the browser again here would
                // send the wearer into a second, doomed attempt.
                beginSignIn(openBrowser = false)
            interrupted != null -> {
                // The process that ran the callback server is gone, but the
                // browser still sits on the callback URL (served or refused), and
                // the code in it is spent only by the exchange. Bring the server
                // back on the same attempt and let a reload deliver the code;
                // reopening the browser would only show a spent sign-in.
                attempt = interrupted
                didReceiveBrowserReturn = coldBrowserReturn
                beginSignIn(openBrowser = false, recoverInterrupted = true)
            }
            else -> {
                showStatus(NexusUi.AMBER, INTERRUPTED_MESSAGE)
                browserButton.text = "Try again"
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ATTEMPT_STATE, attempt.state)
        outState.putString(STATE_ATTEMPT_VERIFIER, attempt.codeVerifier)
        outState.putString(STATE_ATTEMPT_REDIRECT, attempt.redirectUri)
        outState.putString(STATE_ATTEMPT_AUTHORIZE, attempt.authorizeUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_BROWSER_RETURN) {
            didReceiveBrowserReturn = true
            if (pendingTokens != null || signInJob != null) {
                showStatus(NexusUi.GREEN, "Returning to Assistant...")
            }
            finishIfReady()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!didReceiveBrowserReturn && CodexChatGptAppReturnSignal.snapshot() > appReturnGeneration) {
            didReceiveBrowserReturn = true
            if (pendingTokens != null || signInJob != null) {
                showStatus(NexusUi.GREEN, "Returning to Assistant...")
            }
            finishIfReady()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            CodexChatGptSignInHoldService.stop(this)
            CodexChatGptSignInSession.liveAttemptState = null
        }
        loopbackServer?.close()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun buildUi() {
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        status = NexusUi.statusLine(this)
        statusDot = NexusUi.dot(this)
        browserButton = NexusUi.pillButton(this, "Open browser").apply {
            setOnClickListener { retryOrReopen() }
        }
        val cancel = NexusUi.textButton(this, "Cancel").apply {
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                statusDot,
                LinearLayout.LayoutParams(
                    NexusUi.dp(context, 8),
                    NexusUi.dp(context, 8),
                ).apply { rightMargin = NexusUi.dp(context, 10) },
            )
            addView(
                status,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        val explainer = NexusUi.card(this).apply {
            addView(
                NexusUi.cardTitle(this@CodexChatGptSignInActivity, "Continue in your browser"),
                NexusUi.block(),
            )
            addView(BusTheme.gap(this@CodexChatGptSignInActivity, 6))
            addView(
                NexusUi.cardBody(
                    this@CodexChatGptSignInActivity,
                    "ChatGPT opens in your own browser, so your password never " +
                        "touches this app. Finish signing in there and you will be " +
                        "brought straight back.",
                ),
                NexusUi.block(),
            )
        }

        val content = NexusUi.contentColumn(this).apply {
            addView(explainer, NexusUi.block())
            addView(BusTheme.gap(this@CodexChatGptSignInActivity, 16))
            addView(statusRow, NexusUi.block())
            addView(BusTheme.gap(this@CodexChatGptSignInActivity, 26))
            addView(browserButton, NexusUi.block())
            addView(BusTheme.gap(this@CodexChatGptSignInActivity, 6))
            addView(cancel, NexusUi.block())
        }

        val root = NexusUi.fixedRoot(this).apply {
            addView(
                NexusUi.pluginHeader(
                    this@CodexChatGptSignInActivity,
                    R.drawable.nexus_glyph_assistant,
                    "Assistant",
                    "Sign in with ChatGPT",
                ),
                NexusUi.block(),
            )
            addView(
                NexusUi.screen(this@CodexChatGptSignInActivity, content),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
        setContentView(root)
        showStatus(NexusUi.INK3, "Preparing sign-in...")
    }

    private fun showStatus(dotColor: Int, message: String) {
        NexusUi.setDotColor(statusDot, dotColor)
        status.setTextColor(if (dotColor == NexusUi.DANGER) NexusUi.DANGER else NexusUi.INK2)
        status.text = message
    }

    private fun retryOrReopen() {
        if (signInJob != null && !recoveringInterruptedAttempt) {
            launchBrowser()
            return
        }
        // A recovered attempt the wearer gives up on, or a finished one that
        // already spent its authorization round: start the next one from a
        // fresh state and verifier.
        abandonCurrentAttempt()
        attempt = CodexChatGptOAuth.createLoginAttempt()
        beginSignIn(openBrowser = true)
    }

    private fun abandonCurrentAttempt() {
        signInJob?.cancel()
        signInJob = null
        loopbackServer?.close()
        loopbackServer = null
        recoveringInterruptedAttempt = false
    }

    private fun beginSignIn(openBrowser: Boolean, recoverInterrupted: Boolean = false) {
        if (signInJob != null) return
        recoveringInterruptedAttempt = recoverInterrupted
        CodexChatGptSignInSession.liveAttemptState = attempt.state
        CodexAuthStore(this).savePendingChatGptSignIn(attempt)
        var server: CodexOAuthLoopbackServer? = null
        signInJob = activityScope.launch {
            val job = coroutineContext[Job]
            try {
                showStatus(NexusUi.INK3, "Starting secure callback server...")
                val bound = withContext(Dispatchers.IO) {
                    CodexOAuthLoopbackServer.create(
                        redirectUri = attempt.redirectUri,
                        appReturnUri = APP_RETURN_URI,
                    )
                }
                server = bound
                loopbackServer = bound
                CodexChatGptSignInHoldService.start(this@CodexChatGptSignInActivity)
                when {
                    openBrowser -> {
                        showStatus(NexusUi.INK3, "Opening browser...")
                        if (!launchBrowser()) return@launch
                        showStatus(NexusUi.GREEN, "Waiting for the browser...")
                    }
                    recoverInterrupted -> {
                        browserButton.text = "Start over"
                        showStatus(NexusUi.AMBER, RECOVER_MESSAGE)
                    }
                    else -> {
                        browserButton.text = "Open browser again"
                        showStatus(NexusUi.GREEN, "Waiting for the browser...")
                    }
                }
                val callbackUri = withContext(Dispatchers.IO) { bound.awaitCallback() }
                recoveringInterruptedAttempt = false
                showStatus(NexusUi.GREEN, "Completing sign-in...")
                browserButton.isEnabled = false
                pendingTokens = CodexChatGptOAuth.completeAuthorization(
                    context = applicationContext,
                    callbackUri = callbackUri,
                    attempt = attempt,
                )
                showStatus(NexusUi.GREEN, "Signed in. Returning to Assistant...")
                finishIfReady()
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                Log.e(TAG, "ChatGPT sign-in failed", error)
                val message = error.conciseProviderMessage("ChatGPT sign-in failed.")
                showStatus(NexusUi.DANGER, message)
                browserButton.isEnabled = true
                browserButton.text = "Try again"
                setResult(
                    RESULT_CANCELED,
                    Intent().putExtra(EXTRA_ERROR, message),
                )
                CodexChatGptSignInSession.liveAttemptState = null
                CodexChatGptSignInHoldService.stop(this@CodexChatGptSignInActivity)
            } finally {
                // The hold stays up after a successful exchange: the wearer is
                // still in the browser, and a backgrounded process without it
                // is exactly what OEM reapers kill before the return arrives.
                // An abandoned attempt is cancelled while its accept loop is
                // still unwinding, so only this attempt's own server and job
                // are released here, never the ones a fresh attempt owns.
                server?.close()
                if (loopbackServer === server) loopbackServer = null
                if (signInJob === job) signInJob = null
            }
        }
    }

    private fun launchBrowser(): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attempt.authorizeUrl)))
            browserButton.text = "Open browser again"
            true
        } catch (_: ActivityNotFoundException) {
            showStatus(NexusUi.DANGER, "No browser is available for ChatGPT sign-in.")
            false
        } catch (error: Exception) {
            showStatus(NexusUi.DANGER, error.conciseProviderMessage("Could not open the browser."))
            false
        }
    }

    private fun finishIfReady() {
        val tokens = pendingTokens ?: return
        if (!didReceiveBrowserReturn && CodexChatGptAppReturnSignal.snapshot() > appReturnGeneration) {
            didReceiveBrowserReturn = true
        }
        if (!didReceiveBrowserReturn) return
        setResult(RESULT_OK, resultIntent(tokens))
        finish()
    }

    private fun restoreAttempt(saved: Bundle): CodexChatGptOAuth.AuthAttempt? {
        val state = saved.getString(STATE_ATTEMPT_STATE) ?: return null
        val verifier = saved.getString(STATE_ATTEMPT_VERIFIER) ?: return null
        val redirect = saved.getString(STATE_ATTEMPT_REDIRECT) ?: return null
        val authorize = saved.getString(STATE_ATTEMPT_AUTHORIZE) ?: return null
        return CodexChatGptOAuth.AuthAttempt(
            state = state,
            codeVerifier = verifier,
            redirectUri = redirect,
            authorizeUrl = authorize,
        )
    }

    companion object {
        const val ACTION_BROWSER_RETURN =
            "com.anezium.rokidbus.plugin.assistant.CHATGPT_BROWSER_RETURN"
        const val EXTRA_ERROR = "assistant_chatgpt_sign_in_error"
        private const val APP_RETURN_HOST = "chatgpt-auth-complete"
        private val APP_RETURN_URI: Uri = Uri.Builder()
            .scheme("nexusassistantauth")
            .authority(APP_RETURN_HOST)
            .build()
        private const val TAG = "AssistantSignIn"
        private const val INTERRUPTED_MESSAGE =
            "The sign-in was interrupted: the system closed Assistant while the " +
                "browser was open. Try again; on phones that stop background apps, " +
                "lock Assistant in recent apps first."
        private const val RECOVER_MESSAGE =
            "The system closed Assistant while the browser was open. Go back to " +
                "the browser and reload the page to finish signing in, or start over."
        private const val EXTRA_ACCOUNT_ID = "assistant_chatgpt_account_id"
        private const val EXTRA_PLAN_TYPE = "assistant_chatgpt_plan_type"
        private const val EXTRA_EMAIL = "assistant_chatgpt_email"
        private const val STATE_ATTEMPT_STATE = "assistant_chatgpt_attempt_state"
        private const val STATE_ATTEMPT_VERIFIER = "assistant_chatgpt_attempt_verifier"
        private const val STATE_ATTEMPT_REDIRECT = "assistant_chatgpt_attempt_redirect"
        private const val STATE_ATTEMPT_AUTHORIZE = "assistant_chatgpt_attempt_authorize"

        fun createIntent(context: Context): Intent =
            Intent(context, CodexChatGptSignInActivity::class.java)

        private fun resultIntent(tokens: CodexChatGptOAuthTokenBundle): Intent =
            Intent()
                .putExtra(EXTRA_ACCOUNT_ID, tokens.accountId)
                .putExtra(EXTRA_PLAN_TYPE, tokens.planType)
                .putExtra(EXTRA_EMAIL, tokens.email)
    }
}

private class CodexOAuthLoopbackServer private constructor(
    private val redirectUri: Uri,
    private val serverSockets: List<ServerSocket>,
    private val appReturnUri: Uri,
) : Closeable {
    fun awaitCallback(): Uri {
        while (true) {
            serverSockets.forEach { serverSocket ->
                val socket = try {
                    serverSocket.accept()
                } catch (_: SocketTimeoutException) {
                    null
                } catch (error: SocketException) {
                    if (serverSockets.all(ServerSocket::isClosed)) {
                        throw CancellationException("ChatGPT callback server closed.", error)
                    }
                    null
                }
                if (socket != null) {
                    val callbackUri = handleRequest(socket)
                    if (callbackUri != null) return callbackUri
                }
            }
        }
    }

    /**
     * Serves one connection and returns the callback URI if this was the real
     * OAuth redirect. Browsers also open speculative connections that never
     * send a request, and fetch favicons after the page loads -- those must
     * not kill the flow, so anything that is not the callback is answered
     * politely and ignored.
     */
    private fun handleRequest(socket: Socket): Uri? {
        socket.use { accepted ->
            val target = try {
                accepted.soTimeout = REQUEST_READ_TIMEOUT_MS
                accepted.requestTarget()
            } catch (_: Exception) {
                return null
            }
            if (target.isEmpty()) return null
            val callbackUri = runCatching {
                callbackUriForRequest(redirectUri, target)
            }.getOrNull()
            if (callbackUri?.path == CodexChatGptOAuth.callbackPath) {
                val failed = !callbackUri.getQueryParameter("error").isNullOrBlank()
                runCatching { accepted.writeCallbackPage(appReturnUri, failed) }
                return callbackUri
            }
            runCatching { accepted.writeNotFound() }
            return null
        }
    }

    override fun close() {
        serverSockets.forEach { socket -> runCatching { socket.close() } }
    }

    companion object {
        private const val ACCEPT_POLL_TIMEOUT_MS = 250
        private const val REQUEST_READ_TIMEOUT_MS = 3_000

        fun create(
            redirectUri: String,
            appReturnUri: Uri,
        ): CodexOAuthLoopbackServer {
            val parsedRedirect = Uri.parse(redirectUri)
            val host = parsedRedirect.host?.takeIf(String::isNotBlank)
                ?: throw CodexChatGptOAuthException(
                    "ChatGPT login redirect URI is missing a host.",
                )
            val port = parsedRedirect.port.takeIf { it > 0 }
                ?: throw CodexChatGptOAuthException(
                    "ChatGPT login redirect URI is missing a port.",
                )
            val sockets = mutableListOf<ServerSocket>()
            val errors = mutableListOf<String>()
            bindHostsForRedirectHost(host).forEach { bindHost ->
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = ACCEPT_POLL_TIMEOUT_MS
                }
                try {
                    socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), port), 1)
                    sockets += socket
                } catch (error: Exception) {
                    runCatching { socket.close() }
                    errors += "$bindHost: ${error.message ?: error::class.java.simpleName}"
                }
            }
            if (sockets.isEmpty()) {
                throw CodexChatGptOAuthException(
                    "ChatGPT login could not bind a localhost callback server. " +
                        errors.joinToString("; "),
                )
            }
            return CodexOAuthLoopbackServer(parsedRedirect, sockets, appReturnUri)
        }

        private fun bindHostsForRedirectHost(host: String): List<String> =
            if (host.equals("localhost", ignoreCase = true)) {
                listOf("127.0.0.1", "::1")
            } else {
                listOf(host)
            }

        private fun callbackUriForRequest(redirectUri: Uri, requestTarget: String): Uri {
            val base = URI.create(redirectUri.toString())
            val target = URI.create(requestTarget)
            return Uri.parse(
                URI(
                    base.scheme,
                    base.userInfo,
                    base.host,
                    base.port,
                    target.path ?: base.path,
                    target.rawQuery,
                    target.rawFragment,
                ).toString(),
            )
        }
    }
}

private fun Socket.requestTarget(): String {
    val reader = BufferedReader(InputStreamReader(getInputStream(), Charsets.UTF_8))
    val firstLine = reader.readLine().orEmpty()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isBlank()) break
    }
    return firstLine.split(" ").getOrNull(1).orEmpty()
}

private fun Socket.writeCallbackPage(appReturnUri: Uri, failed: Boolean) {
    val glyphClass = if (failed) "glyph err" else "glyph"
    val glyph = if (failed) "!" else "&#10003;"
    val title = if (failed) "Sign-in didn't finish" else "Signed in"
    val detail = if (failed) {
        "ChatGPT declined the sign-in. Head back to the Assistant app to see why and try again."
    } else {
        "Your ChatGPT account is connected. Head back to the Assistant app."
    }
    val html = """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Assistant</title>
            <meta http-equiv="refresh" content="0;url=$appReturnUri">
            <script>window.location.replace("$appReturnUri");</script>
            <style>
              :root { color-scheme: dark; }
              body {
                margin: 0; min-height: 100vh;
                display: flex; align-items: center; justify-content: center;
                background: #070a08; color: #dcf3e4;
                font: 16px/1.5 -apple-system, "Segoe UI", Roboto, sans-serif;
              }
              main {
                width: min(420px, calc(100vw - 48px));
                padding: 36px 28px 26px; text-align: center;
                background: #0d150f; border: 1px solid #182619; border-radius: 18px;
              }
              .glyph {
                width: 56px; height: 56px; margin: 0 auto 18px; border-radius: 50%;
                display: flex; align-items: center; justify-content: center;
                font-size: 26px; background: rgba(77,255,140,.12); color: #4dff8c;
                border: 1px solid rgba(77,255,140,.35);
              }
              .glyph.err {
                background: rgba(255,138,138,.10); color: #ff8a8a;
                border-color: rgba(255,138,138,.35);
              }
              h1 { margin: 0 0 8px; font-size: 22px; font-weight: 600; letter-spacing: .2px; }
              p { margin: 0 0 24px; color: #8ba896; font-size: 14.5px; }
              a.btn {
                display: block; padding: 14px 18px; border-radius: 24px;
                background: #4dff8c; color: #04180d; font-weight: 600;
                text-decoration: none; font-size: 14px; letter-spacing: .4px;
              }
              .brand {
                margin-top: 18px; font: 11px/1 ui-monospace, monospace;
                letter-spacing: .18em; color: #42574a;
              }
            </style>
          </head>
          <body>
            <main>
              <div class="$glyphClass">$glyph</div>
              <h1>$title</h1>
              <p>$detail</p>
              <a class="btn" href="$appReturnUri">Back to Assistant</a>
              <div class="brand">ROKID NEXUS &middot; ASSISTANT</div>
            </main>
          </body>
        </html>
    """.trimIndent()
    writeHttp(200, "OK", "text/html; charset=utf-8", html)
}

private fun Socket.writeNotFound() {
    writeHttp(404, "Not Found", "text/plain; charset=utf-8", "Not found")
}

private fun Socket.writeHttp(status: Int, reason: String, contentType: String, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    OutputStreamWriter(getOutputStream(), Charsets.UTF_8).use { writer ->
        writer.appendLine("HTTP/1.1 $status $reason")
        writer.appendLine("Content-Type: $contentType")
        writer.appendLine("Content-Length: ${bytes.size}")
        writer.appendLine("Connection: close")
        writer.appendLine()
        writer.append(body)
        writer.flush()
    }
}
