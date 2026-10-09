# Patcher mockup revision 2 — complete YouTube setup and real green app marks

Continue your existing Fable 5.1 cloud design session and revise the two files you already delivered:
docs/mockups/patcher-multi-app/index.html
docs/mockups/patcher-multi-app/DESIGN.md

Use claude-fable-5-1 exclusively. Do not substitute another model; return the actual error if unavailable. Keep this design-only: no Android changes, builds, installs, environment edits, commits, pushes, PRs, publication, account actions or further agent delegation. Do not inspect private WIP, credentials, signing material, accounts or device identities. You may read public product documentation and official public logo assets.

## User corrections (supersede conflicting round-1 instructions)
1. Move ALL YouTube setup/tutorial UI out of Nexus Glasses apps ("Gestion des applis") and into Patcher → YouTube. Remove the "Set up YouTube" entry from Glasses apps. The installed YouTube app can still be listed/opened there like any installed native app. The user wants MicroG and the full YouTube tutorial HERE, not repeated handoffs to a tutorial somewhere else.
2. YouTube and Reddit are actual app targets, not Nexus plugins. Use their REAL recognizable brand icon geometry, adapted to Nexus green, rather than approximate plugin glyphs. Keep Nexus/Patcher identity separate.

Keep the successful round-1 two-app structure and Phosphor × Mono phone design. This is touchscreen PHONE UI, not the 480x640 glasses HUD. Do not add a third unsupported app or a new unrelated visual direction.

## Entire YouTube setup to retain within Patcher → YouTube
Public source of the current tutorial:
https://github.com/Anezium/Rokid-Nexus/blob/main/docs/YOUTUBE_GLASSES.md
https://github.com/Anezium/Rokid-Nexus
The following are product semantics to represent, not private implementation instructions:

