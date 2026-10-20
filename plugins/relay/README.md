# Relay

Relay forwards selected Android notifications to a Nexus notice band,
keeps a menu-launched in-memory inbox, and sends an explicitly confirmed reply
through the source notification's `RemoteInput` action — dictated, or typed.
A *Type* chip appears about a second into dictation (or *Reply by typing* skips
dictation altogether), and the reply is typed inside the notice band itself on
a glasses hub from 1.4.13, from a bonded keyboard or the phone's Keyboard &
remote screen, which comes forward on its own; an older hub opens a separate
editable card instead. Typing needs a glasses hub that announces the
editable-surface bit; without it Relay falls back to dictation. A notification arriving while the wearer is dictating, typing, or
reviewing a transcript is held and shown once that exchange resolves, and the
band's own timeout is kept alive for as long as the input lasts.

Inbox conversations open as native reader documents. When the source
notification adds a message to the conversation already being read, Relay
updates that document in place without interrupting an active reply flow.

## Choose applications

Open **Nexus → Relay settings → Source apps**. Every app starts on **Messages
only**, preserving forwarding of notifications with a free-form Reply action,
including the first message from a newly installed app. Choose **Off** to exclude
an app, or explicitly choose **All notifications** to include its read-only
alerts. The extra alerts skip ongoing/foreground-service notifications and group
summaries. Launchable apps are listed before they send anything; other sources
appear once Relay observes a notification. Search by app name or package.

Both kinds use the existing band and inbox, titled **Notifications** when an app
uses All, **Messages** otherwise. Read-only
alerts offer no Reply; hidden alerts offer Show. A source notification leaving
the phone keeps its in-memory readable copy but loses reply authority. Changing
an app to Off removes its retained content and stops an active reply; changing
back to Messages only removes that app's read-only alerts. Changing settings
does not cancel source notifications on the phone, and rebuilding the inbox
never replays existing alerts as new bands.

Some messaging apps repost a changed notification containing your own reply
after you send it. In All mode this can appear as a new read-only alert. Identical
content reposts are suppressed; choose Messages only for that app to exclude
these read-only updates.

Notification content, sender names, images, and speech text are process-memory
only. The settings screen stores feature flags, the thread message limit, and
explicit per-package policies. No additional Nexus capability or hub update is
required.

The folded test harness includes a **Read-only alert**. First explicitly choose
**Source apps → Relay → All** to test its forwarding; posting it
never changes the policy for you. Relay itself is included in Source apps so
this test can use the same explicit opt-in as any other notification source.
