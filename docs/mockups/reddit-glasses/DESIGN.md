# Reddit on Rokid glasses: design notes

Status: design prototype only. `index.html` is a self-contained HTML/CSS/JS mockup of
how the official Reddit Android app would look and behave on the glasses after the
Patcher plugin adapts it. Nothing here is built on Android, nothing was verified against
a stock Reddit APK, and no Reddit account or network is involved. All data is fictional.

This is a glasses adaptation of the official APK delivered through the phone-only
Patcher plugin. It is not a Nexus plugin that renders Reddit through Nexus surfaces,
and it adds no plugin capability, bus route or SDK surface.

## How to review

Open `index.html` in a browser, or paste it into T3 `html_preview`/`html_render`. The
black 480 × 640 area is the glasses HUD; everything on the paper-coloured background is
review tooling and is not part of the glasses UI.

- Flow selector: jumps to any screen or error state.
- Two red buttons: "Reply to post demo" and "Reply to a comment demo" open the thread
  with the right Reply action already selected, so the journey starts at step 1.
- Simulate: slow loading, offline, send fails once, rate limit, expired login, signed
  out, locked thread, media unavailable.
- Prev / Tap / Next / Back buttons, or keys: arrows (left/up = previous, right/down =
  next), Enter or Space = tap, Escape = back. "Paired swipe" fires DPAD_RIGHT then
  DPAD_DOWN 40 ms apart to show the alias debounce.
- Phone panel: a simulated Keyboard & remote text field. It is enabled only while a
  glasses editor is focused. Enter inserts a newline and never publishes.

## Rendering rules used

- Root `#000000`, white text, two restrained accents: orange for upvote/sent/action
  label, periwinkle for downvote. No fills, cards, gradients, shadows or scroll rails.
- Selection is outline-only: 2 px white outline plus a 4 px left tick. Actions are
  1 px outline rows; the selected action gets a 2 px outline and a small "tap" cue.
- Text scale S/M/L via a CSS variable; body text 21 px at M, titles 26 px.
- Lists live in the measured remaining HUD viewport (`overflow:hidden` + programmatic
  scrollTop follow). Position is shown as text in the top bar (`3/16`, `Page 2/3`).
- Long text is paginated against the measured page box using canvas text measurement
  with the page's computed font, so pages change with text size.
- The HUD scales proportionally on narrow review widths (CSS aspect-ratio + transform),
  never clipping its bottom. Verified at 360 px: no horizontal overflow.

## Interaction model

One swipe axis plus tap and back, identical on every screen.

1. Content mode: swipe moves through posts, pages, images, rows or messages. Content
   clamps at the ends with a short footer notice; action lists wrap.
2. Tap opens the action list for the selected item. If an item has exactly one obvious
   action (menu rows, "Load more", a reason in Report, a setting value), tap runs it.
3. Action mode: the same swipe cycles actions; tap performs; back closes the list
   without leaving the screen.
4. Back in content mode leaves the screen. On the composer, back keeps the draft.
5. Right/down and left/up are aliases of one physical swipe and are debounced together
   for 250 ms (`nav()` in the script). The review log shows ignored aliases.
6. One canonical selection (`sel`/`actSel` on the screen object) drives rendering,
   follow-scroll, `aria-selected`, and activation. R08 accessibility focus must map to
   this same model on Android; the prototype only represents it.

The footer always states what swipe, tap and back do on the current screen.

## Screen inventory

Browse and discover
- Home (joined communities; sort Hot/New/Top), Popular, Latest: one post per screen.
- Feed switcher menu (Home, Popular, Latest, Communities, Search, Inbox, Profile,
  Create post, Chats).
- Communities list with join state; community detail with Join/Leave, sort, Browse
  posts, Create post here.
- Search entry (phone-typed field + recent searches), results with Posts / Communities /
  People scopes, empty and offline states.

Read
- Text post: title fixed, body paged, `Page n/m`.
- Image post, gallery (`Image n/4`), sensitive image hidden until Reveal.
- Spoiler text post hidden until Reveal; nothing opens on entry.
- Video: compact single-axis menu (Play/Pause, ±10 s, Mute, Replay), text time and a
  2 px progress line.
- Link post: explains "Open here" (stock in-app browser, Back returns) versus "Send to
  phone" (proposed, not built). The simulated in-app browser screen is deliberately
  marked as stock web content.
- Vote/unvote, save/unsave, comment count, report, delete own post.

Comments
- Thread rows: author, OP/you badges, age, score, `↳ to u/parent` depth context, indent
  by depth, selected row shows two more preview lines.
- Actions on a comment: Reply, Read full (paged), Upvote, Downvote, Save, Collapse or
  Expand replies, Go to parent, Edit/Delete (own) or Report/Block (others), Sort.
- Collapsed branches (`[+] u/x · 3 replies hidden`), removed and deleted comments
  (reply disabled with the reason), blocked users, Load more with loading and retry.
- Post row at the top of the thread carries Reply to post.

