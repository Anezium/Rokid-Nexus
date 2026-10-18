# Navigation HUD correction: source acceptance and bounded device check

## Accepted source and artifacts

The independently reviewed production correction ends at `7162e617`.
Canonical `0aa3eb1b87dc2765c7e0d2ac7edfbacfa69d9380` includes two inspected
test-fixture follow-ups with identical production. Astra R3 approved this source
with no outstanding findings, after R1/R2 corrections for short HUD budgets,
manual position notifications and compact maneuver visibility.

The correction limits user-selectable Automatic/Text/Minimap/Panel variants to
Navigation. Other plugins retain developer-selected presentation. The HUD reserves
the calibrated native status row, uses a compact fallback when space is short,
preserves maneuver direction, and adjusts map labels, destination text and stale
GPS presentation. Passing source checks do not establish device appearance.

The final configured-checkout command covered shared, bus-client, both hubs and
Navigation tests, plus debug and release APK assembly for both hubs and Navigation.
Observed XML totals were 2,996 cases, zero failures/errors/skips; unaffected suites
were reused. The final command's actual tail was:

```text
BUILD SUCCESSFUL in 1m 31s
479 actionable tasks: 11 executed, 468 up-to-date
```

All three signed candidate hashes and their existing release signer were checked.
After the phone USB disappeared before the first installation, the owner supplied
a Wi-Fi ADB endpoint. All three exact `0aa3eb1b` artifacts then installed in place
with `Success`. No uninstall, data clear, pairing reset, USB mode switch or ADB
server reset was used. No signing or machine build configuration changed.

## Observed device results

| Check | Result and local evidence |
| --- | --- |
| Phone and glasses upgrade | Three successful results in ignored `build/ambient-options/install-result-r3-wifi.json` |
| Existing hub connection | Phone settings show CXR-L control and SPP data both Up |
| Navigation-only display choices | Current phone UI shows a single Navigation card, Automatic initially selected; `hud-fixed-phone-display` XML and screenshot |
| Save Panel and restore Automatic | Both states observed; `hud-fixed-phone-navigation-panel-choice.png`, `hud-fixed-phone-display-restored.png`, corresponding XML |
| Root navigation and icons | Current glasses root shows Navigation, Notifications and Applications with their icons; `hud-fixed-root-live.png` |
| First-fix waiting state | Phone and glasses show waiting; `hud-fixed-phone-nav-status.png`, `hud-fixed-ambient-panel.png` |
| Current-build street map and native-row clearance | **Not verified**: no first acceptable position was obtained and no route map was computed |

The bounded stationary journey used the public station destination already chosen
by the owner. Precise location was allowed for this session and notifications
temporarily allowed. Location mode was enabled, but no first acceptable fix was
observed. The UI eventually reported "Could not start guidance. Check GPS and try
again." and the journey service ended. This report does not attribute the lack of
a fix to a confirmed hardware, Android or application cause. The owner declined
moving the phone to obtain a fix; no further location attempt was started.

The waiting-state image is not evidence that the corrected map fits above the
native indicators. The earlier real OSM map images and HTML gallery predate this
HUD correction; they remain historical basemap evidence only. No mock position,
route or screenshot was substituted. End-from-glasses remains unverified.

## Cleanup and remaining work

The journey service was already absent before cleanup and remained absent after
it. Navigation Automatic is selected; Text, Minimap and Panel are false. All three
temporary location/notification grants are denied again, and their observed flags
match the pre-test baseline, including existing one-time/history metadata. Glasses
returned to native home. Raw permission/state proof is in ignored
`build/ambient-options/hud-wifi-cleanup.json`. No microphone was used.

The remaining HUD device check needs an actual acceptable position to exercise
Minimap, Panel and full-map street rendering, native-row clearance and map-label
placement. Source acceptance is complete; this appearance check remains open.

Fable 5.1 High owns the separately authorized phone UI/UX corrections in an
isolated worktree. Its eventual implementation still requires integration,
affected tests/builds, a new independent review and actual phone screenshots.

Screenshots and raw route/device evidence stay in ignored local storage because
they may reveal private location or device information.
