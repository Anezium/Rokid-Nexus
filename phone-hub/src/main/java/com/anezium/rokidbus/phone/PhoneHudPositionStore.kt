package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.PageTextSize
import com.anezium.rokidbus.shared.PhoneHubCapabilitiesContract

/** Platform-owned wearer preference; plugins never receive or set this value. */
internal class PhoneHudPositionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        NexusPhoneState.PREFS,
        Context.MODE_PRIVATE,
    )

    fun hudTopInsetDp(): Int {
        val stored = runCatching {
            preferences.getInt(
                KEY_HUD_TOP_INSET_DP,
                PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
            )
        }.getOrNull()
        val cleanValue = PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(
            stored ?: PhoneHubCapabilitiesContract.DEFAULT_HUD_TOP_INSET_DP,
        )
        if (stored == null || stored != cleanValue) {
            preferences.edit().putInt(KEY_HUD_TOP_INSET_DP, cleanValue).apply()
        }
        return cleanValue
    }

    fun setHudTopInsetDp(value: Int) {
        preferences.edit()
            .putInt(
                KEY_HUD_TOP_INSET_DP,
                PhoneHubCapabilitiesContract.sanitizeHudTopInsetDp(value),
            )
            .apply()
    }

    fun hudPositionAuto(): Boolean {
        val stored = runCatching {
            preferences.getBoolean(
                KEY_HUD_POSITION_AUTO,
                PhoneHubCapabilitiesContract.DEFAULT_HUD_POSITION_AUTO,
            )
        }.getOrNull()
        val cleanValue = stored ?: PhoneHubCapabilitiesContract.DEFAULT_HUD_POSITION_AUTO
        if (stored == null) {
            preferences.edit().putBoolean(KEY_HUD_POSITION_AUTO, cleanValue).apply()
        }
        return cleanValue
    }

    fun setHudPositionAuto(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HUD_POSITION_AUTO, enabled).apply()
    }

    fun pageTextSize(): PageTextSize {
        val stored = runCatching { preferences.getString(KEY_PAGE_TEXT_SIZE, null) }.getOrNull()
        val cleanValue = PageTextSize.parse(stored)
        if (stored != cleanValue.wire) setPageTextSize(cleanValue)
        return cleanValue
    }

    fun setPageTextSize(size: PageTextSize) {
        preferences.edit().putString(KEY_PAGE_TEXT_SIZE, size.wire).apply()
    }

    private companion object {
        const val KEY_HUD_TOP_INSET_DP = "hud_top_inset_dp"
        const val KEY_HUD_POSITION_AUTO = "hud_position_auto"
        const val KEY_PAGE_TEXT_SIZE = "page_text_size"
    }
}
