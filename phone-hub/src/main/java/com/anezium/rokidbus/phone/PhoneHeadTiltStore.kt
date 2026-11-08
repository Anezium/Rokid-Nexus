package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.HeadTiltContract
import com.anezium.rokidbus.shared.HeadTiltSettings
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

internal class PhoneHeadTiltStore(context: Context) {
    private val preferences = context.getSharedPreferences("head_tilt", Context.MODE_PRIVATE)
    fun settings(): HeadTiltSettings = runCatching {
        HeadTiltContract.parse(JSONObject(preferences.getString("config", "{}").orEmpty()))
    }.getOrNull() ?: HeadTiltSettings()

    fun save(value: HeadTiltSettings) {
        preferences.edit().putString("config", HeadTiltContract.toJson(value).toString()).apply()
        BusHubService.onHeadTiltPreferenceChanged()
    }

    fun stockDisabled(): Boolean = preferences.getBoolean("stock_disabled", false)
    fun setStockDisabled(value: Boolean) {
        preferences.edit().putBoolean("stock_disabled", value).apply()
        if (!value) save(settings().copy(enabled = false))
    }
}

internal object PhoneHeadTiltPreview {
    private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    fun observe(listener: (JSONObject) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }
    fun receive(payload: JSONObject) {
        if (payload.optInt("v") == HeadTiltContract.VERSION) listeners.forEach { it(payload) }
    }
}
