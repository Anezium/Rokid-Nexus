# Assistant

Phone-side Rokid Nexus voice assistant plugin.

Hold the assist button, ask out loud: the words transcribe live on the HUD, then
the answer streams into the band, is spoken aloud, or hands over in place to a
native Ink page. The model can take one photo through the glasses camera when
the question needs eyes, and it can set reminders and timers, take notes, and
add, list, or delete events in the phone calendar.

Answers come from the provider the wearer picks in Settings: a ChatGPT plan
(OAuth, no key to paste), or an API key for OpenAI, OpenRouter, MiniMax,
DeepSeek, GLM (Z.ai), Hermes, or any OpenAI-compatible server. Every API preset
speaks the same chat-completions SSE dialect through one generic client
(`OpenAiCompatProvider`); the preset catalog lives in `ProviderCatalog.kt`.
Each provider keeps its own encrypted key, model, and endpoint.

Tools go through `AssistantToolRegistry`: every provider declares the tools it
can run, one client-managed tool phase per request, then the final reply. The
text tools (notes, reminders, timers, calendar) are offered to every provider;
only `take_photo` additionally requires a model that can see, and photos are
stripped gracefully for models that cannot. `render_ink_page` and
`render_template` can turn suitable results into the same strict compiled Ink
surface exposed by the public Nexus SDK; the template tool offers seven bounded
layouts. A server that rejects tools outright is retried once without them.

A Hermes backend runs its tools server-side and never returns a client tool
call, so the same twelve phone tools are described in the system prompt and
asked for on one private control line (`[[NEXUS_TOOL]]` plus a JSON object, or
an array of them). The provider filters that line out of the stream, executes
the calls in order, and replays the turn with their results as plain text. Ink
is deliberately absent from that bridge.

Ink requires the separate `ink_surface` grant and a live compatible renderer.
When either is unavailable, Assistant keeps the text answer and ordinary card
path. The listening notice and final surface belong to one display episode, so
the scoped wake lock spans the band-to-card/Ink handover and ends with the
answer rather than leaving a dark panel midway through it. A successful Ink
handover retires the tool progress notice; only a failed render restores the
progress band while Assistant prepares the text fallback.
These are Nexus surfaces rendered with native glasses Views, not windows from
Rokid's private AIUI runtime.

Phone input is optional and disabled by default. When enabled in Assistant
settings, the listening notice offers **Write** alongside normal speech. Picking
it cancels the active speech capture, replaces the choice with a platform-owned
notice field, and opens Nexus's phone keyboard. Phone-keyboard Enter submits the
field through the owner-scoped notice callback and follows the same assistant
pipeline as a final speech transcript. The plugin never receives access to the
trusted `/core/remote-input/*` routes.

Calendar access is local to the phone plugin. It uses Android's Calendar
Provider under the standard `READ_CALENDAR` and `WRITE_CALENDAR` runtime
permissions; it is not a Nexus descriptor capability, SDK surface, receive
prefix, or bus route. The settings screen owns that consent. Calendar failures
are reported to the model instead of being hidden, and deletion proceeds only
when one event exactly matches both title and start time. Multiple matches are
left untouched. The provider identity is checked again immediately before the
guarded delete, and a recurring series is deleted only after an explicit
whole-series request.

Reminders and timers persist in app-private JSON stores and fire through
`AlarmManager` even when the plugin is closed: a short-lived foreground service
posts the phone notification and raises a notice on the glasses (a pin when the
link is down), and a boot receiver reschedules what is still pending. This is
the sanctioned scheduled-delivery exception to the dormant-plugin policy —
see [plugins/AGENTS.md](../AGENTS.md) §1.

Conversations thread with an idle window, ChatGPT memories and local notes ride
along in the system prompt, and a Personality box holds the wearer's standing
instructions. Notes and reminders live in their own settings screen.

Build and test:

```powershell
.\gradlew.bat :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true
```
