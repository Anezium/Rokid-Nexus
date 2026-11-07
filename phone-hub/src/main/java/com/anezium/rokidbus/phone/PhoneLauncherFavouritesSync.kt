package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherFavouritesContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import org.json.JSONObject

/**
 * Sends the owner's launcher favourites to the glasses, whole, on their announce and on every
 * change. Only glasses that announced launcher bundle
 * [LauncherInteractiveContract.FAVOURITES_VERSION] or later get it; older ones have no root to
 * draw it on and would log an unknown core route.
 */
class PhoneLauncherFavouritesSync(
    private val store: PhoneLauncherFavouritesStore,
    private val send: (path: String, payload: JSONObject) -> String?,
    private val launcherVersion: () -> Int?,
    private val log: (String) -> Unit,
) {
    fun push() {
        if ((launcherVersion() ?: 0) < LauncherInteractiveContract.FAVOURITES_VERSION) return
        val favourites = store.favourites()
        val error = send(BusPaths.CORE_LAUNCHER_FAVOURITES, LauncherFavouritesContract.toPayload(favourites))
        if (error == null) {
            log("launcher favourites sent revision=${favourites.revision} entries=${favourites.entries.size}")
        } else {
            log("launcher favourites not sent code=$error")
        }
    }
}
