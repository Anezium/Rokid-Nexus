package com.anezium.rokidbus.plugin.feeds

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class XAccountLoginActivity : Activity() {
    private val settingsStore by lazy { FeedsSettingsStore(applicationContext) }
    private lateinit var webView: WebView
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }
        webView = WebView(this).apply {
            setBackgroundColor(0xff030c06.toInt())
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
        }
        setContentView(webView)
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

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://x.com/i/flow/login"
        private fun isHomeUrl(url: String): Boolean = runCatching {
            val parsed = android.net.Uri.parse(url)
            parsed.host.equals("x.com", ignoreCase = true) && parsed.path == "/home"
        }.getOrDefault(false)
    }
}
