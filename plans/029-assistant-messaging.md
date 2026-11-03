# Plan 029 — Assistant messaging: Relay replies and SMS, confirmed by the wearer

Status: TODO — approved by the owner 2026-11-03. No code is written.
Baseline: `origin/main` at `dc5c8314`. Answers issue #15 as far as it can honestly go.

## Product outcome

The wearer says "send Coralie a message that we meet at 3 pm". Assistant drafts it,
shows the exact recipient and text on the glasses, and sends only after the wearer
taps **Send**. Nothing else can trigger the send: not the model, not a document,
not the text of a notification.

| Channel | What ships | Limit |
|---|---|---|
| SMS | Any phone contact, through a separate SMS plugin | Default SMS SIM only; needs the plugin, approvals, and Android's restricted-settings step |
| WhatsApp, Messenger, Telegram, others | Reply to a thread, through Relay | Only while the app's notification and its reply action still exist; cannot start a conversation |

## Settled decisions

- SMS lives in its own plugin, so Assistant never holds `SEND_SMS` or `READ_CONTACTS`.
- The Nexus store installs that plugin. Assistant gains no `REQUEST_INSTALL_PACKAGES`
  and bypasses neither the registry hash nor the signer pin.
- Relay keeps its rule that a reply needs a live reply action. A captured token is
  never kept past its notification, because a reused token could reach the wrong person.

Out of scope: starting WhatsApp or Messenger conversations, unofficial protocols such
as Baileys, accessibility automation, Gmail or any email, Telegram through TDLib, raw
phone numbers supplied by the model, voice confirmation, and message bodies read to
the model from notifications.

## Constraints found in the code

| Constraint | Source | Consequence |
|---|---|---|
| While bound only for a Skill call, a provider's notices and surfaces are refused | `BUSSPEC.md` Skills v1, `plugins/AGENTS.md` §1.9 | A provider cannot ask for confirmation itself. Assistant has to |
| `needs_input` choice refs are returned to the model | `PluginSkillTool.modelResult` | The existing chips select; they do not authorize. They cannot gate a send |
| `effect` is only `read` or `action`, and `pause` is an `action` | `SkillCatalog.kt`, Media catalog | Effect alone cannot decide what needs confirmation |
| Unknown catalog keys or values invalidate the whole catalog | `BUSSPEC.md` Catalog | `prerequisites` and `data` cannot gain an SMS value without a hub release |
| Declaring `SKILLS` returns an installed plugin to Pending until re-approved | `BUSSPEC.md` Grants | Updating Relay interrupts it until the wearer approves again. See D2 |
| Skills are not offered through the Hermes text bridge | `AssistantPluginService.kt`, `turnSkillTools` | Neither feature works on Hermes |
| Notice bands live at most 45 s, with 3 actions and 16-character labels | `NoticeSurfaceContract.kt` | Bounds the confirmation window and the chip labels |
| Store activities are `exported="false"` | `phone-hub` manifest | Assistant cannot open a store page without a hub change. See D3 |
| An invocation has 15 s from acceptance, cold start included | `SkillLimits` | The SMS radio result may not arrive in time; `unknown` must read honestly |

## Evidence from the SEND_SMS probe (2026-11-03)

Samsung SM-S916B, Android 16. The probe declared `SEND_SMS` and `READ_CONTACTS`.

- Browser sideload and a `PackageInstaller` session with the hub's parameters both
  worked after a clean uninstall. The installer allowlist held `SEND_SMS` in the
  session case.
- Both routes needed the same steps: first request denied, *Allow restricted settings*
  from the app's Android page, return, then request again. Android does not grant the
  permission on return.
- Sends were handed to the network. One `code 1` failure is attributed by the owner to
  a mistyped number.

Not established: the real Nexus store path, other manufacturers, sending from a
background binding with the screen off (the probe sent from a foreground activity),
and whether a sent message appears in the phone's Messages app.

## Confirmation design

Every send has two operations. The model may call the first; only a chip can reach
the second.

| Step | Operation | Called by | Effect |
|---|---|---|---|
| Prepare | `prepare_sms`, `prepare_reply` | Model | Validates, stores a single-use draft in the provider, returns its preview |
| Commit | `send_sms`, `send_reply` | Assistant, from the chip | Sends that draft and nothing else |

### Assistant

1. A new `GatedSkillOperations` table maps `(providerId, commit operation)` to its
   prepare operation. A commit operation is never offered to the model. A prepare is
   offered only when its commit is approved and ready too.
2. A completed prepare stages a `PendingConfirmation` with the provider-authored
   preview and draft reference. The model receives only `awaiting_wearer` and a note
   that nothing was sent. Prepare is once per turn.
