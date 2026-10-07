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

The phone ran Android 16/API 36. Testing used wireless ADB; USB ADB was also
available. Assistant used the already-connected ChatGPT provider. Questions were
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

### Transit stop search: passed for the ambiguity case

Transit was approved, and `Search stops by name` and `Departures at a stop` were
enabled for Assistant. Favorite access and journey actions were left disabled.

The test searched for the public Gare du Nord station in Paris. The bus inspector
showed a Transit provider invocation and result, the result delivered to Assistant,
and a non-rejected SKILL event. Assistant reported several matching stations and
asked the wearer to choose. It did not claim a departure from a guessed station.
No departure-board lookup or journey was validated in this session.

### Workspace folder selection: blocked

An isolated QA text document was placed in ordinary phone-storage test folders.
The Android document-tree picker displayed no entries and disabled `Use this
folder` with the privacy restriction message. Attempts to open the test subfolders
through `EXTRA_INITIAL_URI`, including a fresh standalone `ACTION_OPEN_DOCUMENT_TREE`
task, did not produce a selectable folder. This also occurred outside Assistant.

The storage root itself is restricted for document-tree selection; seeing that
restriction does not establish why the test subfolders could not be selected.
The underlying picker/provider cause remains unverified. No folder grant was
returned, and Workspace remains off with `No folder selected`.

Consequently, on-device indexing, prompt injection, fallback search, document
editing/re-indexing, and grant revocation were not validated. Their unit tests
passed in the combined suite; those results do not replace device acceptance.

## Handoff state and remaining acceptance

Assistant's original `Voice only` input setting was restored and visually checked.
Temporary phone Developer mode was disabled after recording the bus evidence.
The Assistant session was closed on the glasses. Approved test operations remain
available for the wearer to try. Test documents and previous APK backups were
retained locally for diagnosis or rollback.

Workspace needs a successful native folder selection before its hardware tests
can continue. Plan 024 still needs departure/follow-up tests, permission revocation
checks, real Transit journey and Nav guidance acceptance, and voice testing.
Media Deck's Skills integration is not implemented on the tested branch, as
documented in Plan 024. No complete-plan or production-release acceptance is claimed.
