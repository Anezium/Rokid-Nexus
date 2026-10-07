# Nexus Skills and Assistant Workspace device QA

Date: 2026-10-07. Integration branch: `qa/skills-workspace`.
Implementation tested: `20a9c4a1`.

This branch combines the current main checkout, Plan 024's Skills implementation,
and Plan 026's Assistant Workspace implementation for testing. It is not a release
or a declaration that either plan's entire hardware acceptance is complete.

## Automated verification

The combined implementation passed the following command, without the plugin-only
`skipCxrGlobal` flag because the command also builds both hubs:

```powershell
.\gradlew.bat :shared:test :bus-client:testDebugUnitTest :phone-hub:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:testDebugUnitTest :glasses-hub:assembleDebug :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug :plugin-transit:testDebugUnitTest :plugin-transit:assembleDebug :plugin-nav:testDebugUnitTest :plugin-nav:assembleDebug :plugin-sample:testDebugUnitTest :plugin-sample:assembleDebug
```

Observed output tail:

```text
BUILD SUCCESSFUL in 13s
330 actionable tasks: 2 executed, 328 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

The resulting XML reports contain 2,583 tests, with zero failures, errors, or skips:

| Module | Tests |
| --- | ---: |
| shared | 384 |
| bus-client | 149 |
| phone-hub | 740 |
| glasses-hub | 683 |
| Assistant | 441 |
| Transit | 105 |
| Nav | 64 |
| Sample | 17 |

Release builds of the phone hub, Assistant, Transit, Nav, and Sample also passed:

```text
BUILD SUCCESSFUL in 1m 12s
360 actionable tasks: 219 executed, 93 from cache, 48 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.1/userguide/configuration_cache_enabling.html
```

All five APKs were signed with the existing release key and installed with
`adb install -r --user 0`. Every install returned `Success`. The previous APKs
were backed up locally; application data was preserved. The installed glasses hub
remained at version 1.6.0. Both its tests and debug build passed above, but a new
glasses APK was not installed.

## Device observations

The phone ran Android 16/API 36; the glasses ran Android 12L/API 32. Initial
checks used wireless ADB; the resumed session used USB ADB for both devices,
as requested by the wearer. Assistant used the already-connected ChatGPT provider. Questions were
submitted through typed input and the existing DUMP-protected debug question
receiver, so this session does not validate microphone capture.

### Sample skill: passed

Assistant's skills-client permission and Sample's provider permission were
approved through Plugin access. Sample's `Count words` operation was explicitly
enabled for Assistant.

The test requested a skill count of `lune sable vent`. The phone's bus inspector
showed the catalog exchange, `/skills/invoke`, Sample's provider invocation and
result, `/skills/result` back to Assistant, and a non-rejected SKILL event. The
glasses displayed the expected answer: `3 mots, 15 caractères.`

The count was also exercised through normal typed submission. A model-generated
count alone was not used as evidence: the bus round trip was checked separately.

The USB session also disabled `Count words` through Plugin access. A new explicit
request reported that the skill was unavailable and did not count independently.
The inspector showed a new catalog exchange, with no new Hello Nexus invocation;
its last invocation remained from before revocation. Re-enabling the operation
restored invocation and returned `3 mots, 18 caractères` for `bleu cuivre soleil`.
The new provider invocation/result and SKILL event were observed in the inspector.

### Transit: search, departures, and basic journey lifecycle exercised

Transit was approved, and `Search stops by name` and `Departures at a stop` were
enabled for Assistant. Favorite access and journey actions were temporarily
enabled during the USB checks and disabled again before handoff.

The test searched for the public Gare du Nord station in Paris. The bus inspector
showed a Transit provider invocation and result, the result delivered to Assistant,
and a non-rejected SKILL event. Assistant reported several matching stations and
asked the wearer to choose. It did not claim a departure from a guessed station.
The USB session searched for Bibliothèque François Mitterrand in Paris. Assistant
again requested a choice. Selecting the first returned stop produced a real
departure board on the glasses, including bus, metro, and RER services. The
inspector showed the provider invocation/result and the result to Assistant.

Two follow-up requests did not meet acceptance: asking for the next line 14
service toward Orly, then explicitly selecting that line without `after`, returned
no matching departure despite Orly services on the earlier board. A subsequent
unfiltered board again contained an Orly service. This is an unresolved observed
behavior, not proof of a particular feed or argument bug: the inspector does not
show invocation payloads, and live boards can change. The follow-up matrix must
not be marked passed.

The favorites-count and inactive-journey questions produced two separate provider
round trips in one Assistant turn. Existing favorites were not modified. With
`No home saved` independently observed in Transit settings, a home-guidance request
correctly explained the missing setup and did not invent a home or a journey.

A test journey to the public Bibliothèque François Mitterrand station did start.
The glasses displayed its walking activity and itinerary summary, and a later
`Journey progress` call confirmed the destination and current walking leg.
Android's service dump showed `isForeground=true`, `startRequested=true`, and
foreground service types `0x40000008` while guidance was active.

The first ordinary stop request reported that the journey reference was missing;
Assistant correctly did not claim success. An explicit request to retrieve the
current journey reference and then stop recovered. The glasses reported guidance
ended, the walking activity disappeared, and the Transit service dump no longer
contained a running service. Ordinary cross-turn stop behavior remains an
acceptance issue. Source inspection shows conversation memory preserves a
provider's `focus` object, while journey results declare a top-level `journey`
reference without that focus; this is a likely cause to investigate, not a
verified fix. No production code was changed during these resumed device tests.

The wearer did not travel. Boarding, missed connections, underground/no-fix
progress, arrival, process restart, reconnect recovery, and Nav turn guidance were
not validated by this stationary test.

### Workspace: isolated provider tests passed; ordinary storage selection blocked

An isolated QA text document was placed in ordinary phone-storage test folders.
The Android document-tree picker displayed no entries and disabled `Use this
folder` with the privacy restriction message. Attempts to open the test subfolders
through `EXTRA_INITIAL_URI`, including a fresh standalone `ACTION_OPEN_DOCUMENT_TREE`
task, did not produce a selectable folder. This also occurred outside Assistant.

The USB session confirmed that the picker was inside the QA subfolder, not merely
the restricted storage root. The system `ExternalStorage` log showed
`NoSuchFileException: /storage/emulated` from `Files.isSameFile` inside
`ExternalStorageProvider.isRestrictedPath`, reached through `shouldHideDocument`
and `queryChildDocuments`. The inference is that the platform's restriction check
hides the folder contents after that exception; the
[AOSP implementation](https://android.googlesource.com/platform/frameworks/base/+/67d6e08322019f7ed8e3f80bd6cd16f8bcb809ed/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java)
uses a fail-closed restriction path. The device's system storage permission was
examined and restored to its original app-op mode; no OS package was downgraded,
cleared, or replaced. Ordinary phone-storage folder selection remains blocked.

To exercise Assistant independently, a temporary QA-only DocumentsProvider was
built outside the repository with the installed SDK and installed over USB. It had
no network or broad storage permissions, exposed only four fixed fixtures in its
own private directory, and only opened documents for reading. Android's native
folder picker and its confirmation dialog granted the tree; the persisted tree
grant was read-only (`persistedModeFlags=0x1`). The helper also explicitly granted
read access to its own root metadata so Assistant could verify the local-provider
flag. This additional test accommodation means the results do not establish
ordinary storage recovery or general third-party-provider compatibility.

Observed device results:

| Case | Observation |
| --- | --- |
| Index formats | Three files and three excerpts indexed; the unsupported PDF was skipped; zero truncations. |
| TXT answer | `VELA-5836`, Wednesday 16:45, with `nebuleuse.txt` cited. |
| Markdown answer | 47 green spools, citing `vega.md` and its Logistics section. |
| Word body answer | Prototype `Cobalt-19`, citing `aurora.docx`. |
| Edited TXT and re-index | New answer `VELA-9042`, Thursday 11:20, citing the current file. |
| Missing fact | No relevant excerpt for the nonexistent Orphée/navette fixture; no invented answer. |
| Folder removed | `Folder unavailable`, zero files/excerpts, and no answer from the older Aurora response. |
| Folder restored | Three files/excerpts indexed again with the existing read grant. |
| Tree grant revoked | `Folder unavailable`, zero files/excerpts, and no answer from the older Nebuleuse response. |
| Workspace disabled | Cached index cleared to zero files/excerpts. |

The combined Vega/Aurora question initially reported no relevant excerpt. Each
format worked separately. The retriever requires at least half of the question's
distinct search terms to match each passage, so multi-topic phrasing is a coverage
limitation to investigate. A successful `search_workspace` fallback call was not
independently observed; a correct answer or refusal alone is not evidence that
the fallback tool ran. Prompt-injection and restart-persistence acceptance were
not performed on device.

The temporary provider was uninstalled after Workspace was disabled; uninstall
returned `Success`. Its private test data and URI grants were removed. The native
phone-storage issue is still open.

### Folder creation retry on 2026-10-08

At the wearer's request, the USB test was repeated by creating fresh directories
through Android's own `New folder` dialog. A new directory directly under shared
storage opened successfully, but `Use this folder` remained disabled inside it.

Because the picker did not list the existing storage contents, a fresh standalone
`ACTION_OPEN_DOCUMENT_TREE` task was opened with `EXTRA_INITIAL_URI` pointing to
the existing `primary:Download` directory. Inside `Download`, `New folder` created
`NexusWorkspace-20261008` and automatically entered it. The breadcrumb showed
`Download` followed by the new subfolder. Android still displayed its privacy
restriction message; the UI hierarchy confirmed `android:id/button1` had
`enabled=false`. A shell existence check independently confirmed the new path
`/sdcard/Download/NexusWorkspace-20261008`.

Thus the failure also reproduces inside a freshly created Downloads subfolder,
not only at a restricted storage root. No folder grant or Assistant indexing was
claimed from this retry. The Downloads directory was retained for inspection;
the empty temporary root-directory trial was removed. No production code or
device permission settings changed.

## Handoff state and remaining acceptance

Assistant was left on `Type first`, as requested for testing without microphone
input. Its original conversation-retention and voice-output settings were retained.
Temporary phone Developer mode was disabled after recording the bus evidence.
The Assistant session was closed on the glasses. Approved test operations remain
available for the wearer to try. Test documents and previous APK backups were
retained locally for diagnosis or rollback.

Workspace remains off with no cached excerpts; ordinary native folder selection
still needs recovery. The isolated tests above do not remove that acceptance gate.
Plan 024 still needs reliable departure follow-ups and ordinary journey stopping,
the remaining moving-journey/restart checks, Nav guidance acceptance, and voice
testing. Permission revocation and the stationary journey start/recovery checks
were exercised in the USB session.
Media Deck's Skills integration is not implemented on the tested branch, as
documented in Plan 024. No complete-plan or production-release acceptance is claimed.
