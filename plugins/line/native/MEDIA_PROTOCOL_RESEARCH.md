# LINE photo wire format and interoperability evidence

Reference: `github.com/beeper/line` at
`ae616894a66b99e2c09d4e39761f4a60b48c4329` (`v0.0.0-20261106152813-ae616894a66b`).
Source file/line references below refer to that pinned dependency, not the current branch.

## Conclusion

The shipping sender uploads an encrypted original and preview, sets
`MEDIA_THUMB_INFO`, and carries `keyMaterial` only in the Letter Sealing body
and private journal. It does **not** add `ENC_KM` to outgoing metadata. The
official app rendered and opened this shape in the device team's controlled test;
the metadata key is not required for that interoperability. Omitting the preview
produced an unopenable placeholder even with `ENC_KM` restored.

The preview uses the original's key material and thus the same HKDF output,
AES-CTR key, nonce and counter start. This is keystream reuse, inherited from
the LINE media protocol as implemented by the pinned Beeper library. The tested
compatible preview format retains this cryptographic limitation: XOR of the two
ciphertexts reveals XOR of the plaintexts. We do not add a raw metadata key or
invent a different preview counter convention.

**Official client not measured:** no official-client-origin photo reached the
tested group during this session. These measurements establish what the official
app can receive/render, not what official senders emit. They do not establish
whether an official sender includes raw, wrapped or absent `ENC_KM`, or whether
all official clients always upload previews. The pinned sender does include raw
`ENC_KM`, and its receiver accepts keys from either body or metadata; that is
library behavior rather than official-client provenance.

## What the LINE server can see

Outgoing metadata exposes the thumbnail dimensions (`width`, `height`), encrypted
file size, extension/category, file name/type, OID/SID and sealing version. LINE's
server also holds the original and preview ciphertext, including their MACs.
The media key is only in the sealed Letter Sealing body on the wire, never an
outbound `ENC_KM` field. Keeping the key out of metadata does not remove the
CTR keystream-reuse limitation above.

## Device measurements, 2026-11-08

Reported by the Opus device tester using the Samsung, glasses and official LINE
app, group `yoyo`, with `d3d59c56`'s read-only instrumentation available. Times
below are the relayed local message times. Both measured photos were glasses
sends from our engine, not photos emitted by the official app.

| Message | ENC_KM | MEDIA_THUMB_INFO | Preview probe | Official app |
| --- | --- | --- | --- | --- |
| 19:20, variant A | Present, decoded 32 B | Absent | `no_preview_metadata` | Green placeholder; cannot open |
| 21:39, variant B | Absent | Present: `height`, `width` | `previewDecryptsWithBodyKey=true`, `verified_jpeg` | Real image; opens full screen |

At 21:41, the tester checked server history: six messages, with only our 21:39
send newly present. The owner's attempted official-app photo never appeared in
this group, so **official client not measured** remains the provenance limit.
The positive probe authenticates/decrypts our own preview with the sealed-body
key and the existing file HKDF/counter convention; it confirms reuse for our
sample, not for an unobserved official sender.

The failed build before A removed both `ENC_KM` and the preview together. A
(`b28c78f5`) restored only the metadata key and still failed. B (`d3d59c56`)
restored the preview with no metadata key and succeeded. The shipping path is B;
the `diagnosticImageKeyInMetadata` selector and alternate A branch are removed.
The preview download/decrypt probe and its JSON fields are also removed from
production code. The metadata-only describer remains opt-in instrumentation;
normal plugin pages/settings never invoke it.

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
  This is the compatible preview convention retained by the shipping sender.

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
