# Reddit on Rokid glasses: three UI directions (green monochrome HUD)

Status: design proposal only, 2026-10-08. `index.html` in this folder is one
self-contained HTML/CSS/JS document with three interactive design directions and an
external style switcher. Nothing here is built on Android, nothing was verified
against the stock Reddit APK or the preview13 patch, and no Reddit account or network
is involved. All data is fictional and shared by the three directions.

The approved prototype in `../index.html` and its `../DESIGN.md` remain unchanged. This
document supersedes only two of its presentation decisions: the HUD is now green
monochrome, and the browsing view no longer auto-reads full post bodies.

## What changed and why

Device evidence from preview13 showed the feed as a full-screen reader: header,
community and author, `Page n/m`, then title and body in one 25 px block. Long bodies
were paged before the next post could be reached (four pages for one Popular post),
media had no browsing thumbnail, and comment context showed parent ids. The user likes
the menu model (main route list, post action list, comment action list) and wants the
redesign focused on browsing, hierarchy, rhythm, media and comfortable reading.

Common to the three directions:

- One swipe = one post while browsing. Long text pages only after `Read post`.
- Tap opens the familiar post action list: Read post, Comments, View media / Open link,
  Reply to post, Upvote, Downvote, Save, Send link to phone (proposed), Sort feed, Main
  menu. The comment action list keeps Reply to this comment, Read full comment, votes,
  Save, Collapse/Expand, Go to parent, Report/Block (proposed) or Delete (own).
- Back from a post, reader, media or thread returns to the exact row and scroll
  position (feed screen objects are cached per route; `sel` is never reset by Back).
- Parent context in comments is human: `↳ to u/lidnote: "The sticky note one is…"`.
- Media type and comment count are visible while browsing; thumbnails are green
  monochrome stroke/dot SVG art inside the HUD.

## The three directions

### A · Ledger (dense index)

Condensed sans (Bahnschrift SemiCondensed stand-in), 4 to 5 posts visible. Each row:
`r/community · media glyph + type · comment count`, then a two-line title. The selected
row grows: title to three lines, a two-line preview and `author · age · ▲ score`.
Selection is a 2 px outline plus a 5 px left tick (same language as the approved
prototype). Action rows use a thick left tick instead of a full outline.

Tradeoff: fastest scan, most posts per screen, the least context per unselected row.
Best when the user already knows what they are hunting for.

### B · Deck (focused card + next)

One focused card at natural height (community, author, age; three-line title; four-line
preview beside a thumbnail; vote, comments, type and page estimate), a `NEXT` block with
the following post's community, type, two-line title and two-line preview, then a dim
`THEN` list of upcoming titles. Selection is shown by bracket corners, never a fill.
Comment rows reuse the brackets; the selected comment shows its parent context.

Tradeoff: roughly two readable summaries plus a look-ahead; the richest per-post context
while browsing without opening anything. Fewer posts per screen than A.

### C · Headline (editorial)

One post per screen, but with real hierarchy: small-caps kicker (community, author,
age), serif headline (Cambria/Georgia stand-in, up to four lines), full-width thumbnail,
a three-line dek, a stats rule, and an `UP NEXT` line. A dot rail on the left shows the
position in the feed. Comments use a left bar for selection and small-caps parent
context above every reply. Action rows use a `▸` marker plus underline.

Tradeoff: the most comfortable to read and the calmest on a see-through display; the
slowest to scan because each swipe reveals one post. It is the closest in rhythm to the
current implementation, but with the body truncated, a thumbnail and a next-up cue.

## Recommendation

Start with **B · Deck**. It is the direction that most directly fixes the observed
friction: the next post is already readable before the swipe, media type and comment
count are in view, and nothing pages until `Read post` is chosen. Its bracket selection
keeps the lit area small. A is the alternative when fast scanning matters most;
C is the alternative when larger headlines and reading comfort matter most.
Small rows in A require an optics check on the real glasses. The decision stays
with the user.

Fonts in the mockup are system stand-ins. The patched Reddit APK will use its own
bundled typeface; the hierarchy (size, weight, letter-spacing, tracking of small caps)
is what should be carried over, not the family.

## Monochrome rendering rules used

