# LINE Messages (prototype)

A separate phone APK for reading recent LINE conversations and sending text from
Nexus launcher pages. The protocol engine runs inside the APK through Go/gomobile,
like WhatsApp's engine. No Matrix bridge, remote gateway or glasses APK is needed.

## Scope

- Email/password login, with PIN approval in the official LINE app on the same
  phone. The password is used once and is never persisted.
- Up to 48 recent conversations, direct or group, and 24 recent messages per chat.
  The newest messages that fit the launcher payload budget are displayed.
- Live text updates and remote unsends while the plugin pages are held open.
- Dictate or type through the hub composer; only its explicit Send dispatches text.
  Sends are journaled before dispatch, and ambiguous requests are not retried.
- Letter Sealing is required for login and sending in this prototype. Missing
  keys, unsupported mixed groups and crypto errors block sending; there is no
  fallback to plaintext. Incoming plain text can still be read.

Media are represented by a phone-only placeholder. Sending media, calls, OpenChat,
creating group keys and automatic token refresh are outside this first prototype.
An expired/revoked session must be forgotten and linked again. A server-accepted
send is reported as handed off, never as delivered or read.

## Link an account

1. Install the plugin on the phone and approve it in Nexus. Open its settings.
2. In the official LINE app on this phone, register an email/password under
   **Settings › Account**, and enable **Allow login**.
3. Enter that email and password in the plugin, then tap **Sign in**. The fields
   are cleared immediately; credentials cross no Intent, Bundle or preference.
4. Open LINE on this same phone and enter the displayed PIN when asked. Return
   to Nexus after approval. Sign-in is limited to three minutes, with a
   cancellable notification so switching apps does not terminate the attempt.
5. Open a conversation on the glasses, dictate/type, review the recipient and
   draft, then tap Send. Check LINE before manually repeating an unconfirmed send.

LINE's Chrome client identity allows only one session of that type. Linking can
replace an existing Chrome session. This is an unofficial client and LINE can
restrict accounts; start with a test account. The plugin is not affiliated with
LINE or LY Corporation. Credential/PIN screens use `FLAG_SECURE`; tokens and keys stay
in this APK's `noBackupFilesDir`, with no credential logging or clipboard writes.
**Forget this connection** removes local credentials/keys without deleting LINE
messages; revoke the corresponding Chrome device in LINE for remote logout.

## Build and check

Use the existing Android SDK/NDK and the exact Go toolchain from `native/go.mod`.
Do not create a private SDK, change Gradle's home or edit `local.properties`.

```powershell
& ./plugins/line/native/Build-Android.ps1 -GoPath '<existing-go.exe>'
./gradlew.bat :plugin-line:testDebugUnitTest :plugin-line:assembleDebug -PskipCxrGlobal=true
```

On POSIX, use `bash plugins/line/native/build-android.sh`. Native checks:

```sh
cd plugins/line/native
go test ./mobile
go vet ./mobile
```

The AAR and APK are generated, ignored artifacts. Go modules are pinned with
checksums; only `beeper/line`'s protocol/crypto packages are linked, not its Matrix
connector. CI checks the native engine and assembles/tests the Android plugin.
Regenerate bundled license texts after changing dependencies using
`native/Write-Notices.ps1 -GoPath '<existing-go.exe>'`. They are available under
**Open-source licenses** in the phone settings. Nexus code remains Apache 2.0;
the protocol library is MIT and its transitive notices are bundled with the APK.

## Architecture decision

Use [`beeper/line`](https://github.com/beeper/line) at
`ae616894a66b99e2c09d4e39761f4a60b48c4329`, under MIT. This preserves Nexus's
existing Kotlin → gomobile → Go architecture, rather than adding a JS runtime or
a Rust/JNI toolchain. Upstream reports Letter Sealing tests in
[`readme/LETTER_SEALING.md`](https://github.com/beeper/line/blob/ae616894a66b99e2c09d4e39761f4a60b48c4329/readme/LETTER_SEALING.md).
Those reports are not evidence of live verification in Nexus.

The native session has one lock across phone settings and pages. Snapshots and
native change callbacks feed live page revisions; no app polling, boot receiver or
background account monitor is installed. Closing the last owner cancels network
work and closes the session. Only user-started pairing has its bounded service.

See [the validation record](docs/validation.md) for what has actually been tested.
