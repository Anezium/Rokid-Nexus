# Nexus Bulk Link v1

## Status

Bulk Link v1 is the internal high-bandwidth transport shared by Camera/Lens,
video playback, and future features that need more throughput than the control
bus. It is an implementation contract between the phone hub, glasses hub, and
the typed plugin SDK. It is not a general-purpose network capability.

The first implementation remains hardware-unverified. `BUSSPEC.md` is the wire
authority; this document defines ownership, lifecycle, and integration rules.

## Goals

- Keep one glasses-owned Wi-Fi Direct group warm across plugin transitions.
- Give exactly one feature session an exclusive logical lease at a time.
- Move Wi-Fi credentials, group mutation, and TCP ownership into the two hubs.
- Let an approved plugin exchange a full-duplex byte stream without Binder-sized
  media chunks or direct access to Wi-Fi credentials.
- Rotate logical identity on every handoff so bytes from the previous owner can
  never enter the next session.
- Preserve Camera's explicit priority over video and preserve its LOHS reverse
  fallback when the phone cannot use the normal P2P path.

## Non-goals

- Bulk Link is not Internet access, a LAN proxy, a raw socket API, or a generic
  `wifi_p2p` plugin grant.
- It does not multiplex two active media sessions in v1.
- It does not keep codecs, feature activities, plugin processes, or local data
  channels alive during the warm window.
- It does not replace CXR/SPP. The existing bus remains the control plane and the
  recovery path for bounded camera freezes.
- It does not promise LOHS handoff reuse. The warm reusable path is P2P v1.

## Components

### Glasses hub

`GlassesBulkLinkCoordinator` is the only normal P2P group owner. It owns:

- the stable group profile;
- group creation/adoption and removal;
- the TCP listener;
- the active lease and warm timer;
- the network-side handshake;
- the byte pump between TCP and a local full-duplex descriptor.

Camera and Video activities run in isolated app processes. They receive only a
`ParcelFileDescriptor` representing their side of the active data channel.

### Phone hub

`PhoneBulkLinkCoordinator` is the only normal P2P client. It owns:

- joining or reusing the glasses group;
- the TCP connection and handshake;
- the active owner binding;
- the byte pump between TCP and the approved plugin's local descriptor.

Phone plugins do not receive the SSID, passphrase, group-owner IP, TCP token, or
the right to mutate a P2P group. Android's Nearby devices permission belongs to
the phone hub for the shared path.

### Feature endpoints

The hubs do not parse the feature stream after the core handshake. The leased
byte stream carries one feature protocol end-to-end:

- Camera: `CameraLinkProtocol`;
- video playback: `MediaLinkProtocol`;
- future purposes: a separately versioned, bounded protocol.

This keeps the transport generic without turning feature payloads into Binder
messages or coupling the coordinator to codecs.

## Lease model

A lease contains:

| Field | Rule |
|---|---|
| `sessionId` | Canonical UUID created by the feature control plane |
| `purpose` | Closed v1 enum: `camera` or `video` |
| `ownerPluginId` | Authenticated phone plugin id; absent only on the trusted glasses half before the phone resolves Camera's approved consumer |
| `epoch` | Positive random 63-bit value, replaced for every acquisition/handoff |
| `token` | Random ephemeral secret used only by the two hubs |
| `priority` | Core policy, never plugin-supplied |

Only one lease may be active. The v1 priority order is:

1. Camera, explicitly opened by the wearer;
2. video playback;
3. no owner / warm group.

An equal- or lower-priority request receives `busy`. Camera may preempt video.
Preemption is ordered: revoke and close the old local channel, close its TCP
connection, increment/replace the epoch and token, notify the old feature, then
publish the new internal offer. The old plugin never transfers its grant to the
new plugin; Nexus transfers only the exclusive transport lease.

## State machine

```text
OFF
  -> STARTING_GROUP
  -> READY_WARM
  -> LEASED_WAITING_PEER
  -> LEASED_WAITING_LOCAL
  -> LEASED_STREAMING
  -> READY_WARM
  -> STOPPING_GROUP
  -> OFF
```

Any state may move to `OFF` after radio loss, hub shutdown, invalid group state,
or an unrecoverable Android P2P error. Lease failure is fail-closed and terminal
for that feature session; recovery requires a new lease with a new epoch.

## Acquisition

1. A feature starts through its existing capability-protected control route.
2. The glasses hub arbitrates the feature and acquires the core lease.
3. The coordinator creates or reuses the stable P2P group.
4. The glasses hub sends `/core/bulk-link/offer` to the phone hub. This trusted
   route contains the group credentials, token, and epoch.
5. The phone coordinator reuses an already matching association or joins the
   group, opens TCP, and sends the bounded core handshake.