- Root `#000000`; the only ink is one green (`#5dff8e`) at three intensities (full,
  68 %, 40 %) plus a 22 % hairline. No second hue anywhere, including media.
- No fills, cards, gradients, shadows, glow or scroll rails. Selection = outline, tick,
  brackets, left bar or underline depending on the direction.
- Votes, saved, OP, you, sent / not sent, locked, sensitive are shown as outlined text
  chips or wording, never by colour.
- Media is stroke-only SVG with dot patterns for tone. Sensitive media is hidden behind
  a `sensitive` chip until Reveal; unavailable media shows an outlined `media
  unavailable` chip with retry.
- The optional "see-through scene simulation" checkbox paints a grey scene **behind the
  HUD wrapper** and makes the HUD root transparent for review only. It is labelled as a
  simulation and is not an app background.
- Text size S/M/L and preview lines 1–3 are HUD settings (also mirrored by review
  controls). Pagination is measured against the real page box with the computed font,
  so page counts change with text size; menu rows clamp and never overflow.

## Input model (identical in all three)

One axis plus tap and back. Right/down = next, left/up = previous, debounced together
for 250 ms (`nav()` in the script); the review log prints ignored aliases. Enter/Space
= tap, Escape = back. Keys are ignored while the phone text field, a select or an input
has focus, so typing never navigates.

- Browsing: swipe moves the post selection; ends clamp with a short footer notice.
- Action list open: swipe cycles with wrap, tap activates, back closes without leaving.
- Reader / full comment / preview: swipe pages text; back returns.
- Media: gallery swipes images, video swipes ±10 s; tap opens Play/Pause, Mute, Replay
  ahead of the post actions.
- Composer: the glasses editor is the focused field; the phone field is enabled only
  then. Enter inserts a newline. Tap opens Preview / Discard. Back keeps the draft.
- Preview: explicit Send. Tap during sending is ignored and the Send action is not
  offered again, so there is no duplicate send. Failure keeps the draft and offers
  Retry / Edit / Discard; expired login offers Sign in on glasses instead of Retry.

## Coverage

Browse: main menu (Home, Popular, Latest, Communities, Search, Inbox, Profile, Saved,
Create post, Settings), Home/Popular/Latest feeds, sort Hot/New/Top, community route
(`r/space`), communities list with Join/Leave, long title (p9) and four-page body (p3).

Read: text reader with `Page n/m`, image, gallery `image n/3`, GIF play/pause, video
with time, ±10 s, mute, replay and a 2 px progress line, link post with Open here
(stock in-app browser) and Send to phone (proposed), sensitive reveal, media
unavailable.

Comments: nested rows with depth indent, OP/you chips, human parent context, collapse
and expand (counts the whole subtree), deleted comment, load more, full-comment reader,
go to parent, retry a failed own comment.

Reply flows: reply to post (inserted at top level) and reply to a comment (inserted
under its exact parent), both with immutable parent quote, phone editor, draft kept on
Back and reopened via `Reply (draft)`, preview, explicit Send, sending lock, failure
with kept draft, retry, success focusing the new comment.

Other: search (phone-typed, scope cycle, results, empty, offline), inbox opening the
thread at the comment, profile, saved, settings (text size, preview lines, hide
sensitive, reduce motion, proposed auto-keyboard), sign-out and signed-out sign-in
hand-off, create text/link post with review and explicit Post, and loading, empty,
offline, locked, expired, sensitive and media-unavailable states.

## Not verified by this HTML (design-level only)

- Any native integration: reading the official app's feed/comment models, triggering
  native vote, save, reply, submit, report, block, delete, sign-in, in-app browser or
  "send link to phone". Report, Block and Delete are labelled `proposed` in the HUD.
- Preserving post/comment identity through the patched app's submit path. The mockup
  models it; the patch must enforce it.
- Automatic phone keyboard opening for Reddit editors (hub preference, not built).
- Physical R08/ring input. The device recording used ADB compatibility keys. The 250 ms
  alias debounce is a design requirement, demonstrated only in the browser.
- Real fonts, exact pixel density and the optics of the green monochrome display. Row
  heights and intensities will need a device pass.

## Observed verification (browser only, 2026-10-08)

