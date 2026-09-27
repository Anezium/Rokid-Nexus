# Changelog

## Unreleased

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
