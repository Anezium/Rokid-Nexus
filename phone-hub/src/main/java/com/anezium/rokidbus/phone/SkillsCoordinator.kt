package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.skills.CanonicalJson
import com.anezium.rokidbus.shared.skills.SkillAliases
import com.anezium.rokidbus.shared.skills.SkillAvailability
import com.anezium.rokidbus.shared.skills.SkillCatalog
import com.anezium.rokidbus.shared.skills.SkillCatalogEntry
import com.anezium.rokidbus.shared.skills.SkillChoice
import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillEffect
import com.anezium.rokidbus.shared.skills.SkillError
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillInputRequest
import com.anezium.rokidbus.shared.skills.SkillInvokeRequest
import com.anezium.rokidbus.shared.skills.SkillLimits
import com.anezium.rokidbus.shared.skills.SkillOperation
import com.anezium.rokidbus.shared.skills.SkillProviderInvocation
import com.anezium.rokidbus.shared.skills.SkillResultEnvelope
import com.anezium.rokidbus.shared.skills.SkillSchema
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillStatus
import com.anezium.rokidbus.shared.skills.SkillValidation
import com.anezium.rokidbus.shared.skills.SkillsContract
import org.json.JSONObject
import java.security.MessageDigest

/** What the coordinator needs from the hub; the Android implementation lives in BusHubService. */
interface SkillsHost {
    /** Valid installed principals as last discovered, catalogs included. */
    fun installedPrincipals(): List<PhonePluginPrincipal>

    /** The principal's approved descriptor grants, or null unless it is approved and enabled. */
    fun grantedCapabilities(principal: PhonePluginPrincipal): Set<PluginCapability>?

    fun isRegistered(principal: PhonePluginPrincipal): Boolean

    /** Delivers to the principal's live registration only; false when there is none. */
    fun deliver(principal: PhonePluginPrincipal, path: String, id: String, payload: JSONObject): Boolean

    fun deliverError(principal: PhonePluginPrincipal, forId: String, code: String)

    /** Binds the provider for invocations only, independently of the foreground and audio slots. */
    fun bindLease(principal: PhonePluginPrincipal): Boolean
    fun unbindLease(principal: PhonePluginPrincipal)

    /** The provider holds an ordinary display session: foreground, audio background, or camera. */
    fun holdsDisplaySession(principal: PhonePluginPrincipal): Boolean

    fun schedule(key: String, delayMs: Long, action: () -> Unit)
    fun cancel(key: String)
    fun elapsedMs(): Long
    fun wallMs(): Long

    /** A fresh URL-safe random token of exactly [length] characters. */
    fun randomToken(length: Int): String

    fun record(entry: SkillJournalEntry)
}

/** Everything the journal may know about one invocation: no arguments, results, or handles. */
data class SkillJournalEntry(
    val providerId: String,
    val operationId: String,
    val durationMs: Long,
    val status: SkillStatus,
    val errorCode: String?,
    val dispatch: SkillDispatch?,
)

/**
 * The phone hub's side of Skills v1 (see BUSSPEC): catalog exposure, authorization, the
 * invocation lease, entity references, the duplicate ledger, limits, deadlines, and every way an
 * invocation ends. Identities come only from authenticated registrations; the payloads from
 * either plugin never name, grant, or route anything by themselves.
 *
 * All state is in memory and discarded with the hub process, which is the reference lifetime
 * the plan asks for. Every entry point takes the same lock.
 */
