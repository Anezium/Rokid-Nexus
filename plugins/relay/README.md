# Relay

Relay forwards selected Android notifications to a Nexus notice band,
keeps a menu-launched in-memory inbox, and sends an explicitly confirmed reply
through the source notification's `RemoteInput` action — dictated, or typed.
A thread answered from Relay leaves that inbox and the launcher's Relay list until
a new incoming notification arrives. While its launcher reply screen stays open,
the wearer can dictate or type and send multiple consecutive replies. Leaving that
screen ends its reply authority; reopening retained text cannot send again until a
new notification arrives. An answered thread also leaves the launcher's Notifications
list, including its removed entries.
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

With the glasses' Nexus launcher and hubs that both support pages, the Relay card
in the launcher opens `relay:main`, the inbox's conversations as a list, and
each conversation as a document of who said what, headed by the time Relay
captured it; there is no per-message time to show. A long conversation keeps
its newest messages and says that earlier ones are not shown. Pages follow the
hidden-preview and redaction settings, follow new captures while on screen at
most twice a second, and never read aloud or mark as read.

Where both hubs run the launcher bundle, each conversation is instead a
conversation page keyed by the same thread id its notices carry: the messages
on their sides as the notification names their authors (received, ours, or
neither for plain text), the capture time once, and the hub's composer. The
composer's Send is checked against that exact thread's live reply action just
before Relay hands the text to the app, answered once, never retried, and
refused as `authority_lost` once the action is gone. A reply Relay handed off
stays in its thread as ours ("handed to the app", or unconfirmed when the send
failed without saying), within the thread message limit, until the app's own
copy shows up. When an unanswered notification leaves the phone, Relay tells the glasses
the event is gone, the page keeps its text without Send, and a band waiting to
send it stops and keeps the wearer's words unsent. After a successful send, an open
reply screen retains its action for further replies until that screen closes or
its visible lease ends. Clearing the phone notification after a reply is deferred
until then; a cancelled reply intent still fails closed. A band's send countdown only
runs while its updates reach the glasses.

The notice band, the inbox and a page share the plugin service's one hub
connection.

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
never replays existing alerts as new bands. On the launcher, a read-only alert
is a conversation page without Send, and changing an app to Off also empties
its pages.

Some messaging apps repost a changed notification containing your own reply
after you send it. Relay suppresses self-authored MessagingStyle updates, unchanged
incoming content and recent outbound text echoes before they reach the notice or
read-aloud pipeline, with or without a Reply action and in both source modes.
Per-thread echo markers survive source removal. Exact outbound-text matching for
read-only notifications across keys is package-scoped and bounded to two minutes; a newly authored
incoming message is admitted even when it repeats the reply's words.

Notification content, sender names, images, and speech text are process-memory
only. The settings screen stores feature flags, the thread message limit, and
explicit per-package policies. No additional Nexus capability is required. The
launcher history dismissal behavior requires the matching glasses hub update.

## Assistant access

On hubs supporting Skills, approve each Assistant operation separately in
**Nexus → Plugins → Relay → Plugin access**. Drafting and sending a reply do not
grant access to captured message text. Assistant shows the draft's recipient and
text and waits for the wearer's **Send**; it never sends because a model asks it to.

**Find conversations Assistant can read** lists sender, app, age, and availability
without message text. **Share conversation text with Assistant's AI** shares the requested
thread's captured text with Assistant's chosen AI provider. Both start disabled.
Assistant uses reading only on an explicit request to consult messages, never to
prefetch context for an ordinary reply. It can read the latest received message or
the retained conversation, including our replies. Long threads page without dropping
captured text; a changed thread invalidates its page cursor. Reading references bind
the conversation's identity and are separate from reply references.

Retained conversations remain readable after their notification leaves, subject to
notification access, Relay being enabled, the current source-app policy, and inbox
privacy settings. This is captured notification history, not the app's full archive.
Reading does not fetch additional app history, mark messages as read, or send anything.
After an update adding these operations, Relay needs reapproval; the new reading
operations also need their own explicit approvals.

The folded test harness includes a **Read-only alert**. First explicitly choose
**Source apps → Relay → All** to test its forwarding; posting it
never changes the policy for you. Relay itself is included in Source apps so
this test can use the same explicit opt-in as any other notification source.

**Reading conversations** in the same harness posts three fictional exchanges:
the **Mika et José** group arranges dinner and changes its time; **Jean** asks for
several registration documents and checks across multiple messages; **Léa** arranges
a cinema visit. Each includes the device user's own turns. They are real local
MessagingStyle notifications with separate identities and reply actions, not messages
sent to real contacts. Try asking for the group's summary or what Jean requested in
his recent messages. The existing Telegram bot remains a separate real-app test.
