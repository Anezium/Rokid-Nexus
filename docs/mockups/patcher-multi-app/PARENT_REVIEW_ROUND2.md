# Parent review — complete YouTube setup in Patcher

Final Fable cloud delivery retrieved through authenticated Helium on 2026-10-09. The same Claude Code cloud session was used for the initial revision and the narrow follow-up. Its selector reads Fable 5.1; this establishes the configured model, not an independent serving-model identity.

## Final delivered files

The final ZIP contains exactly the two requested relative paths. Entries, size bounds and destination containment were checked; symlinks were rejected. Before each replacement, the previous local files were checked against their recorded hashes. The source files remain byte-for-byte Fable's delivery. Earlier ZIPs retain the previous revisions.

- ZIP `patcher-multi-app-round2b.zip`: SHA-256 `6b3977595af14352dfb8f0b03a23b67977df3c531a2513b40190a7c604c9d585`.
- `index.html`: 135244 bytes; SHA-256 `7210b302eaa3c826061460338f4da1afd4fcb0fd0d391542acef2b428b3d6819`.
- `DESIGN.md`: 24976 bytes; SHA-256 `5932de8dddaa1ccf02eee28f2104d4a7492f7a26c37b5c72454b4c7a98b5863a`.

## Observed checks

The final files were inspected, including the setup screens, source validation, action guards, simulated inventory confirmation and hub-authority notes. There is one inline script, no external script/image/stylesheet references and no network-call primitives. App icon geometry comes from public official asset bytes fetched by the parent and supplied to Fable; the green rendering is an adaptation rather than an official colourway.

T3 native `html_preview` ran **70/70 parent flow checks at both 728px and 360px**, without failed assertions or script errors. The temporary diagnostic and deterministic timer harness existed only in the preview string, not the delivered files. The harness advanced the actual mockup interval callbacks using virtual time; these checks establish UI and phase transitions, not real elapsed-time behavior.

Covered: both app cards and official symbol references; unique SVG IDs; the four-step tutorial and collapsed Advanced; keyboard default off and persistence; MicroG transfer/check/confirmation, external opening, no-icon/update/offline/failure/retry; stock-source external handoff and validation; explicit patch start; Back/job retention; source locking, late picker guard and Advanced locking; cancel/unlock/retry; install transfer/check/confirmation distinct from sign-in; secure-remote stand-in and manual checklist; ordinary Glasses apps opening without setup cards or a setup action in app details; Morphe and patched import validation; signer mismatch stop; notification fallback; Reddit result/handoff and complete YouTube-state isolation; target-specific Nexus entry, root Back and reopen.

Original final Home at 728px, YouTube tutorial at 360px and installed Glasses apps at 360px were rendered and visually inspected without console messages. Content heights were 1166px and 1315px respectively. No horizontal overflow was observed in the exercised Home/tutorial/install/mismatch/Glasses apps routes. Preview-only starting-route scripts show the tutorial and installed-apps examples; they do not alter the source files.

The first round-2 parent run found one real defect: step 2 allowed choosing another source after Back during an active job. Fable corrected disabled controls and action-boundary guards in the follow-up, and removed the remaining app-details setup action. A separate parent assertion falsely rejected Reddit's explanatory "No MicroG" copy; that assertion was corrected to inspect actual controls and state.

Fable reports 96 passing full-suite checks and an additional passing targeted regression suite. These are child checks, distinct from the 70 parent checks above. See DESIGN.md for the child's observed coverage.

## Limits and next step

This is a phone UI proposal with fictional state. HTML checks do not validate Android installation, account access, physical controls or the hub integration. The tutorial moves into Patcher's navigation; download/install/app-open/keyboard authority remains hub-owned. Native placement and the read path for hub-persisted settings remain implementation questions.

No Android source, APK, signing material, build environment or device state was changed for this design revision. No commit or publication occurred. Present the interactive mockup and await the user's approval before native implementation.
