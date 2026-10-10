# r17 — Dictate first (2026-10-10)

Draft only. Nothing here approves the mockup. No whole-UI review, device test, optics QA or formal
Fable approval covers r17; only the author's bounded checks below. The parent's check is still to do.

**Status, as it happened:**
1. The parent verified r16 within R6's bounds and published it
   (`build/pr3a/PARENT-RELAY-SIMPLE-R16-VERIFY-20261010.md`).
2. The owner accepted the simplified Relay flow, with one retouch: Dictate first, because dictation is
   the default.
3. Opus (this author, Claude Code subscription, no API) applied only that retouch.
4. The owner's separate question about a generic activity feature is handled by the parent; r17
   draws nothing for it.

## Files

| File | Bytes | SHA-256 |
|---|---|---|
| r16 `index.html`, as verified and published (`index.r16-reviewed.html`, read-only) | 150,738 | `290db113d045c37914962f0a5aa250c0c58449f6766b00e165b9f1e2a9e4b4ec` |
| r16 `CHANGES.md` (`CHANGES.r16-reviewed.md`, read-only) | 79,695 | `36149435c2be6a90c5c5766f7bb5877e15c7b1b8a2dac905ae735839f0f9b849` |
| r17 `index.html` (this folder) | 152,408 | `a9063300f6fc226694726231860c3cac25d81bc98f677a50347b10b2bd17c0f9` |

The r14 and r15 backups are untouched. r17 `index.html`: UTF-8 without BOM, LF only, 856 lines, one
inline `<script>`.

## What changed

- **Button order.** `RD` is now Dictate, Type; `RS` is Envoyer, Dictate, Type. Envoyer keeps its place.
  The order changes in every panel that draws these buttons: 4.8, 4.9, 4.12, 4.13, 4.19, 4.20, 4.21
  and 4b.7, including the 4.8 key screen.
- **Default focus.** In 4.9, tapping from reading with no draft now preselects Dictate. The hint reads
  `swipe Dictate ⇄ Type · tap commencer`.
- **Unchanged focus rules.** With a draft, Envoyer is still preselected (4.13). It still needs a fresh
  tap: no preselected Send when a capture finishes and no automatic send. The contact-gate requirement
  stays future and untested.
- **Intended Type exceptions.** These keep Type focused while Dictate stays first on screen:
  - 4.11: the glasses microphone is unavailable. There is still no fallback to the phone microphone.
  - 4.21: the handoff was unconfirmed. Type reopens the kept text.
  - 4.10: an explicit Type capture screen. It is unchanged.
- **Text that follows the new order.** Captions 2.6, 4.8, 4.9, 4.11, 4.12, 4.13, 4.19, 4.20, 4.21,
  4.22, 4b.7 and 4b.10; the §7 grammar rows for reading and focused buttons; the key-screens heading;
  the identity rule card; the section intros; the roadmap line.
- **Outside the HUD:** the status headline, a new r17 history entry, and the footer. The r15 history
  line `tap → Type ou Dictate` stays as written and gets a note that r17 moves Dictate first.
- **Unchanged:** the notice band (Reply + Type, 1.11 and 1.12), all code simulations, canonical marks
  and glyphs, chat sides, CSS, geometry, map and navigation. Draft, contact-gate and authority rules
  are also unchanged, and no menu, panel, control, dialog or gesture was added. The CSS comment at
  line 189 still says "Type and Dictate"; it was left alone to keep the CSS byte-identical.

## Bounded author checks (observed)

The scripts are in `verify-r17/` in the Temp folder.
- `node --check` passed on the extracted inline script (exit 0).
- **Panel data.** The data was evaluated in a VM with a deterministic `Math.random`:
  - 87 panels, 87 unique ids, same order as r16; tag, phase, provenance and `d` are identical for all
    87.
  - All 28 code-reference HUDs, captions and metadata are identical to r16. Their HUD and metadata
    are also exact to immutable r14, matched by tag and occurrence. `MK` and `MK_SRC` are exact r14.
- **HUD changes vs r16:** only 4.8, 4.9, 4.12, 4.13, 4.19, 4.20, 4.21 and 4b.7. All of them are footer
  order changes; 4.9 also moves its focus and hint. Rendered order: 4.9 `Dictate* Type`, 4.13
  `Envoyer* Dictate Type`.
- **Caption changes vs r16:** only 2.6, 4.8, 4.9, 4.11, 4.12, 4.13, 4.19, 4.20, 4.21, 4.22, 4b.7 and
  4b.10.
- **Byte-identical to r16:** the CSS, and the script from `function shot(` to the end (rendering,
  filters, scale).
- **Line diff vs r16:**
  - 235–236: status and history.
  - 239, 267, 270, 287, 316: headings and intros.
  - 352–353: grammar.
  - 367: footer.
  - 483–484: helper comment.
  - 504: `RD`/`RS`.
  - Panel lines 582, 654–656, 660, 662, 664, 684, 687, 689, 691, 743 and 749.
- No browser, html_preview, html_render, device or optics run was made for r17.

# r16 — R6 copy fixes and factual status refresh (2026-10-10)

Draft only. Nothing here approves the mockup ("rien ici n'approuve le mockup"). No new whole-UI
review covers r16; R6 permitted these fixes with bounded checks only.

**Status, as it happened:**
1. Independent review R6 examined r15 exactly. Verdict: the owner's request is met and the
   simplification is right, but r15 was not ready for the provisional owner visual review before two
   copy must-fixes. R6 also gave two should-fixes and some optional points left for the owner.
2. Opus (this author, Claude Code subscription, no API) applied MUST-1, MUST-2, SHOULD-1 and SHOULD-2
   and nothing else, then ran the bounded checks R6 permits (below).
3. The parent's bounded post-fix check of r16 has not happened yet.
4. Still pending: the formal Fable review, device and optics QA, and the owner's visual approval.
   Fable 5.1's API answer remains a partial consultation, not a review.
5. Unchanged:
   - LEGACY is the shipped default; SESSION is opt-in.
   - Source approval stays at Opus R4 (2368 historical observed tests), separate from this mockup.

## Files

| File | Bytes | SHA-256 |
|---|---|---|
| r15 `index.html`, as reviewed by R6 (`index.r15-reviewed.html`, read-only) | 146,670 | `84f09c739b695df811d651469c8a4c7ed28d52480248524f03946a3d5ec1a18f` |
| r15 `CHANGES.md`, as reviewed by R6 (`CHANGES.r15-reviewed.md`, read-only) | 70,734 | `31d7cb4d283ce5fd319e252bdbf53016d31e41dac12d9b9855ffd04c5e5eaa63` |
| r16 `index.html` (this folder) | 150,738 | `290db113d045c37914962f0a5aa250c0c58449f6766b00e165b9f1e2a9e4b4ec` |

- The r14 baselines (`index.r14-baseline.html`, `CHANGES.r14-baseline.md`) are untouched.
- This file cannot carry its own hash; the author's report and the parent record it.
- r16 `index.html`: UTF-8 without BOM, LF only, 855 lines, one inline `<script>`, 0 network
  references, 0 vh units.

## Fixes applied (the minimum delta)

