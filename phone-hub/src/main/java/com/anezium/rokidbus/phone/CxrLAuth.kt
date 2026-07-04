package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object CxrLAuth {
    private const val GLOBAL_AI_APP_PACKAGE = "com.rokid.sprite.global.aiapp"
    private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
    private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
    private const val EXTRA_AUTH_RESULT = "auth_result"
    private const val EXTRA_AUTH_TOKEN = "auth_token"
    private const val AUTH_RESULT_SUCCESS = 2001
    private const val AUTH_RESULT_CANCEL = 2003

    sealed class Result {
        data class Success(val token: String) : Result()
        object Cancel : Result()
        data class Fail(val reason: String) : Result()
    }

    fun isGlobalHiRokidInstalled(activity: Activity): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(
                    GLOBAL_AI_APP_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(GLOBAL_AI_APP_PACKAGE, 0)
            }
        }.isSuccess

    fun requestAuthorization(activity: Activity, requestCode: Int): Result? {
        if (!isGlobalHiRokidInstalled(activity)) {
            return Result.Fail("Global Hi Rokid is not installed")
        }

        return runCatching {
            val explicit = Intent().setComponent(
                ComponentName(GLOBAL_AI_APP_PACKAGE, AUTH_ACTIVITY_CLASS),
            )
            @Suppress("DEPRECATION")
            activity.startActivityForResult(explicit, requestCode)
            null
        }.recoverCatching {
            val fallback = Intent(AUTH_ACTION).setPackage(GLOBAL_AI_APP_PACKAGE)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(fallback, requestCode)
            null
        }.getOrElse { error ->
            Result.Fail(error.message ?: error.javaClass.simpleName)
        }
    }

    fun parseAuthorizationResult(resultCode: Int, data: Intent?): Result {
        if (resultCode != Activity.RESULT_OK || data == null) return Result.Cancel
        return when (data.getIntExtra(EXTRA_AUTH_RESULT, -1)) {
            AUTH_RESULT_SUCCESS -> {
                val token = data.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
                if (token.isBlank()) Result.Fail("Authorization returned an empty token")
                else Result.Success(token)
            }
            AUTH_RESULT_CANCEL -> Result.Cancel
            else -> Result.Fail("Authorization failed")
        }
    }
}
