# Nexus Patcher: YouTube + Reddit phone UI

## Assignment

The user requests Claude Fable 5.1 through Claude Code **cloud** to redesign Patcher's phone interface and deliver an interactive HTML mockup for approval. Patcher used to be presented as a YouTube tool; it now supports YouTube and Reddit. Give both apps a first-class place and make the preparation flow understandable. This is design work only. Do not implement Android UI or patches.

Use the configured model `claude-fable-5-1`. Do not substitute another model. Report the configured model and whether you can independently verify the serving model. The parent deliberately launches this through `claude --cloud`, rather than a local Claude subscription session.

## Edit and delivery boundary

Only create/edit these files in the cloud checkout:
- `docs/mockups/patcher-multi-app/index.html`
- `docs/mockups/patcher-multi-app/DESIGN.md`

Deliver the ACTUAL complete files, preferably as downloadable attachments or a ZIP, and include a unified diff or file contents if needed for retrieval. Cloud work is separate from the parent's Windows checkout: an unsupported local path reference is not a delivery. Do not commit, push, create a PR, publish, release, build, install, change machine settings, contact anyone, or modify any other file. Do not change the existing Reddit prototypes. Do not run app, APK, signing or device operations. Do not spawn additional agents.

The user's local checkout has uncommitted Reddit work that may not exist in your remote checkout. The current source facts below are authoritative preparation findings. Do not revert them or redesign around an older YouTube-only checkout.

## Product and actual implementation

Nexus is an Android phone companion and glasses hub. Patcher is a **phone-only plugin**, opened inside Nexus; it prepares APKs locally. It has no separate launcher icon and does not install applications on its own. The Nexus phone hub owns validation, transfer and installation on the glasses. Patcher returns a verified APK to it.

Local source inspected by the parent:
- `plugins/patcher/src/main/java/com/anezium/rokidbus/plugin/patcher/PatchActivity.kt`
- `plugins/patcher/src/main/java/com/anezium/rokidbus/plugin/patcher/PatchTarget.kt`
- `plugins/patcher/README.md`
- `docs/PLUGINS.md` settings-screen design kit
- `bus-client/src/main/java/com/anezium/rokidbus/client/ui/NexusUi.kt`

The current settings screen is a long single scroll with:
1. Patcher header, currently using the generic bolt mark; subtitle Phone-only / version.
2. A small `App: YouTube · Change` text control opening an Android picker dialog. It is omitted for a target-specific Nexus request.
3. App description and three stacked blocks: Stock app, Patches, Patch/progress/result.
4. Signing-key backup/import, technical Bundle and patcher details, plugin uninstall.

The backend already has data-driven targets, target-specific bundles and selections. The UI should reflect that instead of suggesting a YouTube-only tool. Present a convincing multi-app home and an app-specific preparation screen, with a clear app identity throughout. Keep navigation concise; the user wants a practical rework, not a sprawling store or dashboard. Do not invent more supported apps.

### YouTube target

- Required official version: **21.04.223**.
- Stock package: `com.google.android.youtube`.
- Default featured patches: **Rokid controls**, **GmsCore support**, **Hide ads**, **SponsorBlock**.
- Compatible extra patches come from the bundle; existing UI has an All patches disclosure with enabled count. If you display further items, label them illustrative rather than claim an exact current count.
- GmsCore/MicroG is part of YouTube's Nexus setup; turning off GmsCore support can break sign-in and the expected output package. Turning off Rokid controls removes glasses controls. Model the corresponding explicit confirmation.
- Expected output with normal defaults: `app.morphe.android.youtube`.
- Published pinned bundle: `1.39.1-rokid.3`; it supports a validated prepared-bundle updater. Never promise arbitrary upstream bundles work on the phone.

### Reddit target

- Required official version: **2026.14.0**, code **2614001**.
- Complete official `.apkm` works; a loose split or split-required base-only APK is refused.
- Stock and output package: `com.reddit.frontpage`.
- Default featured patches: **Rokid Reddit controls**, **Spoof signature**, **Hide ads**. Change package name is off.
- Reddit has its own green monochrome AR HUD, compact feed with media previews, text paging, media, comments, search, communities and account routes. Phone keyboard comes through Nexus. Do not apply the green-only AR rules to Patcher: this mock is a touchscreen PHONE screen.
- No MicroG requirement for Reddit.
- Current bundle: **preview23**, unpublished/local bundled preview. Reddit's remote bundle updater is disabled. Do not portray it as a public stable release or show a working remote Update button.
- Turning off Rokid Reddit controls removes the HUD and replies. Turning off Spoof signature can make official Reddit reject the patched APK. Demonstrate a warning/confirmation rather than silently remove essential defaults.
- Real navigation/media/draft testing exists. Server writes, votes, saving and physical R08 gestures are not comprehensively validated; do not advertise that every integration is proven.

### Existing safeguards and flow facts