**MUST-1. Triple tap during a capture.** The §7 capture row ("Conversation · saisie en cours") said
triple tap finishes, keeps the draft, then goes to the root. That contradicted the current guard: a
focused editable field turns triple-tap recognition off (`InputArbiter.kt`), and an armed band refuses
it too. The cell now reads « non reconnu pendant la saisie (comme tout champ focalisé aujourd'hui) :
tap termine ou back annule d'abord ». The r15 input table below is updated to match. No new capture
triple-tap behaviour or root claim is made.

**MUST-2. One rule for an unavailable draft.** The 4.22 caption, the 4b.10 caption, the §7 draft-rule
row and the r15 "Unavailable reply" bullets now say the same thing:
- **Loss of reply authority** (notification removed or expired, access revoked, the exact thread no
  longer replyable): the old draft cannot be sent as is. Its text is kept.
- **Link loss alone** does not kill the draft. It stays unchanged and can leave only after a new,
  deliberate Envoyer, revalidated against the exact live thread. It is never sent automatically on
  reconnection.
- **The same exact thread gets a new replyable notification** after an authority loss: Type opens the
  phone keyboard pre-filled with the old text as a new capture; only OK and then a new, revalidated
  Envoyer can send it. Dictate records a new one, which replaces the old draft when it finishes.
- No other thread or recipient is guessed, and no text is silently discarded.

The 4.22 HUD is unchanged: its local label « non transmis · plus envoyable ici » stays true while the
reply is unavailable. The policy wording lives in captions and the grammar, outside the HUD.

**SHOULD-1. Fresh-gesture send is a requirement, not a fact.** The 4.13 caption and r15 "Changed, on
purpose" point 1 no longer state that two quick taps cannot send. They now state a target runtime
requirement, untested: Envoyer acts only on a tap whose contact starts after the Envoyer focus is
displayed; an Enter whose contact precedes that transition is ignored. Feasibility: the existing
`SessionEvent.Contact` timestamps make it implementable. Nothing is implemented here and no device
gesture proof is claimed.

**SHOULD-2. 4b.7 header.** 4b.7 now passes `title:'MIKA'` to `RC`, which already supported a title, so
its header matches 4b.10 (`MIKA`, Relay mark kept). This is the only HUD change in r16; no header or
helper layout changed. No deviation was needed.

**Outside the HUD:** the status headline, the review-history fold (new r16 entry; the r15 entry's
"Aucune revue n'a examiné r15" now says R6 examined it afterwards) and the footer.

## Contrast: parent measurements, not the author's

The parent measured r15 in the native T3 tab by compositing the status background over the real theme
background. Scope: the status block only, with representative regular and bold text.

| Theme | Background | Composited status | Regular text | Warning bold |
|---|---|---|---|---|
| Light | `#fcfcfc` | `[244,241,234]` | 13.20 | 5.11 |
| Dark | `#0a0a0a` | `[25,22,14]` | 16.57 | 9.36 |

These are parent-observed values. They are not a new full-page audit or an optics proof. The author's
approximate 1.41 in the r15 checks was a probe error, never a native contrast value. r16 changes no CSS.

## Checks actually run (by the author, on the final r16 bytes)

Scripts are in `C:/Users/saim2/AppData/Local/Temp/relay-simple-work/r16/`, outside the draft folder.

- **Edits:** two Node scripts with exact-match replacements, each asserted to match exactly once
  (7 for the fixes, 5 for status, fold and footer).
- **Syntax:** inline script extracted (1 script); `node --check`: OK.
- **`diff index.r15-reviewed.html index.html`:** 8 hunks, 9 lines changed: 235–236 (status, fold),
  354 (capture triple-tap cell), 356 (draft-rule row), 367 (footer), 663 (4.13 caption), 690 (4.22
  caption), 742 (4b.7: only the 13-character `title:'MIKA',` insertion), 748 (4b.10 caption tail).
  The `<style>` block hashes identically before and after.
- **Panel data,** evaluated in Node up to the rendering code with a seeded deterministic
  `Math.random` (no DOM), r14 baseline, r15 reviewed, r16:
  - panels 84 / 87 / 87; r16 ids identical to r15, in order, all unique;
  - code panels: the same 28; **HUD HTML 28/28 byte-identical to r14**, tag, phase and provenance
    28/28 equal to r14;
  - HUD changed r15 → r16: **4b.7 only** (`RELAY · MIKA` → `MIKA`); every other HUD is raw
    byte-identical to r15;
  - captions changed r15 → r16: 4.13, 4.22, 4b.10;
  - tag, phase or provenance changed r15 → r16: none.

**Not done, and not required by R6 for this bounded copy:** no browser render or geometry rerun, no
contrast measurement, no `html_preview` or `html_render`, no device or optics check, no source,
runtime, build or Git work.

## Remaining questions for the owner's visual review

R6's optional points, deliberately not edited:
1. **Footer language:** `Type · Dictate · Envoyer` mixes English and French. Keep Envoyer, or say Send?
2. **Focus contrast:** in 4.9 only the selected button turns phosphor. Is that visible enough on the
   additive optics? This is a device question.
3. **Label size:** 17 px labels on 56 px buttons. Should they be bigger, for example 20 px?
4. **Band vocabulary:** the band says Reply + Type and the chat says Type + Dictate (r15 question 3).
5. **Still undescribed (PR4):** how the global draft interacts with a band Reply to another sender,
   and how a pending draft shows on the root card.

---

# r15 — Relay conversation simplified to one screen (2026-10-10)

> **Updated by r16.** R6 reviewed r15 afterwards. The input-table capture row, "Changed, on purpose"
> point 1, the "Unavailable reply" bullets and open question 2 below now carry the R6 corrections.
> The exact reviewed r15 text is in `CHANGES.r15-reviewed.md`.

Draft only. Not reviewed, not approved: nothing here approves the mockup. A new independent review
follows this revision before any repo copy.

**Why.** The owner found r14's Relay target too complicated ("trop de menus, trop d'options pour pas
grand chose") and asked for the simple model of before: swipe turns the pages, and two big buttons,
Type and Dictate. They asked Fable 5.1 for an opinion via API, then asked Opus to make the changes.

**Status, as it happened:**
1. The parent ran the Fable 5.1 API consultation. The result was stopped by length (`finish_reason:
   length`). The diagnosis and the interaction table are complete, but the panel list is cut
   mid-bullet. That makes it advice, not a formal review. The missing tail was not invented here.
2. Opus (this author, Claude Code subscription, no API) wrote r15 in a new Temp folder. The repo is
   untouched.
3. No review covers r15. Still pending: the new independent review, a formal Fable review, device
   and optics QA, and the owner's visual approval.
4. Unchanged from r14:
   - LEGACY is the shipped default; SESSION is opt-in.
   - Source approval stays at Opus R4 (2368 tests).
   - R5 reviewed r13 only, and covered r14 through a bounded copy.

## Files

| File | Bytes | SHA-256 |
|---|---|---|
| r14 `index.html`, baseline (`index.r14-baseline.html`, read-only; equal to the repo copy) | 135,947 | `0f9f536128b0bee98ce45f6302d2b54f2b323799e6248ba200c15f2c69c31226` |
| r14 `CHANGES.md`, baseline (`CHANGES.r14-baseline.md`, read-only) | 51,643 | `0009a8d509fddb7269f1db7df16cb9e01e6d0f8b662b88d052c764ad7e2479b0` |
| r15 `index.html` (this folder) | 146,670 | `84f09c739b695df811d651469c8a4c7ed28d52480248524f03946a3d5ec1a18f` |

- r15 `index.html`: UTF-8 without BOM, LF only, 855 lines, 0 network references, 0 vh units.
- One inline `<script>`.
- Folder: `C:/Users/saim2/AppData/Local/Temp/NexusLauncherMockup-relay-simple-20261010/`. It was
  created for this round; it did not exist before.
- Every earlier Temp folder is untouched.
- The launcher-rework worktree was only read:
  - it is still at `5a05b50d`;
  - its mockup pair still has the r14 hashes;
  - the contract is unchanged.
- No source, asset, contract, build, device, Git or publication action.

## Normal flow, before and after

**Before (r14 target):** promoted card → 4.8 detail (Lire le fil / Répondre) → 4.9 thread → tap
commands → 4.10 mode choice page (Clavier du téléphone / Dicter) → capture → 4.11 review page
(Modifier / Transmettre à l'app / Effacer, Modifier preselected) → transmission. That is four
surfaces and about five taps to say "j'arrive".

**After (r15 target):** one screen, `RELAY · MIKA`.

| Step | What the wearer does | What the screen shows |
|---|---|---|
| Open | Taps the promoted card (PR4), a thread in the Relay list, or, later, the exact thread of a notice (PR3b reference) | The conversation on its newest page, received on the left, ours on the right. Two big outline buttons, **Type** and **Dictate**, at rest at the bottom (4.8). |
| Read | Swipe | Pages of the captured window, on the single axis (4.19) |
| Reply | Tap | Focus moves to the buttons; Type is preselected (4.9) |
| Choose | Swipe, then tap | Type opens the phone keyboard (4.10); Dictate listens with the glasses microphone (4.11) |
| Write | Types on the phone, or speaks | Text appears live in a dashed bubble on our side |
| Finish | Taps (or presses OK on the keyboard) | Back to reading. The draft stays in the chat, and a third button, **Envoyer**, joins (4.12) |
| Send | Taps (focus; Envoyer is preselected and the hint names Mika), then taps again | Relay rechecks the exact thread just in time, then hands the reply to the app: "transmise à l'app" (4.20) |

The detail page, the mode page and the review page are gone from the target. Their ids are reused
for states of the same screen (below). r14 stays readable in the baseline copies.

## Complete input table (also in the HUD-free §7 grammar, row `#gram-relay`)

| State | Swipe | Tap | Back (double tap) | Triple tap |
|---|---|---|---|---|
| Reading (4.8, 4.12, 4.19–4.21) | Previous / next page of the captured thread, no wrap | Focus the buttons. Type is preselected; Envoyer is preselected when a draft exists; Type after an unconfirmed handoff | Back to the origin (root, Relay list, notice); the draft stays | Root, same session; the draft stays |
| Buttons focused (4.9, 4.13) | Next / previous button, no wrap; no paging | Type: phone keyboard. Dictate: glasses mic. Envoyer: revalidate the exact thread, then hand over | Back to reading, nothing lost | Root; the draft stays |
| Capture in progress (4.10, 4.11) | No effect | Finish: the text becomes the draft, back to reading; never a send | Cancel this capture (the hint says so). A previous draft, if any, comes back intact | Not recognised during capture, like any focused field today (r16, R6 MUST-1): tap finishes or Back cancels first |
| Reply unavailable (4.22) | Page | Nothing | Back to the origin; an unsent draft stays readable | Root, same session |

The rest of the gesture set:
- No long press, no armed triple tap, no touch, no ring and no second axis.
- The notice band is unchanged: triple tap is still not recognised while a band is armed.

## Decisions on Fable's advice

**Accepted:**
- The chat is the only normal surface.
- The detail, mode and review pages go.
- The input choice becomes an inline footer state.
- Tap moves focus to the footer; swipe has one meaning at a time, and the hint states it.
- The draft is a dashed bubble on our side.
- "transmise à l'app" only after a real handoff; never delivered or read.

**Changed, on purpose:**
1. **Envoyer is not preselected at the end of a capture.** Fable recommended preselecting Envoyer
   right after the capture. If it were, the tap that ends a dictation, or a leftover contact, would
   be one tap away from sending. In r15 a capture always ends in reading. Sending then takes a new tap
   to focus, with Envoyer preselected and the hint `tap envoyer à Mika`, and then a second deliberate
   tap. It costs one tap more than Fable's version and needs no confirmation screen. A firmware double
   tap is Back on Rokid, but whether two taps spaced just past the double-tap window both count as
   Enter is not measured. So "two quick taps cannot send" is a target runtime requirement, untested
   (r16, R6 SHOULD-1): Envoyer acts only on a tap whose contact starts after the Envoyer focus is
   displayed; an Enter whose contact precedes that transition is ignored. The existing
   `SessionEvent.Contact` timestamps make this feasible; nothing is implemented. After an unconfirmed
   handoff, Type is preselected instead, so that a duplicate send never takes only two taps.
2. **No "Refaire" that clears the text.** Fable's Refaire reopened the capture with the text cleared,
   which loses text. r15 keeps the same Type and Dictate buttons next to Envoyer:
   - Type reopens the phone keyboard pre-filled with the draft.
   - Dictate records a new dictation, and it replaces the draft only when it finishes.
   - Back during either capture keeps the previous draft intact.
   - The only way to discard a draft is explicit: empty the text in the keyboard, then OK.
3. **One global draft, not "one draft per thread".** Fable's "only one draft exists per thread … the
   other draft is simply dropped when its thread is left" is wrong against the prior design and was
   not copied:
   - The draft is unique, bound to its authenticated recipient (Mika), and kept across the root and
     Back.
   - In another thread, starting a capture would replace it. The focused button's hint says
     « remplace le brouillon pour Mika », and the replacement only happens when the new capture
     finishes; Back keeps Mika's draft.
   - The draft is never sent to another recipient and is never silently destroyed.
4. **The notice band is not rerouted.** Fable proposed that the band's Reply open the conversation.
   That is a proposal; the band (1.8, 1.11, 1.12) is unchanged in r15. Its gesture constraints stay
   as documented: double tap closes it, and triple tap is not recognised while it is armed.
5. **Three footer buttons when a draft exists** (Envoyer, Type, Dictate) instead of Fable's two
   (Envoyer, Refaire). The owner's two buttons stay in place and keep their meaning; Envoyer is added
   only when there is something to send.

## Panels (ids kept; three appended)

| Id | r14 | r15 |
|---|---|---|
| 4.8 | detail · conversation (Lire le fil / Répondre) | **conversation · lecture**: the chat on its newest page, Type / Dictate at rest, `tap répondre` / `back racine`. Also a key screen. |
| 4.9 | thread as conversation, `tap commandes` | **conversation · boutons en focus**: Type selected (bright), Dictate dim, `swipe Type ⇄ Dictate · tap commencer` / `back lecture`. The chat is not dimmed. |
| 4.10 | mode choice page | **Type · clavier du téléphone**: live dashed draft bubble with caret; footer line « Clavier du téléphone · saisie en cours »; `tap terminer` / `back annuler la saisie` |
| 4.11 | review page | **Dictate · micro des lunettes**: the same bubble; footer « Micro des lunettes · écoute »; `back annuler la dictée`. No phone-mic fallback: if the glasses mic is unavailable, the line says so and focus returns to Type. |
| 4.12 | transmission unconfirmed | **brouillon gardé · lecture**: dashed bubble, « brouillon · pas encore envoyé », footer Envoyer · Type · Dictate at rest, `back racine · brouillon gardé` |
| 4.13 | reply token expired | **brouillon · Envoyer en focus**: Envoyer selected, `tap envoyer à Mika` / `back lecture` |
| 4.19 | long thread, `tap commandes` / `back détail` | The same screen on page 2/2, with the footer. `swipe page · tap répondre` / `back racine`. The bounded-history clause is kept. |
| 4.20 (new) | — | **transmise à l'app**: solid bubble with the 12 px line « transmise à l'app »; footer back to Type · Dictate |
| 4.21 (new) | (was 4.12) | **transmission non confirmée**: still dashed, « non confirmée · vérifie sur le téléphone »; no automatic retry; Type preselected on the next focus |
| 4.22 (new) | (was 4.13) | **réponse indisponible**: readable chat. The footer becomes the inert dashed line « Réponse indisponible ici · réponds depuis le téléphone ». An existing draft shows « non transmis · plus envoyable ici ». |
| 4b.7 | exact thread, chat without footer | The same screen as 4.8, `back notice`. Phases PR3b + PR4: the reference is PR3b, the reply PR4, and before PR4 the thread reads without buttons. |

**Captions changed:**
- 2.6: the card opens the conversation directly.
- 4b.10: no reply while disconnected; never sent on reconnection without a new Envoyer.
- 4b.12: the promoted card opens 4.8; a thread picked in the list opens the same screen.

**Key screens:** 2.5, 4.17, **4.8** (was 4.9), 1.11. There are still 4 copies.

**Outside the HUD:**
- the status headline and the review-history fold, with the before/after;
- the PR4 legend;
- the conversation-badge legend;
- the "no mark" rule card: Type, Dictate and Envoyer carry action glyphs (keyboard, microphone,
  arrow), never an app mark;
- the D2 exceptions card;
- the section 4 lead, which links to the grammar;
- the §7 grammar: the old "Composeur de réponse (PR4)" row is replaced by four conversation rows and
  one draft-rule row;
- the footer.

**Not changed:**
- the notice band panels 1.8, 1.11 and 1.12;
- the root, maps, navigation, transit, states and the other stack panels;
- the identity marks;
- theme, scale, view and filter logic;
- every code simulation.

## Visual treatment

- 480×640, pure black, outlines only. The measured background of every footer button, line and
  bubble is `rgba(0,0,0,0)`. Message text is 17 px.
- The chat area runs from the header (71) to 528. It is bottom-aligned like a chat, so the newest
  message, or our draft, sits just above the buttons.
- Footer: 56 px high, at 536–592, above the 40 px hint.
  - Two buttons are 217 px wide each; three are 141 px each.
  - Each has a 22 px action glyph and a 17 px label.
  - At rest the outline is `--dim #2f6b45` and the label `--ink`.
  - The selected button is phosphor. Reading has no selected button.
- A draft is a dashed phosphor bubble. A capture adds a 2 px caret.
- The one 12 px meta line under the bubble is local state only: « brouillon · pas encore envoyé »,
  « transmise à l'app », « non confirmée … », « non transmis … ». There are no timestamps and no read
  or delivery marks.
- The capture caption « Messages · capturé 14:11 » stays.

## Safety and honesty rules kept

- **Revalidation at send.** Relay rechecks, just in time, that the notification of this exact
  authenticated thread still accepts a reply, then hands the reply over.
- **Fail closed.** If the check fails, nothing is sent (4.22), with no guessed recipient.
- **No technical wording in the HUD.** There is no "jeton"/token wording in the wearer flow.
- **Unavailable reply** (updated by r16, R6 MUST-2).
  - A stale, disconnected or read-only thread stays readable and offers no reply.
  - Loss of reply authority (notification removed or expired, access revoked, the exact thread no
    longer replyable): the unsent draft cannot be sent as is; its text is kept.
  - Link loss alone does not kill the draft: it stays unchanged and can leave only after a new,
    deliberate Envoyer, revalidated against the exact live thread; never on reconnection by itself.
  - It never takes authority from another notification and never goes to another recipient.
  - If the same exact thread later gets a new replyable notification, Type opens the phone keyboard
    pre-filled with the old text as a new capture; only OK and then a new, revalidated Envoyer can
    send it. Dictate records a new one, which replaces the old draft when it finishes.
  - No other thread or recipient is guessed, and no text is silently discarded.
- **Handoff wording.** Only a successful handoff says « transmise à l'app ». An unacknowledged one
  says « non confirmée » and is never retried automatically.
- **Capture only.**
  - The history is bounded: 4–40 messages, 20 by default, trimmed from the oldest end at 8192
    characters, as in 4.19.
  - Only the capture time is shown; no invented timestamps or receipts.
- **Entry points.**
  - The app entry is still the Relay list, and a chosen thread opens the chat.
  - The promoted card and the notice exact-thread routes stay future and fail-closed.
- **Labels.** The target labels Type / Dictate do not describe the current runtime. Today Relay sends
  `Reply` (or `Show` + `Reply`); `replyByTyping` changes what Reply does; `Type` appears during
  dictation after 1.2 s. The 1.8 caption still says so.

## Checks actually run

All run on the final r15 bytes. Scripts live in `C:/Users/saim2/AppData/Local/Temp/relay-simple-work/`,
outside the draft folder.

**Edits:** a Node script with exact-match replacements, each asserted to match once.

**Syntax:** `node --check` on the extracted inline script: OK.

**Panel data:** both files were evaluated in Node up to the rendering code, with a seeded
deterministic `Math.random`. No DOM was used.
- Panels: r14 84 → r15 87. Added 4.20, 4.21 and 4.22; none removed.
- Code panels: 28 in both, the same set.
  - **HUD HTML 28/28 byte-identical to r14**, by exact raw string compare.
  - Tag, phase and provenance 28/28 equal.
  - None draws `.am`, `.hg`, `.cm`, `.rc` or `.rf`.
- HUD changed: 4.8–4.13, 4.19 and 4b.7. Map clip ids were stripped for this compare only.
- Captions changed: 2.6, 4.8–4.13, 4.19, 4b.7, 4b.10 and 4b.12.
- Tag, phase or provenance changes:
  - 4.8–4.13: the tags only, phases `p4` kept;
  - 4b.7: `b` → `b`, `p4`.
- Panels using the conversation screen: 4.8–4.13, 4.19–4.22 and 4b.7.

**`diff` r14 → r15:** 20 hunks, all listed above. `<style>` gained only the conversation-screen rules.

**Native T3 collaborative tab:**
- Opened with `preview_status`, then `preview_open`. The exact file was served from a temporary
  `127.0.0.1:8783` loopback server.
- The page fetched its own source and hashed it: `84f09c73…`, 146,670 B.
- Afterwards the tab was closed and the server stopped; `curl` confirms it is down.

At 360 px, in auto (0.743 / 0.5, browser default appearance) and clamped 1:1 (0.743 / 0.689, emulated dark):
- scrollWidth 360;
- 0 HUD elements outside a frame, 0 wrap cuts, 0 clipped `.vp`, 0 cut chat areas, 0 ellipsis cuts;
- 0 filled bubbles, buttons or lines;
- every header 54 px and every hint 40 px.

Default Cible view: 59 visible panels (r14: 56, plus 3), 0 code panels, and 4 key screens (2.5, 4.17,
4.8, 1.11).

Per conversation panel, in frame px:
- Sides: received messages touch the left edge and ours the right. The recorded sequences were LRL,
  LRLR and LRLRLL.
- Text: 17 px.
- Chat content runs from 201–368 down to 528, and the footer sits at 536–592.
- No button, line or hint text overflows.
- The selected button is where expected: Type at 18–235 in 4.9, Envoyer at 18–159 in 4.13, and none
  while reading.

At 728 px, emulated light in auto (0.75 / 0.5), ½ and 1:1 (1.0), then emulated dark in auto: the same zero counts.
- **Views:** Code livré shows 28 panels with the keys hidden; Tout shows 87.
- **Conversation filter:** 4.8–4.13, 4.19–4.22, 4b.7 and 4b.10.
- **Jump to 4.22** from Code livré + PR5 resets both and lands at 12 px.
- **Links:** the key-screen link to 4.8 lands at 12 px, and the section-4 link `#gram-relay` reaches
  the new grammar row.
- **Contrast** (approximate T3 dark variables injected, as in r13) on the changed outside-HUD text,
  50 blocks:
  - dark: minimum 5.81;
  - light: every block ≥ 4.5, except the `.status` block. The probe misread its 7 % translucent
    tint, unchanged since r14, and reported 1.41; that is not a real value, and it was not
    re-measured.

**Screenshots inspected:** 4.9 and 4.11 at 360, 1:1, default appearance; 4.22 at 360, 1:1, emulated dark. In each:
- the buttons are legible;
- the selection is visible;
- the dashed draft and the caret are clear;
- the inert line reads as inert;
- nothing is clipped.

**Not done:**
- `html_preview` was not used: the native tab rendered the exact bytes, confirmed by the in-page
  hash.
- No `html_render` publication.
- No device or optics check. 17 px text and the dim `--dim` outlines at rest are CSS-px simulations;
  whether the rest-state buttons read on the additive optics is unverified.
- No runtime or state machine. This is a static storyboard; the table above is the specification to
  review.

## Open questions for the reviewer and the owner

1. **Send effort.** Is "tap, tap" after a capture acceptable? The alternative is Fable's preselected
   Envoyer, which is one tap less but risks a residual-tap send.
2. **Same-thread authority after a visible loss (4.22).** Resolved in r16 by R6 MUST-2 with the safe
   form below (pre-filled new capture, then a new revalidated Envoyer); link loss alone is not an
   authority loss. The original r15 question follows. r15 is conservative: an unsent draft never
   becomes sendable again, even if Mika's thread later becomes replyable. Should the owner want the
   text reusable, the only safe form is a new capture pre-filled from it, followed by a new Envoyer
   revalidated for that exact thread. The opposite case is not treated as a loss: the app updates the
   same thread's notification while it stays replyable. Whether the runtime can tell these two cases
   apart is to verify.
3. **Band vocabulary.** The band target 1.11 says Reply + Type, and the chat says Type + Dictate.
   Should the band's Reply be labelled Dictate too, and should a band tap open the conversation (Fable's
   suggestion)? This is left unchanged in r15.
4. **Draft lifetime.** It is kept across the root and Back, as before. No new expiry was invented. The
   retention bound itself is still a PR4 decision.
5. The r14 questions below still stand. Questions 1 and 2 of r13 (Reply + Type semantics; newest page
   first) are partly answered by this revision: the thread opens on its newest page.

---

# r14 — R5 copy fixes and factual status refresh (2026-10-10)

Draft only. Not approved: nothing here approves the mockup. r14 is r13 with two copy-only should-fixes
from independent review R5 applied, outside the HUD, plus a factual refresh of the status banner and
footer. It is then copied, uncommitted, to `docs/mockups/glasses-home/` in the launcher-rework
worktree.

**Status, as it happened:**
1. Independent review R5 reviewed the exact r13 (`d7c48031…`, 134,477 B). Verdict: ready for the
   provisional owner visual review, no must-fix, two copy-only should-fixes.
2. Opus applied both in r14 and ran the bounded recheck R5 named (below).
3. **R5 did not review r14.** R5 explicitly permitted the bounded recheck, the status refresh and the
   uncommitted repo copy without another review.
4. Still pending: Fable review, device and optics QA, and owner visual approval. The Opus R4
   source-code approval is unchanged. LEGACY stays the shipped default; SESSION stays opt-in.
5. The owner-target presentations (chat thread, Reply + Type, app marks and place glyphs) keep their
   "cible non planifiée" / "proposition non planifiée" badges; none is implemented or phased.

## Files

| File | Bytes | SHA-256 |
|---|---|---|
| r13 `index.html`, reviewed by R5 (kept as `index.r13-reviewed.html`, read-only) | 134,477 | `d7c4803169fc6530f96850a6ae70ea63d6c9a63fc8e7b41db350e2f16ac1f595` |
| r13 `CHANGES.md` (kept as `CHANGES.r13-reviewed.md`, read-only) | 45,785 | `0a8a18ec7e4e25e71b31eb3a479de8131aebe0305d226e17c491b3c02db44c02` |
| r14 `index.html` (this folder, and the repo copy) | 135,947 | `0f9f536128b0bee98ce45f6302d2b54f2b323799e6248ba200c15f2c69c31226` |

r14 `index.html`: UTF-8 without BOM, LF only, 806 lines, 0 network references, 0 vh units.

## What changed

**S1. The banner no longer says the code simulations are never the target.**
- Banner: « jamais la cible » → « pas la cible là où elle diffère ; les états PR3a-A (5.1, 5.3–5.7)
  restent aussi le comportement voulu, 5.2 montre l'état d'avant A, remplacé ».
- **Deviation from R5's suggested wording, on purpose:** R5 wrote "5.1–5.7", but 5.2 is
  « indisponible · avant A », the base `f37d379d` state that its own caption says A replaced. Calling
  it the wanted behaviour would be a new false claim, so 5.2 is named as superseded instead.
- Section 5 gained one lead line under its `<h2>` saying the same and pointing to « tout afficher »
  and the Code livré · références view. The existing script inserts the section note before the grid,
  so the lead sits above it; no script changed.
- Mirrored in r13 §5 below ("Corrected in r14").

**S2. Panel 4.19 now bounds the history.**
- Caption clause added after « swipe arrière remonte vers les messages plus anciens »: « dans la limite
  des derniers messages capturés par Relay (réglage messages par fil) ; rien d'antérieur n'est
  disponible ».
- Kept: « Proposition à valider » on opening at the newest page, the chat sides and the HUD.
- Source facts, read in the worktree: `RelaySettings.kt` `DEFAULT_MESSAGES_PER_THREAD = 20`,
  `MIN_ = 4`, `MAX_ = 40`; `NotificationTextExtractor` keeps `takeLast(messageLimit)`;
  `NoticeSurfaceContract.MAX_BODY_CHARS = 8192`. The caption does not quote numbers, so it cannot
  drift from the setting.

**Status refresh (outside the HUD).**
- Banner headline, the banner's last status sentence, the review-history fold, and the footer now
  record R5's verdict on r13, the two fixes in r14, the bounded checks, and that R5 did not review r14.

**Not changed:** every HUD, panel id, phase, provenance, tag, the default Cible view, filters, jumps,
key screens, layout, glyphs, chat sides and the typing band. R5's optional nits (« captured » vs
« capturé », the faint received outline, sender names) are not edited.

