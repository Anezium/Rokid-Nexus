# LINE media metadata: evidence for diagnostic variant A

Reference: `github.com/beeper/line` at
`ae616894a66b99e2c09d4e39761f4a60b48c4329` (`v0.0.0-20261106152813-ae616894a66b`).
All file/line references below refer to that pinned source, not the current branch.

## Conclusion

The inspected reference proves that **Beeper's sender** puts the raw base64 media
key in `ENC_KM` as well as in the sealed body, and its receiver accepts either.
It does **not** establish that official LINE clients always do that, encrypt that
metadata field, or omit it. No official-client message capture containing these
fields was found in the inspected fixtures/docs. Nor does the reference establish
that the official app requires an uploaded preview: the bridge deliberately
continues when preview generation/encryption/upload fails.

Consequently, there is no evidence here establishing that official clients seal
the key and *never* send raw `ENC_KM`. The owner's stop condition for variant A
was not met. Variant A is an explicitly authorized diagnostic, not a finding that
raw metadata is a required or secure LINE protocol property. If A fails, it does
not prove raw metadata is needed; the next experiment must be separately chosen.

## Receive-side evidence: support is not provenance

- `pkg/connector/handlers/handler.go:54-75`: the decoder first parses
  `keyMaterial` from the decrypted JSON, then adds a metadata candidate. Exact
  snippets at lines 71, 73 and 75 are `keys = append(keys, bodyKey)`,
  `encKM, metadataKeyDeclared := metadata["ENC_KM"]`, and
  `keys = append(keys, encKM)`. The latter goes straight to the file decryptor,
  with no separate unwrapping of the metadata key.
- `pkg/connector/handlers/handler_test.go:16-35` tests preference for the body key;
  line 28 explicitly supplies synthetic strings `body-key` and `metadata-key`.
  Lines 20-26 inject a fake `DecryptMedia` callback. This is a unit fixture,
  not an official-client wire capture.
- `pkg/connector/handlers/handler_test.go:40-63` tests fallback to the metadata
  key when the synthetic body key fails. Again the decryptor is mocked. It proves
  fallback behavior, not where a real sender stored the key.
- `pkg/connector/handlers/image_test.go:9-24` uses `/r/official/business-image`
  as a fabricated public resource. Here "official" is a business-resource path;
  it is not evidence about the metadata emitted by the official LINE app.

## Sender and cryptography evidence

- `pkg/connector/media.go:188-229`: `encryptFileData` generates 32 random bytes,
  encrypts the file and at line 227 performs
  `keyMaterialB64 := base64.StdEncoding.EncodeToString(keyMaterial)`. Base64 is
  encoding, not another encryption layer.
- `pkg/connector/send_message.go:376` assigns
  `contentMetadata["ENC_KM"] = keyMaterialB64`. Lines 395-396 also create the JSON
  key payload. Lines 747 and 753 put the metadata and sealed chunks into separate
  fields of the outgoing message. These are bridge-emitted messages; no comment
  identifies this assignment as a verified official-client requirement.
- `pkg/connector/send_message.go:342-360`: the bridge attempts preview generation,
  encryption and upload. At lines 345, 347 and 353 its failure messages end with
  "continuing without it". `MEDIA_THUMB_INFO` is assigned only at line 360 after
  successful preview upload. That is designed optionality in this implementation,
  not proof that every official receiver tolerates the omission.
- `pkg/connector/media.go:314-344`: the preview encryptor derives the same key
  and nonce as the original from the same material and starts at the same counter.
  Variant A does not restore this code or upload a second ciphertext.

## Sync, documentation and missing capture evidence

- `pkg/connector/sync.go` contains no `ENC_KM`, `keyMaterial` or
  `MEDIA_THUMB_INFO` interpretation. The metadata logging at line 2128 does not
  supply an actual message fixture or provenance for these fields.
- `readme/LETTER_SEALING.md:31` describes the **bridge** sending E2EE media as
  "E2EE encrypted media with keyMaterial in payload." Lines 131-132 mark incoming
  plain/encrypted images verified, but include no message envelope demonstrating
  which fields official clients used. The native-client comment at line 10 is
  specifically about transparent PNG compositing, not metadata or previews.
- The inspected `handlers/*_test.go`, other test JSON/Markdown fixtures and the
  `readme/` directory contained no captured official-client image envelope proving
  presence, absence or wrapping of `ENC_KM`, or mandatory `MEDIA_THUMB_INFO`.
  `.agents/commands/line-capture.md:9-17` documents how to create a Chrome capture;
  the capture tooling is included, not the resulting wire capture. Nothing was run
  against a LINE account or device for this research.

## Supplementary primary sources

The [official 2024 encryption report](https://www.lycorp.co.jp/en/privacy-security/security/transparency/encryption-report/2024/)
states that compatible clients support Letter Sealing for images/media, with the
rollout completed in November 2024. It does not specify `ENC_KM` or preview wire
fields. This is architectural context, not proof of the field-level behavior.
The whitepaper linked there returned HTTP 403 during this research and was not
used as evidence. [Beeper issue 54](https://github.com/beeper/line/issues/54), linked
from the pinned note, discusses sealed/unsealed interoperability but its fetched
text contains none of `ENC_KM`, `keyMaterial` or `thumbnail`.

## Controlled device question

The failed security build changed two factors together. Variant A restores only
raw `ENC_KM`, keeps exactly one original-file upload and no `MEDIA_THUMB_INFO`, and
leaves invocation tombstones, rollback and own-photo history unchanged. Its result
can test whether restoring the metadata key alone fixes this official-client
rendering failure. Success would establish that compatibility observation, not
that every official client emits raw metadata keys or that they are confidential.

## Variant B follow-up

The owner reported that A (`b28c78f5`: raw `ENC_KM`, no preview) still produced an
unopenable placeholder in the official app. Restoring that field alone therefore
did not repair this failure. B keeps `ENC_KM` absent and restores the preview,
matching `5241f577`'s format and its known CTR key/nonce reuse. The single selector
is now `diagnosticImageKeyInMetadata=false` by default. Tombstones, rollback and
the sealed media-key body remain unchanged.

The redacted diagnostic now attempts authenticated preview decryption with only
the sealed-body key, using the same HKDF/counter as the file decryptor, followed
by JPEG/PNG header parsing. `previewDecryptsWithBodyKey=true` establishes that
compatibility fact for the inspected sample. Combined with successful original
decryption on a controlled official-app send, it is evidence of protocol-level
key/nonce reuse for that sample, to be documented as a limitation rather than
silently changed. Missing keys/objects or a failed fetch remain inconclusive and
are distinguished by a fixed status. No official-client probe result has been
assumed here before the device session reports it.
