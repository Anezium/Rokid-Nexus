# Working in this repository

Android/Kotlin. Phone hub, glasses hub, a shared bus client, and the plugins under
`plugins/`. Start with `README.md` for what the product is; `plugins/AGENTS.md` covers
plugin work specifically.

Ink Surface crosses `:ink-engine`, `:shared`, `:bus-client`, `:phone-hub`, and
`:glasses-hub`. Treat `docs/PLUGIN_SDK.md` as the public typed API and `BUSSPEC.md`
as the wire authority; do not copy the older implementation sketch in
`plans/020-ink-surface.md` over the delivered contracts. The
`/core/native-apps/*`, `/core/remote-input/*`, `/core/navigation/*`, and
`/core/pointer/*` routes are trusted hub-to-hub controls and must never be exposed
as plugin capabilities.

Assistant's phone-calendar tools are local Android integrations. They use the
Calendar Provider and the plugin's own `READ_CALENDAR`/`WRITE_CALENDAR` runtime
permissions; they add no Nexus capability, receive prefix, SDK surface, or bus
route. Keep destructive calendar operations fail-closed: delete only one exact
title-and-start match, revalidate that identity at the final provider delete
boundary, reject ambiguity, and require an explicit whole-series request before
deleting a recurring event.

Wireless ADB crosses `:plugin-wireless-adb`, `:shared`, `:phone-hub`, and
`:glasses-hub`. Its `/debug/adb/request` path requires the high-risk
`wireless_debugging` grant; replies are owner-scoped and the phone hub, never the
plugin, stamps the authenticated plugin id. Keep the privileged bridge fixed-input,
limited to the validated Rokid Android 12L/API 32 transaction contract, and
fail-closed on every other API. Pairing codes are two-minute secrets: Nexus must
never persist or log them, Wi-Fi identifiers, or device identity. Only an explicit
user copy action may place the generated command on the Android clipboard, and any
screen displaying it must use `FLAG_SECURE`. Changes normally require the shared,
plugin, and both hub test/build suites.

Skills cross `:shared`, `:bus-client`, `:phone-hub`, Assistant as the caller,
and the providers (Transit, Media Deck, Sample). `BUSSPEC.md#skills-v1` is the
wire authority and `SkillLimits` holds every limit. Skill traffic is
phone-local; the phone hub, never a plugin, stamps both identities, and each
caller/provider/operation approval is bound to the operation's digest, so a
changed contract starts disabled. Plugins request the grants only through the
`SKILLS` and `SKILLS_CLIENT` metadata keys, never `.CAPABILITIES`, or older
hubs refuse the whole plugin.

## Building

This is the verified way to build and test a module:

```
./gradlew :plugin-assistant:testDebugUnitTest :plugin-assistant:assembleDebug -PskipCxrGlobal=true
```

Swap the module for whichever one you touched (`:phone-hub`, `:glasses-hub`,
`:bus-client`, `:plugin-relay`, …).

The Ink compiler is a pure Kotlin module and can be checked directly with
`./gradlew :ink-engine:test`; changes to its public session path normally also
require `:shared:test`, `:bus-client:testDebugUnitTest`, and both hub test suites.

**`-PskipCxrGlobal=true` is only for modules that do not link against the vendor CXR
library.** It is right for the plugins. The hubs do link against it, so building
`:phone-hub` or `:glasses-hub` with that flag fails with
`Could not find com.example.cxrglobal:lib`. Build those without it:

```
./gradlew :phone-hub:testDebugUnitTest :phone-hub:assembleDebug
```

The flag works by substituting the `com.example.cxrglobal:lib` dependency with the
sibling checkout at `../CxrGlobal` (see `settings.gradle.kts`), so that directory has to
exist next to this one — including for a worktree.

Gradle 9.5.1 and the Android SDK are already installed on this machine. Java 17 is the
toolchain.

## The build environment is not yours to repair

`local.properties` holds the machine's Android SDK path. It is untracked on purpose and
**must never be modified, regenerated, or pointed somewhere else**. A fresh git worktree
may not have one; that is a setup problem, not a build problem.

Likewise, do not create a private Android SDK, a private Gradle home, or a private
dependency cache inside the working tree, and do not add such directories to the repo.

**If the build fails for an environment reason** — SDK not found, the Gradle distribution
cannot be downloaded, a cache directory is not writable, network is refused — then
**stop and say so in your report**. Do not work around it. An agent that rewrites
`local.properties` to keep a build green leaves every later build broken, and the damage
outlives the task that caused it. Reporting "I could not verify the build because X" is a
correct, useful outcome; a green build bought by breaking the machine's configuration is
not.

Never report a build or test result you did not actually observe. Paste the real tail of
the command output. If you could not run it, say that plainly.

## Conventions

- Code, comments, documentation, and commit messages are written in English.
- Commit messages describe what changed and why, in the imperative, with no AI
  attribution and no `Co-Authored-By` trailers.
- Match the surrounding code: its naming, its comment density, its idioms. Comments
  explain a constraint the code cannot show, never what the next line does.
- Prefer small, reviewable changes. Do not restyle or refactor code you were not asked
  to touch.
