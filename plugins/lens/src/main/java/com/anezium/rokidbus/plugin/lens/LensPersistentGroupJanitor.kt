package com.anezium.rokidbus.plugin.lens

import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class LensPersistentGroup(
    val networkName: String,
    val networkId: Int,
)

internal object LensPersistentGroupPolicy {
    private const val NETWORK_NAME_PREFIX = "DIRECT-RN-"

    fun isOwnedGroup(networkName: String): Boolean =
        networkName.startsWith(NETWORK_NAME_PREFIX)

    fun networkIdsToDelete(groups: List<LensPersistentGroup>): List<Int> = groups
        .filter { isOwnedGroup(it.networkName) }
        .map { it.networkId }
}

/** Reflection-free seam for testing persistent-group selection and deletion decisions. */
internal interface LensPersistentGroupStore {
    fun requestGroups(
        onGroups: (List<LensPersistentGroup>) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun deleteGroup(
        networkId: Int,
        onResult: (Boolean) -> Unit,
        onFailure: (Throwable) -> Unit,
    )
}

/** Best-effort removal of only the persistent P2P profiles created by the Nexus camera link. */
internal class LensPersistentGroupJanitor(
    private val logger: (String) -> Unit,
) {
    fun clean(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        onComplete: () -> Unit = {},
    ) {
        clean(ReflectiveLensPersistentGroupStore(manager, channel), onComplete)
    }

    internal fun clean(
        store: LensPersistentGroupStore,
        onComplete: () -> Unit = {},
    ) {
        val finished = AtomicBoolean(false)

        fun finish() {
            if (finished.compareAndSet(false, true)) onComplete()
        }

        fun fail(failure: Throwable) {
            reportFailure(failure)
            finish()
        }

        try {
            store.requestGroups(
                onGroups = groups@{ groups ->
                    val networkIds = try {
                        LensPersistentGroupPolicy.networkIdsToDelete(groups)
                    } catch (failure: Throwable) {
                        fail(failure)
                        return@groups
                    }
                    if (networkIds.isEmpty()) {
                        logger("lensLinkJanitor deleted=0 of=0")
                        finish()
                        return@groups
                    }

                    val deleted = AtomicInteger(0)
                    val completed = AtomicInteger(0)
                    networkIds.forEach { networkId ->
                        val callbackDelivered = AtomicBoolean(false)

                        fun recordResult(wasDeleted: Boolean) {
                            if (!callbackDelivered.compareAndSet(false, true)) return
                            if (wasDeleted) deleted.incrementAndGet()
                            if (completed.incrementAndGet() == networkIds.size) {
                                logger(
                                    "lensLinkJanitor deleted=${deleted.get()} " +
                                        "of=${networkIds.size}",
                                )
                                finish()
                            }
                        }

                        try {
                            store.deleteGroup(
                                networkId = networkId,
                                onResult = ::recordResult,
                                onFailure = { failure ->
                                    reportFailure(failure)
                                    recordResult(false)
                                },
                            )
                        } catch (failure: Throwable) {
                            reportFailure(failure)
                            recordResult(false)
                        }
                    }
                },
                onFailure = ::fail,
            )
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    fun reportFailure(failure: Throwable) {
        if (!janitorFailureLogged.compareAndSet(false, true)) return
        logger("lensLinkJanitor unavailable type=${rootCause(failure).javaClass.simpleName}")
    }

    private fun rootCause(failure: Throwable): Throwable =
        if (failure is InvocationTargetException && failure.cause != null) {
            failure.cause!!
        } else {
            failure
        }

    private companion object {
        val janitorFailureLogged = AtomicBoolean(false)
    }
}

/** Hidden WifiP2pManager APIs are contained here so the janitor remains unit-testable. */
private class ReflectiveLensPersistentGroupStore(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
) : LensPersistentGroupStore {
    private var deletePersistentGroup: Method? = null

    override fun requestGroups(
        onGroups: (List<LensPersistentGroup>) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            val listenerClass = Class.forName(PERSISTENT_GROUP_LISTENER_CLASS)
            val requestPersistentGroupInfo = WifiP2pManager::class.java.getMethod(
                "requestPersistentGroupInfo",
                WifiP2pManager.Channel::class.java,
                listenerClass,
            )
            deletePersistentGroup = WifiP2pManager::class.java.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
            val callbackDelivered = AtomicBoolean(false)
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "onPersistentGroupInfoAvailable" -> {
                        if (callbackDelivered.compareAndSet(false, true)) {
                            try {
                                onGroups(readGroups(arguments?.getOrNull(0)))
                            } catch (failure: Throwable) {
                                onFailure(failure)
                            }
                        }
                        null
                    }
                    "equals" -> proxy === arguments?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "LensPersistentGroupInfoListener"
                    else -> null
                }
            }
            requestPersistentGroupInfo.invoke(manager, channel, listener)
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }

    override fun deleteGroup(
        networkId: Int,
        onResult: (Boolean) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        try {
            val method = deletePersistentGroup
                ?: throw NoSuchMethodException("deletePersistentGroup")
            method.invoke(
                manager,
                channel,
                networkId,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = onResult(true)

                    override fun onFailure(reason: Int) = onResult(false)
                },
            )
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }

    private fun readGroups(groupList: Any?): List<LensPersistentGroup> {
        if (groupList == null) return emptyList()
        val groups = groupList.javaClass.getMethod("getGroupList").invoke(groupList) as? Iterable<*>
            ?: throw ReflectiveOperationException("getGroupList returned a non-iterable value")
        return groups.mapNotNull { group ->
            if (group == null) return@mapNotNull null
            val networkName = group.javaClass.getMethod("getNetworkName").invoke(group) as? String
                ?: return@mapNotNull null
            val networkId = group.javaClass.getMethod("getNetworkId").invoke(group) as? Number
                ?: return@mapNotNull null
            LensPersistentGroup(networkName, networkId.toInt())
        }
    }

    private companion object {
        const val PERSISTENT_GROUP_LISTENER_CLASS =
            "android.net.wifi.p2p.WifiP2pManager\$PersistentGroupInfoListener"
    }
}

/** Marks credential joins temporary on platforms that retain AOSP's public netId field. */
internal object LensTemporaryP2pConfig {
    private val failureLogged = AtomicBoolean(false)

    fun apply(config: WifiP2pConfig, logger: (String) -> Unit) {
        try {
            WifiP2pConfig::class.java.getField("netId").setInt(
                config,
                WifiP2pGroup.NETWORK_ID_TEMPORARY,
            )
        } catch (failure: Throwable) {
            if (failureLogged.compareAndSet(false, true)) {
                logger(
                    "lensLinkTemporaryNetId unavailable " +
                        "type=${rootCause(failure).javaClass.simpleName}",
                )
            }
        }
    }

    private fun rootCause(failure: Throwable): Throwable =
        if (failure is InvocationTargetException && failure.cause != null) {
            failure.cause!!
        } else {
            failure
        }
}
