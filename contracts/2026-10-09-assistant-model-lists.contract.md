---
task: assistant-model-lists
date: 2026-10-09
status: active
scope_globs:
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/ChatGptCodexProvider.kt"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/CodexAuthStore.kt"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/ProviderCatalog.kt"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/OpenAiProvider.kt"
  - "plugins/assistant/src/main/res/values*/strings.xml"
  - "plugins/assistant/src/test/java/com/anezium/rokidbus/plugin/assistant/**/*.kt"
  - "plugins/assistant/CHANGELOG.md"
forbidden_globs:
  - "shared/**"
  - "bus-client/**"
  - "phone-hub/**"
  - "glasses-hub/**"
  - "plugins/transit/**"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/Workspace*.kt"
  - "plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/*Tool*.kt"
  - "plugins/**/build.gradle.kts"
  - "settings.gradle.kts"
  - "local.properties"
  - "gradle/**"
  - "docs/**"
test_commands:
  - "./gradlew :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true"
max_failures: 2
---

# Goal
The Assistant's ChatGPT (Codex backend) provider offers the current model trio — Luna (`gpt-6-luna`, fast, DEFAULT), Sol (`gpt-6.1-sol`, balanced), Astra (`gpt-6-astra`, deep) — with settings labels/captions in the existing style. Previously persisted selections (`gpt-5.6-luna`, `gpt-5.6-terra`, `gpt-5.6-sol`, `gpt-6-sol`, and anything else) resolve to their closest current equivalent instead of silently resetting. Every reasoning effort sent to the backend is one the selected model supports (Luna has no `ultra`). The OpenAI API preset in `ProviderCatalog` suggests `gpt-6-luna` (default), `gpt-6.1-sol`, `gpt-6-astra` (vision true) instead of `gpt-4o`/`gpt-4o-mini`.

Worktree: `E:\Tools\Rokid\RokidNexus-fu-models`, branch `dev/assistant-model-lists` (based on `qa/skills-workspace-typed-160` at `2b439f08`). Work only there. Read `AGENTS.md` and `plugins/AGENTS.md` at the worktree root first.

# Non-goals
- No changes to request/stream parsing, auth, tool calling, Workspace, or any provider other than ChatGPT (Codex) and the OpenAI preset/provider.
- No new settings (no effort picker if none exists today), no UI restyling.
- No changes to other presets in `ProviderCatalog` (Anthropic, Gemini, OpenRouter, …).
- No device testing.

# Constraints
- MUST set `FAST_MODEL_ID = "gpt-6-luna"`, `BALANCED_MODEL_ID = "gpt-6.1-sol"`, `DEEP_MODEL_ID = "gpt-6-astra"`, `DEFAULT_MODEL_ID = FAST_MODEL_ID` in `ChatGptCodexProvider.kt`; `SUPPORTED_MODEL_IDS` = exactly these three.
- MUST add a legacy alias table in `ChatGptCodexProvider.kt` used by `supportedModel(...)` (or a single new resolve function that `supportedModel` delegates to): `gpt-5.6-luna → gpt-6-luna`, `gpt-5.6-terra → gpt-6.1-sol`, `gpt-5.6-sol → gpt-6-astra`, `gpt-6-sol → gpt-6.1-sol`; any other unknown id → `DEFAULT_MODEL_ID`. The settings activity's selected-model derivation and `CodexAuthStore.chatGptModel()` consumers MUST go through the same resolution so the picker highlights the migrated model (do not duplicate the mapping).
- MUST ensure `supportedReasoningEffort(model, effort)` (or the equivalent existing function) returns, for every supported model and every effort value the code can produce, a value in that model's allowed set: Luna `low, medium, high, xhigh, max`; Sol and Astra `low, medium, high, xhigh, ultra` (plus `max` if the existing code already uses it for these — follow the catalog: Luna lacks `ultra`; map `ultra` → `max` for Luna). Covered by an exhaustive unit test.
- MUST keep the existing default-effort behavior per tier unless it would produce an unsupported value; the fast default path (Luna) MUST stay the cheapest effort the current code uses for the fast tier.
- MUST update `ProviderCatalog.kt` OpenAI preset: `defaultModel = "gpt-6-luna"`, `suggestedModels` = `gpt-6-luna`, `gpt-6.1-sol`, `gpt-6-astra` with `vision = true`; keep `supportedEfforts` as it is unless a test proves the field is consumed for OpenAI.
- MUST decide `OpenAiProvider.kt` by evidence: run `grep -rn "OpenAiProvider" plugins/assistant/src/main --include=*.kt`; if it is referenced from any main source other than itself, update `DEFAULT_MODEL_ID = "gpt-6-luna"` and `SUPPORTED_MODELS` to the three current ids; if unreferenced, leave the file untouched and state that in the final report.
- MUST update settings labels/captions: three entries, short title (`GPT-6-Luna`, `GPT-6.1-Sol`, `GPT-6-Astra` or the existing title pattern if it uses tier names) + one-line caption in the existing tone (source material: Luna "Fast and affordable model for easier tasks", Sol "Latest workhorse model for coding and everyday work", Astra "Frontier intelligence for the most demanding work"). If labels live in `strings.xml`, edit every `values*` variant that defines them.
- MUST update tests that reference the old ids (grep `gpt-5.6` and `gpt-4o` under `src/test`) and add tests: `legacyModelMigration` (every alias + an unknown id), `effortClamp` (exhaustive model × effort), `defaultModelIsLuna`, `providerCatalogOpenAi` (default + suggested + vision).
- MUST add a bullet under `## Unreleased` in `plugins/assistant/CHANGELOG.md` in the existing bold-title style.
- MUST run the full test command with 0 failures and test count ≥ 479 + tests added; MUST commit at every coherent step with the repo's configured git user, imperative English messages, no AI attribution, no `Co-Authored-By`.
- MUST NOT modify any file outside `scope_globs`; MUST NOT touch `settings.gradle.kts`, `local.properties`, Gradle home/cache; on environment build failures STOP and report.
- MUST NOT use adb, install APKs, or touch devices. MUST NOT push, merge, rebase, or switch branches. MUST NOT bump `versionName`/`versionCode`.
- MUST NOT silently rewrite the user's persisted selection to the default; rewriting the persisted value to its MIGRATED id on read is allowed but not required.
- MUST NOT leave any `gpt-5.6` id in main sources outside the alias table, nor any `gpt-4o` id in main sources except an unreferenced `OpenAiProvider.kt`.