## Checks actually run

All run on the final r14 bytes; scripts ran from stdin and wrote no file.
- **Text diff r13 → r14:** 4 hunks only:
  - lines 218–219, the status banner and its review-history fold;
  - line 311, the new section 5 lead (added);
  - line 346, the footer;
  - line 641, the 4.19 caption.
- **`node --check`** on the inline script: OK. The first attempt failed on an unescaped apostrophe in
  the 4.19 clause; it was fixed before any copy.
- **Panel data, evaluated in Node from r12 (the repo copy), r13 and r14:**
  - panels 81 / 84 / 84; the r13 and r14 id lists are identical;
  - code panels: 28; HUD HTML **28/28 exactly equal to r12**, no normalisation; tag, phase and
    provenance 28/28 equal;
  - r13 → r14: 0 tag, phase, provenance or `d` changes; captions changed: only 4.19;
  - HUD HTML: 12 map panels differ between any two loads, even r13 against itself, because the map
    `clipPath` id comes from `Math.random` (line 359). With those ids stripped, r13 → r14 HUD
    diffs are 0 of 84.
  - Section 5 provenance: 5.1–5.7 code, 5.8 target, 5.9–5.10 illustration.
- **Not run, and not needed per R5:** no render, `html_preview`, `html_render` or browser; no
  layout, contrast or view-count recount; no device or optics.

