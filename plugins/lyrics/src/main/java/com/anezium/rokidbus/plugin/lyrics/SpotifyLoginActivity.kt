package com.anezium.rokidbus.plugin.lyrics

import com.anezium.rokidbus.client.ui.NexusUi
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.anezium.rokidbus.lyrics.LyricsRuntimeGraph
import com.anezium.rokidbus.lyrics.settings.LyricsProviderSettingsStore

class SpotifyLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var captureHandled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(NexusUi.GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(NexusUi.LINE)
        }
        webView = WebView(this)
        setContentView(
            NexusUi.fixedRoot(this).apply {
                addView(
                    progressBar,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        NexusUi.dp(this@SpotifyLoginActivity, 3),
                    ),
                )
                addView(
                    webView,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            },
        )

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Spotify's login page rejects obvious WebView user agents.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                captureSpDcIfAvailable()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                if (request?.isForMainFrame == true) {
                    captureSpDcIfAvailable()
                }
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            },
        )

        // Start from a clean WebView session: a stale sp_dc left over from an
        // earlier visit must never be captured in place of a fresh login.
        CookieManager.getInstance().removeAllCookies {
            WebStorage.getInstance().deleteAllData()
            webView.loadUrl(SPOTIFY_LOGIN_URL)
        }
    }

    private fun captureSpDcIfAvailable(): Boolean {
        if (captureHandled) return true

        val cookieManager = CookieManager.getInstance()
        val spDc = spDcFromCookieHeaders(
            cookieManager.getCookie(SPOTIFY_WEB_URL),
            cookieManager.getCookie(SPOTIFY_ACCOUNTS_URL),
        ) ?: return false

        captureHandled = true
        if (LyricsProviderSettingsStore(applicationContext).saveSpotifySpDc(spDc)) {
            LyricsRuntimeGraph.onSpotifyCookieChanged()
            Toast.makeText(this, "Spotify connected.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Secure storage unavailable; Spotify was not connected.",
                Toast.LENGTH_LONG,
            ).show()
        }

        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        WebStorage.getInstance().deleteAllData()
        finish()
        return true
    }

    override fun onDestroy() {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        private const val SPOTIFY_LOGIN_URL =
            "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        private const val SPOTIFY_WEB_URL = "https://open.spotify.com"
        private const val SPOTIFY_ACCOUNTS_URL = "https://accounts.spotify.com"
    }
}
