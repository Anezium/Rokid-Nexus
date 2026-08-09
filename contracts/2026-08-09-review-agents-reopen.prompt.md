# Adversarial code review — READ-ONLY, no edits, no commits

Review the diff `git diff 974841a3..HEAD` in this worktree (commit "Reopen
the conversation stream after a link flap") against its contract at
`contracts/2026-08-09-agents-detail-reopen.contract.md`. Read the contract,
the full diff, then IN FULL: `AgentSessionStore.kt`, `AgentdClient.kt`,
`AgentdLinkServer.kt`, `AgentsMonitorService.kt`, `AgentsPluginService.kt`
(the conversation open/close paths), and the touched test file.

Note a design deviation to scrutinize: the contract sketched the open-detail
memory in the service; the author put it in `AgentSessionStore` and re-emits
from inside the two transports. Decide whether that placement creates
problems the service-level design would not have (who clears the state when
the surface closes? does the plugin service's close path reach it on every
exit — BACK, surface hidden by hub, plugin restart, monitor stop?).

Hunt for:

1. **Auth-order violations**: re-emit must fire only after the hello/auth
   handshake completes on each transport — trace the exact callback used; a
   frame sent to an unauthenticated or wrong-generation socket is a real bug
   (the link server has socket generations; check the re-emit can't hit a
   stale socket).
2. **Threading**: socket writes from a main-thread callback fail silently
   under runCatching in this codebase (NetworkOnMainThreadException scar).
   Trace the re-emit's dispatch path end to end.
3. **Stale-reopen bugs**: wearer closed the chat during the flap → reconnect
   must NOT re-emit; wearer switched to a different chat → re-emit must carry
   the CURRENT one; provider keying (a Codex and a Claude session with the
   same id are different conversations).
4. **Duplicate emission**: open while connected then reconnect → exactly one
   re-emit per transport per reconnection; no loops with the 30 s app-level
   ping or the 60 s codex sweep.
5. **Contract fidelity + test theater**: the three mandated test scenarios
   present and actually exercising the logic (not mock-echo); CHANGELOG
   entry; nothing outside scope_globs.

For EACH finding, actively try to refute it first. Report survivors only:
file:line, concrete failure story, severity (BLOCKER / REAL / NIT), one-line
refutation attempt. Ranked, findings only, no praise. If nothing survives,
say exactly that. Do NOT modify files. Do NOT run gradle (fails in this
sandbox); reason statically.