## Open questions for the owner

The five r13 questions below stand. R5 adds one, recorded here only, with no design:
6. **Group threads.** Received messages carry no sender name. That works for one-to-one; should a group
   thread show who said each message?

---

# r13 — owner target restoration (2026-10-10)

Draft only. Not reviewed, not approved. r13 answers the owner's refusal of r12. They asked for:
- icons on Notifications and Applications;
- Relay as a chat with our replies on the right;
- Reply **and** Type on the notice band when typing is enabled;
- and asked why so much had changed.

The parent acknowledged that delivered-code reference panels had contaminated the target. r13 restores
the target and makes the gallery open on it. No independent review covers r13: not round 4 (r10),
not round 3 (r9). Independent review R5, Fable review, device and optics QA, and owner visual
approval are all pending. LEGACY stays the shipped default; SESSION stays opt-in.

## Files

| File | Bytes | SHA-256 |
|---|---|---|
| Starting point r12 `index.html` (Temp `NexusLauncherMockup-icons-20261010/`, and the repo copy, both unchanged) | 119,186 | `82458d9726763d4b5a68186d57a4905c02e8c917d3df0c9c12ebc79267752720` |
| Starting point r12 `CHANGES.md` (same places, unchanged) | 31,968 | `9bea051e93830300e436f6a5679d01f7c628970802e93d941332e70ab3e89eb7` |
| r13 `index.html` (this folder) | 134,477 | `d7c4803169fc6530f96850a6ae70ea63d6c9a63fc8e7b41db350e2f16ac1f595` |

- r13 `index.html`: UTF-8 without BOM, LF only, 805 lines, 0 network references, 0 vh units.
- This folder started as a byte-exact copy of r12 (both hashes checked before any edit).
- The r12 text of this file follows below, unchanged.
- Nothing outside this folder was written:
  - the launcher-rework worktree is still at `5a05b50d`;
  - the repo mockup files still have the r12 hashes;
  - no Kotlin, asset, contract, build, device, Git or publication action.

## What changed

### 1. Hub-place glyphs on every target root

- **What changed:** Notifications gets the bell and Applications the grid, on every target root.
  That covers 2.5–2.17, including the redacted, hidden, resolved, withdrawn, aggregate, overflow and
  ended states, plus the proposal 2.18.
- **Sources:**
  - bell = `bus-client/src/main/res/drawable/ic_plugin_bell.xml` (path copied exactly; stroke 1.7 on 24);
  - grid = `ic_plugin_grid.xml`, the same path as the generic fallback (`MK.app`).
- **Treatment:**
  - 24 px, stroke only;
  - grey at rest, phosphor on the selected row (measured on 2.7: bell `rgb(113,255,151)`, grid
    `rgb(126,149,133)`);
  - class `hg`, never `am`, so they are not counted as app marks and do not change the Marques filter.
- **Code changes:**
  - `T.notifs` / `T.apps` set the new `V.row` option `hg`;
  - `accessRows` (2.5–2.7, 2.17) draws the glyph before the label.
  - `A.*` never sets `hg`, so every code simulation is unchanged.
- **Rules and captions:**
  - The identity rules gain a "Glyphe de lieu" card: a place glyph, not an app mark.
  - Activités · n has no existing glyph and was left without one (open question).
  - The captions of 2.5 and 2.18 now say these places carry their place glyph. They no longer say
    the places have no icon.

### 2. Target app lists

- 4.16 and 4.17 already carried the canonical marks (Navigation = the `ic_plugin_map` pin; Teleprompter
  and Camera = the mockup-only grid placeholder).
- 4.17, the marked counterpart of the bare delivered list 4.7, is now one of the four key screens shown
  at the top of the page.
- 4.16's caption points to 4.7 in the Code livré view and to 4.17.
- No mark data, path or battery treatment changed.

### 3. Relay target thread as a conversation

- **New block `V.chat`.** It is target-only and not the delivered renderer:
  - received messages sit on the left, ours on the right, each at most 80 % wide;
  - the bubbles are outlines only: received `--hair`, ours `--dim`, with a 4 px tail corner on the
    speaker's side. Nothing is filled; the measured background is `rgba(0,0,0,0)` on every message;
  - text is 17 px; the latest received message is phosphor;
  - one capture caption, "Messages · capturé 14:11"; no per-message time and no read or delivery mark.
- **Mock data `CH`** has an explicit role on every message (`them` or `me`):
  - `CH.mika` is the existing Mika / Moi sample (3 messages);
  - `CH.mikaLong` is a longer last page (6 messages).
- **4.9 "lire le fil · conversation"** replaces the plain document; its panel id is unchanged.
- **New 4.19 "fil long · conversation, dernière page"** (appended) shows the thread paging on the
  single swipe axis.
  - Opening on the newest page is labelled a proposal to validate.
- **D2 target lane:**
  - 4b.7 "fil exact" and 4b.10 "téléphone déconnecté" now draw the conversation; 4b.10 keeps it dimmed.
    4b.10 keeps the reading-branch structure that review round 2 verified: failure blocks, then the
    dimmed content, no rows, `tap retry`.
  - 4b.6–4b.10 get the Relay mark in their header, consistent with 4.8–4.13.
  - The identity exception card now says this; the delivered D2 panels stay text-only.
- **Honesty, in the captions:**
  - The side of each message comes from explicit mock roles.
  - For the real thread, Relay would need to supply each message's author as the notification gives
    it. That is "à vérifier, travail futur, non planifié": no SDK, wire or phase claim.
  - A message with an unknown author gets no guessed side.
  - The delivered renderer reads the thread as a document with no sides (4.6, Code livré view).
