# Changelog

## Unreleased

- **The glasses can name who messaged without showing what.** Two new switches:
  *Hide message text on the glasses* keeps the band to the sender and a Show chip
  — tap it when the moment is right, and Reply is still one tap away — and *Hide
  previews in the inbox* keeps the list to names; opening a conversation still
  reads it. Read-aloud names the sender only while the band is hidden.
- **Message display time is now a number you type.** Two to 45 seconds, 3 by
  default, with a separate *Longer for long messages* switch that adds reading
  time per character. Existing settings carry over: Auto becomes 3 seconds with
  scaling on, a fixed value stays fixed.

## 1.2.0

- **Inbox conversations now open as full-screen reader documents.** Messages
  wrap as prose and scroll in the native glasses renderer, while tapping still
  starts a reply and Back still returns to the Messages list.
- **An open conversation now updates as new messages arrive.** Relay refreshes
  a reader in place while preserving its scroll position, without interrupting
  dictation, reply review, or sending.

## 1.1.4

- **After a reply is sent, the inbox comes back a bit sooner.** The "Sent"
  screen used to hand you back to the list after 1.2 seconds — which turned
  out to be the exact moment most people tap Back, so the tap landed on a
  list they had not seen yet and threw them out of Relay entirely. The
  hand-back now happens at 0.85 seconds: still enough to read "Sent", but
  the switch is over before your thumb gets there.

## 1.1.3

- **Messages Android hides can now come through.** Since Android 15, the system
  blanks out any notification it reads as carrying a code — and it reads far
  more than real codes that way, so an ordinary message with a few digits in it
  arrived on the glasses as "Sensitive notification content hidden". The only
  exemption Android grants is to an app registered as a device's companion,
  which is exactly what Relay is, so a new **Show messages Android hides** entry
  under Access offers to register it with your glasses. It is off until you ask
  for it, and Android's own dialog tells you what registering covers before
  anything happens.
- The registration is worth having on its own: it also lets Relay keep running
  when the system would otherwise stop it, which is one of the ways a message
  used to go missing entirely.
- **When Android does hide a message, Relay says so in its own words** instead
  of showing you the system's placeholder as though it were the message.

## 1.1.2

- **A message now waits for the glasses instead of being dropped after five
  seconds.** The link to the glasses drops and heals on its own all the time —
  a pocket, a doorway, standby — and healing routinely takes ten to thirty
  seconds. Relay used to give a captured message five seconds to reach the
  glasses and then silently gave up: the message sat in the inbox, and the
  band never came. A message now waits out the reconnection for up to two
  minutes and shows the moment the link is back. Relay also stopped trusting
  a "sent" that never left the phone — a send that dies on a broken
  connection is now retried instead of counted as delivered — and a few
  transient hiccups during registration no longer throw the waiting message
  away. If a message still can't be shown, the log now says exactly what
  blocked it, so field reports can point at the culprit.

## 1.1.1

- **Choose how long messages stay up.** Display time has always scaled with
  the length of the message, which means a two-word text was gone in about
  four seconds — often before your hand reached the touchpad, let alone the
  Reply chip. A new Message display time stepper sets a fixed duration
  instead, from 5 to 45 seconds, applied to every message whatever its
  length. Auto, the default, keeps the scaling behavior unchanged. Reading
  aloud is not affected: while the glasses speak a message the band already
  stays up for the reading, plus the usual answering window after it.

## 1.1.0

- **Read notifications aloud.** A new switch, off by default, has the glasses
  speak a message when it arrives. It reads the newest message whole — not a
  preview, not the first line, because a message cut off halfway sends you back
  to your phone anyway, which is the thing Relay exists to avoid. The band is
  held open while it reads and gives you the usual answering window once it
  stops, so a message is never still being read after its band has gone.
  Answering interrupts the reading, and so does dictating: the glasses stop
  talking the moment they start listening. Needs Rokid Nexus 1.1.5 on the hub;
  the reading is produced on the glasses, so nothing leaves the device and no
  network is involved. Speed and voice come from your Rokid assistant settings.

- **"Sent" lands on the chip you were watching.** Sending used to confirm
  itself above the button while the button went on counting down to a send that
  had already happened. The countdown chip now becomes the confirmation in
  place, and the line above it goes quiet.

## 1.0.2

- **Black out behind notifications.** A new switch, off by default, asks the
  glasses to hide everything else while a Relay notification is up — only the
  notification shows, the way the Even G2 does it. Leaving it off keeps the
  band floating over whatever you were looking at. Needs Nexus 1.1.4 on the
  hub; older hubs simply ignore the request.

- **The test harness can crowd the inbox.** An Eight threads button posts
  eight conversations at once, each from its own sender — one more than the
  glasses list shows at a time, which is exactly the case the inbox needed
  testing against.

## 1.0.1

- Relay has its own mark instead of the shared `chat` icon: two bubbles, the
  message that arrived and the answer going back, with the upper outline
  breaking where the reply crosses in front. The same mark is now the app icon,
  so what you tap in the Store is what you find afterwards.

## 1.0.0

- Initial notification listener, notice, menu-launched inbox, explicit voice
  reply, and local fake notification harness.
