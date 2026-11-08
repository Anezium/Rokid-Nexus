# Native photo sending

```go
func (e *Engine) SendImage(invocation, chatID, chatTitle string, jpeg []byte) string
```

The gomobile method accepts a JPEG up to 6 MiB, checks the current chat identity,
title, ready session and Letter Sealing keys, and uses the same operation mutex
and durable invocation journal as text. Empty, oversized or invalid JPEG inputs
are rejected. Decoding is additionally bounded to 32 million pixels and 16,384
pixels per edge. The original JPEG, including trailing metadata, is encrypted
without re-encoding or stripping. No preview is generated or uploaded.

## Wire contract

The encrypted image flow uses the pinned protocol library with these security constraints:

1. Check the recipient's sealing key and `determineMediaMessageFlow`. A flow
   explicitly requiring plaintext is rejected; no plaintext fallback exists.
2. Encrypt the JPEG with fresh 32-byte key material. HKDF-SHA256, no salt and
   `FileEncryption` info derive 32-byte encryption/MAC keys plus a 12-byte nonce.
   AES-256-CTR uses that nonce followed by a zero counter. HMAC-SHA256 of the
   ciphertext is appended, matching the existing authenticated decryptor.
3. `UploadOBS` uploads ciphertext to SID `emi`, before sending a message.
4. Seal the raw JSON `{"keyMaterial":"..."}` using content type 1 through
   `EncryptMessageV2Raw` or `EncryptGroupMessageRaw`. These raw APIs are required:
   the text helpers would incorrectly wrap the JSON as text.
5. Persist an `unconfirmed` invocation before `SendMessage`, then record its
   acknowledgement. There is no automatic resend or plain-media retry.

The IMAGE message has `contentType=1`, `hasContent=true`, sealed chunks and:

- `OID`, `SID=emi`, `e2eeVersion`, and diagnostic `ENC_KM` (see below);
- `FILE_SIZE`: ciphertext length including its 32-byte MAC;
- `FILE_NAME=image.jpg`, metadata `contentType=1`;
- `MEDIA_CONTENT_INFO`: `category=original`, encrypted `fileSize`, `extension=jpg`.

The owner-requested **diagnostic variant A** sets
`diagnosticImageKeyInMetadata=true` by default. It adds raw base64 key material as
`ENC_KM`, matching the pinned bridge's sender, while retaining the sealed-body key.
This exposes the media key to LINE's server; this diagnostic must not be described
as keeping the photo key confidential from the server. Its purpose is to isolate
the official client's compatibility requirement after removing both metadata key
and preview together broke rendering in the official app. Whether that client
actually requires `ENC_KM` remains unconfirmed pending the isolated device result.

`MEDIA_THUMB_INFO` and the second `__ud-preview` upload remain absent. Encrypting
another plaintext with the original's key and counter would reuse its CTR
keystream. There is no alternative counter convention. The receive side supports
both sealed-body and legacy metadata keys. No connector package or dependency is
introduced. See [the research evidence](MEDIA_PROTOCOL_RESEARCH.md): synthetic
bridge tests do not establish how official clients populate these fields.

## Outcomes and history

- `sent`: LINE acknowledged a message ID, or an exact own-photo echo/history row
  confirmed a previously ambiguous upload. This is handoff, not delivery/read.
- `not_sent`: validation, ownership/readiness, key, flow, encryption, main upload
  or pre-dispatch journal failure. Failed uploads can leave orphan encrypted objects; they
  have not dispatched a message to the recipient.
- `unconfirmed`: dispatch occurred but no trustworthy message ID was returned.
  Its journal survives process death and replay never dispatches it again.
- `recipient_changed`: a fresh chat is missing/renamed, or an invocation was
  reused with different recipient/content (including text-versus-image).

Acknowledged photos enter snapshots like received photos: server message ID,
`media="photo"`, `mine=true`, text fallback `[Photo]`. Replays reuse the server ID
to avoid duplicates. `Media(chatID, messageID)` reads/decrypts the original object;
the normal 4 MiB download/thumbnail-input bound still applies. A larger original
or failed readback keeps the history entry with its text fallback.
Native readback is covered by synthetic OBS tests; live owner-upload readback
must be checked by the device team.

The private journal retains the chat, encrypted object locator, key material,
confirmed message ID, alongside its digest/outcome.
It stores no JPEG. These secrets never enter snapshots. An own photo with the
same chat and OID reconciles an ambiguous record through SSE or loaded history;
another sender/chat/OID cannot confirm it. If acknowledgement persistence fails,
the observed return value is still `sent` and durable state stays ambiguous,
matching text sending.

## Durable retention and failed writes

Text and photos share 64 full records and up to 1,024 compact confirmed-send
tombstones. Retiring a confirmed full record preserves its invocation hash,
content digest and last outcome, but drops image secrets. Both send APIs consult
these tombstones before readiness checks or network work. Ambiguous full records
are never retired to make space. Tombstones are persisted in FIFO order; the
oldest expires only when a 1,025th tombstone is added. Deduplication therefore has
an explicit bounded retention window, not unlimited lifetime retention.

The session file is capped at 512 KiB for both reading and writing, allowing
bounded tombstones even with maximally escaped full-record invocation IDs. A
failed pre-dispatch save rolls back the new record, order entry and sequence in
memory. A later save cannot accidentally persist a send that never dispatched.

## Verification scope

Tests exercise real file encryption against the existing decryptor,
unchanged original bytes, the intentional variant-A metadata key, exactly one OBS upload,
input/authority/key failure, failed dispatch, durable
replay and exact-echo/history confirmation. Message sealing is supplied at the
prepared-upload unit boundary in positive dispatch tests; missing-key tests use
the public method. Account provisioning, actual group/direct message sealing and
official-app interoperability remain device acceptance work. No test reads an
owner session, sends a network message or installs an APK on a device. Journal
tests exceed 64 full records, reopen tombstoned text/photo invocations, fill the
tombstone bound and retry text after a failed pre-dispatch save.
