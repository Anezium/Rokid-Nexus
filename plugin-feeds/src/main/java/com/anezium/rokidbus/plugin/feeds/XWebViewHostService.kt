package com.anezium.rokidbus.plugin.feeds

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class ServiceXWebViewCaptureClient(context: Context) : XWebViewCaptureClient {
    private val applicationContext = context.applicationContext
    private val serviceIntent = Intent(applicationContext, XWebViewHostService::class.java)
    private val connectionLatch = CountDownLatch(1)

    @Volatile
    private var binder: XWebViewHostService.LocalBinder? = null

    @Volatile
    private var bound = false

    @Volatile
    private var closed = false

    private var startAttempted = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as? XWebViewHostService.LocalBinder
            connectionLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }

        override fun onNullBinding(name: ComponentName) {
            connectionLatch.countDown()
        }
    }

    override val isOverlayGranted: Boolean
        get() = Settings.canDrawOverlays(applicationContext)

    override fun capture(
        request: XWebViewCaptureRequest,
        timeoutMillis: Long,
    ): XWebViewCapturedResponse? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        if (!ensureStartedAndBound()) return null
        val connectionWait = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val connected = await(connectionLatch, connectionWait)
        if (!connected || closed) return null
        val captureWait = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return binder?.capture(request, captureWait)
    }

    @Synchronized
    private fun ensureStartedAndBound(): Boolean {
        if (closed) return false
        if (startAttempted) return bound
        startAttempted = true
        return runCatching {
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
            bound = applicationContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) applicationContext.stopService(serviceIntent)
            bound
        }.getOrElse {
            bound = false
            runCatching { applicationContext.stopService(serviceIntent) }
            false
        }
    }

    private fun await(latch: CountDownLatch, timeoutMillis: Long): Boolean = try {
        latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    override fun close() {
        closed = true
        connectionLatch.countDown()
        binder?.cancelCapture()
        binder = null
        if (bound) {
            runCatching { applicationContext.unbindService(connection) }
            bound = false
        }
        runCatching { applicationContext.stopService(serviceIntent) }
    }
}

