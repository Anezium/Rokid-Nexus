# Patcher phone UI mockup: YouTube and Reddit

User-authorized task: use Claude Fable 5.1 in a Claude Code cloud session to design an interactive HTML mockup of the Nexus Patcher phone UI with YouTube and Reddit. The user explicitly wants this cloud design task. Do not implement Android changes yet.

The attached brief contains design requirements and public product context only. Do not request or inspect private work-in-progress files, signing material, credentials, accounts, device identities or local configuration. Public background, if useful: https://github.com/Anezium/Rokid-Nexus and its docs/PLUGINS.md. The user's central request is to evolve a YouTube-oriented Patcher interface into an obvious two-app interface.

Use `claude-fable-5-1` exclusively. If unavailable, stop and return the exact error; do not substitute a different model. State the configured model and distinguish it from independently verifying the serving model.

## Scope and delivery

Create only two design files: `docs/mockups/patcher-multi-app/index.html` and `docs/mockups/patcher-multi-app/DESIGN.md`. No Android implementation, APK operations, builds, installs, commits, pushes, PRs, publication, machine changes, messages to other people or further agent delegation. Preserve all other files.

Cloud files do not automatically reach the user's local checkout. Deliver the complete HTML and design notes as downloadable files/ZIP where supported, or return complete file contents and a usable diff. Do not claim a file was delivered merely by referring to a local cloud path.

## Design task

This is a touchscreen Android PHONE interface, not the glasses HUD. Keep Nexus's public Phosphor × Mono visual language: restrained dark green layers, bright green accent, readable system sans for names/body and monospace for labels/meta, thin dividers and concise copy. Do not invent a new Nexus logo. Apply frontend-design if available while respecting this established product design system. Use no external fonts, scripts, assets or network requests in the HTML.

Make YouTube and Reddit equally discoverable from Patcher's initial screen. Give each its app identity, a short useful summary and a clear next action. Avoid a crowded dashboard or catalogue of unsupported apps. A two-app picker and app-specific preparation flow should feel intentional and easy to understand. Keep app identity visible during preparation, progress and success.

The mockup should cover these practical routes:
- Home: choose YouTube or Reddit; a secondary maintenance/settings entry.
- Prepare selected app: show an example required version, choose a fictional official APK/complete bundle, display validation, review relevant patch toggles, then explicitly start patching. Version values and file names may be safe illustrative examples, labelled as examples rather than claims about a production release. Do not offer a fake automatic APK downloader.
- App differences: show YouTube controls/sign-in support/ad filtering and optional SponsorBlock; show Reddit glasses HUD/signature compatibility/ad filtering, without adding a MicroG prerequisite to Reddit. Give Reddit a visible Preview treatment so this design does not falsely advertise a stable public release. Proposed patch rows are illustrative, not a verified patch inventory.
- Running job: stage list Load / Patch / Build / Sign / Save, elapsed time, known-fraction and indeterminate progress, meaningful current activity, explicit Cancel. No invented remaining-time estimates. Back/leave retains the job; reopening returns to it. Prevent changing target or source while running.
- Completed APK: clear verified-result simulation with Return to Nexus or Open Nexus handoff, Save APK, Share APK and Patch again. Explain that installation belongs to Nexus afterward; do not simulate a real successful glasses installation as a verified fact.
- Failure, wrong app/version, incomplete bundle, cancelled and interrupted/retry states with plain useful recovery actions.
- Secondary maintenance: encrypted signing-key backup/import demonstrations with fictional inputs; concise explanation that keeping the signing key permits updates and replacing/losing it can block them. Show a signer mismatch stop, with no automatic uninstall. Optional technical details stay secondary. Plugin management/uninstall is a simulation only.

Support two entry contexts in the review controls: normal Patcher settings (user chooses app) and a Nexus request for a specific app (target fixed until returning to Nexus). Keep separate fictional input/patch/result state for each app. Do not change the other app's state when switching.

If phone keyboard settings are mentioned, place them in Nexus setup through a clear handoff, not as a Patcher-owned keyboard capability. Keep ordinary first-screen copy focused on what the user needs to choose and do. Avoid hashes, package IDs or patcher APIs in the main pitch.

## HTML quality and review

One complete self-contained HTML/CSS/JS file with fictional local data and functional click/Back routes. This is an interactive mockup, not static screenshots. No actual files read, keys generated, app installation, account actions or API calls. Inputs support normal typing, controls have visible focus, Escape goes Back, and reduced-motion preference is respected.

Outside the phone simulation provide compact review controls to choose app, entry context and scenarios. Clearly label simulations there. The document will be embedded in T3 at about 728px desktop / 360px mobile. Keep the outer page fluid and transparent, no decorative outer banner/card, no 100vh, no horizontal overflow. The phone surface may use Nexus's dark palette; outer review controls use T3 theme variables.

Exercise both apps, app-lock context, file error scenarios, patch confirmation warnings, running Back/reopen/Cancel/retry, success/export/handoff, maintenance, state isolation, and the 360px layout. Use T3 native preview tools if exposed; otherwise an already-installed headless browser is acceptable without installing dependencies. Report only observed checks. HTML checks do not validate Android APIs or physical glasses input.

DESIGN.md: describe app discovery/navigation, visual hierarchy, differences between the apps, proposed versus established behavior, mapping to native Android/Nexus components, observed verification and limitations. The parent will inspect and present this mockup for the user's approval before implementation.
