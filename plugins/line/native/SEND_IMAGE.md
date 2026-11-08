# Native photo sending

```go
func (e *Engine) SendImage(invocation, chatID, chatTitle string, jpeg []byte) string
```

The gomobile method accepts a JPEG up to 6 MiB, checks the current chat identity,
title, ready session and Letter Sealing keys, and uses the same operation mutex
and durable invocation journal as text. Empty, oversized or invalid JPEG inputs
are rejected. Decoding is additionally bounded to 32 million pixels and 16,384
pixels per edge. The original JPEG, including trailing metadata, is encrypted
without re-encoding or stripping. Only the separate preview is re-encoded.

## Wire contract

The pinned `beeper/line` reference is followed for the encrypted image flow:

1. Check the recipient's sealing key and `determineMediaMessageFlow`. A flow
   explicitly requiring plaintext is rejected; no plaintext fallback exists.
2. Encrypt the JPEG with fresh 32-byte key material. HKDF-SHA256, no salt and
   `FileEncryption` info derive 32-byte encryption/MAC keys plus a 12-byte nonce.
   AES-256-CTR uses that nonce followed by a zero counter. HMAC-SHA256 of the
   ciphertext is appended, matching the existing authenticated decryptor.
3. `UploadOBS` uploads ciphertext to SID `emi`, before sending a message.
4. Generate a JPEG quality-60 preview with a maximum 1280-pixel long edge,
   encrypt it with the same material and upload `OID + "__ud-preview"` using
   `UploadOBSWithOID`. Preview failure is optional, as in the reference.
5. Seal the raw JSON `{"keyMaterial":"..."}` using content type 1 through
   `EncryptMessageV2Raw` or `EncryptGroupMessageRaw`. These raw APIs are required:
   the text helpers would incorrectly wrap the JSON as text.
6. Persist an `unconfirmed` invocation before `SendMessage`, then record its
   acknowledgement. There is no automatic resend or plain-media retry.

The IMAGE message has `contentType=1`, `hasContent=true`, sealed chunks and:

- `OID`, `SID=emi`, `ENC_KM`, `e2eeVersion`;
- `FILE_SIZE`: ciphertext length including its 32-byte MAC;
- `FILE_NAME=image.jpg`, metadata `contentType=1`;
- `MEDIA_CONTENT_INFO`: `category=original`, encrypted `fileSize`, `extension=jpg`;
- `MEDIA_THUMB_INFO`: preview `width` and `height`, only after a successful upload.

The reference OBS endpoints explicitly use the Chrome identity already used by
this engine. No bridge/connector package, additional dependency or alternate
upload endpoint is introduced. The standard-library thumbnail scaler uses nearest
neighbour sampling; this does not change the image message or file encryption.

## Outcomes and history

- `sent`: LINE acknowledged a message ID, or an exact own-photo echo/history row
  confirmed a previously ambiguous upload. This is handoff, not delivery/read.
- `not_sent`: validation, ownership/readiness, key, flow, encryption, main upload
  or pre-dispatch journal failure. An optional preview failure alone does not
  block the main photo. Failed uploads can leave orphan encrypted objects; they
  have not dispatched a message to the recipient.
- `unconfirmed`: dispatch occurred but no trustworthy message ID was returned.
  Its journal survives process death and replay never dispatches it again.
- `recipient_changed`: a fresh chat is missing/renamed, or an invocation was
  reused with different recipient/content (including text-versus-image).

Acknowledged photos enter snapshots like received photos: server message ID,
`media="photo"`, `mine=true`, text fallback `[Photo]`. Replays reuse the server ID
to avoid duplicates. `Media(chatID, messageID)` reads/decrypts the uploaded preview
when available, so even a 4–6 MiB original fits the existing Kotlin thumbnail
input limit. It falls back to the main object if the preview download fails;
the normal 4 MiB download bound still applies. A failure keeps the history entry.
Native readback is covered by synthetic OBS tests; live owner-upload readback
must be checked by the device team.

The private journal retains the chat, encrypted object locator, key material,
preview availability and confirmed message ID, alongside its digest/outcome.
It stores no JPEG. These secrets never enter snapshots. An own photo with the
same chat and OID reconciles an ambiguous record through SSE or loaded history;
another sender/chat/OID cannot confirm it. If acknowledgement persistence fails,
the observed return value is still `sent` and durable state stays ambiguous,
matching text sending.

## Verification scope

Tests exercise real file/preview encryption against the existing decryptor,
unchanged original bytes, metadata and upload order through synthetic RPC/OBS,
input/authority/key failure, optional preview failure, failed dispatch, durable
replay and exact-echo/history confirmation. Message sealing is supplied at the
prepared-upload unit boundary in positive dispatch tests; missing-key tests use
the public method. Account provisioning, actual group/direct message sealing and
official-app interoperability remain device acceptance work. No test reads an
owner session, sends a network message or installs an APK on a device.
