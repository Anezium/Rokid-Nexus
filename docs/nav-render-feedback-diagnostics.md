# Navigation rendered-map retest

Delayed feedback overtaken by a newer publication is a contributing race, not
yet a proven explanation of the island cutoff. In the original capture an
ambient acknowledgment reached the phone before the next geometry publication,
but streets disappeared before its expected five-second lease expired. The next
device run must distinguish that case from page feedback starvation.

## Capture

Install builds containing this change for Navigation and both hubs. Capture the
phone and glasses concurrently, starting before the journey. With a walking
journey running, let the home PANEL acquire streets, open the route page's Follow
map for 30 seconds, then return home. Keep the same ambient size during the run.
Repeat with automatic ambient size if collapse needs to be isolated.

Run these in separate Git Bash terminals, substituting the selected device serials:

```bash
MSYS_NO_PATHCONV=1 adb -s PHONE_SERIAL logcat -v threadtime 'ROKIDBUS-PHONE:I' 'RokidBusClient:I' 'NexusNav:I' '*:S' | tee phone-map-diag.log
MSYS_NO_PATHCONV=1 adb -s GLASSES_SERIAL logcat -v threadtime 'ROKIDBUS:I' '*:S' | tee glasses-map-diag.log
```

Filter the saved files with `rg NexusMapDiag`. The other lines retain transport
timing context. No ADB command is run by the implementation or unit tests.

## Reading the gates

All diagnostic lines start with `NexusMapDiag`, use the module's existing INFO log
tag, and contain only fixed enums, revision/sequence numbers, boolean state,
counts and local monotonic ages. No owner, request id, token, coordinates, tile
keys, street names or map payloads are logged. Each distinct
gate/kind/view/verdict/reason combination emits immediately and then at most once
per five seconds. `count` is its lifetime event count; `suppressed` is the count
since its previous emitted line. A first rejection is never hidden by successful
events. Lines are summaries, so absence of a repeated line is not evidence of a
dropped event. Counters and distinct enum keys are bounded independently of traffic.

- `PHONE_CAPABILITY` covers the remote rendered-map capability check.
- `PHONE_AMBIENT` reports the first failing check: capability, data plane,
  event schema/kind/view, activity identity, online state, activity sequence,
  registration count, or geometry revision. PASS also means local dispatch
  accepted the delivery. A failure there is `DELIVERY_FAILED`.
- `PHONE_PAGE` covers capability, schema/kind, session/frame, page identity,
  request, revision, route/actionable/pending state, page lease and registration.
  `PHONE_PAGE_DELIVERY` rechecks the frame/request/revision/lease/registration
  immediately before dispatch and reports the delivery result.
- `SDK_DELIVERY` covers closed client, owner identity, duplicate event id and
  rendered-map support. `SDK_LEASE` covers parsing, accepted lease replacement,
  matching hide, ignored old hide (`TOKEN_MISMATCH`), reset and expiry. Expiry is
  reported on the first lease read after its deadline; it adds no callback.
- `NAV_CORRELATION` reports whether a live SDK frame matches a remembered local
  publication. `keys` counts that publication's cover; zero keys can also mean a
  known view too wide for street tiles. `UNKNOWN_PUBLICATION` differs from
  `NO_LEASE`. `NAV_PUBLICATION` reports whether the acknowledged revision is
  current or a replacement is held awaiting acknowledgment.
- `GLASSES_ISLAND` reports the ambient presentation's map authority, automatic
  collapse timer and visibility decision. `GLASSES_DRAW` independently checks
  actual attachment, notice cover and full map visibility. `GLASSES_SEND` reports
  the transport result and whether feedback names a visible or hidden map.
  `kind` separates AMBIENT from PAGE, and `view` names FOLLOW or OVERVIEW.

`revision` and `seq` are feedback numbers; `currentRevision` and `currentSeq` are
the receiver's comparison values. `ageMs` is local: time since geometry forwarding
at PHONE_AMBIENT, page forwarding at PHONE_PAGE, queuing at PHONE_PAGE_DELIVERY,
lease receipt at SDK_LEASE, publication at Navigation, context binding at glasses
draw/send, and the start of the collapse timer at GLASSES_ISLAND.
`leaseRemainingMs` is the local page/SDK lease time left. `-1` and `null` mean not
known or not applicable. There is no cross-device duration calculation: correlate
the same kind/view/revision/sequence using both log timestamps, accounting for
clock offset. Compare phone hub and SDK arrival timestamps to detect Binder delay.

## Island evidence

The revision race is supported if AMBIENT/FOLLOW feedback is sent while the island
remains visible and uncollapsed, PHONE_AMBIENT drops it with `REVISION`, and the
SDK then reports `EXPIRED` followed by Navigation `NO_LEASE`. With pacing active,
`NAV_PUBLICATION HOLD AWAITING_ACK` should alternate with fresh accepted feedback
and publication, retaining remembered covers while updates continue.

It is refuted as the explanation of a particular cutoff if the ambient
acknowledgment passes PHONE_AMBIENT and SDK_LEASE, NAV_CORRELATION reports
REMEMBERED with nonzero keys, and streets disappear before that SDK lease expires
without a matching hide/reset. That evidence instead points downstream of demand.

Other patterns localize the missing evidence: PHONE_AMBIENT `SEQUENCE` or
`REGISTRATION` identifies a different phone rejection; a long phone-to-SDK gap
identifies local delivery delay; `GLASSES_ISLAND CLEAR COLLAPSED` with
`collapsed=true`, followed by GLASSES_SEND HIDDEN and SDK_LEASE CLEAR HIDDEN,
identifies automatic collapse. A hidden page or a PAGE/OVERVIEW acknowledgment
does not renew AMBIENT/FOLLOW. Always assess both slots independently.