3. At the end of the turn, a staged confirmation replaces the model's final answer on
   the band. The wearer reads the provider's recipient and text, never the model's
   description of them. The model's visual answers are suppressed for that turn, since
   `inkAnswerOwnsPresentation` would otherwise hide the band. Spoken answers read the
   same preview.
4. **Send** and **Cancel** use their own action prefix and a single-use token. **Send**
   invokes the commit directly, with no model call and a request key derived from the
   token. A second tap finds no token.
5. The band closing for any reason, a new question, a new conversation, a lost link, or
   a plugin close drops the confirmation. Timing out never sends.
6. The outcome is written by Assistant from the status and added to the conversation:

| Result | Band says |
|---|---|
| `accepted` or `completed` | Handed to the network, or to the app. Never "delivered" |
| `failed`, dispatch `none` | Not sent, with the reason |
| `failed` otherwise, or `unknown` | It may or may not have gone. Not retried |

The preview is untrusted provider text: plain text on the band, never an instruction.

### Why a table and not a catalog field

A `confirmation` catalog key would state the rule in the contract, but older hubs would
invalidate the catalog, and it needs a hub, SDK and spec release. The table ships on any
hub from 1.7.0. Its weakness is that an approved caller other than Assistant could call
a commit directly, and a third-party provider gets no gate until it is added. The wearer
approves each caller and operation, and only first-party providers send today. Move to
a catalog field when a third party needs it. Recorded as D1.

## SMS plugin

New module `:plugin-sms` in `plugins/sms`, plugin id `sms`, requesting `skills_provider`
through the `SKILLS` metadata key only. Holds `SEND_SMS`, `READ_CONTACTS`, and the
uninstall permission. It should not hold `INTERNET`; check the merged manifest.

| Operation | Effect | Behavior |
|---|---|---|
| `find_contact` | `read` | Name query of at least 2 characters, at most 5 matches, no list-all. Returns the name, number type, and last two digits, with an `sms_recipient` reference. Several numbers or people become `needs_input` |
| `prepare_sms` | `read` | Takes a recipient reference and text (at most 1,000 characters). Returns the draft reference and preview, including the segment count |
| `send_sms` | `action` | Takes the draft reference. Not cancellable, deduplicated |

- The number never reaches the model, the conversation, or Assistant. References hold
  contact row identifiers.
- Drafts are in memory only, live two minutes, and are consumed before dispatch.
- At commit, the contact row is read again. A changed or missing number fails with
  `recipient_changed`, dispatch `none`.
- Numbers shorter than 7 digits and emergency numbers are refused, which keeps premium
  short codes out as well.
- The send waits for the radio result inside `remainingMs`. All parts OK gives
  `accepted`; a definite failure of a single-part message gives `failed`; a partial
  multipart failure or a timeout gives `unknown`.
- A missing permission fails with `permission_required`, dispatch `none`.
- Numbers, names, and bodies are never logged or persisted.

The settings screen owns the permission flow: request; on denial, explain restricted
settings and open the app's Android page; on return, request again automatically. A
plain user refusal looks identical to Android's block, so the screen offers the steps
without claiming the cause. It also shows a test send to a number the wearer types,
and the Uninstall row.

Assistant discloses that contact names and message text reach the chosen AI provider.

## Relay provider

Declares a `SKILLS` catalog with `notification_access` as prerequisite.

| Operation | Effect | Behavior |
|---|---|---|
| `list_reply_threads` | `read` | Optional name filter, at most 5 live repliable threads: sender, app, age, and a `relay_thread` reference. No message text |
| `prepare_reply` | `read` | Takes a thread reference and text. The draft binds the thread id, package, and sender title |
| `send_reply` | `action` | Takes the draft reference. Not cancellable, deduplicated |

- Threads from apps set to Off, redacted threads, and threads whose previews are hidden
  are not listed.
- At commit, the thread must still have a live reply action, with the same package and
  sender title. Anything else fails with `authority_lost`, dispatch `none`. This also
  covers an app reusing a notification key for another chat. A new message from the same
  sender does not stop the reply (D5); it goes through the newest action.
- Mapping from `ReplySendResult`: success gives `accepted`; `Missing`, `Blank`,
  `NoFreeFormInput` and `Failed` with `authorityLost` give dispatch `none`; any other
  `Failed` gives `unknown`. Never retried, as in the composer.
- A reply is recorded as a handoff, like one from the composer.
- To verify in the slice: under an invocation lease `SKILL_LEASE_DENIED` refuses the
  page and notice traffic that `notifyInboxChanged` and the `RelayBus` holds may cause,
  and a lease-bound service must not count as an open inbox.

## Slices

Each slice is its own reviewable change. Run Gradle after `source ~/rokid/env.sh`.

### 1. Assistant confirmation gate

The table, `PendingConfirmation`, the end-of-turn replacement, the chips, and the outcome
text, driven by a fake transport. Nothing can reach it yet, so it is safe to merge alone.