# Acceptance tests
| # | Check | Command | Expected |
|---|-------|---------|----------|
| 1 | Build + tests pass | `./gradlew :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true` | BUILD SUCCESSFUL, 0 failures |
| 2 | Test count | `grep -ho 'tests="[0-9]*"' plugins/assistant/build/test-results/testDebugUnitTest/*.xml \| awk -F'"' '{s+=$2} END{print s}'` | ≥ 479 + number of `@Test` added |
| 3 | Constants | `grep -n -e 'FAST_MODEL_ID' -e 'BALANCED_MODEL_ID' -e 'DEEP_MODEL_ID' -e 'DEFAULT_MODEL_ID' plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/ChatGptCodexProvider.kt` | `"gpt-6-luna"`, `"gpt-6.1-sol"`, `"gpt-6-astra"`, `DEFAULT_MODEL_ID = FAST_MODEL_ID` |
| 4 | Old ids only in alias table | `grep -rn "gpt-5.6" plugins/assistant/src/main` | Hits only in `ChatGptCodexProvider.kt`, all within the alias map |
| 5 | gpt-4o gone | `grep -rln "gpt-4o" plugins/assistant/src/main` | Empty, OR exactly `OpenAiProvider.kt` AND `grep -rn "OpenAiProvider" plugins/assistant/src/main --include=*.kt \| grep -v OpenAiProvider.kt` is empty |
| 6 | Single resolution path | `grep -rn -e "gpt-6-sol\"" -e "gpt-5.6-terra" plugins/assistant/src/main` | Each alias string appears exactly once in main sources |
| 7 | Settings uses shared resolution | `grep -n "ChatGptCodexProvider\." plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/AssistantSettingsActivity.kt` | The selected-model code (~line 691 area) calls the provider's resolve/`supportedModel` function; no local model id strings except the three current ids |
| 8 | Provider catalog | `grep -n -A8 -i "openai" plugins/assistant/src/main/java/com/anezium/rokidbus/plugin/assistant/ProviderCatalog.kt` | `defaultModel = "gpt-6-luna"`; suggested `gpt-6-luna`, `gpt-6.1-sol`, `gpt-6-astra`; vision true for all three |
| 9 | Required tests | `grep -rn -e "legacyModelMigration" -e "effortClamp" -e "defaultModelIsLuna" -e "providerCatalogOpenAi" plugins/assistant/src/test` | Each keyword in at least one `fun` test name |
| 10 | No ultra for Luna | test `effortClamp` asserts for `gpt-6-luna` that `ultra` input yields `max` and never `ultra`; for Sol/Astra `ultra` is preserved | Test present and passing |
| 11 | Only in-scope files changed | `git diff --stat 2b439f08..HEAD --name-only` | Every path matches `scope_globs` |
| 12 | CHANGELOG entry | `git diff 2b439f08..HEAD -- plugins/assistant/CHANGELOG.md` | One added bullet under `## Unreleased`, bold title, existing style |
| 13 | Commit hygiene | `git log 2b439f08..HEAD --format='%an%n%b'` | Author is the repo's configured user; no `Co-Authored-By`, no AI mention |