- A clear step-by-step overview with individual detail routes/sections and status, so beginners can complete the whole process while experienced users can patch/update directly. Back stays within Patcher and retains file selection, drafts/settings and job state.
- MicroG on glasses: show missing / installed / installed but not launchable / update available / offline / failed install. Install or update the verified official Morphe MicroG-RE launcher-icon release; expose Open MicroG, Check for updates and source under useful secondary actions. Source: https://github.com/MorpheApp/MicroG-RE . Do not silently mark install Done after a download or transfer; confirmation comes from fresh glasses inventory. Automatic MicroG download is established; it is different from the manual stock YouTube source download.
- Get the required official YouTube source (example 21.04.223): Download/open APKMirror in an external browser, then Choose APK or complete APKM. Do not pretend stock APKMirror download is automatic or that opening the link makes this step Done. Validate app/version/complete ARM64 source. Version/file examples must remain explicitly illustrative.
- Patch and install: review patches and explicit start, running Load/Patch/Build/Sign/Save, cancel confirmation, Back/leave job retention, reopen, interrupted/failed recovery. The final APK can be installed from the YouTube setup flow in Patcher; show connected/disconnected, validation, transfer/install/checking inventory, success, retry. Download, patch success and transfer completion alone are NOT confirmed installation. The trusted Nexus hub still performs validated download/install/open/keyboard operations behind this UI. This is a proposed UI migration and does not give a plugin installer/keyboard capability or expose trusted /core routes. Explain that only in DESIGN.md, not as technical friction on every user screen. Do not require the user to leave Patcher to find the setup tutorial.
- Updates retain data only with the compatible signing key. Signer mismatch must stop with understandable recovery; no automatic uninstall. Preserve encrypted signing-key backup/import and patch-result save/share.
- Sign in and open: guide Open MicroG → Add account ON THE GLASSES → Nexus Keyboard & remote → Open YouTube to check. Tokens/accounts remain in the app on glasses. Patcher/Nexus never ask for credentials, import a phone account or detect successful account login. Provide "Done (marked by you)" / "Mark sign-in to do"; this is a user's checklist only, distinct from installation confirmation. Buttons may open the actual app/remote in a native implementation but the mockup only simulates handoffs and returns to this tutorial.
- Phone keyboard: put the Auto-open YouTube keyboard toggle in this YouTube setup, initially OFF. Settings still belong to Nexus behind the proposed UI. Explain ordinary behavior concisely: automatically opens for YouTube search/editors when enabled; otherwise use Keyboard & remote. MicroG sign-in uses the manual secure remote. Simulate the notification fallback when Android blocks automatic opening. No credentials or real keyboard/network.
- Advanced (collapsed): "Patch with Morphe Manager instead", Add Rokid patches to Morphe (public source https://github.com/Anezium/morphe-patches), open Morphe Manager, How the Rokid patches work, choose an ALREADY PATCHED final YouTube APK, validate/install prepared APK, refresh glasses apps, retry/reinstall/update. Clearly distinguish official source input from already-patched import. Keep signer protections on both routes.

Reddit must remain an equal discoverable target and retain its complete round-1 preparation/job/result/maintenance coverage. It has NO MicroG requirement. Keep its Preview treatment. Do not mix YouTube tutorial/settings/state into Reddit.

Add a navigable minimal "Glasses apps" review route (or an external review entry) showing ordinary installed native app management/opening and NO "Set up YouTube" card/shortcut. This lets the user verify the move. The installed YouTube row may have Open / ordinary app details, but its setup/tutorial now only appears inside Patcher → YouTube. Do not create a duplicate setup destination.

## Logos
Use faithful official app icon artwork, inline and self-contained. Do not draw your own approximation of Snoo or a generic play/plugin glyph.
Official public asset provenance:
- https://brand.youtube/ (the old https://www.youtube.com/howyoutubeworks/resources/brand-resources/ now redirects here)
- https://redditinc.com/brand links its current Logo assets library at https://redditbrand.lingoapp.com/s/Logo-d9x3n2/
- Reddit's official brand page directly serves https://redditinc.com/hs-fs/hubfs/Reddit%20Inc/Content/Brand%20Page/Reddit_Logo.png?height=400&name=Reddit_Logo.png&width=400
Select a genuine monochrome official asset with transparent background where possible, preserve the exact silhouette/proportions and negative spaces, adapt its single foreground color to Nexus green. Inline SVG paths or embedded image data/CSS masks are acceptable; no runtime requests. If an asset source fails, use another actual official asset and document its provenance. Do not claim the green adaptation is an official brand colorway or brand-approved. Keep documentation factual without a permission flow.

## Interaction/quality
One complete self-contained HTML/CSS/JS, fictional local data only, no external fonts/scripts/assets/network in the finished mockup. Keep the original entry contexts (Patcher app picker and Nexus target lock), distinct per-app state, one retained job, file validation, errors, cancellation/interruption/retry, save/share/handoff and maintenance.

Review controls outside phone: app/entry/scenario plus direct YouTube setup/Glasses apps routes, including missing MicroG, not-launchable, offline, install failed, installed, user-marked sign-in, mismatch and blocked-auto-keyboard. Outer controls use T3 variables and fluid transparent layout at 728/360px; no horizontal overflow or 100vh. Inputs type normally, Escape/Back retains appropriate state, focus/reduced-motion respected.

Verify the entire YouTube guided tutorial, opening/returning from simulated app/keyboard/source handoffs, default keyboard OFF and persistence, source vs patched import, MicroG error/recovery/confirmation, install confirmation only after inventory, manual sign-in Done, removal from Glasses apps, app state isolation and all existing job/Reddit coverage. Inspect actual 360px screenshots. Use T3 native previews if exposed; otherwise preinstalled headless tools with NO installs are acceptable. Report observed checks separately from untested native APIs.

Update DESIGN.md around the final design and migration, not only a changelog. State proposed UI ownership vs unchanged hub authority and remaining implementation questions honestly.

DELIVER complete updated index.html and DESIGN.md as downloadable files AND a uniquely named patcher-multi-app-round2.zip containing these two files. Attach a usable diff if convenient. The parent retrieves them with authenticated Helium, inspects and previews, and asks the user to approve before Android implementation.
