# Reddit for Rokid glasses: complete interactive design prototype

## Requested outcome and approval gate

The user selected Reddit as the next genuine Patcher target and explicitly asked
for Claude Fable 5.1 to build a complete HTML mockup of the app as it would appear
on the glasses. Replying to posts and replying to individual comments are central
requirements. Present the finished, checked prototype to the user for approval
before implementing its Android interface or Reddit patches.

The user says Fable's usage becomes available at 20:05 Europe/Paris on
2026-10-07. Delegate design work at or after that time to the live catalog's
`claudeAgent` provider and `claude-fable-5-1` model. Do not substitute a different
model without asking. This is a delegated child task of the existing thread, not
a new top-level conversation.

## Deliverables

Create these files in `E:/Tools/Rokid/RokidNexus/docs/mockups/reddit-glasses/`:

- `index.html`: a complete, self-contained, interactive HTML/CSS/JavaScript
  prototype. No build step, external scripts, CDN, network requests, real login,
  or real Reddit writes. All example data is fictional and local.
- `DESIGN.md`: the screen inventory, interaction rules, reply flows, and any
  proposed features whose integration remains unverified. Write repository
  documentation, code, comments, and interface copy in English.

Only edit prototype files in this directory. Do not change this brief, Android
source, Gradle files, patch bundle pins, signing material, or machine setup. Do
not commit, tag, publish, install APKs, or contact anyone. Use the frontend-design
and rokid-glasses-dev skills; do not spawn additional agents.

## Product and implementation context

This is a glasses adaptation of the official Reddit Android APK, delivered
through the phone-only Patcher plugin. It is not a new phone plugin that renders
Reddit through Nexus surfaces. Preserve that distinction in the design.

`PatchTarget` already holds target-specific package, supported stock versions,
stock signing certificate, bundle source, patch choices, and output identity.
Only YouTube is currently registered, and the phone hub's native-app setup
currently offers only YouTube. Each new target needs its own actual patch and
installation integration; a generic theme switch cannot supply Reddit actions.

The local Morphe fork is `E:/Tools/Rokid/Morphe-Patches`. It contains existing
Reddit patches and activity/application hooks, but no delivered Rokid Reddit
navigation or reply hooks. Reusing the official app's authenticated models and
native operations is the implementation direction, subject to investigation of
one pinned stock APK. Do not imply that the HTML proves those integrations.

Nexus already provides a system-wide remote Android IME: a real focused editor
on the glasses can receive text typed on the phone's Keyboard & remote screen.
Existing text is not mirrored back to the phone. Manual keyboard opening works
for native apps; automatic opening is currently specific to YouTube and needs
an explicit Reddit integration. Do not invent Nexus plugin capabilities or bus
routes. `/core/native-apps/*`, `/core/remote-input/*`, `/core/navigation/*`, and
`/core/pointer/*` remain trusted hub-to-hub controls.

Voice dictation from the glasses microphone is not established for a patched
native Reddit APK. Keep the primary composer based on phone keyboard input. If
you show dictation, identify it as a separately proposed/unverified feature in
the review notes, never as an already available production path.

## Glasses rendering requirements

- Use a real 480 x 640 portrait reference viewport; scale the complete viewport
  proportionally on narrow review screens without clipping its bottom.
- Inside the HUD: full black `#000000` root, large readable text, restrained
  high-contrast accents, sparse content, transparent outline-only controls, and
  clearly visible selection. No opaque phone cards, grey panels, gradients,
  frosted glass, shadows, or filled button backgrounds.
- Design around one reliable swipe axis plus tap/select and back. No workflow
  may require both horizontal and vertical gesture directions, a touchscreen,
  dragging, or a mouse pointer.
- Suggested reading pattern: swipe through posts/pages/comments; tap opens a
  clearly labeled action mode; the same swipe axis cycles actions; tap performs
  the selected action; back exits action mode before leaving the screen.
- Keep every selected row in the actual measured remaining HUD viewport. Avoid
  visible scroll rails. Use short textual position indicators when needed.