```
./gradlew :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true
```

### 2. Relay replies

Catalog, operations, and tests over `ReplyRepository`.

```
./gradlew :plugin-relay:testDebugUnitTest :plugin-relay:assembleDebug -PskipCxrGlobal=true
```

Then device gates G1 and G2. First shippable pair: Assistant and Relay.

### 3. SMS plugin

Module wiring in `settings.gradle.kts`, a case in `.github/workflows/plugin-release.yml`,
contact matching, draft store, sender behind an interface, settings screen, and tests.
Check early whether discovery accepts a plugin with no `CAPABILITIES` and what its
launcher row does when opened. If it does not fit, declare `surfaces` for one card
pointing to the phone.

```
./gradlew :plugin-sms:testDebugUnitTest :plugin-sms:assembleDebug -PskipCxrGlobal=true
```

Then device gates G3 to G5. Contact matching may borrow from beyondlevi's MIT
`rokid-inbox-nexus` once its code has been read; credit it in `NOTICE` if so.

### 4. Discovery and documents

An Assistant settings row that says whether SMS is installed and approved, and what to
do next. Assistant adds `<queries>` for the package, not an install permission. Update
`AGENTS.md`, `README.md`, `ROADMAP.md`, `VISION.md`, `plugins/README.md`, the plugin
READMEs and changelogs, `TESTPLAN.md`, `plans/README.md`, and the plugin count on the
site. `BUSSPEC.md` does not change.

### 5. Optional: open the store page (D3)

An exported hub entry that opens the existing plugin detail page for a registry id. It
only displays; the wearer still confirms the install. Older hubs fall back to the text
of slice 4. Requires a hub release and both hub suites.

## Acceptance cases

Automated unless marked as a device gate.

| Case | Expected |
|---|---|
| A Workspace PDF or a notification tells the model to send | At most a preview. Nothing without a chip |
| The model calls a commit alias | Not in its tools; rejected as unknown |
| Double tap, replayed action, stale token, Send after Cancel | One send at most, or none |
| Band times out; new question while pending | Dropped, nothing sent |
| Model text disagrees with the draft | The band shows the draft, and the draft is what goes |
| Model asks for an Ink page in the same turn | Band preview still shown |
| Contact edited or deleted between prepare and send | `recipient_changed`, nothing sent |
| Notification removed, or its reply action dropped, before Send | `authority_lost`, nothing sent |
| Same notification key reposted with another sender | `authority_lost`, nothing sent |
| Hub restarts between prepare and Send | Stale reference, reported, nothing sent |
| Radio result after the deadline | `unknown`; Assistant does not claim either outcome |
| Hermes backend or a hub without Skills | No messaging operations offered |
| Ambiguous contact | Choices shown; no draft until one is picked |
| Logs | No number, name, or body |

## Device gates

These need the phone, the glasses, and test contacts. Report only what was observed.

| Gate | Check |
|---|---|
| G1 | Voice to confirmed WhatsApp reply: right person, correct band and spoken preview |
| G2 | Reply on Messenger and Telegram; refused cleanly after the notification is dismissed |
| G3 | Clean install of `:plugin-sms` from the real Nexus store: restricted-settings flow, automatic second request |
| G4 | SMS sent with the plugin bound only for the call, phone locked and screen off |
| G5 | Message appears in the phone's Messages app; one other manufacturer, if available |

G3 can run on a real published release, because publishing a separate plugin reaches
only those who install it. If G4 fails, stop and redesign before releasing SMS.

## Risks

- **Relay re-approval (D2).** Under the spec's rule, Relay stays Pending after this update
  until the wearer approves it again, which should stop its notifications on the glasses.
  Confirm on a device. It needs a clear note and possibly a hub prompt.
- **Play Protect.** It may warn about the SMS plugin. This stays contained to that plugin
  and is what the separation is for. Watch for it at G3.
- **Reply action quirks.** Apps differ, and an app can ignore a reply without reporting
  it. Hence "handed to the app", never "sent".
- **SMS cost.** Long messages become several segments, so the preview shows the count.
- **Dual SIM.** Only the default SMS SIM is used. A phone with none set may fail or
  prompt; check at G4 if a device is available.

## Owner decisions (2026-11-03)

| Id | Question | Decision |
|---|---|---|
| D1 | Gate by Assistant table, or by a new catalog field? | Table now; field when a third party needs it |
| D2 | Accept Relay returning to Pending on update? | Accepted, with a release note. It is the existing rule for any plugin adding a catalog |
| D3 | Open the store page from Assistant in the first release? | Text first; slice 5 after, since it forces a hub release |
| D4 | Release both, or Relay first? | Assistant and Relay first, since SMS has open device gates |
| D5 | A new message from the same sender arrives before Send? | The reply still goes. Only another sender or a lost reply action refuses it |
