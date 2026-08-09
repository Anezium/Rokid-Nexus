# Adversarial code review — READ-ONLY, no edits, no commits

Review the diff `git diff 974841a3..HEAD` in this worktree (commit "Retry
plugin registration until the hub can say yes") against its contract at
`contracts/2026-08-09-sdk-registration-retry.contract.md`. Read the contract,
the full diff, then IN FULL: `BusClient.kt`, the new
`PluginRegistrationRetry.kt`, `NexusPluginClient.kt` (especially
onRegistrationState at ~421-465 and the grants handling at ~427-443),
`NexusPluginService.kt`, the transport interfaces, and the new test file.
This is a PUBLISHED SDK consumed by external developers — treat any
observable behavior change to existing flows as guilty until proven innocent.

Hunt for:

1. **Public-API drift**: any signature changed/removed, any callback that
   now fires in a different order, count, or thread for the already-working
   flow (connect after approval → APPROVED once). External plugins were
   written against the old timing; a second APPROVED callback, or a REJECTED
   followed later by APPROVED where code treated REJECTED as terminal
   (e.g. `FeedsPluginService.kt:86` does `runtime?.close()` on non-APPROVED
   — check every in-repo plugin for this pattern and report which ones the
   retry actually helps vs breaks).
2. **Result-code mapping**: which `PluginRegistrationResult` codes are
   retried vs terminal, and is that mapping defensible? A wearer approving
   minutes later must eventually succeed; an explicitly revoked plugin must
   not hot-loop.
3. **Lifecycle leaks**: timers/handlers surviving close(); retry racing a
   concurrent manual re-register; retry keeping the process alive; thread
   the retries run/deliver on vs the documented dispatch discipline.
4. **The 16 ms grants race** (NexusPluginClient.kt:427-443): does the retry
   path route through the exact same approval/grants code, or around it?
5. **Test theater**: do tests exercise the real client/transport wiring or a
   parallel toy? Are rejected-then-approved-once, closed-during-backoff,
   hard-denied-no-hot-loop actually pinned?

For EACH finding, actively try to refute it first. Report survivors only:
file:line, concrete failure story (which plugin, which sequence, what the
wearer sees), severity (BLOCKER / REAL / NIT), one-line refutation attempt.
Ranked, findings only, no praise. If nothing survives, say exactly that.
Do NOT modify files. Do NOT run gradle (fails in this sandbox); reason
statically.