- Design for paired Rokid direction events: right/down mean the same next step,
  left/up mean the same previous step, debounced together for about 250 ms.
- R08 accessibility focus and input focus must correspond to the same canonical
  selection and activation. Represent selection consistently in the prototype;
  native accessibility integration is subsequent Android work.
- A visible, consistent back/escape path is required on every screen.

## Complete core app coverage

Build linked screens and meaningful interactions, not a static contact sheet or
only the home screen. Use a compact screen/flow index outside the simulated HUD
so the user can inspect the whole design without discovering every route.

### Browse and discover

Home, Popular, Latest, followed communities, community detail/feed, join/leave,
feed sorting, search entry, and search results for posts, communities, and users.
One-post browsing must support different text lengths and post types.

### Read and interact

Full text post reading with paging; image posts and galleries; video playback
with a compact single-axis action menu; link posts with a clearly explained
handoff/native fallback; vote/unvote, save/unsave, and comment count/actions.
Comment threads need author, depth/parent context, selected comment, long text
paging, collapsed branches, and load-more comments. A nested reply must never
silently switch its target to the top-level post.

### Reply to a post and reply to a comment

Both complete journeys must be easily discoverable from the review interface:

1. Select Reply on the post or on a specific comment.
2. Show the exact recipient/parent context before editing.
3. Focus a real proposed editor; explain phone keyboard input briefly. The
   prototype may provide an external simulated phone text field for typing.
4. Preserve the draft through editing, preview, and returning to the thread.
5. Preview the response with enough parent context to verify its destination.
6. Require an explicit Send action. Finishing keyboard input or tapping Enter
   while typing must not publish automatically.
7. Show sending and success; insert the simulated response beneath its actual
   parent and return focus to it.
8. Show a failure state that keeps the draft. Avoid accidental duplicate sends.

Include edit-your-own-comment, a discard-draft decision, empty-input validation,
signed-out restrictions, and locked/deleted/unavailable parent states. Display
sent/sending/failed as distinct states. This entire HTML journey stays local.

### Account and participation

Inbox/notifications, comment-reply notifications that open the correct thread,
profile with posts/comments, saved items, account/sign-in handoff, and settings
for text size/readability. Include creating a text/link post with community
selection, title/body entry, review, and explicit submission. Cover private
message/chat viewing and composing at design level, labeling integration limits
in DESIGN.md where native hooks have not been verified. Report/block and deleting
one's own content need clear confirmation views and simulated outcomes.

### Non-happy paths

Include loading, empty results, offline/load failure with retry, expired login,
posting failure/rate limit, locked thread, removed/deleted comment, and unavailable
media. Add an explicit reveal step for blurred/spoiler/sensitive content rather
than opening it on entry. Keep the HUD copy short and actionable.

## Review shell and interaction

- Outside the HUD, provide an unobtrusive flow/screen chooser and controls for
  previous, next, tap, and back. Clearly separate review controls from glasses UI.
- Support keyboard arrows as previous/next aliases, Enter/Space as select, and
  Escape as back, while allowing normal typing inside the simulated phone input.
- Include entry points for a complete post-reply demo and nested-comment-reply
  demo, plus an error-state selector or equivalent.
- The document should work when pasted into T3 `html_preview`/`html_render`.
  Use inline CSS and JavaScript, no external assets, and safe fictional media.
- T3's inline reply width is typically 728 px and can be around 360 px on phones.
  Use a fluid review layout without an outer decorative card/banner, horizontal
  page overflow, or `100vh`/viewport-based body height that causes iframe growth.
- Check normal navigation, long content, nested replies, draft retention, explicit
  send, back navigation, and narrow layout before returning. Report exactly what
  you observed. Do not claim a successful Android build or device test.

## Handoff

Return the absolute HTML path, the design notes path, an inventory summary,
observed verification results, and unresolved implementation assumptions. The
parent agent will inspect and render the result, then ask the user to approve or
request changes. Android implementation remains behind that user approval gate.
