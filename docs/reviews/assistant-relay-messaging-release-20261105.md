# Assistant 1.5.2 and Relay 1.4.1 release checks

Date: 2026-11-05. Release Assistant and Relay together; SMS remains a later slice.
No hub changes are part of this release. The branch includes main's existing
2.0.3 fixes so the plugin release does not replace newer source.

## Behavior and checks

Assistant offers drafting, never a send operation, to the model. Only the
wearer's Send dispatches the exact provider-authored draft. Cancellation,
timeout, a new question and link loss never send. Failed or uncertain sends
are never retried automatically; success reports a handoff, not delivery.

Reading has two new independently approved operations, both initially disabled.
The reading label discloses sharing text with Assistant's chosen AI provider.
Assistant's system policy permits reading only on an explicit request to
consult messages, never as automatic context for a supplied-text reply.
This intent rule is model policy; hub approval and reference ownership are
enforced independently of the model.

Reading covers retained notification text and Relay's handoffs, not the app's
complete archive. It preserves known authors, pages long messages without losing
captured text, binds cursors to the scope and transcript revision, and rechecks
current source policy, Relay being enabled, redaction and hidden previews.
Reading references are distinct from reply authority. Reading never marks as
read or sends.

The final Assistant suite has 599 tests; Relay has 203. All 802 observed tests
passed, with zero failures and errors. Both debug and release APKs assembled
during development. The final Relay harness spacing check had this real tail:

```text
BUILD SUCCESSFUL in 24s
200 actionable tasks: 16 executed, 184 up-to-date
```

After merging main, both relevant suites and debug builds were checked again:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-relay:testDebugUnitTest :plugin-relay:assembleDebug -PskipCxrGlobal=true
```

```text
BUILD SUCCESSFUL in 6s
124 actionable tasks: 124 up-to-date
```

The latter check reused the already-passing tests and builds. Published APKs
must come from the tagged Plugin release workflow, not the local test APKs.

## Observed device behavior

- A real Telegram bot received the reply after the wearer dictated it, checked
  the recipient and text, and tapped Send. The video contains the confirmation
  and final handoff result. Receipt was independently checked through the test
  bot API; this does not give Nexus general delivery receipts.
- Earlier Telegram checks covered a 687-character paged draft, a new message
  before Send, Cancel, Back, typed input and a locked phone. The harness covered
  timeout without sending. These preceded the reading additions and are not
  claimed as reruns on the final release APK.
- The two new reading switches were observed off before enabling them for the
  owner's requested synthetic demo.
- A spoken question correctly summarized the Mika/Jose group: the revised 20:00
  dinner, participants' contributions, and pending questions. The answer
  qualified the capture's incomplete history.
- A spoken question explicitly about Jean's single latest message returned his
  request to notify him after sending the documents. A spoken multi-request
  summary of Jean's thread was not performed.
- A spoken question distinguished Lea's 20:00 meeting from the 20:15 screening.
- The launcher showed three conversations after all demo notices arrived and
  the launcher was reopened. Demo arrivals are now spaced by three seconds:
  simultaneous posts on a cold connection had populated Relay's inbox while
  only the latest pending band reached the glasses.
- The complete 180-second reading video was recovered over the existing Wi-Fi
  ADB transport after USB disconnected. No recording or Assistant question was
  lost in that transport failure.

## Remaining limits

- WhatsApp, Discord and Messenger delivery were not qualified with a real
  correspondent in this session. Messenger was not installed.
- Post-read approval revocation and privacy changes are unit-tested, but their
  complete behavior was not separately exercised on hardware.
- Pending approval was observed during the initial Relay Skills update. No new
  Pending transition is claimed from later same-contract updates.
- There is no per-thread forget control for retained text. Source-app Off or
  switching Relay off clears the corresponding retained content.
- Reading uses separate discovery and read operations. The observed interval
  from the first Relay tool step to spoken results was roughly 8-11 seconds,
  excluding transcription and the first model decision. No latency optimization
  is included.
- No SMS plugin, hub release, or Reddit post is included.
