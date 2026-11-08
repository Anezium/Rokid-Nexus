# 033 — Relay → Meta Messages conversation bridge

Status: decided, not started. Scheduled after the first Meta Messages release.

## Goal

A Relay notification from WhatsApp or Instagram offers **Open in Meta Messages**. That opens
the same conversation in Meta Messages, with its history, thumbnails and the composer's
Photo. Relay stays the alert and Meta Messages becomes the destination. Neither plugin
duplicates the other.

## Why the hub owns it

Page navigation targets must stay inside the providing plugin's namespace
(`PageTemplateContract`, "Navigation targets must stay inside [namespace]"). So Relay
cannot point at a Meta Messages page, and it must not be able to: a plugin able to open
another plugin's pages with params could drive them. The routing therefore lives in the
hubs:

1. Meta Messages declares, in its manifest, the source packages whose conversations it
   opens: `com.whatsapp`, `com.instagram.android`, `com.facebook.orca`.
2. For a Relay notification from one of those packages, the glasses hub adds a hub row
   **Open in Meta Messages** to Relay's conversation page. Relay draws nothing new.
3. On tap, the phone hub asks Meta Messages for its conversation page, with params
   carrying the source package and the conversation key read from that notification.
4. Meta Messages resolves the key to a listed chat and opens it. With no exact match it
   fails closed: "Conversation not found" and the service's chat list. It never opens a
   chat by guess.

No plugin capability, receive prefix or SDK surface is added for plugins to call.

## Conversation keys (verified on device, 2026-11-07, Samsung SM-S916B, Android 16)

- **Instagram**: `Notification.shortcutId` is null. The key is in the notification
  **tag**: `direct|<10 digits>;thread_id:<39 digits>`.
  - The `thread_id` is the Instagram thread id (39 digits, prefix `340282366841710`). It
    equals the engine's `slidetypes.ThreadInfo.ThreadID` (json `thread_id`): an exact
    match.
  - The 10 digits after `direct|` are most likely the receiving account's id. Unverified.
    If confirmed, use it to refuse a notification addressed to another Instagram account
    than the one signed in.
- **WhatsApp**: expected to be the chat JID (`<number>@s.whatsapp.net`, or `@g.us` for
  groups) in `shortcutId`. Not yet observed on a live notification. Check it first, the
  same way.
- **Messenger**: not installed on the test phone, so no key observed. Without a verified
  key, Messenger is left out rather than matched by title.

How keys were read: `adb shell dumpsys notification --noredact` on a live notification.
Shell `dumpsys shortcut` and `cmd shortcut get-shortcuts` redact ids as `***`.

## Privacy rules

- A conversation key is an identifier: a WhatsApp JID is a phone number. It stays on the
  phone, travels only from the phone hub to Meta Messages, never reaches the glasses, and
  is never logged or persisted by the hubs.
- Meta Messages logs durations and outcomes only, never ids, names or contents, as
  everywhere else in the plugin.

## Work

- shared + BUSSPEC: the manifest declaration, the hub row, the phone-local open request.
- phone hub: map a Relay notice to its source package and key; hand off to Meta Messages.
- glasses hub: the hub row on Relay conversation pages, version-gated like the other
  launcher bundle additions.
- Relay: expose the source package and key to the phone hub only, never on the wire to
  the glasses.
- Meta Messages: resolve keys (Instagram `ThreadID`, WhatsApp JID), open the chat page,
  fail closed.
- Tests on every layer. Device check: an Instagram DM notification opens the right chat,
  and a non-matching key shows "Conversation not found".

Estimate: about one day, device test included.

## Not in scope

Waking Meta Messages from notifications to keep its activity fresh was considered and
set aside for now.
