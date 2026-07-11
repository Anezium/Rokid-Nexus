package com.anezium.rokidbus.phoneprobe

import android.util.Log
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusClientService
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.HubTarget
import org.json.JSONObject

private const val TAG = "ROKIDBUS-CLIENT"
private const val PATH_PROBE = "/probe"
private const val PATH_ECHO = "/probe/echo"
private const val PATH_ECHO_REPLY = "/probe/echo/reply"

class ProbeService : BusClientService() {
    private var client: BusClient? = null

    override fun createBusClient(): BusClient {
        val next = BusClient(
            context = applicationContext,
            clientId = "phone-client-probe",
            pathPrefixes = listOf(PATH_PROBE),
            hubTarget = HubTarget.PHONE,
        ) { event -> handleEvent(event) }
        client = next
        Log.i(TAG, "Phone ProbeService BusClient created")
        return next
    }

    private fun handleEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> Log.i(TAG, "phone linkState=${event.state}")
            is BusEvent.Error -> Log.w(TAG, event.message, event.cause)
            is BusEvent.Message -> handleMessage(event)
            is BusEvent.Binary -> Log.i(TAG, "phone binary path=${event.path} id=${event.id} dataBytes=${event.data.size}")
        }
    }

    private fun handleMessage(event: BusEvent.Message) {
        when (event.path) {
            PATH_ECHO -> {
                Log.i(TAG, "phone echo request id=${event.id} payloadBytes=${event.payload.toString().length}")
                val reply = JSONObject(event.payload.toString())
                    .put("side", "phone")
                    .put("payloadBytes", event.payload.toString().length)
                client?.send(PATH_ECHO_REPLY, event.id, reply)
            }
            PATH_ECHO_REPLY -> {
                Log.i(TAG, "phone echo reply observed id=${event.id} payloadBytes=${event.payload.toString().length}")
            }
            else -> Log.i(TAG, "phone message path=${event.path} id=${event.id}")
        }
    }
}