class SkillsCoordinator(
    private val host: SkillsHost,
    private val grants: SkillGrantStore,
) {
    private data class SessionKey(val caller: PluginGrantKey, val session: String)

    private class Reference(
        val handle: String,
        val provider: PluginGrantKey,
        val revision: Long,
        val catalogDigest: String,
        val type: String,
        val value: String,
        var lastUsedMs: Long,
    )

    private class LedgerEntry(
        val argumentsDigest: String,
        var result: SkillResultEnvelope? = null,
        var extraReplies: Int = 0,
    )

    private class Session(val key: SessionKey, var lastActivityMs: Long) {
        val references = LinkedHashMap<String, Reference>()
        val ledger = LinkedHashMap<String, LedgerEntry>()
    }

    private enum class Phase { AWAITING_REGISTRATION, DISPATCHED }

    private class Invocation(
        val id: String,
        val caller: PhonePluginPrincipal,
        val session: SessionKey,
        val requestKey: String,
        val alias: String,
        val provider: PhonePluginPrincipal,
        val catalog: SkillCatalog,
        val operation: SkillOperation,
        val providerArguments: JSONObject,
        val acceptedAtMs: Long,
        val deadlineAtMs: Long,
        var phase: Phase,
    )

    private data class Exposed(
        val alias: String,
        val provider: PhonePluginPrincipal,
        val catalog: SkillCatalog,
        val operation: SkillOperation,
        val availability: SkillAvailability,
    )

    private val lock = Any()
    private val sessions = LinkedHashMap<SessionKey, Session>()
    private val invocations = LinkedHashMap<String, Invocation>()
    private val leased = mutableSetOf<PluginGrantKey>()

    /** Providers the lease itself started: they had no registration when their first call came. */
    private val coldStartedByLease = mutableSetOf<PluginGrantKey>()

    // Catalog lookup.

    fun catalogRequest(caller: PhonePluginPrincipal, requestId: String) = synchronized(lock) {
        val entries = exposedOperations(caller).map { exposed ->
            SkillCatalogEntry(
                alias = exposed.alias,
                providerId = exposed.provider.descriptor.id,
                providerName = exposed.provider.descriptor.displayName,
                operationId = exposed.operation.id,
                version = exposed.operation.version,
                label = exposed.operation.label,
                description = exposed.operation.description,
                examples = exposed.operation.examples,
                effect = exposed.operation.effect,
                cancellable = exposed.operation.cancellable,
                availability = exposed.availability,
                input = exposed.operation.input,
                dataCategories = exposed.operation.dataCategories,
            )
        }
        host.deliver(
            caller,
            BusPaths.SKILLS_CATALOG_REPLY,
            requestId,
            SkillsContract.catalogReply(caller.descriptor.id, entries),
        )
    }

    private fun exposedOperations(caller: PhonePluginPrincipal): List<Exposed> {
        val callerGrants = host.grantedCapabilities(caller) ?: return emptyList()
        if (PluginCapability.SKILLS_CLIENT !in callerGrants) return emptyList()
        val taken = mutableSetOf<String>()
        val exposed = mutableListOf<Exposed>()
        host.installedPrincipals().sortedBy { it.descriptor.id }.forEach { provider ->
            val providerGrants = host.grantedCapabilities(provider) ?: return@forEach
            if (PluginCapability.SKILLS_PROVIDER !in providerGrants) return@forEach
            val catalog = provider.skillCatalog ?: return@forEach
            catalog.operations.sortedBy(SkillOperation::id).forEach { operation ->
                if (!grants.isApproved(caller, provider, operation)) return@forEach
                val alias = SkillAliases.generate(provider.descriptor.id, operation.id, taken)
                taken += alias
                val availability = if (providerGrants.containsAll(operation.requires)) {
                    SkillAvailability.READY
                } else {
                    SkillAvailability.SETUP_REQUIRED
                }
                exposed += Exposed(alias, provider, catalog, operation, availability)
            }
        }
        return exposed.take(SkillLimits.MAX_EXPOSED_OPERATIONS)
    }

    // Invocation.

    fun invoke(caller: PhonePluginPrincipal, requestId: String, payload: JSONObject) = synchronized(lock) {
        val request = SkillsContract.parseInvokeRequest(payload)
        if (request == null) {
            host.deliverError(caller, requestId, ERROR_INVALID_REQUEST)
            return@synchronized
        }
        val callerGrants = host.grantedCapabilities(caller)
        if (callerGrants == null || PluginCapability.SKILLS_CLIENT !in callerGrants) {
            reject(caller, request, SkillErrorCodes.PERMISSION_REQUIRED)
            return@synchronized
        }
        val now = host.elapsedMs()
        val session = sessionFor(caller, request.session, now)
        val argumentsDigest = digest(CanonicalJson.write(request.arguments) + "\u0000" + request.alias)
        session.ledger[request.requestKey]?.let { entry ->
            if (entry.argumentsDigest != argumentsDigest) {
                reject(caller, request, SkillErrorCodes.OPERATION_CONFLICT)
            } else {
                val cached = entry.result
                if (cached != null) deliverResult(caller, cached) else entry.extraReplies += 1
            }
            return@synchronized
        }
        val exposed = exposedOperations(caller).firstOrNull { it.alias == request.alias }
        if (exposed == null) {
            reject(caller, request, SkillErrorCodes.UNSUPPORTED_OPERATION)
            return@synchronized
        }
        if (exposed.availability != SkillAvailability.READY) {
            reject(caller, request, SkillErrorCodes.SETUP_REQUIRED, exposed)
            return@synchronized
        }
        if (SkillsContract.utf8Size(request.arguments) > SkillLimits.MAX_ARGUMENTS_BYTES ||
            SkillSchemaValidator.validate(request.arguments, exposed.operation.input) is SkillValidation.Invalid
        ) {
            reject(caller, request, SkillErrorCodes.INVALID_ARGUMENTS, exposed)
            return@synchronized
        }
        val mapped = SkillSchemaValidator.mapReferences(request.arguments, exposed.operation.input) { type, handle ->
            resolveHandle(session, exposed, type, handle, now)
        }
        val providerArguments = when (mapped) {
            is SkillSchemaValidator.ReferenceMapping.Mapped -> mapped.instance
            is SkillSchemaValidator.ReferenceMapping.Rejected -> {
                reject(caller, request, SkillErrorCodes.STALE_REFERENCE, exposed)
                return@synchronized
            }
        }
        val executingInSession = invocations.values.count { it.session == session.key }
        if (executingInSession >= SkillLimits.MAX_EXECUTING_PER_SESSION ||
            invocations.size >= SkillLimits.MAX_LIVE_INVOCATIONS
        ) {
            reject(caller, request, SkillErrorCodes.BUSY, exposed)
            return@synchronized
        }

        val invocation = Invocation(
            id = newInvocationId(),
            caller = caller,
            session = session.key,
            requestKey = request.requestKey,
            alias = request.alias,
            provider = exposed.provider,
            catalog = exposed.catalog,
            operation = exposed.operation,
            providerArguments = providerArguments,
            acceptedAtMs = now,
            deadlineAtMs = now + SkillLimits.INVOCATION_DEADLINE_MS,
            phase = Phase.AWAITING_REGISTRATION,
        )
        invocations[invocation.id] = invocation
        session.ledger[request.requestKey] = LedgerEntry(argumentsDigest)
        trimLedger(session)
        host.schedule(deadlineKey(invocation.id), SkillLimits.INVOCATION_DEADLINE_MS) {
            onDeadline(invocation.id)
        }
        acquireLease(invocation)
    }

    private fun acquireLease(invocation: Invocation) {
        val key = invocation.provider.grantKey()
        val registered = host.isRegistered(invocation.provider)
        if (key !in leased) {
            if (!host.bindLease(invocation.provider)) {
                finish(invocation, failure(invocation, SkillErrorCodes.UNAVAILABLE))
                return
            }
            leased += key
            if (!registered) coldStartedByLease += key
        }
        if (registered) {
            dispatch(invocation)
        } else {
            val wait = minOf(SkillLimits.REGISTRATION_TIMEOUT_MS, invocation.deadlineAtMs - host.elapsedMs())
            host.schedule(registrationKey(invocation.id), wait.coerceAtLeast(0L)) {
                onRegistrationTimeout(invocation.id)
            }
        }
    }

    private fun dispatch(invocation: Invocation) {
        host.cancel(registrationKey(invocation.id))
        val remaining = invocation.deadlineAtMs - host.elapsedMs()
        if (remaining <= 0L) {
            finish(invocation, failure(invocation, SkillErrorCodes.DEADLINE_EXCEEDED))
            return
        }
        // The provider that registered must still be the one whose catalog the call was validated against.
        val current = host.installedPrincipals().firstOrNull { it.grantKey() == invocation.provider.grantKey() }
        if (current == null || current.packageRevision != invocation.provider.packageRevision ||
            current.skillCatalog?.digest != invocation.catalog.digest ||
            !stillAuthorized(invocation)
        ) {
            finish(invocation, failure(invocation, SkillErrorCodes.UNAVAILABLE))
            return
        }
        val delivered = host.deliver(
            invocation.provider,
            BusPaths.SKILLS_PROVIDER_INVOKE,
            newEnvelopeId(),
            SkillsContract.providerInvoke(
                invocation.provider.descriptor.id,
                SkillProviderInvocation(
                    invocationId = invocation.id,
                    operationId = invocation.operation.id,
                    contractVersion = invocation.operation.version,
                    arguments = invocation.providerArguments,
                    deadlineMs = remaining,
                ),
            ),
        )
        if (!delivered) {
            finish(invocation, failure(invocation, SkillErrorCodes.UNAVAILABLE))
            return
        }
        invocation.phase = Phase.DISPATCHED
    }

    // Provider answers.

    fun providerResult(sender: PhonePluginPrincipal, payload: JSONObject) = synchronized(lock) {
        val invocationId = payload.optString("invocationId")
        val invocation = invocations[invocationId]
        if (invocation == null || invocation.provider.grantKey() != sender.grantKey() ||
            invocation.phase != Phase.DISPATCHED
        ) {
            // A late answer, or one from anyone but the exact provider dispatched to, changes nothing.
            host.record(
                SkillJournalEntry(sender.descriptor.id, invocation?.operation?.id.orEmpty(), 0L, SkillStatus.FAILED, "REJECTED_RESULT", null),
            )
            return@synchronized
        }
        val parsed = if (SkillsContract.utf8Size(payload) <= SkillLimits.MAX_RESULT_BYTES) {
            SkillsContract.parseProviderResult(payload)
        } else {
            null
        }
        if (parsed == null) {
            finish(invocation, invalidResult(invocation))
            return@synchronized
        }
        val session = sessions[invocation.session]
        if (session == null) {
            finish(invocation, null)
            return@synchronized
        }
        val now = host.elapsedMs()
        var data: JSONObject? = null
        parsed.data?.let { raw ->
            if (SkillSchemaValidator.validate(raw, invocation.operation.output) is SkillValidation.Invalid) {
                finish(invocation, invalidResult(invocation))
                return@synchronized
            }
            val mapped = SkillSchemaValidator.mapReferences(raw, invocation.operation.output) { type, value ->
                issueHandle(session, invocation, type, value, now)
            }
            data = (mapped as? SkillSchemaValidator.ReferenceMapping.Mapped)?.instance ?: run {
                finish(invocation, invalidResult(invocation))
                return@synchronized
            }
        }
        val input = parsed.input?.let { providerInput ->
            val allowedTypes = referenceTypes(invocation.catalog)
            SkillInputRequest(
                reason = providerInput.reason,
                prompt = providerInput.prompt,
                choices = providerInput.choices.map { choice ->
                    val handle = choice.reference.type.takeIf { it in allowedTypes }
                        ?.let { issueHandle(session, invocation, it, choice.reference.value, now) }
                        ?: run {
                            finish(invocation, invalidResult(invocation))
                            return@synchronized
                        }
                    SkillChoice(choice.label, choice.detail, handle)
                },
            )
        }
        val envelope = envelope(
            invocation = invocation,
            status = parsed.status,
            data = data,
            input = input,
            error = parsed.error ?: if (parsed.status == SkillStatus.UNKNOWN) {
                SkillError(SkillErrorCodes.UNAVAILABLE, SkillDispatch.UNKNOWN)
            } else {
                null
            },
            observedAtMs = parsed.observedAtMs ?: host.wallMs(),
        )
        finish(invocation, envelope.takeIf { stillAuthorized(invocation) })
    }

    // Cancellation, sessions, and lifecycle.

    fun cancel(caller: PhonePluginPrincipal, payload: JSONObject) = synchronized(lock) {
        val (sessionId, requestKey) = SkillsContract.parseCancelRequest(payload) ?: return@synchronized
        val key = SessionKey(caller.grantKey(), sessionId)
        val invocation = invocations.values.firstOrNull { it.session == key && it.requestKey == requestKey }
            ?: return@synchronized
        cancelInvocation(invocation, SkillErrorCodes.CANCELLED, deliver = true, reason = "caller_cancelled")
    }

    fun closeSession(caller: PhonePluginPrincipal, payload: JSONObject) = synchronized(lock) {
        val sessionId = SkillsContract.parseSessionClose(payload) ?: return@synchronized
        closeSessionLocked(SessionKey(caller.grantKey(), sessionId))
    }

    fun onRegistered(principal: PhonePluginPrincipal) = synchronized(lock) {
        invocations.values
            .filter { it.provider.grantKey() == principal.grantKey() && it.phase == Phase.AWAITING_REGISTRATION }
            .forEach(::dispatch)
    }

    /** The principal's last live registration went away. */
    fun onRegistrationRemoved(principal: PhonePluginPrincipal) = synchronized(lock) {
        val key = principal.grantKey()
        invocations.values.filter { it.caller.grantKey() == key }.forEach { invocation ->
            cancelInvocation(invocation, SkillErrorCodes.CANCELLED, deliver = false, reason = "caller_closed")
        }
        invocations.values.filter { it.provider.grantKey() == key && it.phase == Phase.DISPATCHED }
            .forEach { invocation -> finish(invocation, failure(invocation, SkillErrorCodes.UNAVAILABLE)) }
    }

    fun onLeaseBindingDied(principal: PhonePluginPrincipal) = synchronized(lock) {
        invocations.values.filter { it.provider.grantKey() == principal.grantKey() }
            .forEach { invocation -> finish(invocation, failure(invocation, SkillErrorCodes.UNAVAILABLE)) }
    }

    /** A descriptor grant changed or was revoked for [key]; skill approvals may have changed too. */
    fun onAuthorizationChanged(key: PluginGrantKey) = synchronized(lock) {
        invocations.values.filter { it.caller.grantKey() == key || it.provider.grantKey() == key }
            .filterNot(::stillAuthorized)
            .forEach { invocation ->
                cancelInvocation(
                    invocation,
                    SkillErrorCodes.PERMISSION_REQUIRED,
                    deliver = invocation.caller.grantKey() != key || stillCallerAuthorized(invocation),
                    reason = "revoked",
                )
            }
        if (host.installedPrincipals().none { it.grantKey() == key && host.grantedCapabilities(it) != null }) {
            dropPrincipalState(key)
        }
    }

    /** The wearer changed a per-operation skill approval. */
    fun onSkillGrantsChanged() = synchronized(lock) {
        invocations.values.filterNot(::stillAuthorized).forEach { invocation ->
            cancelInvocation(invocation, SkillErrorCodes.PERMISSION_REQUIRED, deliver = true, reason = "revoked")
        }
    }

    /** A package was installed, replaced, or removed: its invocations and references are void. */
    fun onPackageChanged(packageName: String) = synchronized(lock) {
        invocations.values.filter { it.caller.packageName == packageName || it.provider.packageName == packageName }
            .forEach { invocation ->
                cancelInvocation(
                    invocation,
                    SkillErrorCodes.UNAVAILABLE,
                    deliver = invocation.caller.packageName != packageName,
                    reason = "package_changed",
                )
            }
        sessions.keys.filter { it.caller.packageName == packageName }.forEach(::closeSessionLocked)
        sessions.values.forEach { session ->
            session.references.values.removeAll { it.provider.packageName == packageName }
        }
    }

    /**
     * Why the hub must refuse [path] from [principal] because a skill lease alone keeps it
     * running, or null. A lease-started provider without its own display session cannot draw,
     * notify, listen, or be adopted as foreground; it may drive an activity only while running an
     * operation that declares it needs the `surfaces` grant.
     */
    fun leaseRestriction(principal: PhonePluginPrincipal, path: String): String? = synchronized(lock) {
        val key = principal.grantKey()
        if (key !in coldStartedByLease || host.holdsDisplaySession(principal)) return@synchronized null
        if (path in ACTIVITY_PATHS) {
            val allowed = invocations.values.any {
                it.provider.grantKey() == key && PluginCapability.SURFACES in it.operation.requires
            }
            return@synchronized if (allowed) null else ERROR_LEASE_DENIED
        }
        if (LEASE_DENIED_PATHS.any { path == it || path.startsWith("$it/") }) ERROR_LEASE_DENIED else null
    }

    /** Live invocations, for diagnostics and tests. */
    fun liveInvocationCount(): Int = synchronized(lock) { invocations.size }

    fun isLeased(principal: PhonePluginPrincipal): Boolean = synchronized(lock) { principal.grantKey() in leased }

    // Internals.

    private fun onDeadline(invocationId: String) = synchronized(lock) {
        val invocation = invocations[invocationId] ?: return@synchronized
        cancelInvocation(invocation, SkillErrorCodes.DEADLINE_EXCEEDED, deliver = true, reason = "deadline")
    }

    private fun onRegistrationTimeout(invocationId: String) = synchronized(lock) {
        val invocation = invocations[invocationId] ?: return@synchronized
        if (invocation.phase != Phase.AWAITING_REGISTRATION) return@synchronized
        finish(invocation, failure(invocation, SkillErrorCodes.UNAVAILABLE))
    }

    private fun cancelInvocation(invocation: Invocation, code: String, deliver: Boolean, reason: String) {
        if (invocation.phase == Phase.DISPATCHED) {
            host.deliver(
                invocation.provider,
                BusPaths.SKILLS_PROVIDER_CANCEL,
                newEnvelopeId(),
                SkillsContract.providerCancel(invocation.provider.descriptor.id, invocation.id, reason),
            )
        }
        finish(invocation, failure(invocation, code).takeIf { deliver })
    }

    /** Ends [invocation]: releases its timers and lease, and delivers [result] when non-null. */
    private fun finish(invocation: Invocation, result: SkillResultEnvelope?) {
        if (invocations.remove(invocation.id) == null) return
        host.cancel(deadlineKey(invocation.id))
        host.cancel(registrationKey(invocation.id))
        val session = sessions[invocation.session]
        val ledger = session?.ledger?.get(invocation.requestKey)
        if (result != null) {
            ledger?.result = result
            deliverResult(invocation.caller, result)
            repeat(ledger?.extraReplies ?: 0) { deliverResult(invocation.caller, result) }
            ledger?.extraReplies = 0
        } else {
            session?.ledger?.remove(invocation.requestKey)
        }
        val providerKey = invocation.provider.grantKey()
        if (invocations.values.none { it.provider.grantKey() == providerKey } && leased.remove(providerKey)) {
            coldStartedByLease -= providerKey
            host.unbindLease(invocation.provider)
        }
        host.record(
            SkillJournalEntry(
                providerId = invocation.provider.descriptor.id,
                operationId = invocation.operation.id,
                durationMs = host.elapsedMs() - invocation.acceptedAtMs,
                status = result?.status ?: SkillStatus.FAILED,
                errorCode = result?.error?.code ?: if (result == null) "NOT_DELIVERED" else null,
                dispatch = result?.error?.dispatch,
            ),
        )
    }

    private fun failure(invocation: Invocation, code: String): SkillResultEnvelope = envelope(
        invocation = invocation,
        status = SkillStatus.FAILED,
        error = SkillError(
            code,
            if (invocation.phase == Phase.DISPATCHED) SkillDispatch.UNKNOWN else SkillDispatch.NONE,
        ),
        observedAtMs = host.wallMs(),
    )

    private fun invalidResult(invocation: Invocation): SkillResultEnvelope =
        if (invocation.operation.effect == SkillEffect.ACTION) {
            envelope(
                invocation,
                SkillStatus.UNKNOWN,
                error = SkillError(SkillErrorCodes.INVALID_RESULT, SkillDispatch.UNKNOWN),
                observedAtMs = host.wallMs(),
            )
        } else {
            envelope(
                invocation,
                SkillStatus.FAILED,
                error = SkillError(SkillErrorCodes.INVALID_RESULT, SkillDispatch.UNKNOWN),
                observedAtMs = host.wallMs(),
            )
        }

    private fun envelope(
        invocation: Invocation,
        status: SkillStatus,
        data: JSONObject? = null,
        input: SkillInputRequest? = null,
        error: SkillError? = null,
        observedAtMs: Long,
    ) = SkillResultEnvelope(
        session = invocation.session.session,
        requestKey = invocation.requestKey,
        invocationId = invocation.id,
        providerId = invocation.provider.descriptor.id,
        operationId = invocation.operation.id,
        alias = invocation.alias,
        contractVersion = invocation.operation.version,
        status = status,
        data = data,
        input = input,
        error = error,
        observedAtMs = observedAtMs,
    )

    private fun reject(
        caller: PhonePluginPrincipal,
        request: SkillInvokeRequest,
        code: String,
        exposed: Exposed? = null,
    ) {
        deliverResult(
            caller,
            SkillResultEnvelope(
                session = request.session,
                requestKey = request.requestKey,
                invocationId = newInvocationId(),
                providerId = exposed?.provider?.descriptor?.id.orEmpty(),
                operationId = exposed?.operation?.id.orEmpty(),
                alias = request.alias,
                contractVersion = exposed?.operation?.version ?: 0,
                status = SkillStatus.FAILED,
                error = SkillError(code, SkillDispatch.NONE),
                observedAtMs = host.wallMs(),
            ),
        )
        host.record(
            SkillJournalEntry(
                providerId = exposed?.provider?.descriptor?.id.orEmpty(),
                operationId = exposed?.operation?.id.orEmpty(),
                durationMs = 0L,
                status = SkillStatus.FAILED,
                errorCode = code,
                dispatch = SkillDispatch.NONE,
            ),
        )
    }

    private fun deliverResult(caller: PhonePluginPrincipal, result: SkillResultEnvelope) {
        host.deliver(
            caller,
            BusPaths.SKILLS_RESULT,
            newEnvelopeId(),
            SkillsContract.resultPayload(caller.descriptor.id, result),
        )
    }

    private fun stillCallerAuthorized(invocation: Invocation): Boolean =
        host.grantedCapabilities(invocation.caller)?.contains(PluginCapability.SKILLS_CLIENT) == true

    private fun stillAuthorized(invocation: Invocation): Boolean {
        if (!stillCallerAuthorized(invocation)) return false
        val providerGrants = host.grantedCapabilities(invocation.provider) ?: return false
        if (PluginCapability.SKILLS_PROVIDER !in providerGrants) return false
        if (!providerGrants.containsAll(invocation.operation.requires)) return false
        return grants.isApproved(invocation.caller, invocation.provider, invocation.operation)
    }

    private fun sessionFor(caller: PhonePluginPrincipal, sessionId: String, now: Long): Session {
        pruneSessions(now)
        val key = SessionKey(caller.grantKey(), sessionId)
        sessions[key]?.let { session ->
            session.lastActivityMs = now
            return session
        }
        val ofCaller = sessions.keys.filter { it.caller == key.caller }
        if (ofCaller.size >= SkillLimits.MAX_SESSIONS_PER_CALLER) {
            ofCaller.minByOrNull { sessions.getValue(it).lastActivityMs }?.let(::closeSessionLocked)
        }
        return Session(key, now).also { sessions[key] = it }
    }

    private fun pruneSessions(now: Long) {
        sessions.values
            .filter { now - it.lastActivityMs > SkillLimits.SESSION_IDLE_MS }
            .map(Session::key)
            .forEach(::closeSessionLocked)
    }

    private fun closeSessionLocked(key: SessionKey) {
        invocations.values.filter { it.session == key }.forEach { invocation ->
            cancelInvocation(invocation, SkillErrorCodes.CANCELLED, deliver = false, reason = "session_closed")
        }
        sessions.remove(key)
    }

    private fun dropPrincipalState(key: PluginGrantKey) {
        sessions.keys.filter { it.caller == key }.forEach(::closeSessionLocked)
        sessions.values.forEach { session -> session.references.values.removeAll { it.provider == key } }
    }

    private fun trimLedger(session: Session) {
        val iterator = session.ledger.entries.iterator()
        while (session.ledger.size > SkillLimits.MAX_LEDGER_PER_SESSION && iterator.hasNext()) {
            val entry = iterator.next()
            // A running invocation's entry stays, so its result still has somewhere to land.
            if (entry.value.result != null) iterator.remove()
        }
    }

    private fun resolveHandle(session: Session, exposed: Exposed, type: String, handle: String, now: Long): String? {
        if (!SkillsContract.HANDLE.matches(handle)) return null
        val reference = session.references[handle] ?: return null
        val valid = reference.provider == exposed.provider.grantKey() &&
            reference.revision == exposed.provider.packageRevision &&
            reference.catalogDigest == exposed.catalog.digest &&
            reference.type == type &&
            now - reference.lastUsedMs <= SkillLimits.REFERENCE_IDLE_MS
        if (!valid) {
            if (now - reference.lastUsedMs > SkillLimits.REFERENCE_IDLE_MS) session.references.remove(handle)
            return null
        }
        reference.lastUsedMs = now
        return reference.value
    }

    private fun issueHandle(session: Session, invocation: Invocation, type: String, value: String, now: Long): String? {
        if (value.isEmpty() || value.length > SkillSchemaValidator.MAX_REFERENCE_CHARS) return null
        val provider = invocation.provider.grantKey()
        session.references.values.firstOrNull { reference ->
            reference.provider == provider && reference.revision == invocation.provider.packageRevision &&
                reference.catalogDigest == invocation.catalog.digest && reference.type == type &&
                reference.value == value
        }?.let { existing ->
            existing.lastUsedMs = now
            return existing.handle
        }
        session.references.values.removeAll { now - it.lastUsedMs > SkillLimits.REFERENCE_IDLE_MS }
        while (session.references.size >= SkillLimits.MAX_REFERENCES_PER_SESSION) {
            val oldest = session.references.values.minByOrNull(Reference::lastUsedMs) ?: break
            session.references.remove(oldest.handle)
        }
        val handle = "r_" + host.randomToken(HANDLE_TOKEN_CHARS)
        session.references[handle] = Reference(
            handle = handle,
            provider = provider,
            revision = invocation.provider.packageRevision,
            catalogDigest = invocation.catalog.digest,
            type = type,
            value = value,
            lastUsedMs = now,
        )
        return handle
    }

    private fun referenceTypes(catalog: SkillCatalog): Set<String> =
        catalog.operations.flatMapTo(hashSetOf()) { referenceTypesOf(it.input) + it.outputReferenceTypes }

    private fun referenceTypesOf(schema: SkillSchema): Set<String> = when (schema) {
        is SkillSchema.ObjectType -> schema.properties.values.flatMapTo(hashSetOf(), ::referenceTypesOf)
        is SkillSchema.ArrayType -> referenceTypesOf(schema.items)
        is SkillSchema.StringType -> setOfNotNull(schema.referenceType)
        else -> emptySet()
    }

    private fun newInvocationId(): String = "inv_" + host.randomToken(INVOCATION_TOKEN_CHARS)

    private fun newEnvelopeId(): String = host.randomToken(ENVELOPE_TOKEN_CHARS)

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun deadlineKey(invocationId: String) = "skill-deadline:$invocationId"
    private fun registrationKey(invocationId: String) = "skill-registration:$invocationId"

    companion object {
        const val ERROR_INVALID_REQUEST = "INVALID_SKILL_REQUEST"
        const val ERROR_LEASE_DENIED = "SKILL_LEASE_DENIED"
        private const val HANDLE_TOKEN_CHARS = 22
        private const val INVOCATION_TOKEN_CHARS = 20
        private const val ENVELOPE_TOKEN_CHARS = 24

        private val ACTIVITY_PATHS = setOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE, BusPaths.ACTIVITY_END)
        private val LEASE_DENIED_PATHS = listOf(
            "/surface",
            "/ink",
            "/notice",
            "/pin",
            "/audio",
            "/stt",
            "/tts",
            "/camera",
        )
    }
}