- **Badge:** `shot()` adds the badge `cv` "fil en conversation · cible non planifiée" to any panel
  drawing `.cm`. It logs a console error if a code panel ever does.

### 4. Notice band: Reply + Type when typing is enabled

- **Source facts.** Read at launcher-rework `5a05b50d` in `plugins/relay/.../RelayNoticeRuntime.kt`
  and `glasses-hub/.../NoticeController.kt`:
  - `INITIAL_ACTIONS` = Reply only.
  - `HIDDEN_ACTIONS` = Show + Reply.
  - `onAction`: Reply/Retry types when `replyByTyping()` is on, and Type types.
  - `LISTENING_ACTIONS` = Type, armed after `TYPE_CHIP_ARM_DELAY_MS` = 1,200 ms during dictation.
  - The KDoc of `INITIAL_ACTIONS` says a two-chip row "takes the directions to choose along", so the
    band could not page and a long thread was ellipsized.
  - `NoticeController.selectedActionIndex` defaults to 0, so the first action is preselected.
  - `RelayPrivacy.HIDDEN_BODY` = "New message".
- **1.8 caption** (delivered illustration, still `a` / `ill`). The old caption said "Relay n'envoie
  qu'une action" without saying it was today's behaviour. It now says:
  - **today** there is one initial action;
  - the typing setting only changes what Reply does and adds no second action;
  - Type appears after 1.2 s during dictation;
  - this is also the target when typing is disabled; with typing enabled the target is 1.11.
  - The double-tap-close and triple-tap-not-recognised text is unchanged.
- **New 1.11 "notice · Reply + Type (saisie activée)"** (appended): the owner target.
  - Two chips, Reply preselected and Type, with a one-page message.
  - The caption says:
    - with two actions the single swipe axis chooses between them, so the band no longer pages;
    - a longer message would be cut, which is the reason the code moved to one action, and it is read
      whole in the stack after a double tap;
    - the triple tap is still not recognised while the band is armed.
  - Open reading to confirm: Reply dictates and Type opens the field. Today the setting makes Reply
    itself open the field.
- **New 1.12 "notice · texte masqué, saisie activée"** (appended):
  - Show, Reply, Type, with Show preselected (first action, as today);
  - the band shows the sender and "New message";
  - what the band offers after Show is to confirm.
- **Badge:** both carry `ty` "Reply + Type · cible non planifiée". Nothing is implemented or phased.
- **Gesture table:** the armed-notice swipe cell adds the conditional target. Its "inchangé" now
  qualifies only today's behaviour.

### 5. Target-first gallery

- **New "vue" control**, outside the HUD:
  - **Cible** (default): every panel without the `code` provenance badge;
  - **Code livré · références**: exactly the 28 code simulations;
  - **Tout**.
  - It combines with the phase filter, which gains **Conversation** (`cv`) and **Reply + Type** (`ty`).
- **Section notes:** each section shows how many panels the current view hides, for example
  "7 simulations du code livré masquées dans la vue Cible", with a "tout afficher" button. References
  are never hidden silently.
- **Key screens** ("Écrans clés de la cible"), placed right after the status:
  - copies of 2.5 (root with bell and grid), 4.17 (app list with marks), 4.9 (chat) and 1.11 (Reply + Type);
  - each copy links back to its gallery panel;
  - the section is hidden in the Code livré view and under a phase filter;
  - the copies are excluded from the jump list and the filters.
- **Jump list and links:**
  - the jump list labels code panels "· code livré";
  - jumping to a hidden panel resets only what hides it: the phase filter if it excludes the panel,
    and the view to Tout if the view excludes it;
  - a section link whose section is hidden resets both.
- **Folding:**
  - the status banner keeps a short visible headline; review history and badge reading move into a
    `<details>`;
  - the Jalons legend and the Identity rule, sheet and cards are folded into `<details>`.
  - The text inside is kept (history updated for r13), so nothing is deleted.
- **Unchanged:** panel numbers, ids, phases and provenance (except the additive `cv` / `ty` keys), maps,
  HUD typography, gestures, the theme and the scale logic.
- **Corrected in r14 (R5 S1):** the status banner said the code simulations are "jamais la cible".
  That was false for the PR3a-A states the owner chose. It now says they are "pas la cible là où elle
  diffère ; les états PR3a-A (5.1, 5.3–5.7) restent aussi le comportement voulu, 5.2 montre l'état
  d'avant A, remplacé". Section 5 gained a one-line lead saying the same and pointing to "tout
  afficher" and the Code livré view. No view, filter or panel logic changed.

## Checks actually run

**Node data evaluation.** The script ran from stdin and wrote no file. It evaluated the panel data of
immutable r12 and of r13:
- Panels: r12 81 → r13 84 (added 1.11, 1.12, 4.19).
- Code panels: **28/28 byte-identical** to r12, exact string compare with no normalisation. 0 code
  panels draw `.am`, `.hg` or `.cm`.
- Tag, phase and provenance changes on existing panels: only 4.9's tag text ("lire le fil · document" →
  "lire le fil · conversation"). Its id, phase and provenance are unchanged.
- HUD changed:
  - 2.5–2.18 (place glyphs);
  - 4.9 (chat);
  - 4b.6, 4b.8, 4b.9 (Relay mark);
  - 4b.7, 4b.10 (mark + chat).
- Captions changed: 1.8, 2.5, 2.18, 4.9, 4.16, 4b.7, 4b.10.
- Bell and grid: on all 14 target roots (2.5–2.18), 28 place glyphs in total.
- Marks: 67 marks in 37 panels (r12: 61 in 31; +4b.6–4b.10, +4.19).
- Chat panels: 4.9 (3 messages), 4.19 (6), 4b.7 (3), 4b.10 (3), each with at least one `me` message.

The inline script compiles.

**Native T3 collaborative tab.** Used, as preferred. The exact file was served from a temporary
`127.0.0.1:8779` loopback server. The page itself fetched its source and hashed it: `d7c48031…`, the
r13 hash, 134,477 B. The tab is closed and the server stopped: 0 listeners confirmed.

At 360 px:
- **Default view:** Cible, 56 of 84 panels visible, 0 code panels visible, 4 key screens.
- **Views:** Code livré shows 28 panels with 0 targets, and the key screens are hidden; Tout shows 84.
- **Section notes:** 4 / 3 / 7 / 7 / 7 hidden code panels (stack, nav, relay, D2, states).
- **Layout,** in auto (0.743 / 0.5), ½ (0.5 / 0.5) and clamped 1:1 (0.743 / 0.689):
  - scrollWidth 360;
  - 0 wrap cuts, 0 HUD elements outside a frame, 0 clipped `.vp`, 0 ellipsis cuts;
  - headers all 54 px and hints all 40 px.
- **Chat:**
  - every received message touches the viewport's left edge and leaves at least 40 px on the right;
    every `me` message is the mirror;
  - none extends below the viewport; font 17 px; 0 filled backgrounds.
- **Jumps:**
  - to 4.7 (code) from the Cible view: the view switches to Tout and the panel lands at top 12 px;
  - to 4.9 from Code livré + PR5: both filters reset and the panel is visible;
  - the key-screen link to 4.9 lands at 12 px.
- **Filters:** Conversation = 4.9, 4.19, 4b.7, 4b.10; Reply + Type = 1.11, 1.12; Marques = 37 under Cible.

At 728 px:
- **Themes:** light, which is the standalone fallback, and dark, simulated by setting `color-scheme:
  dark` plus approximate T3 variables on the root.
- **Layout:** scrollWidth 728; 0 cuts, outside elements, clipped `.vp` or ellipsis; 0 chat overflow.
- **Scales:** auto 0.75 / 0.5, ½ 0.5, 1:1 1.0. In the Cible view only the target D2 lane shows.
- **Contrast of the new outside elements:**

| Element | Light | Dark |
|---|---|---|
| `.ph.cv` / `.ph.ty` | 7.56 | 11.08 |
| section notes and their button | 6.31 | 7.76 |
| fold summaries | 6.31–12.74 | 7.76–15.73 |
| mode buttons | 6.31 | 7.76 |
| key-screen links | 5.38 | 15.59 |

**Screenshots inspected:**
- key 2.5 at 360, 1:1: bell and grid aligned, grey at rest;
- key 4.9 at 360, 1:1: received left, ours right, readable, nothing clipped;
- 1.11 at 728, dark: Reply selected and Type;
- the 4.19 meta row.

**Limits:**
- `html_preview` was not used. It needs the 134 KB file pasted inline; the native tab rendered the
  exact bytes, confirmed by the in-page hash.
- Dark-theme variables were approximated, not injected by T3.
- No device or optics: 17 px and 1.5 px outlines are CSS-px simulations.
- `html_render` was not published.

## Open questions for review and the owner

1. **Reply + Type semantics.** Reply dictates and Type types, so the setting would change meaning. Is
   that the owner's intent? What does the band offer after Show (1.12)?
2. **Long thread.** Does it open on its newest page (4.19)?
3. **Activités · n** has no existing glyph. Leave it bare, or choose one?
4. **Applications grid vs generic fallback.** Both use `ic_plugin_grid`, so the Agenda preview card
   (2.9, 2.12) and Applications show the same grid. Is that acceptable?
5. **Chat roles in the real thread.** Can Relay supply a reliable self/other author per message? To be
   verified; no phase is assigned.

---

# r12 — factual status refresh and local repo copy (2026-10-10)

Draft only. Not approved. r12 is r11 with only the outside-HUD status banner and footer refreshed to
record what actually happened. It is then copied, uncommitted, to `docs/mockups/glasses-home/` in the
launcher-rework worktree. No panel, HUD, template, gesture, map, phase or provenance changed.

**History, as it happened:**
1. Independent Opus mockup review round 4 reviewed r10 and returned "needs fixes": two must-fixes
   (Navigation's mark, Vivo Promo's source) and two should-fixes (Rokid native-app placeholder
   wording, runtime-work wording).
2. Opus applied them in r11, plus the optional battery correction, the optional 4.15 header-mark
   removal and two optional copy clarifications.
3. The parent ran the bounded light re-check that round 4 explicitly permitted: all four findings are
   resolved. This is not a new independent review. Round 4 did not review or approve r11.
4. The parent accepted the 4.15 header-mark removal. The glasses system is the hub itself, not an
   identified plugin, so it follows the documented no-mark rule for hub places and restores r9's 4.15
   HUD exactly.

**Status:** ready for the provisional owner visual review. Fable review, device and optical QA and
owner visual approval remain pending: "rien ici n'approuve le mockup". LEGACY remains the shipped
default; the PR3a SESSION backend stays opt-in. The existing source-code approval from the Opus R4 code
review is unchanged. No phase is assigned to app marks.