class XWebViewHostService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenGenerator = AtomicLong()
    private val captureLock = Any()
    private val localBinder = LocalBinder()
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    private var overlayRoot: FrameLayout? = null
    private var webView: WebView? = null
    private var documentStartScript: ScriptHandler? = null
    private var activeCapture: PendingCapture? = null

    override fun onCreate() {
        super.onCreate()
        enterForeground()
        if (Settings.canDrawOverlays(this)) setupWebViewOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onDestroy() {
        cancelActiveCapture()
        mainHandler.removeCallbacksAndMessages(null)
        documentStartScript?.remove()
        documentStartScript = null
        webView?.apply {
            removeJavascriptInterface(JAVASCRIPT_BRIDGE_NAME)
            stopLoading()
            webViewClient = WebViewClient()
        }
        overlayRoot?.let { root -> runCatching { windowManager.removeViewImmediate(root) } }
        overlayRoot = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        internal fun capture(
            request: XWebViewCaptureRequest,
            timeoutMillis: Long,
        ): XWebViewCapturedResponse? = captureBlocking(request, timeoutMillis)

        internal fun cancelCapture() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cancelActiveCapture()
            } else {
                mainHandler.post(::cancelActiveCapture)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewOverlay() {
        val browser = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = XWebViewInterception.USER_AGENT
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(GraphQlBridge(), JAVASCRIPT_BRIDGE_NAME)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    injectInterception(view)
                    mainHandler.postDelayed({ injectInterception(view) }, REINJECT_DELAY_MILLIS)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    injectInterception(view)
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartScript = runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    browser,
                    XWebViewInterception.javascript(),
                    setOf("https://x.com"),
                )
            }.getOrNull()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(browser, true)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                browser,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f
        }
        val attached = runCatching { windowManager.addView(root, params) }.isSuccess
        if (attached) {
            overlayRoot = root
            webView = browser
        } else {
            documentStartScript?.remove()
            documentStartScript = null
            browser.removeJavascriptInterface(JAVASCRIPT_BRIDGE_NAME)
            browser.destroy()
        }
    }

    private fun captureBlocking(
        request: XWebViewCaptureRequest,
        timeoutMillis: Long,
    ): XWebViewCapturedResponse? {
        if (Looper.myLooper() == Looper.getMainLooper() || timeoutMillis <= 0L) return null
        val pending = PendingCapture(
            token = tokenGenerator.incrementAndGet(),
            previousFingerprint = request.previousFingerprint,
            threadPostId = request.threadPostId,
        )
        mainHandler.post { beginCapture(pending, request) }
        val completed = try {
            pending.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) mainHandler.post { cancelCapture(pending.token) }
        return pending.response
    }

    private fun beginCapture(pending: PendingCapture, request: XWebViewCaptureRequest) {
        val browser = webView
        if (browser == null) {
            pending.latch.countDown()
            return
        }
        cancelActiveCapture()
        synchronized(captureLock) { activeCapture = pending }
        installCookies(request.cookies, browser)
        injectInterception(browser)
        if (request.threadPostId != null) {
            browser.stopLoading()
            browser.loadUrl("https://x.com/i/status/${request.threadPostId}")
        } else if (request.initialPage) {
            browser.stopLoading()
            browser.loadUrl(HOME_URL)
        } else if (!browser.url.orEmpty().startsWith(HOME_URL)) {
            browser.stopLoading()
            browser.loadUrl(HOME_URL)
            scrollForNextPage(pending.token, browser)
        } else {
            scrollForNextPage(pending.token, browser)
        }
    }

    private fun installCookies(cookies: XAccountCookies, browser: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(browser, true)
            cookies.asCookieHeader().split(';').map(String::trim).filter(String::isNotBlank).forEach { cookie ->
                setCookie(HOME_URL, "$cookie; Domain=.x.com; Path=/; Secure; SameSite=Lax")
            }
            flush()
        }
    }

    private fun scrollForNextPage(token: Long, browser: WebView) {
        SCROLL_DELAYS_MILLIS.forEach { delay ->
            mainHandler.postDelayed(
                {
                    if (activeCaptureToken() != token) return@postDelayed
                    injectInterception(browser)
                    browser.evaluateJavascript(SCROLL_JAVASCRIPT, null)
                },
                delay,
            )
        }
    }

    private fun injectInterception(browser: WebView) {
        browser.evaluateJavascript(XWebViewInterception.javascript(), null)
    }

    private fun activeCaptureToken(): Long? = synchronized(captureLock) { activeCapture?.token }

    private fun handleCapturedResponse(url: String, body: String) {
        val expectsThread = synchronized(captureLock) { activeCapture?.threadPostId != null }
        val matches = if (expectsThread) {
            XWebViewInterception.isTweetDetailGraphQlUrl(url)
        } else {
            XWebViewInterception.isHomeTimelineGraphQlUrl(url)
        }
        if (!matches) return
        val response = XWebViewCapturedResponse(body)
        val pending = synchronized(captureLock) {
            val current = activeCapture ?: return
            if (
                XWebViewInterception.shouldSuppressDuplicate(
                    current.previousFingerprint,
                    response.fingerprint,
                    current.threadPostId,
                )
            ) return
            activeCapture = null
            current
        }
        pending.response = response
        pending.latch.countDown()
    }

    private fun cancelCapture(token: Long) {
        val pending = synchronized(captureLock) {
            val current = activeCapture?.takeIf { it.token == token } ?: return
            activeCapture = null
            current
        }
        pending.latch.countDown()
    }

    private fun cancelActiveCapture() {
        val pending = synchronized(captureLock) {
            val current = activeCapture
            activeCapture = null
            current
        }
        pending?.latch?.countDown()
    }

    private fun enterForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "X WebView feed",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val settingsIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, "com.anezium.rokidbus.phone.FeedsSettingsActivity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Nexus Feeds")
            .setContentText("Capturing the selected X timeline")
            .setContentIntent(settingsIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private inner class GraphQlBridge {
        @JavascriptInterface
        fun onGraphQlResponse(url: String, body: String) {
            mainHandler.post { handleCapturedResponse(url, body) }
        }
    }

    private class PendingCapture(
        val token: Long,
        val previousFingerprint: String?,
        val threadPostId: String?,
    ) {
        val latch = CountDownLatch(1)

        @Volatile
        var response: XWebViewCapturedResponse? = null
    }

    private companion object {
        const val HOME_URL = "https://x.com/home"
        const val JAVASCRIPT_BRIDGE_NAME = "NexusXBridge"
        const val REINJECT_DELAY_MILLIS = 100L
        const val NOTIFICATION_CHANNEL_ID = "x_webview_feed"
        const val NOTIFICATION_ID = 1402
        val SCROLL_DELAYS_MILLIS = listOf(0L, 1_000L, 2_500L)
        const val SCROLL_JAVASCRIPT =
            "window.scrollTo(0, Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));"
    }
}
