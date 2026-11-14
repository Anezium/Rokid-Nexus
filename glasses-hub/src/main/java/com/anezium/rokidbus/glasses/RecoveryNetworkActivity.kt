package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.anezium.rokidbus.shared.GlassesFilesHotspotCredentials

/** Private, bounded foreground owner of Android's temporary network request. */
class RecoveryNetworkActivity : Activity() {
    private var id = ""
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("recoveryId").orEmpty()
        if (!SelfArmHotspotRecovery.attach(this, id)) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Restoring glasses controls…\n\nConnecting to your phone"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            textSize = 24f
            gravity = Gravity.CENTER
        })
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else prepare()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 1) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) prepare()
        else SelfArmHotspotRecovery.abandoned(id)
    }

    private fun prepare() {
        if (!getSystemService(LocationManager::class.java).isLocationEnabled) {
            SelfArmHotspotRecovery.abandoned(id)
            return
        }
        SelfArmHotspotRecovery.prepareNetwork(id)
    }

    internal fun connect(credentials: GlassesFilesHotspotCredentials) {
        if (callback != null) return
        runCatching {
            val specifier = WifiNetworkSpecifier.Builder().setSsid(credentials.ssid).apply {
                when (credentials.security) {
                    "wpa2" -> setWpa2Passphrase(credentials.passphrase)
                    "wpa3" -> setWpa3Passphrase(credentials.passphrase)
                    else -> error("Unsupported recovery network")
                }
            }.build()
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
            val listener = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread { SelfArmHotspotRecovery.networkAvailable(id, network) }
                }
                override fun onUnavailable() { runOnUiThread { SelfArmHotspotRecovery.abandoned(id) } }
                override fun onLost(network: Network) { runOnUiThread { SelfArmHotspotRecovery.abandoned(id) } }
            }
            callback = listener
            getSystemService(ConnectivityManager::class.java).requestNetwork(request, listener, 60_000)
        }.onFailure {
            // Framework exception messages can contain network credentials.
            log("Hotspot recovery request failed type=${it.javaClass.simpleName}")
            SelfArmHotspotRecovery.abandoned(id)
        }
    }

    internal fun releaseNetwork() {
        val listener = callback ?: return
        callback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(listener) }
    }

    override fun onDestroy() {
        releaseNetwork()
        SelfArmHotspotRecovery.abandoned(id)
        super.onDestroy()
    }
}
