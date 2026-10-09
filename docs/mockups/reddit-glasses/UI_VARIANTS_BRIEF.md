# Reddit AR HUD: three new UI directions

## User request and scope

On 2026-10-08 the user asked Claude Fable 5.1 to propose several new HTML mockups with genuinely different visual/layout styles. Start the delegated design task at 15:00 Europe/Paris on 2026-10-08. Use provider claudeAgent, model claude-fable-5-1, after verifying the live catalog. Never substitute a different model silently.

The previous complete HTML mockup was approved, and Reddit preview13 now runs on the actual glasses. After viewing real feed screenshots and a navigation recording, the user finds the current large-text feed unpleasant. Their new hardware constraint is a GREEN MONOCHROME see-through AR display. This supersedes the original multicolor/orange mockup presentation.

The user explicitly LIKES the existing menu model:
- A main list with Home, Popular, Latest, Communities, Search, Inbox, Profile, Saved and other routes.
- Inside a post/thread, a list of actions such as Read post, Comments, Reply to post, View media.
- Inside a comment, an action menu including Reply to this comment.

Keep these menus, discoverability and single-axis controls. Focus the redesign on browsing, hierarchy, visual rhythm, media presentation and comfortable reading. Do not remove functions to make attractive static pictures.

This is design-only work for an official Reddit APK adapted by Nexus Patcher/Morphe. Do not implement Android changes until the user selects and approves a direction.

## Deliverables and edit boundary

Only create/edit:
- E:/Tools/Rokid/RokidNexus/docs/mockups/reddit-glasses/ui-variants/index.html
- E:/Tools/Rokid/RokidNexus/docs/mockups/reddit-glasses/ui-variants/DESIGN.md

index.html must be one complete, self-contained HTML/CSS/JS document containing THREE distinct interactive design directions with an external comparison/style switcher. Each direction must be navigable; switching styles must make real differences in layout, hierarchy, typography and treatment of previews/selection, not merely change an accent or font. Give each a name and explain its browsing/reading tradeoff. Recommend a direction, while leaving the choice to the user.

Preserve the earlier approved docs/mockups/reddit-glasses/index.html and DESIGN.md. Read them as background if useful. Code, comments, documentation and HUD copy are English. No external assets, fonts, scripts, CDN, network requests, credentials, real login or real Reddit writes. All example data is fictional and local, shared across the directions to allow fair comparison.

Use the frontend-design and rokid-glasses-dev skills. Do not spawn additional agents, change Android/Morphe code, Gradle files, patch pins, APKs, signing material, machine setup, build or install anything, commit, release, publish or contact anyone.

## Observed problem and implementation findings

The real current feed is a full-screen reader: header HOME/POPULAR n/7; community and author; Page n/m; title and body in the same large 25px text block. Swiping pages through ALL of a long body before advancing to the next post. In the recorded Popular example one post took four pages. This prevents quick scanning and creates monotonous walls of text. Media is hidden behind a View media action; the browsing view has no thumbnail. Current comment rows show author and a clipped body; full comment context exposes technical parent IDs instead of useful human context.

Suggestions discussed with the user:
- Explore a compact feed with roughly two readable post summaries visible, rather than opening full bodies automatically.
- One navigation step advances to the next POST while browsing; paging long text only occurs after choosing to read.
- Include title, community, a limited preview, media type/thumbnail and comment count when space permits. Do not force every metadata field into every row.
- Keep readable text; merely shrinking everything is not a solution.
- Maintain exact feed selection and scroll position when returning from a post/thread.
These are useful directions, not mandatory layouts. Fable should propose three thoughtful alternatives rather than three copies of the same list.

Actual preview13 has exercised native login, Home/Popular feeds, image/GIF/gallery/video viewing, nested comments, and unsent post/comment drafts and previews using the existing Nexus phone keyboard. Tokens stay in Reddit. Live Send/server success, votes/save, moderation, create-post and several account routes remain unverified; physical R08 has not been tested. Phone remote controls have known limitations. The recording uses ADB compatibility keys, and must not be treated as proof of physical controls.

Real evidence (optional local visual reference, not a dependency of the final HTML):
- E:/Tools/Rokid/reddit-patch-lab/device-preview13-feed-home.png
- E:/Tools/Rokid/reddit-patch-lab/device-preview13-feed-popular.png
- E:/Tools/Rokid/reddit-patch-lab/device-preview13-feed-actions.png
- E:/Tools/Rokid/reddit-patch-lab/reddit-navigation-demo-poster.png
- E:/Tools/Rokid/reddit-patch-lab/reddit-navigation-preview13-final-20261008.mp4
- E:/Tools/Rokid/RokidNexus/docs/mockups/reddit-glasses/RESUME.md
Do not expose the user's account, credentials or device identifiers. The approved mockup contains safe fictional data and can be used as a starting point.