**Current counts (r11 = r12, since r12 changes no panel):** 81 panels; 28 code-simulation HUDs,
byte-identical to r9 and drawing no mark; 31 marked panels, 61 marks; the **Marques** filter shows 31
panels. Sections further down that give 32 panels / 63 marks are historical r10 evidence and are
labelled as such.

## Parent bounded verification of r11 (as reported by the parent)

Input: r11 `index.html` 118,754 B `5f804dacd866b9ca6d5a76f83064fcddb023eec3254b8606d7be1782d8562f99`,
`CHANGES.md` 25,818 B `a4bc77a0457bc88c315b9375a9edb0e73abad91051004107ff1df8a05822d69a`.

- The three original inline scripts were evaluated in a fresh JavaScript function, stopped before DOM
  rendering, with a deterministic `Math.random` only to fix map clip ids. HUD strings were compared
  exactly, without normalisation:
  - 81 panels, 28 code panels, 28/28 equal to r9, no code panel drawing a mark;
  - Navigation draws the exact pin; `MK_SRC.nav` cites `bus-client/src/main/res/drawable/ic_plugin_map.xml`
    (`ICON=map`, a built-in key both hubs show today before `ICON_DRAWABLE`; `nexus_glyph_route`
    stays the unknown-manoeuvre glyph);
  - CSS exactly r10; no metadata change; HUD changes beyond the pin only in 4.14 and 4.15; caption
    changes in 2.18, 3.6, 4.14, 4.15, 4.16 and 4.17;
  - 61 marks in 31 panels; D2 and the system detail exactly r9; the Vivo row uses the fallback, with
    its source stated as unidentified; same line count, 21 changed lines.
- All eight `MK` geometries are string-equal to the paths in their cited XML resources.
- The phone resolver source (built-in, then custom, then legacy/fallback), native icon retrieval via
  `PackageManager.getApplicationIcon` and the legacy renderer uses were inspected. Captions, the
  identity lead and the runtime-work copy describe them correctly.
- One `html_preview` of the exact r11 plus a temporary inspection payload, 360 px, dark, auto scale:
  81 panels, 61 marks in 31 panels, 0 code marks, no console errors, `pageOverflow` false,
  `frameCuts` []. A screenshot of 2.18, 4.14 and 4.15 was inspected.
- No new contrast recount and no device or optics claim. r11's CSS is byte-identical to r10, so the
  r10 four-configuration layout, scaling and contrast checks further down remain applicable. Those
  renders were of r10, not of r11.
- Source HEAD `5a05b50d`. Worktree status: only the r9 HTML modification plus the untracked mockup
  `CHANGES.md` and contract. r9 HTML `7392f701…`, r9 CHANGES `ad5e4ec2…` and contract `94fbf1f8…`
  were unchanged before promotion.

## r12 changes and files

- `index.html` r11 → r12: outside-HUD copy only, 2 changed lines. Line 195 (status banner) gets a new
  bold heading and a rewritten round-4 sentence; line 309 (footer) gets a new parenthetical. Script and
  style are byte-identical to r11.
- This report: the r12 sections above, plus labels in the r11 and r10 sections below marking stale
  status lines and r10 counts as historical. The rest of the report is unchanged.
- r11 is preserved in the Temp folder as `index.r11-verified.html` (118,754 B, `5f804dac…`) and
  `CHANGES.r11-verified.md` (25,818 B, `a4bc77a0…`). The r10 backups are untouched.
- The r9 files in the worktree are replaced by this r12 pair, uncommitted. No git action, build,
  device or publication.

---

# r11 — bounded fixes from independent mockup review round 4 (2026-10-10)

Draft only. Not approved. Round 4 reviewed r10 and returned "needs fixes" with two must-fixes and two
should-fixes, all data and copy. r11 applies all four plus the optional battery correction and two
optional copy clarifications. No redesign, no layout or CSS change, no new phase, no runtime icon work.
When delivered, these corrections awaited the parent's verification; it has since been done (see r12
above). Round 4 did not review or approve this corrected version. Fable review, device QA and owner visual approval remain
pending; LEGACY remains the shipped default.

## Fixes

1. **Navigation's mark is now the pin both hubs show (must-fix 1).**
   - `MK.nav` = the path of `bus-client/src/main/res/drawable/ic_plugin_map.xml`, byte-equal:
     `M12,21 c-4,-5 -6,-8 -6,-11 a6,6 0 0,1 12,0 c0,3 -2,6 -6,11 Z M12,7.8 a2.2,2.2 0 1,0 0,4.4 a2.2,2.2 0 1,0 0,-4.4`
     (24 viewport, stroke 1.7, round caps and joins).
   - `MK_SRC.nav` cites that file and "ICON = map, built-in key, shown by both hubs today, before
     ICON_DRAWABLE (nexus_glyph_route, which stays the unknown-maneuver glyph)".
   - The identity lead and the swatch sheet state the real order (phone: built-in key → ICON_DRAWABLE →
     legacy → grid; glasses launcher: built-in key → `nexus_glyphs` entry named by ICON → legacy →
     grid). Relay's and Assistant's swatch text now says their ICON key is not built-in.
   - The state-glyph rule card says the route is Navigation's unknown-maneuver glyph, not its mark.
   - The 2.18 caption says "l'épingle de Navigation" instead of "la route de Navigation".
   - Every panel drawing the Navigation mark picks up the new path through `MK`; no panel code changed.
2. **"Vivo Promo · image" is no longer Relay (must-fix 2).** The 4.14 row uses `k:'app'`. The caption
   says its source is not identified in the example, so it gets the generic fallback, and says "Relay
   pour Mika et Alan". The "assumption to confirm" line is removed from this file.
3. **Rokid native apps: the grid is a placeholder (should-fix 3).** The fallback rule card, the 4.16
   and 4.17 captions and `MK_SRC.app` say the mockup has no copy of their icon and that the shipped
   launcher draws each app's own icon. Open question 1 now asks: own full-colour icon, as in the
   legacy launcher, or a monochrome stand-in on the additive HUD?
4. **Runtime work wording (should-fix 4).** The rule card and "Expected runtime work" say the shipped
   glasses launcher already resolves these marks, and that the remaining work is internal plumbing into
   `SessionScreenView`. No phase is attached.

**Optional items applied.**
- Battery (4.14 nit): the "Lunettes · 20 %" row has r9's battery state glyph again (markup identical
  to r9), not the plugin grid. For consistency, 4.15's header mark was also removed, since the glasses
  system is the hub, not a plugin; 4.15's HUD is byte-identical to r9 again. As a result 4.15 no
  longer carries the "marques d'app" badge: **31 marked panels, 61 marks** (r10: 32 / 63). The Marques
  filter follows automatically.
- 3.6 caption: repeats that the fullscreen map carries no app mark, as in 3.5.
- D2 rule card: "if marks are adopted, D2 pages get them like 4.9–4.13". No D2 HUD drawing changed.
- Status banner and footer (r11 wording, superseded by the r12 refresh): they said round 4 asked for
  bounded fixes, that they were applied and awaited verification, and that round 3 covered only r9. "rien ici n'approuve le mockup" is kept.

## Checks actually run (r11)

- Input hashes matched the brief before editing. r10 preserved as `index.r10-reviewed.html` and
  `CHANGES.r10-reviewed.md` (same hashes as the inputs).
- Edits applied by a Node script with exact-match replacements, each asserted to match once.
- `ic_plugin_map.xml` read at `5a05b50d`; `MK.nav` is byte-equal to its `pathData`. Resolver order read
  from `NexusPluginIcons.resolveWithLoaders` and `GlassesHub.launcherDrawable`; `ICON=map` and
  `ICON_DRAWABLE=@drawable/nexus_glyph_route` read from `plugins/nav/src/main/AndroidManifest.xml`;
  `ICON=relay` / `ICON=assistant` are not in `drawableForBuiltIn`.
- `node --check` on the extracted script: OK. `<style>` byte-identical to r10. UTF-8 without BOM, LF
  only, 703 lines, 0 vh units, 0 network references.
- Node evaluation of the panel data of r9, r10 and r11 (seeded random for map clip ids):
  - 81 panels; no tag, phase or provenance change against r10;
  - all **28/28** `code`-provenance panels have HUD HTML byte-identical to r9 (raw string match), and
    0 of them draw a mark;
  - every HUD difference against r10, once the old Navigation path is swapped for the new one, is
    limited to 4.14 (Vivo row, battery row) and 4.15 (header);
  - 4.14: the Vivo row draws the `app` path and not the Relay path; the Lunettes row has no `.am`
    mark and its glyph markup equals r9's;
  - captions changed: 2.18, 3.6, 4.14, 4.15, 4.16, 4.17.
- `diff` r10 → r11 touches only lines 195 (status), 232 (lead), 237–240 (four rule cards), 309
  (footer), 351 (`MK.nav`), 361–363 and 368 (`MK_SRC`), 501 (2.18 caption), 517 (3.6 caption),
  565–569 (4.14 rows, row renderer, captions, 4.15), 573 (4.16 caption), 576 (4.17 caption).
- A grep finds no remaining "ICON_DRAWABLE, sinon", "aucune marque disponible", "non revue", "la route
  de Navigation" or "Relay pour les messages" in the HTML.

**Not done:** no `html_preview` render this round (pure data and copy; no CSS or layout change; the
pin sits inside the same 24 viewport as the route). No device, no Fable review, no `html_render`, no
repo copy, no git action.

---

# Glasses-home mockup — app-identity retouch (2026-10-10)

Draft only. Not approved. This retouch answers the owner's feedback on r9:
"bah ya plein d'écran sans icône des app, c'est normal ? genre dans des écrans y a les icônes et
d'autres non." It applies one app-identity rule across the target panels and adds three proposal
counterparts. Independent Opus mockup review round 4 examined the first version (r10) and asked for
bounded data and copy fixes; r11 applies them (see the r11 section above). The parent's bounded verification of r11 is done (see
r12 above); r11 is ready for the provisional owner visual review. Round 4 did not review or approve r11. The
Opus mockup review round 3 covered r9 only. Fable review, device QA and owner visual approval are all
still pending, and LEGACY remains the shipped default.

## Files