- User supplies an official APK or complete APKM/APKS/XAPK bundle. File validation checks the target's package, accepted version, official signer, complete split dependencies and ARM64 support. Show useful errors for wrong app/version, incomplete bundle and unsupported input. No stock-download service currently exists: do not add a fake automatic Download APK feature.
- All patching stays on this phone. One explicitly started job at a time. Target, file and selection changes are disabled while the job runs. Going Back or leaving the screen does not cancel it; only explicit Cancel does. Show reopen/resume and interrupted/retry states.
- Patching progress has **Load, Patch, Build, Sign, Save** stages, elapsed time and a meaningful current substep. Percentages only exist when a fraction is actually measured. No invented remaining-time estimate; support indeterminate movement and reduced motion. Include running, success, failure, cancelled and interrupted states.
- Only a completely patched, signed and verified APK is offered as a result.
- When opened for a specific app by Nexus, keep that target fixed and label the context. `Return to Nexus` / `Use result` hands off a verified APK; installation happens in Nexus afterward. A mock handoff is not proof of device installation.
- When opened from plugin settings, app choice is available. Result actions may include **Open Nexus**, **Share patched APK**, **Save APK**, **Patch again**. A saved older result should show its age and explicit reuse/patch-again actions.
- Target choices and bundle settings persist separately. Switching apps must not accidentally reuse the other app's file or result.
- Signing-key backup/import is existing functionality, shared by Patcher's outputs. Keep it discoverable in a secondary maintenance/settings route, with plain user copy: keep the key to update existing apps; losing or replacing it can block updates. An encrypted key backup requires a password. Simulate import/export locally with fictional state, no actual file upload or secrets. Show mismatched signer as an actionable stop, never an automatic uninstall or promise to repair it.
- Native Android backup is disabled. Do not imply cloud key backup or that Morphe Manager keys are compatible with this portable backup format.
- The actual per-app **auto-open Nexus phone keyboard** switches live in Nexus's YouTube/Reddit setup screens. If referenced in Patcher, use a clear route back to Nexus and label it as hub setup; don't invent a Patcher-owned keyboard setting or permission.
- Keep the existing plugin management/uninstall entry in a secondary route. In HTML it must be simulated only.

## Visual direction

Apply the frontend-design skill if available, preserving the established Nexus **Phosphor × Mono** design language. This product consistency supersedes generic skill suggestions for unusual fonts or dramatic backgrounds.

Actual fixed palette:
- background `#070A08`, panel `#0D150F`, card `#0E150F`
- primary text `#DCF3E4`, secondary `#8BA896`, muted `#5F7A68`, quiet `#42574A`
- accent `#4DFF8C`, subdued green `#2F9D5C`
- sparse warning `#FFB84D`, error `#FF8A8A`
- thin dividers, restrained corners, system sans for titles/body and monospace for uppercase/meta.

Use a mobile layout with comfortable touch targets and readable hierarchy. Differentiate YouTube and Reddit by app names, useful summaries, icon treatment and route context, not by large decorative color fields. Use the real existing Nexus/Patcher mark if available; do not redraw the Nexus logo. Safe inline generic icons are acceptable where the existing generic bolt is used. No external fonts, scripts, images, CDN or network requests.

Keep ordinary user copy about choosing an app, required version, choosing a file and next action. Technical hashes, exact package IDs, patcher API and detailed bundle information belong in an optional details view, not the first-screen pitch. Required preview/release status and compatibility errors must remain visible where they affect a decision.

## HTML deliverables

One complete self-contained `index.html` with inline CSS/JS and local fictional data. Functional interactions, not static pictures. Include:
1. Multi-app home with YouTube and Reddit; target detail/preparation; simulated source picker and validation; featured patches and extra-patch disclosure.
2. A full simulated job route with measured/indeterminate stages, elapsed time, explicit cancellation, leaving/reopening the job and saved results.
3. Per-app success/handoff/export plus failure/retry/interrupted; correct Reddit preview and YouTube updater distinctions.
4. Maintenance/settings with key-backup/import demonstrations and optional technical details. Preserve contextual Back and selection/drafts/choices when appropriate.
5. An OUTSIDE-the-phone review toolbar to choose app, launch context (settings vs target-specific Nexus request) and scenarios. Do not put developer test controls in the product UI.

Use accessible controls and visible keyboard focus; Escape can go Back. Inputs retain normal typing. No genuine file reads, credential entry, signing, patching, transfers, account operations or installation. Label review simulations honestly.

T3 review presentation: outer page fluid and transparent, no outer decorative banner/card and no 100vh on html/body. Normally 728px reply width; about 360px on phones. The simulated phone screen may use the actual Nexus palette, inside an appropriately padded container. Keep no horizontal overflow at 360px; allow normal page height/scroll. Review toolbar should follow T3 theme variables. No external dependencies.

`DESIGN.md` should explain the navigation/visual choices, before/after app discovery, app-specific differences, current source assumptions, Android component mapping, and observed checks vs remaining limitations. Clearly label proposed additions and simulations. Android implementation waits for user approval.

## Verification and final response

Use T3 native preview tools if exposed. Otherwise you may use an already-installed headless browser with no dependency installs. Check 728px and 360px, both apps, target-specific entry lock, file-error scenarios, patch warning confirmations, job Back/reopen/Cancel/retry, per-app isolation, and result/handoff/maintenance routes. Inspect the actual files and report only observed results. A headless HTML check cannot validate native Patcher/Nexus APIs.

Return the actual files and a short recommendation. Do not just claim that you wrote them to the parent's Windows path. The parent will inspect/preview and show the mockup to the user for approval before Android implementation.
