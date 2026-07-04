package com.anezium.rokidbus.client

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

private const val TAG = "RokidBusClientSvc"

open class BusClientService : Service() {
    interface Factory {
        fun create(context: android.content.Context): BusClient
    }

    companion object {
        @Volatile private var factory: Factory? = null

        fun registerFactory(factory: Factory) {
            this.factory = factory
        }
    }

    private val binder = Binder()
    private var client: BusClient? = null

    override fun onCreate() {
        super.onCreate()
        ensureClient()
    }

    override fun onBind(intent: Intent?): IBinder {
        ensureClient()
        return binder
    }

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
    }

    protected open fun createBusClient(): BusClient? =
        factory?.create(applicationContext)

    private fun ensureClient() {
        if (client != null) return
        client = createBusClient()?.also {
            it.connect()
            Log.i(TAG, "BusClient created by ${javaClass.name}")
        }
        if (client == null) {
            Log.w(TAG, "No BusClient factory for ${javaClass.name}")
        }
    }
}
