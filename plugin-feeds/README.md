# Nexus Feeds plugin

Feeds puts social timelines on the glasses HUD. The source menu on the glasses
offers **Bluesky** and, when the X account is connected, **X (account)** and
**X (WebView)** — availability follows what is configured in the plugin
settings on the phone.

Navigation is three levels deep: the timeline (one post per card), a tap opens
the post's **thread**, and a tap on a post with media opens the **gallery**,
which downloads the actual photo and renders it on the HUD through the binary
image surface (with a text fallback when the image link is unavailable). In
list views, media shows as inline markers: `[photo]`, `[N photos]`, or
`[video M:SS]` with the clip duration — video playback itself is not a HUD
surface.

## X (WebView)

`X (WebView)` is an experimental alternative to `X (account)`. Both reuse the
single X login in the plugin settings. The existing account source makes its own
internal API request; the WebView source instead lets `x.com` issue the request
and captures the `HomeTimeline` or `HomeLatestTimeline` GraphQL response through
injected `fetch` and `XMLHttpRequest` wrappers. A document-start script is used
when the installed Android WebView supports it, with page-start reinjection as a
fallback. It does not read the page DOM.

### One-time setup

1. Open Nexus Feeds settings on the phone and connect the X account.
2. Tap **Grant display-over-apps access** and allow Nexus Feeds to display over
   other apps. This permission is needed for the invisible 1 x 1 WebView window.
3. Select **X (WebView)** and save settings.

While this source is open, a dedicated foreground service (`XWebViewHostService`)
owns the transparent overlay and WebView. Closing the Feeds surface tears down
both. Moving forward at the end of the captured page scrolls the WebView to
trigger X's next network response.

The live WebView, JavaScript execution, overlay, and infinite-scroll path require
on-device testing. JVM tests cover URL matching, interception payload properties,
the shared GraphQL parser path, timeout and missing-session messages, and repeated
response pagination stopping.