6. The glasses coordinator validates session, purpose, token, and epoch before
   accepting the TCP stream.
7. Each local endpoint calls the appended Binder method
   `openBulkChannel(sessionId, purpose)`.
8. Each hub creates one full-duplex stream socket pair, returns one descriptor, and
   pumps the retained descriptor to TCP.
9. Once both halves are available, the feature protocol starts on the stream.

`openBulkChannel` never waits for network setup. It returns `null` unless the
caller is eligible for the current lease. Feature code may retry only within its
bounded startup deadline.

## Caller authorization

On the phone, the synchronous Binder call is authorized from Binder identity:

- the calling UID must resolve to one live approved plugin registration;
- its authenticated plugin id must equal the active lease owner;
- the requested session and purpose must exactly match the lease;
- `camera` requires the approved `camera` capability;
- `video` requires the approved `video_playback` capability.

On the glasses, only the app's trusted UID may open the local half, and the
session/purpose must match the coordinator's active lease.

The descriptor is single-open. A second call for an occupied local endpoint
returns `null`. Binder death, plugin revocation, activity process death, feature
close, or link loss closes the retained descriptor and the network socket.

## Core handshake

The first TCP record is a bounded Bulk Link v1 handshake, before any feature
protocol bytes. It contains exactly the current `sessionId`, `purpose`, `epoch`,
and token. The glasses coordinator rejects:

- an unknown version or record type;
- metadata larger than 4 KiB;
- a malformed UUID or purpose;
- a zero/mismatched epoch;
- a token mismatch;
- a handshake that does not match the current active lease.

The token is never persisted, logged, journaled, or delivered to a plugin. A
rejected socket is closed without changing the active lease.

## Warm handoff

Releasing a feature immediately closes everything feature-owned:

- codec and activity state;
- plugin-side descriptor;
- both hub-side retained descriptors;
- the lease TCP connection;
- the old epoch/token.

The P2P group and listener may remain in `READY_WARM` for 40 seconds. A new lease
during that window cancels teardown and publishes fresh logical credentials over
the existing physical group. The new TCP socket is intentionally separate; v1
does not reuse an unauthenticated byte boundary between feature protocols.

There is one 40-second grace, not two. While the coordinator is warm, the normal
glasses Wi-Fi ownership reconciliation treats the core transport as retained.
At Bulk Link expiry the coordinator removes its group and asks reconciliation to
continue with its grace already satisfied. The older camera radio-disable timer
must not add another 40 seconds.

The warm window is an optimization, never a correctness requirement. Either hub
may tear down immediately for radio loss, shutdown, conflicting system P2P use,
or a security failure.

## Group ownership

The glasses coordinator may remove only a group it created or safely adopted
from Nexus's persisted stable profile. It must not remove an unrelated group to
win arbitration. The phone coordinator may leave a mismatched client group only
as part of joining a current trusted offer; routine feature release never calls
`removeGroup` on the phone.

## Camera fallback

Camera keeps its existing LOHS reverse fallback. When policy requires
`lohs_reverse`, the legacy Camera/Lens link owns that one session and Bulk Link
immediately yields any warm P2P group. A later P2P session may acquire Bulk Link after
the fallback has fully released. Video v1 has no LOHS mode.

## Failure semantics

- Missing hub Nearby permission: feature reports permission required; no plugin
  is asked to acquire P2P permission for the shared path.
- Group busy or unrelated group present: fail closed instead of deleting it.
- Join/connect/handshake timeout: close the lease, retain the group only when its
  ownership is still known, and report a terminal feature error.
- Local channel EOF: close that lease's TCP stream and notify the feature.
- Control-link loss: revoke the lease immediately; physical warm retention is
  allowed only while both hubs still have authenticated control state.
- Old epoch traffic: reject/drop without affecting the current owner.

## Observability and privacy

Logs may contain purpose, state, elapsed time, epoch-change events, byte counts,
and non-sensitive error codes. They must not contain SSID, passphrase, token,
peer identity, media URLs, or feature payloads. The plugin bus journal records
only the trusted route name and verdict, never its payload.

## Validation gates

Before Bulk Link is treated as release-ready, the physical-device matrix must
prove:

- Camera -> Feeds video -> Camera handoff without group recreation;
- a new epoch/token and TCP connection at every handoff;
- no stale Camera frame or video sample crosses ownership;
- Camera LOHS fallback still works;
- forced plugin/activity/hub death closes descriptors and leases;
- warm expiry removes only the Nexus group and does not create an 80-second
  double grace;
- normal phone Internet routing returns after teardown;
- sustained Camera and video throughput, thermals, and battery remain acceptable.
