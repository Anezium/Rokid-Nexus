# Navigation OSM basemap verification, 2026-10-18

The real OpenStreetMap street basemap is implemented and installed on the connected
phone and glasses. A bounded, stationary journey to the owner's chosen public
destination showed real streets, names and attribution in the route views. This
does not establish walking accuracy, optical comfort or the entire release matrix.

Runtime and test source: `1ce8b970f30cf7039932732cca45d8e841d530f0` on
`dev/launcher-rework`. All compilation and execution used the already configured
integration checkout. Root main, unrelated worktrees and the historical untracked
contract were preserved. No push, release, tag or external message occurred.

## Feature and source map

| Behavior | Implementation |
| --- | --- |
| Existing Transitous route planning; official OSM Shortbread street tiles fetched on the phone | `plugins/nav/.../OsmBasemap.kt`, `NavBasemap.kt`, `NavJourneyService.kt` |
| Bounded protobuf parsing, street/name decoding and route-first geometry packing | `MvtReader.kt`, `NavBasemapLayer.kt`, `NavMapProjection.kt` |
| Bounded HTTP/cache work, conditional requests, received no-store policy preserved through body failures and cancellation | `NavBasemap.kt`, `BasemapCache.kt` |
| Only authenticated, actually drawn and acknowledged map footprints authorize tile work | shared `RenderedMapContract.kt`; SDK `NexusRenderedMaps.kt`; phone `PhoneActivityRouter.kt`, `PhonePageBroker.kt`; glasses `RenderedMapLease.kt`; Nav `NavRenderedFrames.kt` |
| Current transport/session support; older peers remain route-only | `PhoneRenderedMapNegotiation.kt`, both hub capability gates and SDK publication checks |
| Lightweight map drawing with visible OSM credit | glasses `RouteMapPainter.kt`, route page/full-map and ambient renderers |

Geometry retains the existing limits of 24 paths, 1,024 points, four labels and
16 KiB. Dense or missing data can degrade to route-only. No map engine, GMS,
new account, server provisioning or route-wide tile prefetch was added. The phone
discloses the additional tile traffic before starting and links to OSM copyright.

## Independent review and observed execution

Four fresh independent rounds reviewed the feature and its corrections. The last
was GPT-6 Astra R4 at `f9c526ab`, independently resolving all prior production
findings. It requested one test-fixture correction: the loopback HTTP server's
real Date and the fake cache clock were months apart, preventing the no-store
regression from reaching its rejection assertions. Commit `1ce8b970` adds exactly
two test-only lines aligning each scenario's clock. Every assertion and production
path remains. The parent inspected the diff and executed the requested checks;
this is the R4 requested follow-up, not a claimed additional independent round.

Observed XML: shared 497, bus-client 194, phone hub 943, glasses hub 1,152,
Navigation 200: **2,986 cases, zero failures, errors or skips**. Navigation reran
after the clock correction; other suites and debug assemblies were reused from
the immediately preceding run with identical production source.

Actual canonical command:

```text
.\gradlew.bat :shared:test :bus-client:testDebugUnitTest :phone-hub:testDebugUnitTest :glasses-hub:testDebugUnitTest :plugin-nav:testDebugUnitTest :phone-hub:assembleDebug :glasses-hub:assembleDebug :plugin-nav:assembleDebug --continue --no-parallel

BUILD SUCCESSFUL in 17s
215 actionable tasks: 2 executed, 213 up-to-date
```

The actual signed release build of both hubs and Navigation, without
`skipCxrGlobal`, ended:

```text
BUILD SUCCESSFUL in 1m 58s
276 actionable tasks: 48 executed, 228 up-to-date
```

Both the installed and candidate APK signatures were verified against the existing
release signer before all three in-place upgrades returned Success. No uninstall,
data clear, pairing reset or signer change was used. Raw logs, APK hashes, backups
and reviews remain locally under ignored `build/nav-osm/`.

## Actual device observations

| Check | Observed evidence under ignored `build/smoke/` |
| --- | --- |
| Walking planner disclosure and selected public destination; actual route planned from a real fix | `logs/osm-nav-before-search.xml`, `logs/osm-nav-gps-after-wait.xml` |
| Route page draws surrounding streets and OSM credit | `shots/osm-first-route-page.png` |
| Follow full map shows real streets, a street name and credit | `shots/osm-fullmap-follow.png` |
| Whole-route full map shows street network, multiple real names, route and credit | `shots/osm-fullmap-overview.png` |
| Ambient minimap and panel show streets/credit; panel also shows a name | `shots/osm-ambient-minimap-loaded.png`, `shots/osm-ambient-panel.png` |
| Text ambient mode draws guidance without a map | `shots/osm-ambient-text.png` |
| Already loaded Follow and Overview streets remain after both phone Wi-Fi and mobile data are disabled | `shots/osm-fullmap-cached-offline-follow.png`, `shots/osm-fullmap-cached-offline-overview.png`; radio state files under `build/nav-osm/` |
| SESSION ambient map hides over native Settings after the signal interval | `shots/osm-hidden-over-native-settings.png` |

These are screenshots of real device rendering, not mockups. During this indoor,
stationary run the fix became old; the ambient renderer reported GPS lost and drew
the route degraded. Later fixes restored live appearance. No movement was simulated
or invented. Street loading remains asynchronous: an initial frame after a view
change can show only the route before cached/downloaded streets arrive.

The End action's confirmation was opened. The subsequent injected input sequence
reached an Open app prompt while the service still existed; it is not counted as
a successful End-from-glasses test or as a confirmed product defect. Permission
revocation during explicit cleanup stopped the journey. The final phone UI says
No walking journey yet, and NavJourneyService is absent.

## Cleanup, open product correction and limits

Navigation's original Automatic size is restored, with the other radio choices
false. Precise/coarse location and notification grants are denied again; their
observed flags match the pre-test baseline, including existing one-time/history
metadata. Wi-Fi, mobile data and Bluetooth are on, airplane mode is off, the phone
hub runs and the glasses are at native home without a route overlay. Accounts,
pairing and all other plugin preferences were preserved. No microphone was used.

For the subsequent accepted Navigation-only setting and HUD correction, signed
in-place upgrade, partial device check and remaining GPS-dependent appearance
check, see [the HUD device report](NAV_HUD_DEVICE_CHECK_20261018.md). The map
screenshots in this basemap report predate that correction.

The owner then rejected the universal per-plugin Text/Map/Panel settings card:
only Navigation should currently expose these variants; other presentation is
chosen by the plugin developer. This is a separate open UI/policy correction,
not closed by the basemap test. Its isolated worktree is preserved. The first
Opus subscription attempt was quota-blocked before edits; no replacement model
or new API spend was used for UI authoring.

No device proxy/request trace was collected, so hidden/text/Commands zero-request
behavior is supported by source review and coupled regressions, not asserted from
traffic measurement on hardware. No cold-process offline cache test, physical
walking/heading accuracy, old-peer device matrix, optical attribution judgment,
physical ring matrix or latency/thermal measurement is claimed. Cached visible
street rendering during a bounded Internet outage is the observed offline result.

Images and raw route evidence may reveal a private origin and stay in ignored
local storage. The local HTML gallery is `build/smoke/nexus-captures-20261018.html`.
