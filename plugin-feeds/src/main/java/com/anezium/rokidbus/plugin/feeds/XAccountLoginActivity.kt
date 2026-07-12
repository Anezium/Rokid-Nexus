package com.anezium.rokidbus.plugin.feeds

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import com.anezium.rokidbus.client.ui.NexusUi

class XAccountLoginActivity : Activity() {
    private val settingsStore by lazy { FeedsSettingsStore(applicationContext) }
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(NexusUi.GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(NexusUi.LINE)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = XWebViewInterception.USER_AGENT
            cookieManager.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (isHomeUrl(url)) captureCookies(cookieManager)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
                }
            }
        }
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(
                    progressBar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@XAccountLoginActivity, 3),
                    ),
                )
                addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            },
        )
        webView.loadUrl(LOGIN_URL)
    }

    private fun captureCookies(cookieManager: CookieManager) {
        if (completed) return
        val cookies = XAccountCookies.fromCookieHeader(cookieManager.getCookie(LOGIN_URL).orEmpty())
        if (!cookies.isConnected) return
        completed = true
        settingsStore.saveXAccountCookies(cookies)
        cookieManager.flush()
        setResult(RESULT_OK)
        Toast.makeText(this, "X account connected", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val LOGIN_URL = "https://x.com/i/flow/login"

        fun isHomeUrl(url: String): Boolean = runCatching {
            val parsed = android.net.Uri.parse(url)
            parsed.host.equals("x.com", ignoreCase = true) && parsed.path == "/home"
        }.getOrDefault(false)
    }
}
