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

The phone settings also demonstrate a standalone background pin. **Pin something
from the phone** sends it once, then disconnects. **Remove pin** reconnects and
calls the public SDK's `hidePin()` for this plugin; the Todo activity and list
remain unchanged.

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

## Todo example

The Todo settings pair **Keep in activities** with the SDK's **Floating
activity** link. Nexus owns that display choice and its duration. The sample
uses its persisted list revision as `NexusActivity.eventKey`, so a real list
edit may briefly reveal its band when the user chooses On changes. A resend
or reconnect keeps the same key; expiry hides the band while retaining the card.

A small checklist shows the launcher bundle through the public SDK only: pages,
typed `setChecked` actions and an ongoing activity, with no glasses app and
without opening the plugin. Edit the list in the settings screen.

- **Pages.** The service declares `rokidbus.plugin.pages=1`, so the session's
  Applications row opens `hello:main`: the list's progress and a labelled
  **Open the demos** row (an `IMMERSION` action) for everything above.
  `hello:todo` is the list: one row per task, its id the task's stable id, with
  `checked` drawing the box. Each row targets an action whose id names the task
  and the state it should end up in (`set:t3:1`) and whose type is
  `SetChecked(desired)`, never a toggle. The page's revision is the list's.
- **Actions.** `onNexusPageAction` applies that explicit state only on the
  revision the wearer saw: a task removed since, or any change to the list since,
  answers `stale` and changes nothing; anything else that does not fit is
  `rejected`. Success is reported only after the store took it, with the list at
  its new revision as the replacement. An invocation is handled once; a repeat is
  rejected and never applied again. The service declares
  `rokidbus.plugin.quickActions=checklist`, so the user may approve the family
  in Rokid Nexus and check a task with one tap; without approval every check is
  confirmed.
- **Without the launcher bundle** the same page uses plain rows that spell out
  the state ("to do · tap to check") and untyped, confirmed actions.
- **Show in activities.** The switch in the settings screen publishes the list
  as an activity: "4 left" over the list's name, a tap opens `hello:todo`
  (`activation = DESTINATION`), and `persistence = ONGOING`. Every change of the
  list updates it; turning the switch off ends it. Nothing is held for it: when
  the service is asleep, the settings screen binds it just long enough to send
  the change through the plugin's one registration, and Nexus keeps the entry as
  an offline snapshot. A new registration publishes it afresh. It needs both hubs
  on the launcher bundle; otherwise the screen says so and nothing is sent.

The code is in the `Todo*.kt` files; `HelloPluginService` and `HelloActivity`
only forward to them.

## Dictation

The Dictation menu entry demonstrates a typed `NexusSpeechSession`, including live
partials, accumulated final text, batch-mode feedback, stop reasons, and retry. It
requires `CAPABILITIES=surfaces,stt` and
`RECEIVE_PREFIXES=/plugin/hello,/system/plugin,/stt` in the service metadata.

After install, grant **Speech to text** in **Rokid Nexus > Settings > Plugin access**;
installation never grants it. A speech API key must also be configured in Rokid
Nexus's Speech screen.

## Skills

The Sample plugin also publishes one skill operation, `count_words`, so an
approved assistant can ask it to count the words in a short text. The catalog
is [`res/raw/nexus_skills.json`](src/main/res/raw/nexus_skills.json), declared
with the `com.anezium.rokidbus.plugin.SKILLS` metadata key rather than in the
capability list, and the handler is
[`HelloSkills.kt`](src/main/java/com/anezium/rokidbus/plugin/sample/HelloSkills.kt),
called from `onNexusSkillInvoked`. After install, approve the plugin again (the
catalog adds the `skills_provider` grant), then allow **Count words** for
Assistant on the plugin's access screen. The call never opens the plugin.

The demo route (`DEMO_ACTIVITY` intents) runs through the SDK's
`NexusGuidancePlanner`, the planner Navigation and Transit use.

To start a plugin, copy this module, rename its package and plugin ID consistently,
then replace the sample service and settings content. Read
[PLUGINS.md](../../docs/PLUGINS.md) for the headless and design-kit rules and
[PLUGIN_SDK.md](../../docs/PLUGIN_SDK.md) for the SDK contract.
