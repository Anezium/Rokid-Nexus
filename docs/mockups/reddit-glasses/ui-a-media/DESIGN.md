# A Ledger with inline media: design proposal (round 3)

Status: design proposal for user approval, 2026-10-08. `index.html` in this folder is
one self-contained HTML/CSS/JS document (fictional data, inline monochrome SVG art, no
network). Nothing here is built on Android; nothing validates the official Reddit app,
its APIs, physical R08 input or the optics of the green display. The earlier prototypes
in `../index.html` and `../ui-variants/` remain unchanged.

## Provenance

- Round 2: Claude Fable 5.1 wrote the complete prototype in delegated task
  `reddit-glasses-fable-a-media-20261008-round2`; that task ended with
  `Claude API rate limit reached` before any design notes were delivered.
- Parent review: on 2026-10-08 the parent inspected the file in T3 native preview,
  fixed one null dereference in the full-screen media action menu (optional post
  actions were filtered without a null guard), and recorded the observations kept
  below under "Parent checks". The parent also objected that the "Tap opens media"
  comparison was a two-tap menu route, not a direct tap.
- Round 3 (this document): Claude Fable 5.1, in a Claude Cloud session, re-read the
  actual file, kept the parent's guard, replaced the comparison route with a genuine
  single tap, made the preview use the space the text leaves on its page, and ran its
  own browser checks (section "Fable checks, round 3"). The session's configured model
  identifier was `claude-fable-5-1`; the session had no tool to confirm the serving
  model independently, and no signal that it differed.

## What the user asked for

A · Ledger stays the foundation (dense title index, one swipe = one post, the familiar
main menu and post/comment action lists). Media previews come into the selected feed
row in the spirit of B. Inside a post, long text is read first; swiping pages the body;
at the end the media preview is visible; the user asked whether the next swipe should
open the media large instead of swipe + tap, and for a mockup of the best sequence.

## Recommendation: swipe opens the media

Recommended sequence, as built and set as the default route:

1. Feed: swipe = next post. The selected row grows: three-line title, thumbnail with
   type and count/duration (`▤ 1/3`, `▶ 1:35`, `◎ GIF`, `⇗ link`), a three-line
   excerpt and `author · age · ▲ score`. Unselected rows stay two lines plus
   `r/community · type · comments`. Text-only rows show `¶ text · n pg` and a wider
   excerpt instead of a thumbnail.
2. Tap on the row opens the post directly (no intermediate menu). Inside the post,
   tap opens the familiar action list: Comments, Reply to post, Open media, Upvote,
   Downvote, Save, Send link to phone (proposed). The menu order is identical in the
   post, in full screen and on the post row of a thread, so one list is learned.
3. Swipe pages the body. The header carries a page rail: one segment per text page and
   a boxed media glyph (`▤`, `▶`, `◎`, `▣`, `⇗`) as the last segment, so the end of
   the text and the position of the media are visible from page 1.
4. The media preview is always the last page. When the remaining text is short the
   preview shares that page under the text; otherwise it gets its own page with a
   caption line (`3 images · Top before finish, …`, `video 1:35 · opens paused, no
   sound until Play`). The preview box is outlined 2 px with a `swipe ▶ full screen`
   cue inside it, fills the space the text leaves (170 to 340 px), and the footer
   switches to a bold `Swipe ▶: open media`.
5. One more forward swipe opens the media full screen (about 540 px tall, so the step
   up is visible). Gallery: swipe = next/previous image, clamped with `Last image ·
   Back: post`. Video: enters paused at 0:00 with a large outlined `▶`, `❚❚ paused ·
   sound on` caption and `Swipe: seek ±10 s` footer; the first tap action is Play,
   then Mute, Replay. GIF: plays on open, silent. Swipes never leave full screen.
6. Back returns to the exact preview page; Back again returns to the exact feed row
   (feed screens are cached per route; selection is never reset by Back). Tap in full
   screen opens Play/Pause/Mute/Replay or Reveal/Retry, then Back to post, then the
   same post actions as everywhere.

Why swipe rather than tap at the boundary: it keeps the single rule the user learns on
day one, `swipe = keep going, tap = actions, back = up one level`, true on every
screen. The media is the natural continuation of the post, like page n+1. The boundary
is visible three times before it is crossed (rail glyph, outlined preview with its cue,
bold footer), so the mode change from "next page" to "open" is announced rather than
discovered. The cost is one risk: a reader swiping quickly past the last text page
lands in full screen. That costs one Back, loses nothing (the page is restored), and
the alias debounce plus a 300 ms guard after opening guarantee it cannot also skip the
first image or seek the video.

## Comparison: direct single tap

The review shell's second route is now a real one-tap alternative, not the previous
two-tap menu. Both routes share every screen and differ only on the preview page:

