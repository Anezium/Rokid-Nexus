# Patcher text-wrap validation — Claude Fable 5.1 API

Read-only review. Native renders are simulated test states.

```json
{
  "requested_model": "claude-fable-5-1",
  "lookup_model": "claude-fable-5-1",
  "source_sha256": "8ca0e739766308440b9179c3eedad367a5421ab2f778ecc0c07725ca2b53cd21",
  "request_id": "req_011CfriZQBzssBG3WdboBpXk",
  "response_model": "claude-fable-5-1",
  "usage": {
    "input_tokens": 21444,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 0,
    "cache_creation": {
      "ephemeral_5m_input_tokens": 0,
      "ephemeral_1h_input_tokens": 0
    },
    "output_tokens": 3220,
    "service_tier": "standard",
    "inference_geo": "global",
    "output_tokens_details": {
      "thinking_tokens": 1961
    }
  },
  "stop_reason": "end_turn"
}
```

# Fable verdict — Back defect correction (read-only)

## Verdict: **APPROVE** — code is correct for build; phone validation follows.

## Callback correctness (API 33+)

`registerBackCallback()` runs in `onCreate` after `setContentView`, obtains `onBackInvokedDispatcher` from the Activity, registers at `PRIORITY_DEFAULT`, and stores the callback. On Android 16 with targetSdk 36 this is the only Back path the platform dispatches, which is exactly the defect: the old `super.onBackPressed()` never ran and the system finished the activity directly. The callback now calls `navigateBack()`. The Kotlin SAM construction `OnBackInvokedCallback { navigateBack() }` is valid for the single-method Java interface.

## Lifecycle

Register in `onCreate`, unregister in `onDestroy` before `super`, with the `backCallback != null` guard against double registration. Recreation (rotation, process death) re-registers and `route` is restored from `savedInstanceState` first, so Back after recreation still resolves against the restored route. The `expanded` set survives the OVERVIEW return as before.

## Route correctness

`navigateBack()` is the single rule: non-OVERVIEW → `show(Route.OVERVIEW)` (full `rerender`, header retitles); OVERVIEW → explicit `finish()`, which returns to the originating Patcher screen exactly as the previous `super.onBackPressed()` did. The header arrow is the same function. Nothing re-dispatches Back, so no recursion. `ADVANCED` correctly returns to overview as it has no step ordinal but is not OVERVIEW.

## Older-device safety

`backCallback` is `Any?`; `android.window` types appear only inside the two `@TargetApi(33)` helpers, and both call sites are behind `SDK_INT >= TIRAMISU`. Top-level imports do not trigger class loading, and ART resolves the unreached methods lazily, so API ≤ 32 never touches `OnBackInvokedDispatcher`. The retained `onBackPressed` override covers pre-33 and API 33–35 without the manifest opt-in; the platform delivers each Back event to exactly one of the two paths, so there is no double handling. Registering the callback where the dispatcher is disabled is harmless.

## Scope and design

Diff is confined to `YoutubeSetupActivity.kt`: imports, one field, one line in `onCreate`, `onDestroy`, the Back helpers and the header lambda. No manifest, dependency, theme or kit change. Authentication, `patchPending`/request-code rotation, source and picker locks, `acceptsResult`, signing hints, keyboard settings and every NexusUi view are byte-identical to the round-4 approved state. Design is unchanged; the frontend-design skill was correctly applied as restraint here.

## Non-blocking observation

With a default-priority callback registered, Android 16 will not show the predictive cross-activity preview when backing out of the overview; `finish()` still returns to Patcher. Acceptable for this fix.

## Honest limits

The Gradle tail shows `testDebugUnitTest` BUILD SUCCESSFUL but no test asserts this Back behaviour, so the defect is fixed by code reasoning only. I executed no tools, builds, tests or device actions. The parent should verify on the installed phone: Nexus → Patcher → Set up YouTube → MicroG → KEYCODE_BACK reaches the YouTube overview; a second Back returns to Patcher; the header arrow matches both; and, if available, the same sequence on a pre-33 or non-opted-in 33–35 device. This is code approval to build, not actual phone validation.
