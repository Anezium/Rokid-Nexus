package com.anezium.rokidbus.plugin.lens

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** The plugin never falls back to plaintext when Android Keystore is unavailable. */
internal fun lensPreferences(context: Context): SharedPreferences {
    val appContext = context.applicationContext
    val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    return EncryptedSharedPreferences.create(
        LENS_TRANSLATION_PREFS_NAME,
        masterKeyAlias,
        appContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
