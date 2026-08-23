package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentSessionStoreTest {
    @Test
    fun mergesProvidersAndSortsByMissionControlRules() {
        val store = AgentSessionStore()
        store.replaceProvider(
            AgentProvider.CLAUDE,
            listOf(
                session("idle", AgentProvider.CLAUDE, AgentStatus.IDLE, activity = 90),
                session("working-old", AgentProvider.CLAUDE, AgentStatus.WORKING, activity = 100),
                session(
                    "need-new",
                    AgentProvider.CLAUDE,
                    AgentStatus.NEEDS_YOU,
                    pendingAt = 30,
                ),
            ),
            nowMs = 200,
        )
        store.replaceProvider(
            AgentProvider.OPENCLAW,
            listOf(
                session("error", AgentProvider.OPENCLAW, AgentStatus.ERROR, activity = 200),
                session("working-new", AgentProvider.OPENCLAW, AgentStatus.WORKING, activity = 150),
                session(
                    "need-old",
                    AgentProvider.OPENCLAW,
                    AgentStatus.NEEDS_YOU,
                    pendingAt = 20,
                ),
            ),
            nowMs = 200,
        )

        assertEquals(
            listOf("need-old", "need-new", "error", "working-new", "working-old", "idle"),
            store.sessions.value.map(AgentSession::id),
        )
    }

    @Test
    fun dropsOnlyDoneSessionsOlderThanThirtyMinutes() {
        val now = 2_000_000L
        val store = AgentSessionStore()
        store.replaceProvider(
            AgentProvider.CLAUDE,
            listOf(
                session(
                    "expired",
                    AgentProvider.CLAUDE,
                    AgentStatus.DONE,
                    activity = now - AgentSessionStore.DONE_RETENTION_MS - 1,
                ),
                session(
                    "kept",
                    AgentProvider.CLAUDE,
                    AgentStatus.DONE,
                    activity = now - AgentSessionStore.DONE_RETENTION_MS,
                ),
                session("unknown-time", AgentProvider.CLAUDE, AgentStatus.DONE),
            ),
            now,
        )

        assertFalse(store.sessions.value.any { it.id == "expired" })
        assertEquals(listOf("kept", "unknown-time"), store.sessions.value.map(AgentSession::id))
    }

    @Test
    fun pruneForgetsExpiredSessionsInsteadOfOnlyHidingThem() {
        val now = 2_000_000L
        val store = AgentSessionStore()
        val expired = session(
            "expired",
            AgentProvider.CLAUDE,
            AgentStatus.DONE,
            activity = now - AgentSessionStore.DONE_RETENTION_MS - 1,
        )
        store.replaceProvider(
            AgentProvider.CLAUDE,
            listOf(expired, session("live", AgentProvider.CLAUDE, AgentStatus.WORKING, activity = now)),
            now,
        )

        assertEquals(setOf(expired.key), store.prune(now))
        // Gone for good: pruning again has nothing left to drop.
        assertEquals(emptySet<String>(), store.prune(now))
        assertEquals(listOf("live"), store.sessions.value.map(AgentSession::id))
    }

    @Test
    fun replaceLinkSessionsKeepsDirectUrlCodexThreads() {
        val store = AgentSessionStore()
        store.replaceMachineSessions(
            "direct-abc",
            AgentProvider.CODEX,
            listOf(session("direct-thr", AgentProvider.CODEX, AgentStatus.IDLE, machineId = "direct-abc")),
            nowMs = 10,
        )
        store.replaceLinkSessions(
            AgentProvider.AGENTD_PROVIDERS,
            listOf(session("link-thr", AgentProvider.CODEX, AgentStatus.WORKING, machineId = "pc-1")),
            nowMs = 20,
        )
        assertEquals(setOf("direct-thr", "link-thr"), store.sessions.value.map { it.id }.toSet())
    }

    @Test
    fun replaceMachineSessionsDoesNotTouchOtherComputers() {
        val store = AgentSessionStore()
        store.replaceMachineSessions(
            "direct-a",
            AgentProvider.CODEX,
            listOf(session("a", AgentProvider.CODEX, AgentStatus.IDLE, machineId = "direct-a")),
            nowMs = 10,
        )
        store.replaceMachineSessions(
            "direct-b",
            AgentProvider.CODEX,
            listOf(session("b", AgentProvider.CODEX, AgentStatus.WORKING, machineId = "direct-b")),
            nowMs = 20,
        )
        store.replaceMachineSessions("direct-a", AgentProvider.CODEX, emptyList(), nowMs = 30)
        assertEquals(listOf("b"), store.sessions.value.map(AgentSession::id))
    }

    @Test
    fun clearApprovalsForMachineLeavesOtherComputers() {
        val store = AgentSessionStore()
        store.replaceMachineSessions(
            "direct-a",
            AgentProvider.CODEX,
            listOf(session("thr-a", AgentProvider.CODEX, AgentStatus.NEEDS_YOU, machineId = "direct-a")),
            nowMs = 10,
        )
        store.replaceMachineSessions(
            "direct-b",
            AgentProvider.CODEX,
            listOf(session("thr-b", AgentProvider.CODEX, AgentStatus.NEEDS_YOU, machineId = "direct-b")),
            nowMs = 10,
        )
        store.upsertApproval(
            AgentApproval("n:1", "thr-a", AgentProvider.CODEX, "tool", "run", fourVerdicts = true),
        )
        store.upsertApproval(
            AgentApproval("n:2", "thr-b", AgentProvider.CODEX, "tool", "run", fourVerdicts = true),
        )
        store.clearApprovalsForMachine("direct-a")
        assertEquals(listOf("n:2"), store.approvals.value.map { it.requestId })
    }

    private fun session(
        id: String,
        provider: AgentProvider,
        status: AgentStatus,
        activity: Long? = null,
        pendingAt: Long? = null,
        machineId: String? = null,
    ) = AgentSession(
        id = id,
        provider = provider,
        machineId = machineId,
        title = id,
        status = status,
        lastActivityAt = activity,
        pendingRequest = pendingAt?.let {
            AgentPendingRequest(PendingRequestKind.PERMISSION, "Approve", it)
        },
    )
}
