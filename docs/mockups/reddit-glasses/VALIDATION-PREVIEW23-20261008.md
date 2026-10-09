# Reddit preview23: native navigation and Nexus keyboard validation

## Delivered behavior

Root Back and the explicit Exit row leave the Reddit task. Back within a post,
reader, media view, comments, or secondary listing restores its owning route.
Native Search provides post results, Relevance/Top/New sort, and paginated
community results. Joined communities open their exact scoped native feeds.
Latest is acquired from the validated account graph without visiting a stock screen.

Profile and Saved support native posts and comments, full-comment hydration and
exact parent context. Inbox reads notifications and replies without marking them.
Matrix chat/private-message transport is not included. Seven account preferences
are actual native values and are read only. Readability remains adjustable.

The phone and glasses Nexus hubs and phone Patcher were updated in place with
their existing release certificate. The Reddit keyboard toggle is enabled.
Automatic phone editor opening was observed from phone Home. Futo soft keys
entered `test` into an unsent Reddit reply; its draft and preview were verified.
No Send, vote, save, join, moderation or create-post write was performed.

## Important implementation checks

- Native providers and typed query arguments match the pinned 2026.14.0 APK.
  Profile post sort uses PostFeedSort.NEW; comments use ProfileFeedSort.NEW.
- Account-bound graph services replace cold or stale captured hydration services.
  Requests are discarded after leaving a route or switching accounts.
- Feed caches keep Home, Popular, Latest, Search, community, Profile and Saved
  identities separate. Returning to a list retains selection and scroll.
- Paginated secondary lists select the first newly appended entry. Native Latest
  retains its source and correlation state until account change or refresh.
- Feed options are reached by a previous swipe from the first post. Refresh loads
  current native results. Paired direction aliases still move once.
- Android default focus highlights were the white translucent wash after editing;
  disabling them restores actual black HUD pixels. Screenshots verified 480x640,
  a black corner and more than 75 percent off pixels on the post-search feed.

## Observed build results

These are the actual command tails, using the documented existing authentication,
normal process-only release signing and the existing build environment:

```text
Morphe :patches:test :patches:jar
BUILD SUCCESSFUL in 9s
211 actionable tasks: 7 executed, 204 up-to-date

Nexus :plugin-patcher:testDebugUnitTest :plugin-patcher:assembleRelease
-PskipCxrGlobal=true with the frozen YouTube bundle, preview23 Reddit source,
the complete pinned APKM and RealRedditPatchTest output enabled
BUILD SUCCESSFUL in 2m 44s
185 actionable tasks: 18 executed, 167 up-to-date

Nexus :phone-hub:testDebugUnitTest :phone-hub:assembleRelease
BUILD SUCCESSFUL in 2m 7s
253 actionable tasks: 52 executed, 5 from cache, 196 up-to-date

Nexus :shared:test :bus-client:testDebugUnitTest
:glasses-hub:testDebugUnitTest :glasses-hub:assembleRelease
BUILD SUCCESSFUL in 1m 14s
214 actionable tasks: 26 executed, 2 from cache, 186 up-to-date
```

JUnit XML totals: Morphe 110 passed; phone 721; glasses 683; shared 363;
bus-client 131; Patcher 130 passed plus 11 existing opt-in skips. No failures or
errors. Patcher verification includes the actual complete-APKM patch through the
Nexus engine and conversion of the bundle to executable Android assets.

## Device observations and limits

Actual read-only checks exercised native Home/Latest, joined communities,
post/community search, community feeds, full posts and nested comments, native
Profile/Saved posts and full comments, Inbox and preferences. Exact selection
was restored from search posts, community results and account comments; returning
through the root menu did not contaminate Home. Phone soft-key text survived
into the draft and preview. Intermediate testing found and corrected the profile
sort mismatch and navigation/paging issues. QA selectors were corrected for
root menus with Home offscreen and utility rows with empty metadata.

All checks use actual USB devices and ADB compatibility keys. They do not prove
physical R08 input or real-world optical comfort. No native authentication
re-login or live publication was needed; the existing logged-in session survived
replacement installs. Drafts are retained in memory while navigating and do not
survive process replacement. Server writes remain unverified by this delivery.

The frozen stock APKM and YouTube bundle remain unchanged. No commit, release,
publishing, local.properties change, private SDK/cache, production-key export or
permanent accessibility/device-setting change was performed.

Artifacts: E:/Tools/Rokid/reddit-patch-lab/final-preview23. Public hashes and
observed suite totals are in manifest.json. Lab evidence and exact logs are in
exit-preview18-20261008; this folder also contains private local build-reference
paths and must not be shared wholesale.

## Final device recording and signing

The 113.08-second 480x640 recording shows post-search browsing, post/thread and
comment navigation, a community result and its feed, text pages ending in an inline
image preview, one paired swipe opening the full-screen image, its action menu,
Back to the exact preview, and root Back leaving Reddit. It contains no phone
keyboard, login, draft preview or account-route recording. Those paths were checked
separately. Media screenshots were visually inspected; the actual image loaded.

Reddit uses the same existing Android debug certificate as the prior device
previews, preserving its app data and login. Nexus hubs and Patcher use their
existing normal release certificate. No phone Patcher signing key was extracted;
a future phone-generated Reddit export must also match the installed Reddit
signer to update it in place.
