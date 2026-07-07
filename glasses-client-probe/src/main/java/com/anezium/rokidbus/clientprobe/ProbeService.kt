package com.anezium.rokidbus.clientprobe

import android.util.Log
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusClientService
import com.anezium.rokidbus.client.BusEvent
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ROKIDBUS-CLIENT"
private const val PATH_PROBE = "/probe"
private const val PATH_ECHO = "/probe/echo"
private const val PATH_ECHO_REPLY = "/probe/echo/reply"
private const val PATH_HTTP_TRIGGER = "/probe/http"
private const val PATH_HTTP_TRIGGER_REPLY = "/probe/http/reply"
private const val PATH_HTTP_REQUEST = "/http/request"
private const val PATH_HTTP_REPLY = "/http/request/reply"
private const val DEFAULT_HTTP_URL = "https://api.transitous.org/api/v1/geocode?text=Paris"

class ProbeService : BusClientService() {
    private val pendingHttp = ConcurrentHashMap<String, Long>()
    private var client: BusClient? = null

    override fun createBusClient(): BusClient {
        val next = BusClient(
            context = applicationContext,
            clientId = "glasses-client-probe",
            pathPrefixes = listOf(PATH_PROBE, PATH_HTTP_REPLY),
        ) { event -> handleEvent(event) }
        client = next
        Log.i(TAG, "ProbeService BusClient created")
        return next
    }

    private fun handleEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> Log.i(TAG, "linkState=${event.state}")
            is BusEvent.Error -> Log.w(TAG, event.message, event.cause)
            is BusEvent.Message -> handleMessage(event)
            is BusEvent.Binary -> handleBinary(event)
        }
    }

    private fun handleMessage(event: BusEvent.Message) {
        when (event.path) {
            PATH_ECHO -> {
                Log.i(TAG, "echo request id=${event.id} payloadBytes=${event.payload.toString().length}")
                val reply = JSONObject(event.payload.toString())
                    .put("side", "glasses")
                    .put("payloadBytes", event.payload.toString().length)
                client?.send(PATH_ECHO_REPLY, event.id, reply)
            }
            PATH_ECHO_REPLY -> {
                Log.i(TAG, "echo reply observed id=${event.id} payloadBytes=${event.payload.toString().length}")
            }
            PATH_HTTP_TRIGGER -> {
                val url = event.payload.optString("url", DEFAULT_HTTP_URL)
                pendingHttp[event.id] = 0L
                Log.i(TAG, "HTTP via bus requested id=${event.id} url=$url")
                client?.send(PATH_HTTP_REQUEST, event.id, JSONObject().put("url", url))
            }
            PATH_HTTP_REPLY -> Log.w(TAG, "unexpected JSON HTTP reply id=${event.id}")
            else -> Log.i(TAG, "message path=${event.path} id=${event.id}")
        }
    }

    private fun handleBinary(event: BusEvent.Binary) {
        when (event.path) {
            PATH_HTTP_REPLY -> handleHttpReply(event)
            else -> Log.i(TAG, "binary path=${event.path} id=${event.id} dataBytes=${event.data.size}")
        }
    }

    private fun handleHttpReply(event: BusEvent.Binary) {
        val bytes = event.meta.optLong("bytes", event.data.size.toLong())
        if (bytes > 0) {
            pendingHttp.compute(event.id) { _, current -> (current ?: 0L) + bytes }
        }
        val total = event.meta.optLong("totalBytes", pendingHttp[event.id] ?: 0L)
        if (event.meta.optBoolean("done", false)) {
            pendingHttp.remove(event.id)
            Log.i(TAG, "HTTP done id=${event.id} status=${event.meta.optInt("status")} totalBytes=$total")
            client?.send(
                PATH_HTTP_TRIGGER_REPLY,
                event.id,
                JSONObject()
                    .put("status", event.meta.optInt("status"))
                    .put("totalBytes", total)
                    .put("side", "glasses"),
            )
        } else {
            Log.i(TAG, "HTTP chunk id=${event.id} bytes=$bytes dataBytes=${event.data.size}")
        }
    }
}