| File | Bytes | SHA-256 |
|---|---|---|
| Starting point: r9 `docs/mockups/glasses-home/index.html` in the launcher-rework worktree (read only, unchanged) | 102,210 | `7392f7012f50b99be8336b7d4ab7b584d09f374d2aca89ec4ab9426ffbfe821d` |
| r9 `CHANGES.md` (read only, unchanged) | 25,735 | `ad5e4ec26a59d15115b90d90855a1bd2953006b767770ac2e5f070749b048cbf` |
| r10 draft, reviewed by round 4, preserved as `index.r10-reviewed.html` (Temp only) | 116,472 | `4567768709d9d8c6e521493ed6c9b2722a3250ddded92917eb5aa7bfb0dddd0e` |
| r10 `CHANGES.md`, preserved as `CHANGES.r10-reviewed.md` (Temp only) | 17,508 | `7b3b6b2fa6ccab3161e8570fd64f2e0811e89987b0c7140c8919ff9fa183a33c` |
| r11 as delivered and parent-verified, preserved as `index.r11-verified.html` (Temp only) | 118,754 | `5f804dacd866b9ca6d5a76f83064fcddb023eec3254b8606d7be1782d8562f99` |
| r11 `CHANGES.md`, preserved as `CHANGES.r11-verified.md` (Temp only) | 25,818 | `a4bc77a0457bc88c315b9375a9edb0e73abad91051004107ff1df8a05822d69a` |
| Current r12 `index.html` (Temp draft and the uncommitted repo copy are byte-equal) | 119,186 | `82458d9726763d4b5a68186d57a4905c02e8c917d3df0c9c12ebc79267752720` |

- The new `index.html` is UTF-8 without BOM, LF only, 703 newline-terminated lines.
- One mockup `<style>`: FNV-1a `67e64c6a`, 16,693 chars (r9: `5be116e6`).
- One mockup `<script>` (r10 value; r11 changed data and copy, r12 is byte-identical to r11): FNV-1a `19ba0681`, 77,021 chars (r9: `489dc457`).
- 0 network references, 0 vh units, no external assets. The marks are inline SVG paths.
- Nothing in the repo changed during r10 and r11 (r12 is the local copy). Checked after the work:
  - the worktree is still at `5a05b50d`;
  - its `index.html` and `CHANGES.md` still have the r9 hashes above;
  - the main checkout is still on `dev/reddit-patcher-ui` with the same untracked files as before.
- No git action, build, Gradle, device, ADB, install, publish or push.

## Diagnosis (why some screens had icons and others did not)

- The PR3a code simulations are text only. `SessionScreens` (`ScreenHeader`, `Card`, `Row`) and
  `SessionScreenView` render TextViews and no icons, so those panels are faithful as they are.
- The target root panels built from `T.*` reused the text-only `V.card`, so they had no app identity.
- Other target panels used generic glyphs from the mockup's own `I` set (eye, star, music note,
  single bubble, grid, camera). These are not the apps' real marks:
  - Assistant and Feeds shared the same star.
  - Relay's single bubble is not Relay's two-bubble mark.
  - Media's music note is not Media Deck's disc.
- Hub places also carried icons inconsistently. Notifications had a bell and Applications a grid in
  2.5–2.7 and 2.17, but not in the `T.root` panels.

## The rule (shown in the HUD-free "Identité des apps" section and its rule cards)

