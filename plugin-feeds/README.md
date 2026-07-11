# Nexus Feeds plugin

`X (WebView)` is an experimental alternative to `X (account)`. Both reuse the
single X login in the plugin settings. The existing account source makes its own
internal API request; the WebView source instead lets `x.com` issue the request
and captures the `HomeTimeline` or `HomeLatestTimeline` GraphQL response through
injected `fetch` and `XMLHttpRequest` wrappers. A document-start script is used
when the installed Android WebView supports it, with page-start reinjection as a
fallback. It does not read the page DOM.

## One-time setup

1. Open Nexus Feeds settings on the phone and connect the X account.
2. Tap **Grant display-over-apps access** and allow Nexus Feeds to display over
   other apps. This permission is needed for the invisible 1 x 1 WebView window.
3. Select **X (WebView)** and save settings.

While this source is open, a foreground service owns the transparent overlay and
WebView. Closing the Feeds surface tears down both. Moving forward at the end of
the captured page scrolls the WebView to trigger X's next network response.

The live WebView, JavaScript execution, overlay, and infinite-scroll path require
on-device testing. JVM tests cover URL matching, interception payload properties,
the shared GraphQL parser path, timeout and missing-session messages, and repeated
response pagination stopping.
