# Read-only media envelope diagnostic

```go
func (e *Engine) DescribeLatestMedia(chatID string) string
```

The method reads the chat's recent server history (up to 24 messages), selects the
newest IMAGE by valid timestamp, and keeps the API's newest-first order for ties
or unavailable timestamps. It includes own and received images. It does not use
the locally reconstructed sent-photo history, send a message, or modify the
journal/public snapshot. Decryption may fetch the same peer/group keys used by
normal history reads. When thumbnail metadata and a decrypted body key are
available, it now downloads that preview for the redacted probe described below.

Successful output contains only:

- `found`, sorted `metadataKeys` (names, never metadata values);
- `ENC_KM.present` and `ENC_KM.decodedByteLength` (standard base64 decoded size;
  `-1` for invalid encoding, `0` for absent/empty, distinguished by `present`);
- `sealedBody.present`, `decrypted`, `jsonObjectValid`, `keyMaterialPresent`;
- `MEDIA_THUMB_INFO` and `MEDIA_CONTENT_INFO`, each with `present`,
  `jsonObjectValid`, and sorted top-level JSON `keys` without values;
- the explicit scalar exceptions: numeric-string `e2eeVersion`, integer wire
  `contentType`, numeric-string `metadataContentType`, recognized `SID`, boolean
  `senderIsSelf`, and numeric `timestamp` (server `createdTime`, milliseconds)
  with `timestampValid`.

It additionally emits `previewDecryptsWithBodyKey` and a fixed `previewProbeStatus`.

No message/chat/sender IDs, OIDs, URLs, filenames, key bytes, encrypted chunks,
text, nested JSON values or raw errors are emitted. Unknown SID values and
non-numeric version/type strings become empty strings rather than disclosing
unexpected content. Recognized SID values are `m`, `emi`, `emv`, `ema`, `emf`.
Empty key lists serialize as `[]`. Fixed `found=false` results distinguish
unavailable session/chat, failed history reads and no IMAGE in the recent window.

`keyMaterialPresent` means the decrypted JSON object contains that key, even if
its value is empty/null. If `sealedBody.decrypted=false`, a false key-presence
flag is **unknown**, not proof that the sender omitted the field.

## Preview key/counter probe

If `MEDIA_THUMB_INFO` is present, the sealed body was decrypted and contains a
non-empty string `keyMaterial`, and `OID` is present, the probe reads
`OID + "__ud-preview"` through the existing authenticated OBS download path.
The fetch retains the owner cancellation, timeout and 4 MiB response bound.

It uses only the sealed body's key, never metadata `ENC_KM`, with the existing
FileEncryption HKDF-SHA256/AES-CTR zero-counter decryptor. The HMAC must verify
and a JPEG or PNG header must parse before `previewDecryptsWithBodyKey=true`.
Neither image bytes, keys, locators nor error strings enter the JSON or disk.
Fixed statuses are:

- `verified_jpeg`, `verified_png`: authenticated decryption and header succeeded;
- `no_preview_metadata`, `body_not_decrypted`, `body_key_unavailable`, `missing_oid`,
  `session_unavailable`: prerequisites were missing, so no preview download;
- `download_failed`, `authentication_failed`, `not_jpeg_or_png`: the requested
  read/decrypt/header path failed. Raw protocol errors remain private.

A positive result demonstrates that this preview fits the same body-key HKDF
and counter convention used by the existing file decryptor. With a successful
original-file decrypt, it establishes key/nonce reuse for that controlled sample.
Record that as an observed protocol limitation; do not generalize it to every
official client from one photo. A false result with a missing prerequisite or
failed download is inconclusive. Authentication failure may also reflect another
file/MAC format; it is not proof that a different nonce was used.

## Instrumentation

The existing read-only acceptance test gains one opt-in argument. Close Nexus
LINE pages/settings first so the existing exclusive native-session lock is free.
Send the controlled photo through the official app, then run:

```powershell
adb -s PHONE_SERIAL shell am instrument -w -r `
  -e class com.anezium.rokidbus.plugin.line.LineAccountReadOnlyTest `
  -e conversationLabel yoyo -e requireText false -e describeMedia true `
  com.anezium.rokidbus.plugin.line.test/androidx.test.runner.AndroidJUnitRunner
```

The single JSON value appears as:

```text
INSTRUMENTATION_STATUS: latestMediaDescription={...}
```

It is emitted immediately after loading history and before normal message
decryption assertions, so structural evidence is still available if those
assertions fail. Without `describeMedia=true`, this extra read/output is absent.
The existing test continues its normal preview/count checks and closes its
engine in `finally`; this change introduces no send operation.

## Evidence limits

Correlate `timestamp` with the controlled official-app send. `senderIsSelf`
identifies the account author, not the originating device/client. The inspected
object is the envelope received from LINE's server; it is not a packet capture
of the phone's outbound request, and server transformations cannot be excluded.
One observed photo establishes that sample's fields, not what every official
client or every message always contains. A 32-byte decoded `ENC_KM` alone also
does not prove equality with the sealed-body key or reveal a wrapping scheme.

Synthetic regressions cover key/value separation, nested JSON key sorting,
invalid fields, unknown decryption status, message selection for either sender,
fixed error output and unchanged journal/snapshot. Additional synthetic tests cover
authenticated JPEG/PNG previews, wrong body keys despite a usable metadata key,
tampering, plaintext previews, missing prerequisites and redacted failures.
Device observations are left
to the device-test session; no device or real LINE account is used by these tests.