**A mark says who is speaking; a glyph says what is happening.** This is the codebase's own split
(`bus-client/.../ui/NexusGlyphs.kt` KDoc: "A plugin icon says *who is speaking*… a glyph says *what
is happening right now*").

**Gets an app mark:**
- activity cards and the Relay card at the root;
- the notice preview card (mark of its source);
- the header of a page that belongs to one app;
- Applications entries;
- the source of each row in the mixed Notifications collection.

**Never gets an app mark:**
- hub places: NEXUS, Activités, Notifications, Applications;
- people: Mika and Alan get no logo and no invented avatar;
- commands: Commandes, Retry, Back, Répondre, Garder;
- map views.

A list that belongs to one app carries the mark once, in its title (see 4.18).

**State glyphs are unchanged.** The maneuver arrow, train, walk, lost-GPS, arrival tick, battery,
keyboard and microphone still describe what is happening. Navigation's `route` path is the plugin's
guidance glyph for an unknown maneuver (per `plugins/nav/.../values/nexus_glyphs.xml`). Since r11 it
is not used as Navigation's mark: the mark is the pin both hubs already display (see provenance).

**Treatment, measured in the r10 renders (historical; the CSS is unchanged in r11 and r12):**
- Stroke only, width 1.7 on the 24-unit viewport, round caps and joins (the `GlyphDrawable` style). Never filled.
- Sizes in HUD px: 18 in a root card's label row, 20 in a page header, 24 in a list row. At the
  clamped 1:1 scale on a 360 px viewport they render at 13.4 / 14.9 / 17.8 CSS px.
- Colour: grey (`--mute #7E9585`, 6.52:1 on black) at rest; phosphor (`#71FF97`, 16.45:1) on the
  selected card or row and in headers; grey on an ended card.
- Type sizes are unchanged. The header stays 54 px and the hint 40 px.
- The 18 px mark fits the existing 18 px label line, so card heights are unchanged (measured below).

## Asset provenance (paths in the launcher-rework worktree, read only)

Each path was copied path for path into `MK` in the script. The manifest declarations were read from
each plugin's `AndroidManifest.xml` (`com.anezium.rokidbus.plugin.ICON` / `ICON_DRAWABLE`).

**Real resolver order (corrected in r11).** A built-in `ICON` key wins on both hubs:
- phone (`bus-client/.../ui/NexusPluginIcons.kt`, `resolveWithLoaders`): built-in `ICON` key →
  `ICON_DRAWABLE` → legacy table by plugin id → `ic_plugin_grid`;
- shipped glasses launcher (`glasses-hub/.../GlassesHub.kt:588-598`, `launcherDrawable`): built-in key →
  the plugin's `nexus_glyphs` entry named by `ICON` (`PluginGlyphCache`) → legacy table → grid.

Each mark below is therefore the one the hubs draw today for that plugin.

| Key | App | Source asset | How the plugin declares it |
|---|---|---|---|
| `nav` | Navigation | `bus-client/src/main/res/drawable/ic_plugin_map.xml` (the pin, `M12,21 c-4,-5 …`) | `ICON=map`, a built-in key, shown by both hubs today (phone list, Store, permissions, shipped glasses launcher). The manifest also declares `ICON_DRAWABLE=@drawable/nexus_glyph_route`, but the built-in key wins, so the route is never drawn as Navigation's icon; it stays the unknown-maneuver glyph. |
| `relay` | Relay | `plugins/relay/src/main/res/drawable/nexus_glyph_relay.xml` (two stroked paths) | `ICON=relay` is not a built-in key, so the phone falls through to `ICON_DRAWABLE=@drawable/nexus_glyph_relay` and the glasses launcher to the `relay` entry of the plugin's `nexus_glyphs`. The same geometry is in `plugins/relay/.../values/nexus_glyphs.xml`. This is the additive HUD glyph, **not** the filled phone icon `nexus_icon_relay.xml`. |
| `assistant` | Assistant | `plugins/assistant/src/main/res/drawable/nexus_glyph_assistant.xml` (head, shoulders, glint) | `ICON=assistant` is not a built-in key, so the phone falls through to `ICON_DRAWABLE=@drawable/nexus_glyph_assistant` and the glasses launcher to the plugin's `nexus_glyphs` entry. The same geometry is in `values/nexus_glyphs.xml`. This is **not** `nexus_icon_assistant.xml`, whose glint is filled. |
| `lens` | Lens | `bus-client/src/main/res/drawable/ic_plugin_lens.xml` | `ICON=lens`. The geometry equals the group in `plugins/lens/.../ic_launcher_foreground.xml` (108 viewport, scale 2.7, translate 21.6 maps back to 24). |
| `media` | Media Deck | `bus-client/src/main/res/drawable/ic_plugin_disc.xml` | `ICON=disc`. It equals the group in `plugins/media/.../ic_launcher_foreground.xml`. |
| `agents` | Agents | `bus-client/src/main/res/drawable/ic_plugin_terminal.xml` | `ICON=terminal` in `plugins/agents/src/main/AndroidManifest.xml` |
| `feeds` | Feeds | `bus-client/src/main/res/drawable/ic_plugin_send.xml` | `ICON=send` in `plugin-feeds/src/main/AndroidManifest.xml`. It equals the `plugin-feeds` launcher foreground. |
| `app` | **Generic fallback, not an app mark** | `bus-client/src/main/res/drawable/ic_plugin_grid.xml` | `NexusPluginIcons.drawableForLegacyPlugin` returns it for any plugin without an icon. |

**Explicit fallbacks and placeholders (corrected in r11).**
- Generic fallback `app`, name beside it: the 4.14 row "Vivo Promo · image" (source not identified in
  the example) and the Agenda notification source (no plugin identified behind it).
- Placeholder `app` for Teleprompter and Camera/Caméra (Rokid native apps): the mockup has no copy of
  their icon. The shipped glasses launcher already draws each native app's own Android icon
  (`NativeAppsController.icon` → `PackageManager.getApplicationIcon`, `NativeAppsController.kt:121`,
  used at `LauncherOverlayRenderer.kt:92,365`).
- The glasses' system notifications ("Lunettes") get no app mark: the glasses system is the hub
  itself, not a plugin. 4.14 keeps r9's battery state glyph on that row, and 4.15 has no header mark.

No third-party brand is drawn or claimed. "Google Maps" stays a text fact in the Navigation summary,
and the Navigation card carries Nexus Navigation's own mark.

Mika (the Relay notice) and Alan ("TRANSMISE", the Relay reply handoff) are corroborated elsewhere in
the mockup and keep Relay. A shared generic message glyph in r9 is not evidence of a source, so
"Vivo Promo" is no longer attributed to Relay.

## Before and after coverage

**Before (r9):**
- 0 panels drew a canonical app mark.
- 10 panels drew 24 px generic glyphs from `I` in identity positions or on hub places: 1.4, 2.5, 2.6,
  2.7, 2.17, 3.4, 4.8, 4.10, 4.14 and 4.16. In 4.10 they are the keyboard and mic mode glyphs, which
  are kept.
- All `T.root` targets (2.8–2.16) and all target page headers had no identity.

**After, HISTORICAL r10:** 32 panels drew marks, 63 marks in total. These colour and context counts
were measured in the r10 renders:

| Context and size | State | Count |
|---|---|---|
| Header, 20 px | phosphor | 14 |
| Card label, 18 px | selected | 10 |
| Card label, 18 px | at rest | 15 |
| Card label, 18 px | ended | 3 |
| Row, 24 px | selected | 3 |
| Row, 24 px | at rest | 17 |
| Lens immersion illustration, 64 px | phosphor | 1 |

**Current r11/r12:** 31 panels, 61 marks. The category counts are derived from r10's minus the two
marks r11 removed (the 4.15 header mark; the 4.14 glasses-system row, back to the battery state glyph),
not re-measured per category: header 13, card label selected 10 / at rest 15 / ended 3, row selected
3 / at rest 16, Lens illustration 1.

**The 32 r10 panels (historical):** 1.4, 2.5–2.18, 3.4, 3.7, 3.8, 3.9, 4.8–4.18, 5.8 and 6.3.
**The current 31 panels:** the same without 4.15: 1.4, 2.5–2.18, 3.4, 3.7, 3.8, 3.9, 4.8–4.14,
4.16–4.18, 5.8 and 6.3.
- The only generic `I` glyphs left are state glyphs: 4.10 keyboard and mic, the 4.15 battery (since r11
  also the 4.14 glasses-system battery) and the ended-arrival ticks in 1.10 and 2.17.
- Each marked panel (r10: 32; current: 31) shows the new outside-HUD badge `marques d'app · proposition non planifiée`
  (phase key `id`).
- `shot()` adds that badge automatically to any panel whose HTML draws an `.am` mark. It logs a
  console error if a `code`-provenance panel ever draws one; none did in the four r10 renders, nor in the parent's r11 preview.
- A new phase-filter button **Marques** shows exactly the marked panels: 32 in r10, 31 now.

## Changes by panel (numbers and anchors stable; new panels appended)

| Panel | Change |
|---|---|
| 1.4 home · 3 résidents (target) | The Media Deck chip's music note becomes the Media Deck disc. |
| 2.5–2.7, 2.17 (target custom root) | Navigation and Relay card labels get their marks. The ended Navigation card's mark is grey. The bell and grid on the Notifications / Applications access rows are removed (hub places). The 2.5 caption states the rule. |
| 2.8–2.16 (`T.root` targets) | Every Navigation, Media Deck and Relay card gets its mark, including the resolved, withdrawn, aggregate, hidden and redacted states. The Agenda preview (2.9, 2.12) gets the generic fallback. |
| **2.18 new** — proposition · racine livrée avec marques | Counterpart of 2.2: same data, order and English code labels, with marks added. The notice preview carries its source's mark (Relay). |
| 3.4 compact map page | The Navigation mark is in the header. The `I.grid` before "Commandes" is removed (a command). The caption states it. |
| 3.5 fullscreen map | No mark, deliberately: nothing overlays the map. The caption states it. 3.6 is unchanged. |
| 3.7, 3.8, 3.9 | The Navigation mark is in the header. |
| 4.2 | The caption points to 4.17. The HUD is unchanged (code simulation). |
| 4.8 | The Relay mark is in the header. The `I.msg` bubble beside "Mika" is removed (a person, no avatar). The caption states it. |
| 4.9–4.13 | The Relay mark is in the header. 4.12 and 4.13 use `TS()`, the same page as `S()` with a header mark. |
| 4.14 collection | r11: Relay ×2 (Mika, Alan), Feeds, the fallback ×2 (Agenda, Vivo Promo with an unidentified source), and r9's battery state glyph on the glasses-system row. The header has no mark (hub place). |
| 4.15 expired detail | r11: no header mark (the glasses system is the hub, not a plugin); its HUD is byte-identical to r9 again. The battery stays as the state glyph inside the card. |
| 4.16 applications (target) | Lens / Agents / Assistant / Feeds / Media Deck get their declared marks; Teleprompter / Caméra get the grid as a placeholder (the shipped launcher draws their own icon). Before this, Assistant and Feeds shared one star. |
| **4.17 new** — proposition · applications livrées avec marques | Counterpart of 4.7: the same six rows and English labels, with a 24 px mark before each name. |
| **4.18 new** — proposition · liste Relay, marque au titre | Counterpart of 4.5: the Relay mark once, in the header. The Mika and Alan rows are unmarked. |
| 5.8 Lens immersion | The Lens mark is in the header. The large 64 px eye is now the canonical Lens path, not the near-identical generic `I.eye`. |
| 6.3 transit ribbon page | The Navigation mark is in the header. The train stays the state glyph. |

**Unchanged, deliberately:**
- All 28 code simulations.
- The D2 lanes, 4b.1–4b.12. The targets there reuse the PR3a-A renderer ("no new renderer"), so they
  stay text only; the rule card says so.
- The ambient islands with maneuver or transit glyphs (1.1–1.3, 1.5–1.10, 5.9, 5.10, 6.1, 6.2, 6.4).
- The armed and passed notice bands 1.8 and 1.9 (delivered-behaviour illustrations).
- The gesture tables.

**Outside the HUD:**
- New nav link "Identité".
- New section "Identité des apps":
  - a lead paragraph;
  - a swatch sheet that shows each mark at 24 px phosphor and 18 px grey on black tiles, with its
    source path and declaration;
  - six rule cards.
- A new legend entry for the badge.
- The status banner is rewritten. It no longer reuses the r9 "ready for provisional owner review"
  claim. It says the retouch is unreviewed, that round 3 covered only the previous version, and that
  nothing approves the mockup.
- The footer is updated.

## Code simulations unchanged (checked)

- `V.card` and `V.row` only emit a mark when the new optional `mk` field is set; `A.*` never sets it.
- A Node check evaluated the panel data of r9 and of the new file (map clip ids fixed) and compared
  each panel's HUD HTML. All **28/28** `code`-provenance panels are byte-identical to r9.
- Panels whose HUD HTML changed: 1.4, 2.5–2.17, 3.4, 3.7–3.9, 4.8–4.16, 5.8 and 6.3.
- No panel's tag, phase list or provenance changed. Captions edited: 2.2, 2.5, 3.4, 3.5, 4.7, 4.8,
  4.14, 4.15, 4.16 and 5.8.
- Panel count: 81 (r9: 78; plus 2.18, 4.17, 4.18).

## Expected runtime work (future, not done, not claimed)

The shipped glasses launcher already resolves these marks: `GlassesHub.launcherDrawable` uses the
`iconKey` it receives (`GlassesHub.kt:592`, `:1186`), built-in resources for Navigation (`map`), Lens,
Media Deck, Agents and Feeds, and `PluginGlyphCache` for Relay's and Assistant's custom glyphs. It also
draws native apps' own icons. The remaining work is internal plumbing: carrying those marks into the
session renderer (`SessionScreenView`: `ScreenHeader`, `Card`, `Row`) and drawing them in the single
`GlyphDrawable` style, with the same resolver order as the launcher (built-in key → `nexus_glyphs`
entry named by `ICON` → legacy → grid).

- This adds no SDK capability, no wire field and no public asset; plugins already declare these marks.
- **To verify when the work is done:** the exact internal path into `SessionScreenView`.
- This presentation is attached to no phase. A phase owner decides when, or whether, it lands.
  PR3a/PR3b/PR4/PR5 contracts are unchanged.

## Checks actually run (HISTORICAL r10: these four renders were of r10, not r11)

1. **Node checks.**
   - `node --check` on the extracted script: OK.
   - The panel-diff script described above: 81 panels, 28/28 code panels identical, no tag, phase or
     provenance change.
   - FNV-1a of the style and script, as listed in Files.
2. **`html_preview`, four runs.** Each run sent the whole file inline, plus a temporary probe script
   in the tool payload only; nothing was written to the file. There was no loopback server and no
   SOCKS. Runs 2–4 fingerprinted the mockup's own `<style>` and `<script>` and matched
   `67e64c6a` / `19ba0681` exactly. Run 1's probe hashed T3's injected style first, so its byte check
   was invalid, but its layout results matched runs 2–4.

**Scale and layout per run.** In every run and every scale mode, `docSW` and `bodySW` equal the
viewport, with 0 wrap cuts and 0 clipped `.vp`.

| Run | Auto (grid / lane) | ½ | 1:1 (grid / lane) |
|---|---|---|---|
| light 728 | 0.75 / 0.5 | 0.5 / 0.5 | 1.0 / 1.0 |
| dark 728 | 0.75 / 0.5 | 0.5 / 0.5 | 1.0 / 1.0 |
| light 360 | 0.743 / 0.5 | 0.5 / 0.5 | 0.743 / 0.689 (clamped) |
| dark 360 | 0.743 / 0.5 | 0.5 / 0.5 | 0.743 / 0.689 (clamped) |

**Results that held in all four runs:**
- 0 HUD elements outside their 480×640 frame.
- 0 ellipsis cuts.
- 81 panels, 0 without a phase, 32 with the `id` phase (r10; 31 in r11/r12), 0 code panels with a mark.
- Every header 54 px and every hint 40 px.
- Card heights 2.2 = 2.18 = 112/112/73; row heights 4.7 = 4.17 = 46/46/60/60/46/46; 4.5 = 4.18 = 60/60.
- Card label line 18 px with or without a mark; custom `.lbl` rows 28 px with or without a mark.
- Transparent `html`/`body`; 0 `h1` or `header`.
- The **Marques** filter shows exactly 32 panels (r10; 31 in r11/r12).
- Jump to a filtered-out panel (4.18 under PR3a, 2.18 under PR3a-A, 4.17 under PR3b) resets the
  filter to "Tout" and lands at top 12 px.

**Mark overlap.** Runs 3 and 4 used a true rectangle-intersection test between each mark and its
siblings: 0 overlaps in all modes. Run 1 used a looser left-edge test and reported 1, almost certainly
the 64 px inline Lens illustration above its block caption (not a real overlap). Run 2's sibling test
was wrong (flex items always compute as `block`) and is disregarded.

**Contrast** (WCAG, outside-HUD text, 757 elements):

| Theme | Minimum | Below 4.5 | New elements |
|---|---|---|---|
| light | 4.62 (the r9 `span.num` minimum, unchanged) | none | `.ph.id` 6.71, `.ids code` 4.83, status link 4.78 |
| dark | 4.85 | none | `.ph.id` 10.03, `.ids code` 4.85, status link 14.12 |

On black, the marks measure PH 16.45:1 and MUTE 6.52:1.

3. **Screenshots inspected:**
   - light 728: status, identity sheet and rules, 2.2, 2.5, 2.18, 3.4;
   - dark 728: 2.10, 2.14, 3.5, 4.14, 4.16, 5.8;
   - light 360 at clamped 1:1: 2.18, 3.4, 4.8, 4.17;
   - dark 360 at clamped 1:1: 2.2, 2.14, 4.14, 4.18, 6.3.

   Marks are legible, aligned on the label or name baseline, grey at rest and bright when selected. No
   clipping, no pushed hints, pure black HUD. The run 1 screenshot forced a 2-column grid and showed
   an overflow in that layout; it is a screenshot-only override, not the page, whose layout metrics
   are above.

**Limits:**
- No device and no optics: sizes are CSS px simulations.
- I made no claim about how a 13–18 px stroke mark reads on the additive display.
- `html_render` was not published.
- The native `<select>` label truncation at 360 px is unchanged.

## Open questions for review and the owner

1. Rokid native apps: the app's own full-colour icon (as in the legacy launcher), or a monochrome
   stand-in on the additive HUD? The grid in the mockup is only a placeholder.
2. Should the Agenda / calendar notification source map to a plugin (for example Assistant
   reminders)? The mockup does not decide it, and the fallback is used.
3. Should the notice-band headers (1.8 / 1.9 `RELAY · MIKA`) get the mark too? They are delivered
   illustrations, so they are left unchanged here.
4. Which phase, if any, should own hub-drawn app marks in the session renderer?