| | Swipe route (recommended) | Direct tap route |
|---|---|---|
| Text pages | swipe = page, tap = actions | same |
| Preview page, forward swipe | opens full screen | `End of post` notice only |
| Preview page, tap | action menu (Open media listed after Reply) | opens full screen, no menu |
| Post actions on the preview page | tap, as on every other page | only from inside full screen (tap there) |
| Gestures to reach a 2-page gallery from the feed | 1 tap + 2 swipes | 2 taps + 1 swipe |
| Rule the user must remember | none new | "on the picture page, tap means open" |

The counts are the same; the direct tap saves nothing. What it changes is which page
breaks the `tap = actions` rule, and it breaks it on exactly the page where most media
posts land immediately: short-text image, GIF, video and link posts have a single page
that is already the preview page, so for them the direct tap makes Comments and Reply
unreachable until the media has been opened. That is why the direct tap is the
comparison and not the recommendation. If the user prefers it anyway, it is coherent
and complete in the mockup, including sensitive and unavailable media, and the escape
to the action menu is the full-screen tap.

The former two-tap route (tap, menu with Open media, tap) was removed as a separate
option because it was not a direct tap. It still exists inside the recommended route as
the menu fallback, and the step counter under the HUD records it honestly when used.

## Input boundary rules

- Right/down = next, left/up = previous, debounced together for 250 ms in `nav()`. A
  physical swipe that arrives as `DPAD_RIGHT` then `DPAD_DOWN` moves once; the review
  shell's "paired swipe" button reproduces it with a 40 ms gap and the log prints the
  ignored alias.
- Full screen ignores any directional input in the first 300 ms after opening, so even
  an alias arriving late cannot advance the gallery or seek the video.
- Inside full screen swipes never exit. Back is the only way out and lands on the
  originating post page.
- Sensitive media stays hidden in the feed thumbnail, the preview and full screen until
  the explicit Reveal action (first item of the full-screen menu); navigation never
  reveals it. Unavailable media shows an outlined chip everywhere, Retry and Back to
  post first in the menu.
- Video never autoplays in the feed or the preview; full screen enters paused.
- Composer: Enter inserts a newline; Preview is explicit; Send is explicit; taps during
  sending are ignored; failure keeps the draft with Retry/Edit/Discard; expired login
  offers Sign in on glasses instead of Retry.

## Coverage kept from the variant prototype

Main menu (plus a contextual `Sort <feed>` row, because the feed tap now opens the
post), Home/Popular/Latest/community feeds, communities with Join/Leave, search with
scope and offline/empty states, inbox opening the thread at the comment, profile,
saved, settings (text size, preview lines, hide sensitive, open media by, reduce
motion, proposed autoplay and auto-keyboard), sign-out and sign-in hand-off, create
text/link post with review and explicit Post, nested comments with human parent
context, collapse/expand, load more, full-comment reader, go to parent, failed own
comment retry, reply to post and reply to a comment with immutable parent, draft kept
on Back, and loading/offline/locked/expired/sensitive/media-unavailable states.

## Fable checks, round 3 (browser only)

Driven with the pre-installed Playwright and Chromium against the file on disk (no
server, no install, no network). 100 scripted assertions passed, 0 failed; no page
errors, no console messages, no external requests. T3 native preview was not available
in this cloud session, so these are headless-browser observations only.

- Feed: three Next reached `4/10`; Back opened the main menu (Sort Popular, Home,
  Popular, Latest, Communities, Search, Inbox, Profile, Saved, Create post, Settings);
  Back returned to row 4. Only the selected row rendered a visible thumbnail.
- Swipe route, p4 (gallery, 2 pages at M): feed tap opened the post on page 1. A paired
  swipe from page 1 landed on page 2 (the preview page) and not in full screen; the
  footer read `Swipe ▶: open media · Tap: actions · Back: feed` and the rail marked the
  boxed glyph. A second paired swipe opened full screen on `image 1/3` with the log
  `ignored alias DPAD_DOWN (41 ms after previous)`. Next = `image 2/3`; two more swipes
  clamped at `3/3` and stayed in full screen. The full-screen menu listed Back to post,
  Comments, Reply to post, Upvote, Downvote, Save, Send link to phone and no Open
  media. Back returned to page 2 (preview); Back returned to feed row 4 inside the
  list viewport. Step counter: `2 swipe(s) + 1 tap(s) incl. the feed tap [swipe route]`.
- Direct tap route, same post: a paired forward swipe on the preview page stayed on
  the post (`End of post`); one tap opened `image 1/3` with no menu; the counter read
  `1 swipe(s) + 2 tap(s) incl. the feed tap [tap route]`; tap in full screen opened the
  action menu; tap on a text page still opened the familiar list with Open media.
- p5 (video, one merged page): preview caption `▶ 1:35 · paused`; full screen entered
  paused at `0:00 / 1:35` with `Swipe: seek ±10 s · Tap: Play / actions`; one swipe
  seeked to `0:10` still paused; menu Play, Mute, Replay first; Play advanced the
  simulated clock; Back returned to page 1, then to feed row 5.