## Monochrome AR rendering

- Actual HUD reference viewport: 480 x 640 portrait. Scale the WHOLE viewport proportionally at narrow widths; never clip the bottom.
- True black #000000/off pixels inside the HUD; active pixels are GREEN ONLY. Black is see-through, not a dark opaque panel.
- Use a restrained single green hue with luminance/intensity variation if useful. All media previews and icons must be rendered as green-on-black monochrome; no orange, red, blue, full-color photos or semantic reliance on hue.
- Selection, votes, failures and success must remain distinguishable through shape, outline, icon, wording or weight. Do not rely on color differences.
- Outline/no-fill controls; no large green/white/gray filled cards, gradients, frosted panels, shadows, artificial glow or decorative neon. Keep lit area controlled and the real scene visible.
- Use readable typography and a clear hierarchy. Short titles and previews in browsing; comfortable spacing and measured paging in detailed reading. Avoid both tiny metadata soup and the current giant undifferentiated paragraph.
- Supply safe inline fictional media (SVG/canvas/CSS); use clear media types and image/gallery/video states. Optional simulated real-world backdrop for review belongs OUTSIDE the HUD, must be labeled a simulation, and must never turn into the app background.
- Avoid heavy animation and bright dense surfaces.

## Input and menu model

One reliable swipe axis plus tap/select and Back. No touchscreen, separate vertical/horizontal axes, dragging, pointer or sliders.
- Browsing: previous/next selects previous/next post directly, without paging full post bodies first.
- Tap exposes the familiar post/thread actions; Read/Open, Comments, Reply and media must be easy to reach.
- Actions: the same axis cycles entries, tap activates, Back closes the action menu.
- Detailed reading: the same axis pages text; Back returns to the exact originating item/state.
- Comments: list previews and useful nesting/parent context; selected item visible; tap opens familiar comment actions/full reading route; support collapse/expand and load more.
- All lists use the measured remaining HUD viewport. Keep canonical selection shared with rendering, follow-scroll and activation; no visible scroll rails.
- Alias right/down as next and left/up as previous, debounced together about 250ms. A paired direction must move once.
- Every screen has a clear Back/escape route; preserve drafts and reading/feed state.

## Coverage across the three directions

Each design direction must demonstrate:
1. Main navigation and Home/Popular/Latest feeds with multiple text lengths/types, feed sort and a community route.
2. Opening/reading a post, media (image/gallery/GIF/video), the post action menu, nested comments and the comment action menu.
3. BOTH reply-to-post and reply-to-comment flows: immutable exact parent context; phone keyboard editor; draft retention; preview; explicit Send; simulated sending/success/failure keeping the draft and preventing duplicate send. Enter in an editor inserts a newline and never publishes.
4. Search, inbox/profile/saved/settings/sign-in, create text/link post with review, and loading/empty/offline/locked/expired/sensitive/media-unavailable states. Secondary states may share a coherent design system/component framework across the directions, but remain navigable and preserve the previous app coverage.
5. Text-size/readability controls and long-title/body/comment examples, without losing selected rows or overflowing.

Report/block/delete/chat can remain design-level proposals from the previous mockup and must be labeled as unverified native integrations in DESIGN.md. Do not falsely claim the HTML validates actual native APIs.

## Review and verification

Outside the HUD provide a style chooser, screen/flow chooser, Prev/Tap/Next/Back controls, direct post-reply/comment-reply demos and error simulation. Review chrome should use T3 theme variables; inside the HUD stays black and green.

Support arrow aliases, Enter/Space select and Escape back while allowing normal text entry in the simulated phone field. No accidental navigation while typing.

T3 reply width is normally 728px and about 360px on phones. Keep the outer page fluid and transparent, with no decorative outer card/banner or 100vh-based height. No horizontal overflow. The document must work pasted into T3 html_preview/html_render.

Verify all three directions, browsing multiple posts, action menus, detailed paging, media, nested comments, both reply flows, draft retention, explicit Send/errors, back restoring position, paired-key debounce and 360px layout. Use T3 native preview tools; do not switch to standalone browser automation when those tools are available. Record observed checks and limitations honestly.

Return the two actual file paths, a short comparison of the three directions, a recommendation, observed verification and unresolved assumptions. The parent will inspect files, preview at 728px/360px, render the proposals in this thread, and ask the user to choose/approve before any Android UI implementation.
