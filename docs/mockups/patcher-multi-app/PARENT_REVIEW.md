# Parent review of Fable's Patcher mockup

Retrieved on 2026-10-09 from the existing Claude Code cloud session using the user's authenticated Helium browser. No second design session or substitute model was used. Helium's model selector reads Fable 5.1; the delivery also reports configured model `claude-fable-5-1`. Neither observation independently identifies the serving model.

## Delivered files

The downloaded `patcher-multi-app-mockup.zip` contains exactly:
- `docs/mockups/patcher-multi-app/index.html`
- `docs/mockups/patcher-multi-app/DESIGN.md`

Only those two entries were extracted into the requested local folder. Archive paths and size bounds were checked, symlinks and existing destination files were refused. The HTML and design notes are preserved byte-for-byte from Fable's delivery.

SHA-256:
- ZIP: `ff883253bc85da237a7c92b65ceb4b590ff4730e71a2224a493295f091988f33`
- HTML: `6e06b82a6caf835f5e09d464def01ab2e55653ecf4d3498661721cb3481f3050`
- DESIGN.md: `b5968e0a9e63e4c5b5576cb70e12d454959b717d93ebc6c460949e23ad615b94`

## Observed parent checks

The actual self-contained HTML was inspected and rendered with T3 `html_preview` at 728px and 360px in the dark theme. Home shows both targets; Reddit has a Preview badge. The document has one inline script and no external asset URLs or network-call primitives. Both original previews reported no console messages/errors. Content heights were 1110px and 1185px respectively.

A temporary in-memory diagnostic script exercised the delivered HTML through its rendered controls and review API. This diagnostic was not written into or used to alter Fable's source file. It reported **30/30 checks passing at each width**, with no failed assertions or script errors:

- Both app choices, direct Reddit preparation, picker open/Escape, wrong-app and incomplete-bundle rejection, valid input and disabled/enabled start controls.
- Explicit start confirmation, one simulated Reddit job, Back retaining it, reopen, confirmation before cancellation and cancelled result.
- Simulated success/result, Save sheet closure, switching apps without clearing the other app's result, separate YouTube source, optional sign-in warning and confirmation/escape behavior.
- Target-specific Nexus entry, locked app, root Back handoff and reopening the exact app root.
- Signing-key backup validation retaining typed input, successful simulated backup and sheet closure.
- Home and Reddit preparation without horizontal document overflow, and phone width within the reply column.

Fable separately reports 63/63 headless checks, zero console/page errors and zero network requests in its final cloud run. Those are child checks, distinct from the 30 parent checks above. The parent also observed the child command's `63/63 checks passed` output through Helium.

## Interpretation and limits

This is a phone UI proposal, not an Android implementation or native validation. Patch rows, required locks, version/count labels, durations and handoff screens include illustrative choices. The existing target metadata, selection warnings, signing policy and actual Nexus handoff must remain authoritative if the owner approves implementation.

No APK, Android source, build setting, signing material or device state was changed for this design review. No commit, push, PR, publication or real key operation was performed. The user must approve the design before Android UI work begins.