- p8 (sensitive): preview and full screen hidden; swiping inside did not reveal;
  Reveal was the first action and revealed the image. Media unavailable (p2 with the
  simulation): Retry then Back to post first; Retry kept the unavailable state; Back
  returned to the post.
- p3 (text only): 3 pages at M, 4 at L, 2 at S, page index clamped on size change;
  swiping past the end never entered a media screen.
- Preview geometry for p2, p4, p5, p6, p7 at S, M and L: the preview was on the last
  page, inside the page box, above the footer (gap 8 px or more), between 170 and
  340 px tall at M scale.
- Deep selection: row 9 (long title) stayed inside the list viewport before opening
  and after Back from page 3 of its post.
- Reply to a comment with "send fails once": menu on `c2`; phone field enabled only
  while the editor had focus; two lines typed with an Enter newline; Back kept the
  draft (`drafts 1`, action tagged `draft`); Preview quoted `u/quiet_habit`; Send
  showed Sending and two extra taps were logged as ignored; the first send failed with
  the draft kept; Retry send produced exactly one `u/you` comment under `c2` and the
  draft count returned to 0. Reply to post inserted exactly one top-level comment.
- Arrow, Enter and Space inside the phone field did not change HUD state.
- 360 px: document width 360, HUD scaled to 360 × 480 with its bottom inside the
  wrapper at M and L; all 35 flow-chooser entries rendered at 728 px.

Not exercised by the script: Join/Leave, inbox mark-read, profile sub-routes, create
post end to end, rate-limit and offline send messages (same code path as the
exercised failure), and the see-through scene checkbox beyond a visual look.

## Parent checks (2026-10-08, before round 3)

Recorded by the parent in T3 native collaborative preview after adding the null guard:
gallery post reached full screen once from a paired swipe with image 2 on the next
swipe and Back restoring preview page 2 and feed selection 4; video paused on entry
with a working Play; sensitive stayed hidden until Reveal; unavailable media offered
Retry and returned with Back; comment reply kept target `c2` under `p3`, preserved a
multiline draft across Back, survived one simulated failure and retried into one
comment; post reply used target `p3`; nine routes measured at 360 px at M and L stayed
inside their list body; T3 previews at 728 and 360 px reported no console messages.
These checks predate the round 3 changes and were re-covered by the Fable script above.

## Parent follow-up after importing round 3

The parent inspected the actual two-file ZIP supplied by the user, validated its
CRC and filenames, checked that the accompanying HTML diff applied to the local
prototype, and imported only the two authorized files. The imported HTML SHA-256
is `4793d2f7404a08c1b684c3368732114419efb4ce85f22027ae26620057e2aa7c`.
The pre-import files were preserved in a temporary backup outside the repository.
No Android/build/device/account state changed.

T3 collaborative browser interactions initially timed out. The parent closed its
unresponsive review tab, then used T3's native `html_preview` on the actual final
HTML. Clean presentation previews passed at 728 and 360 px with no console
messages; content heights were 1319 and 1817 px. Only the presentation copy starts
with the gallery feed row selected; the repository HTML keeps its original startup.

In a separate test-only copy, a small synchronous script used the real DOM review
controls and measured the resulting HUD. Native preview reported 17 of 17 checks
passing at each width (34 observed checks total): gallery text/preview ordering,
opening on the first image, post actions in media, exact preview/feed Back, direct
feed selection, genuine one-tap opening with no intermediate menu, retained media
actions, short-post actions in the swipe route, paused video with seek labels,
video Back, immutable comment/post reply targets, draft retention across Back,
ten preview geometries (five media posts at M/L) above the footer and inside their
page boxes, and no horizontal document overflow. Measured HUD sizes were 480 x
640 at 728 px and 360 x 480 at 360 px. No QA script was added to the actual HTML.

These synchronous checks do not independently verify the delayed second input
alias, playback timing, full simulated Send/retry or every secondary route. The
100 headless assertions described above remain Fable's reported observations;
the parent did not rerun that entire suite. Physical input, optics and native
Reddit integration still require a separate device pass after design approval.

## Remaining limits

- All data, media and sends are fictional and local; draft persistence is in memory.
- No validation of the official app's feed/comment models, native vote, save, reply,
  submit, report, block, delete, sign-in hand-off, in-app browser or "send link to
  phone". Report, Block, Delete and the two settings marked `proposed` are unverified
  native integrations.
- No physical R08 or touchpad evidence: the 250 ms alias debounce and the 300 ms
  full-screen guard are design requirements demonstrated only in the browser.
- Optics: fonts are system stand-ins (Bahnschrift SemiCondensed if present, otherwise
  Segoe UI/Roboto), and monochrome conversion of real photos and video is not tested.
  The 170 to 340 px preview and the 2 px outline need a device pass.
- Headless browser checks are not T3 native preview; the parent should still inspect
  the file in T3 before presenting it.
- Android implementation waits for the user's approval of the sequence above.
