# Read-only media envelope diagnostic

```go
func (e *Engine) DescribeLatestMedia(chatID string) string
```

The method reads the chat's recent server history (up to 24 messages), selects the
newest IMAGE by valid timestamp, and keeps the API's newest-first order for ties
or unavailable timestamps. It includes own and received images. It does not use
the locally reconstructed sent-photo history, download any media, send a message,
or modify the journal/public snapshot. Decryption may fetch the same peer/group
keys used by normal history reads.

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

No message/chat/sender IDs, OIDs, URLs, filenames, key bytes, encrypted chunks,
text, nested JSON values or raw errors are emitted. Unknown SID values and
non-numeric version/type strings become empty strings rather than disclosing
unexpected content. Recognized SID values are `m`, `emi`, `emv`, `ema`, `emf`.
Empty key lists serialize as `[]`. Fixed `found=false` results distinguish
unavailable session/chat, failed history reads and no IMAGE in the recent window.

`keyMaterialPresent` means the decrypted JSON object contains that key, even if
its value is empty/null. If `sealedBody.decrypted=false`, a false key-presence
flag is **unknown**, not proof that the sender omitted the field.

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
fixed error output and unchanged journal/snapshot. Device observations are left
to the device-test session; no device or real LINE account is used by these tests.
