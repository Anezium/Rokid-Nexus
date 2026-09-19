# Sample plugin

The canonical copyable starting point for a Rokid Nexus plugin. Its manifest is
headless: the exported settings activity has no launcher intent filter, while one
exported `NexusPluginService` advertises the plugin action and descriptor metadata.

The service first demonstrates the public `NexusInkSurfaceSession`: an authored
page with bound metrics, a native chart, a tap action, and set-data-style
patches. It falls back to the ordinary surface API and also demonstrates a
bundled JPEG image, cards, directional input, tap state, and back-to-close
behavior. The settings activity uses the shared `NexusUi`/`BusTheme` kit and
provides the system uninstall action.

The manifest requests `ink_surface` and `microphone` separately from `surfaces`.
After install, approve the grants to exercise Ink and background audio; old
glasses or a down SPP data link take the fallback path. The implementation is in
[`HelloPluginService.kt`](src/main/java/com/anezium/rokidbus/plugin/sample/HelloPluginService.kt).

## Background microphone

Choose **Start background mic** on the Ink page, or **Background mic** on the
card fallback. The sample acquires a typed `NexusAudioSession`, pushes a pin as
the non-waking listening indicator, and calls `NexusSurfaceSession.detach()`
only after the lease becomes active. Frames continue to be counted while the
surface is gone. Reopen the plugin to resume its control card and tap to release
the lease, or use **Stop** on the phone hub.

This flow requires the `microphone` capability and `/audio` receive prefix. The
pin does not wake or keep the display on; it only becomes visible when the HUD
is otherwise awake. Its five-second TTL is renewed every two seconds while audio
frames arrive, so a crashed plugin or stalled stream cannot leave a stale listening
indicator. Normal stops hide it immediately.

## Dictation

The Dictation menu entry demonstrates a typed `NexusSpeechSession`, including live
partials, accumulated final text, batch-mode feedback, stop reasons, and retry. It
requires `CAPABILITIES=surfaces,stt` and
`RECEIVE_PREFIXES=/plugin/hello,/system/plugin,/stt` in the service metadata.

After install, grant **Speech to text** in **Rokid Nexus > Settings > Plugin access**;
installation never grants it. A speech API key must also be configured in Rokid
Nexus's Speech screen.

To start a plugin, copy this module, rename its package and plugin ID consistently,
then replace the sample service and settings content. Read
[PLUGINS.md](../../docs/PLUGINS.md) for the headless and design-kit rules and
[PLUGIN_SDK.md](../../docs/PLUGIN_SDK.md) for the SDK contract.
