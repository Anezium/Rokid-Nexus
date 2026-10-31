# Changelog

## 0.3.0

Street maps and render feedback need Nexus hubs 2.0.0; older hubs keep route-only guidance.

- Anchor every map geometry on the route's ground, so Nexus hubs 2.0.0 glide
  the map between updates instead of jumping every few seconds.
- Stop reporting "GPS signal lost" while you stand still. A walk asked Android
  for a fix only after two metres of movement, so waiting at a crossing let the
  last fix age past 30 seconds; fixes now keep coming every second.
- Send long destinations, streets and instructions whole. On Nexus hubs 2.0.0
  the glasses scroll them instead of ending them in an ellipsis ("Gare du Nord,
  Paris 10e…"); older hubs keep the bounded lines.
- Stabilize GPS quality around the 35 m accuracy and 50 m off-route thresholds.
  Recovery requires accuracy at or below 30 m or distance back within 40 m of
  the route, so threshold jitter cannot repeatedly bypass a map's acknowledgment
  hold. Degradation and genuine off-route entry remain immediate; arrival and
  reroutes keep their immediate publication bypass.
- Keep streets in dense Whole route views as more tiles load. Prepare spatially
  spread detail on the decoding worker within the existing aggregate work limits,
  without changing the visible tile cover. Allow streets without optional names
  when the label reserve alone would exceed the geometry's byte budget.
- Give the first rendered-map acknowledgment a quiet publication window, including
  after lease loss, so one-second GPS updates cannot indefinitely starve Follow
  or Whole route of streets. Coalesce fixes until feedback or a deadline of five
  seconds, then ten and at most twenty after missed acknowledgments. The first
  exact acknowledgment trains the next window from its measured round trip plus
  25% (at least one second), clamped to five through twenty seconds; excess
  backoff decays by a quarter per acknowledgment instead of resetting to five.
  Renewals and older leases do not retrain it. A new turn, route-quality change,
  reroute or arrival is published at once. Exact-revision checks and tile
  authorization are unchanged.
- Keep acknowledged street-map covers through activity and page updates, and
  restore loaded streets on render feedback without waiting for the next GPS fix.
  Wait for a drawn map's pending update to be acknowledged before replacing it
  again to reduce a contributing race with delayed render feedback. Catch up
  held pages after reconciling street demand and retry held publications at
  rendered-lease expiry. Add bounded, counts-only feedback gate diagnostics
  for the next device run; the island cutoff still needs device confirmation.
- Decode dense city street maps without retaining address, POI or other unused
  layers. Keep tile-wide scan and street allocation limits; warn once per
  rejected tile without logging its location. Show route-only availability
  after a tile failure and when rendered-map support is absent.
- Keep the route activity and its launcher position through unreadable notification
  rewrites and reroutes for up to ten seconds from the first unreadable post,
  showing the last readable guidance. Recovery updates the same activity;
  removal or switching the source off still ends it promptly. Ignore a late
  owner close acknowledgement so it cannot restart a replacement route.
- Street map. A walking route's maps now draw the real streets around it,
  from OpenStreetMap vector tiles the phone downloads only for the maps on
  the glasses, confirmed by authenticated, expiring render feedback for the
  selected view. Both hubs must support map attribution and this feedback;
  older peers keep route-only guidance. The route stays on top and is never trimmed for streets;
  the nearest streets and major roads come first, with a few real names.
  Tiles are cached by their HTTP freshness and still drawn offline; a missing
  tile leaves the route alone there. Every map with streets credits
  OpenStreetMap, and the phone screen says before a journey starts that the
  tiles for the area shown are downloaded.
- Bound parser allocations across all layers and features before retaining
  readers, and reject excess aggregate view preparation before clipping or
  packing streets. Merge 304 cache metadata, preserving omitted policy and
  evicting previous data on `no-store`.
- Walking routes. Search a place on Navigation's phone screen and tap Start:
  your position and the destination go to Transitous once to plan a walking
  route, and the glasses guide you turn by turn. Location is asked for at
  that tap and read only while the journey runs, in a location foreground
  service whose notification carries Stop. The journey ends on arrival, on
  Stop (notification, phone screen or the glasses' route page), when location
  permission is removed, when no route could be planned (never retried),
  after ten minutes without GPS and after twelve hours. While it runs,
  other apps' guidance waits; it resumes from what they post when it ends.
- With hubs that both negotiated the launcher bundle, the route activity
  opens `nav:main` as a route page. A walking route shows the next turn, its
  distance and street, the time left, and two maps (follow, whole route) with
  your position live, stale with the age of the last fix, or absent. Its map
  and compass heading follow the activity over the data plane only; the text
  never waits for them. Another app's guidance stays text only there, with no
  position and no street drawn; a Citymapper ride shows its stops, naming only
  the one to get off at, and says when the vehicle's place between two stops
  is an estimate. Older hubs keep the summary page.
- Pages for the glasses' Nexus session, on hubs that support them: the
  current guidance as a page (the turn, its distance, the street, the arrival
  time when the app gives one, the source and when it was received), kept
  live while it is on screen, and a Commands page that opens the full app.
  Read-only; the card and the activity are unchanged.
- Plans its activity traffic through the SDK's shared guidance planner and
  glyphs. No change to what the glasses show; a recorded transcript test pins
  the traffic.

## 0.2.0

- OsmAnd and OsmAnd+: the turn in words, its distance, the street and the
  arrival time, with the close-turn warning. Checked on a phone and glasses.
- Yandex Maps: driving distance, street and arrival time, in English or
  Russian. Checked on a phone and glasses, the distance counting down.
- Organic Maps: distance and street, updated while the app is in the
  background. Checked on a phone and glasses. Its arrows are pictures, so the
  glyph stays neutral.
- maps.me is read too, but maps.me itself does not keep guiding in the
  background on Android 11 and later: in picture-in-picture it posts nothing,
  and without it the distance freezes because Android denies its navigation
  service the location. Organic Maps, built from the same code, works.
- Each new app has its own switch.

## 0.1.1

- Korean: Citymapper GO and Google Maps public transport are read in Korean
  (walk, wait, board, stops left, get off soon, arrival), so Navigation works
  in South Korea, where Google Maps has no turn-by-turn. Korean times keep
  their 오전/오후. Built from the apps' own Korean strings; not yet checked on a
  real trip in Korea.

## 0.1.0

- First release: Google Maps turn-by-turn and public transport, and
  Citymapper GO, as one live activity on the glasses, read from their
  notifications. A new step flares; a turn a few metres away or the stop to get
  off at beats once. Needs both Nexus hubs 1.5.0.
- Opening Navigation on the glasses shows the whole current instruction, or
  how to start one.
- A main switch and one switch per app, so Google Maps or Citymapper can be
  kept off the glasses without uninstalling anything.
