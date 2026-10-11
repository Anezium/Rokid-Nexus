# Changelog — Sample

## Unreleased

- A copyable skill operation, `count_words`, with its catalog and handler. It
  needs a phone hub that announces skills v1 and explicit approval of the
  operation; the call never opens the plugin.
- A todo example for the launcher bundle, through the public SDK only: the
  `hello:main` and `hello:todo` pages, a checklist whose rows carry `checked`
  and whose actions are typed `SetChecked` with the explicit desired state,
  answered done with a replacement, stale or rejected, never optimistically and
  never twice for one invocation, with a base-template fallback.
- A "Show in activities" switch publishes the list as an ongoing activity that
  opens `hello:todo`; no service is held for it.
- Declares `rokidbus.plugin.pages`, `rokidbus.plugin.quickActions=checklist`
  and a `checklist` glyph; no capability changes. The Applications row now opens
  `hello:main`, whose **Open the demos** row leads to the existing demos.

## 1.0.4

- Demonstrate activity extras with SDK 0.21.0 and Nexus hubs 1.5.0: a
  scripted route, one step per `DEMO_ACTIVITY` intent sent over adb, shows the
  fitted panel, a line badge, a measure beside the value, a track of stops and
  an urgent "get off" update. On older hubs the same steps show without the
  extras.

## 1.0.3

- Demonstrate background microphone capture with SDK 0.18.0 and Nexus hubs
  1.4.10: acquire audio, detach the surface, and resume or stop from the hub.
- Put Start background mic first on the Ink page.
- Keep the microphone pin compact and expire it within five seconds of a crash
  or stalled audio stream; normal stops hide it immediately.

## 1.0.2 — unreleased

- Add the background microphone reference flow with a non-waking pin, detached
  surface, resume control, and explicit lease release.
- Add the public Ink Surface reference: capability detection, authored page,
  chart, tap action, data patch, typed callbacks, and card fallback.
- Add the speech-to-text dictation surface, retry flow, and plugin metadata.

## 1.0.0 — unreleased

- Canonical headless template with image and card surfaces, input handling,
  and a kit-based settings screen.