Reply to a post and reply to a comment (both complete)
1. Reply on the post row or on a specific comment.
2. Composer header: "Replying to u/x's comment" or "Replying to the post", parent quote,
   community and post title. The parent identity is carried as an id on the screen
   object and never changes during the journey.
3. The editor is the focused field; the phone panel connects to it. Hint explains
   Keyboard & remote. Enter adds a line.
4. Back keeps the draft ("Draft kept"); the Reply action then shows "draft" and reopens
   it. Drafts are keyed per parent (`reply:c2`, `reply:p1`, `edit:c3`, `chat:ch1`).
5. Preview shows target, depth, parent quote and the full reply (paged if long),
   "as u/you".
6. Send is an explicit action on the preview. Tap during sending is ignored and the
   Send action disappears, so no duplicate send.
7. Sending then "Sent": the comment is inserted under its real parent with a `sent`
   chip and the thread focuses it.
8. Failure keeps the draft and the preview offers Retry / Edit / Discard (expired
   login offers Sign in on phone instead of Retry). A failed comment can also be
   retried later from the thread.

Also covered: edit own comment (existing text shown on glasses; phone typing appends,
because existing text is not mirrored to the phone), discard confirmation, empty input
validation, signed-out handoff, locked thread, removed/deleted parent.

Account and participation
- Inbox: reply/mention notifications open the thread focused on that comment; chat
  notifications open the conversation; mark read.
- Profile: posts, comments (edit/delete), saved items (unsave), settings, account.
- Settings: text size, comment preview lines, hide sensitive media, autoplay, motion,
  and "Auto-open phone keyboard" labelled proposed.
- Account: signed in through the official app on the glasses, sign out with
  confirmation; signed out shows a proposed handoff to Reddit's glasses sign-in,
  with phone Keyboard & remote for typing. This is not phone-account import.
- Create post: community › Text/Link › title › body or URL › review › explicit Post,
  with validation, rate limit, offline and expired failures that keep the draft.
- Chats: list, conversation, reply through the same composer and preview.
- Report (reason › confirm), Block (confirm; content hidden), delete own comment/post.

Non-happy paths: loading, empty results, offline with retry, expired login, rate limit,
send failure, locked thread, removed/deleted comment, media unavailable, hidden
spoiler/sensitive content.

## What the prototype does not prove

These are design proposals whose Android integration is unverified:

- Reading the official app's feed, post and comment models and triggering its native
  vote, save, reply, edit, delete, report, block and submit operations. The real work
  starts by inspecting one pinned stock APK.
- Preserving exact post/comment identity from Reply through final submission inside the
  patched app. The prototype models this; the patch must enforce it.
- Automatic phone keyboard opening for Reddit editors. Today auto-open is specific to
  YouTube; a Reddit preference would need trusted hub integration.
- Opening Reddit's glasses sign-in from the new setup flow is proposed. The user
  signs in to the glasses installation with remote keyboard input; signing in to a
  separate phone Reddit installation does not transfer its account to the glasses.
- "Send link to phone" handoff. Not built.
- Chat viewing and composing. Reddit chat uses a different native stack; no hook was
  inspected.
- Voice dictation from the glasses microphone. Not shown as a production path; the
  composer is phone keyboard only.
- Fonts: the mockup uses Bahnschrift with system fallbacks for the HUD. The patched app
  would use whatever the Reddit APK ships or a bundled font.

## Observed verification (browser only)

Driven with the review controls in a local Chromium tab on 2026-10-07:

- All 40 flow entries render without script errors.
- Reply to comment: compose › back keeps draft › reopen via "Reply (draft)" › Preview
  with correct parent quote › Enter in the phone field does not send › Send › second
  tap during sending ignored › comment inserted with parent `c2`, status `sent`,
  focused.
- Reply to post: inserted at top level (parent null), focused.
- Empty draft: Preview refused with an inline notice. Discard asks for confirmation.
- "Send fails once": failure keeps the draft; Retry succeeds.
- Edit own comment: existing text shown, phone field empty, edit saved with `edited`.
- Paired DPAD_RIGHT + DPAD_DOWN moved the selection exactly one item.
- Long text paginates (3 pages at M); last thread row stays fully inside the list
  viewport; collapse and Load more update the row count.
- 360 px wide: no horizontal overflow, HUD scaled to 0.69 with its bottom intact.

No Android build, patch or device test was performed.

## Parent review before presentation

- Both reply demos were exercised again through T3's browser controls. A post
  reply was inserted at the top level; a comment reply was inserted under `c2`.
  Back retained the draft, Enter in the phone field did not send, and a second
  tap during sending produced no duplicate comment.
- T3 HTML previews at 728 px and 360 px reported no console messages. The review
  selectors and phone text field now set an explicit foreground and light color
  scheme so they remain readable inside T3's dark theme.
- At 360 px, the page width remained 360 px and both the scaled HUD and its footer
  fit inside the HUD wrapper.
- Sign-in copy now keeps the account in Reddit on the glasses and uses the phone
  only for remote text entry. The proposed sign-in handoff remains simulated.