# Plan sketch
1. Read `AGENTS.md`, `plugins/AGENTS.md`, then `ChatGptCodexProvider.kt` (constants ~462-483; reasoning effort handling ~219-410, `supportedReasoningEffort`), `AssistantSettingsActivity.kt` (~147-157 choices, ~691 selected model), `CodexAuthStore.kt` (~283 `chatGptModel()`, ~420/441 defaults), `ProviderCatalog.kt` (~26-50), `OpenAiProvider.kt` (~190). `grep -rn "gpt-5.6\|gpt-4o\|FAST_MODEL_ID\|supportedModel\|supportedReasoningEffort" plugins/assistant/src` to map every consumer.
2. Commit 1: constants + alias table + effort clamp in `ChatGptCodexProvider.kt`, with tests `legacyModelMigration`, `effortClamp`, `defaultModelIsLuna`, and updated id references in existing tests.
3. Commit 2: settings labels/captions + selected-model resolution through the provider function; `CodexAuthStore` defaults pointing at `DEFAULT_MODEL_ID` (reference the constant rather than a literal if it currently duplicates the string).
4. Commit 3: `ProviderCatalog` OpenAI preset (+ `OpenAiProvider` only if wired) with test `providerCatalogOpenAi`; CHANGELOG bullet.
5. Run the full command after each commit.

# Context the executor cannot re-derive
- Owner statement: nobody uses gpt-4o anymore; on ChatGPT they use Luna, Sol, or Astra. Speed at question time is the owner's priority: the fast model must remain the default.
- Authoritative catalog (Codex CLI model cache fetched 2026-10-09 from the same ChatGPT backend the app uses): `gpt-6.1-sol` (GPT-6.1-Sol, default reasoning low, efforts low…ultra), `gpt-6-astra` (GPT-6-Astra, default medium, low…ultra), `gpt-6-sol` (previous-generation workhorse), `gpt-6-luna` (GPT-6-Luna, default medium, efforts low/medium/high/xhigh/max — NO ultra), `gpt-5.6-sol` / `gpt-5.6-terra` / `gpt-5.6-luna` marked "Older". All accept text+image; all `supported_in_api=true`. OpenRouter lists `openai/gpt-6-luna`, `openai/gpt-6.1-sol`, `openai/gpt-6-astra`, confirming the ids exist on the OpenAI API.
- Tier mapping rationale: Luna = fast tier (replaces gpt-5.6-luna), Sol 6.1 = balanced/workhorse (replaces gpt-5.6-terra and gpt-6-sol), Astra = deep (replaces gpt-5.6-sol).
- Current code: `supportedModel(modelId)` falls back to DEFAULT for unknown ids — this is the "silent reset" the owner wants replaced by migration for known legacy ids.
- Baseline for the assistant test command: 479 tests, 0 failures (Windows, Gradle 9.5.1, Java 17, `-PskipCxrGlobal=true`, sibling `../CxrGlobal` present).
- Activity code is likely not unit-tested (no Robolectric); verify by grep and do not add a UI test framework — acceptance #7 is a grep check instead.

# Escalation triggers (mechanical — never self-assessed by the executor)
- The test command fails for an environment reason (SDK missing, download refused, cache not writable, missing `../CxrGlobal`).
- The change would require editing a file outside `scope_globs` (e.g. a model id hard-coded in a hub or shared module).
- After the change, the test command fails twice in a row (`max_failures: 2`).
- The reasoning-effort code structure makes an exhaustive clamp test impossible without changing request-building logic beyond the effort value.
- `git status` shows unexpected modified files outside scope (another session touched the worktree).
On any trigger: stop, leave the tree committed up to the last coherent step, and report.

# Autonomy
Proceed without asking. Naming of the alias table/resolve function, exact caption wording (within the given tone/source), and test file placement are yours. Finish with a report: commit list, whether `OpenAiProvider` is wired (with the grep evidence), the final alias table, the per-model effort sets as implemented, and the effort the default path sends for Luna.

# Device checks (run later by the orchestrator, not by the executor)
- ChatGPT model picker shows exactly GPT-6-Luna (default, selected on a fresh install), GPT-6.1-Sol, GPT-6-Astra; a device that previously had gpt-5.6-terra selected shows GPT-6.1-Sol selected.
- One question answered with each model, no backend "unsupported model/effort" error (Luna with the highest effort the UI can produce).
- Workspace chart question still works with the default model (image input accepted).
- OpenAI API preset shows gpt-6-luna as default with the two other suggestions.
