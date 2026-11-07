# Relay reply session: physical-device verification

Date: 2026-11-07. Source revision: `d6a5a9583f27c3d7046986c7c1d0364786c9c93b`.
Devices: Samsung SM-S916B (Android 16) and Rokid RG glasses (Android 12).

## Observed behavior

A local test app posted a real `MessagingStyle` notification with a mutable
`RemoteInput`. Relay captured the message and completed one TTS reading of it.
Replies were typed through Nexus's normal phone compose keyboard and explicitly
confirmed with Send on the glasses. The test app had no Internet permission,
account or real recipient.

1. The first reply reached the app through Android RemoteInput. The app reposted
   it as a self-authored message and removed its notification 1.2 seconds later.
2. Without leaving the glasses conversation, a second reply also reached the app.
   Both replies remained visible as handed to the app. The second reply generated
   another outbound echo and source-notification removal.
3. Relay rejected both reposts as `self_echo`. The observed run contained exactly
   one TTS request and one completion, both for the original incoming message.
   Neither reply generated another TTS request.
4. Back closed the reply screen and triggered one successful notice withdrawal.
   The launcher notification count became zero, and Notifications showed no rows.

The run recorded two RemoteInput deliveries, two rejected echoes, two source
removals, one TTS request/completion and one successful withdrawal. Original
screenshots and filtered event logs were retained locally; no personal content
or device identifiers are included here.

## Method and limits

Confirm, direction and Back were injected through the physical Rokid touchpad's
input device after ordinary Android injected keys were found to bypass the
accessibility filter. The fixture used NotificationManager and Android's normal
notification delivery, not a direct call to Relay's repository or bus handlers.

This verifies reply-session lifetime, echo filtering and history dismissal on
physical hardware against a controlled notification producer. It does not verify
WhatsApp's current notification format or message delivery, speech recognition,
or physical touchpad/ring gestures. Replies were typed, not dictated.

The temporary app and key helper were removed. Read notifications aloud and Reply
by typing were restored to their original disabled values. No app data was cleared.