Driven through T3's native preview tools on a local static copy of `index.html`
(`http://127.0.0.1:8765`, Chromium, 728 × 900 then 360 × 900). No console errors.

- Directions A, B and C each rendered the Popular feed; screenshots at 728 px were
  inspected for layout, hierarchy and overflow (none).
- In every direction: three `Next` presses reached `4/10`; Back opened the main menu and
  a second Back returned to the feed at `4/10`; Tap opened the ten-entry post action
  list; `Read post` opened the paged reader; Back returned to `4/10`; the paired
  DPAD_RIGHT + DPAD_DOWN press moved exactly one post (log: `ignored alias DPAD_DOWN
  (41 ms after previous)`).
- Four-page body (p3): `Page 1/3` → `3/3` in A and B, `1/4` → `4/4` in C at M; page
  counts changed with S and L text size; no page box overflow.
- Gallery `image 1/3` → `3/3` with "Last image" notice; video `0:15` → `0:25` after a
  swipe; video, GIF and link action lists offered Pause/Mute/Replay, Pause and Open
  here respectively; Open here reached the stock in-app browser screen.
- Comments: 13 rows → 9 after collapsing u/lidnote (`Collapse 2 replies`), end of
  thread notice, selected row stayed inside the measured list viewport (`scrollTop=55`),
  Load more added one row with its notice.
- Reply to a comment (direction B, "send fails once"): phone field enabled only while
  the editor was focused; two lines typed including an Enter newline; Back kept the
  draft (`drafts 1`) and the action read `Reply to this comment · draft`; Preview showed
  the parent quote; Send → `Sending…` and two further taps logged `tap ignored while
  sending`; first send failed with the draft kept and `Retry send / Edit / Discard`;
  retry succeeded and the thread focused `u/you · sent` under parent `c2`.
- Reply to post: inserted with parent `null`, focused, `drafts 0`.
- Create post: community → type → title → body → review → `Post` → `posted (demo)`.
- Settings: Text size M → L → cycled back to M; Preview lines 2 → 3.
- Search "walnut" returned one result; Back returned to the search entry.
- Signed-out reply opened the sign-in screen; choosing sign-in returned to the thread.
- Arrow and Enter key events inside the phone field did not change HUD state; Escape
  on the document closed the action list.
- 360 px: document width 360, HUD scaled to 0.75 (360 × 480), bottom inside the
  wrapper, review chrome stacked in one column.

Not exercised in the browser: the "then" line trimming in B at S/L sizes beyond a visual
check, rate-limit and offline send messages (same code path as the exercised failure),
Join/Leave, inbox mark-read, profile sub-routes.

## Parent inspection before presentation

The parent inspected the actual files and made two review-shell fixes: host theme
variables now control text/form colours, and the initial flow chooser correctly
shows Popular. The three HUD design directions are those delivered by Fable.

T3 native `html_preview` rendered all three directions at 728 px and 360 px with
no console messages. Document heights were 997 px and 1586 px respectively.
The collaborative browser returned `PreviewAutomationNoAvailableHostError`, so
interactive parent checks used the already-installed Playwright/Chromium as the
documented fallback. No browser dependency was installed.

Observed in each direction:

- Three browsing moves reached post 4; Back through the main menu and reader
  preserved that selection. Post action menus contained 10 entries.
- Paired direction aliases advanced once. The long reader had 3 pages in A/B
  and 4 in C; page navigation and return preserved the originating post.
- A comment reply retained its two-line draft on Back, previewed exact parent
  `c2`, ignored extra taps during sending, kept the draft after simulated failure,
  and retried into one sent comment under `c2`.
- A post reply was inserted once at the top level. Enter in the phone field
  inserted a newline and left the composer open.
- Seven representative flows per style passed 360 px width, HUD bottom and
  selected-row visibility checks. No external requests or runtime errors occurred.

The parent receipt and HUD screenshots are retained outside the repository in
`E:/Tools/Rokid/reddit-patch-lab/reddit-ui-variants-parent-qa-20261008.json` and
`reddit-ui-variant-A-parent.png`, `reddit-ui-variant-B-parent.png`,
`reddit-ui-variant-C-parent.png`. These are browser checks, not Android or optics
validation. The user must choose a direction before Android UI implementation.
