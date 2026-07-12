package com.anezium.rokidbus.glasses

data class ImageDecodeKey(
    val surfaceId: String,
    val seq: Long,
    val contentKey: String,
)

sealed interface ImageDecodeCompletion<out T> {
    data class Accepted<T>(val replaced: T?) : ImageDecodeCompletion<T>
    data class Rejected<T>(val stale: T) : ImageDecodeCompletion<T>
}

/** Generic state machine so stale async decode races are testable without Android. */
class ImageDecodeCoordinator<T> {
    private var currentKey: ImageDecodeKey? = null
    private var currentValue: T? = null

    @Synchronized
    fun begin(key: ImageDecodeKey): T? {
        val replaced = currentValue
        currentKey = key
        currentValue = null
        return replaced
    }

    @Synchronized
    fun complete(key: ImageDecodeKey, value: T): ImageDecodeCompletion<T> {
        if (currentKey != key) return ImageDecodeCompletion.Rejected(value)
        val replaced = currentValue
        currentValue = value
        return ImageDecodeCompletion.Accepted(replaced)
    }

    @Synchronized
    fun cancel(key: ImageDecodeKey): Boolean {
        if (currentKey != key) return false
        currentKey = null
        currentValue = null
        return true
    }

    @Synchronized
    fun invalidate(surfaceId: String? = null): T? {
        if (surfaceId != null && currentKey?.surfaceId != surfaceId) return null
        val detached = currentValue
        currentKey = null
        currentValue = null
        return detached
    }
}
