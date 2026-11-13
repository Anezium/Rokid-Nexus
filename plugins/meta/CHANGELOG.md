# Changelog

## 0.1.2

- **WhatsApp groups open on the glasses.** They were listed dimmed as
  "Encrypted · reply from the phone", although WhatsApp's group encryption was
  never the problem. You can now read a group, with each sender's name, and
  answer it with text. Photos to groups stay off. A group first seen after
  pairing shows its real name instead of a number.

## 0.1.1

- **Meta's own mark.** The plugin now shows the Meta infinity instead of the
  generic chat glyph: as one green stroke on the glasses launcher and in
  notification cards, and as the filled mark for the Android app icon and the
  Nexus plugin list. The APK had no icon of its own before.

## 0.1.0

First release. Needs **both hubs at Nexus 2.2.0** for photos, thumbnails and
service icons; older hubs show conversations as text.

- **WhatsApp, Instagram and Messenger on the glasses.** Pick a service, then a
  conversation, and read its latest messages in one scrolling column. Older
  messages load when a conversation shows fewer than you asked for (10, 30 or 50
  in the phone settings).
- **Reply from the glasses.** Dictate or type an answer in the conversation's
  composer. Nothing is sent before you tap Send; the result says the message was
  handed to the service, never that it was read.
- **Photos.** Take a picture from any conversation with the **Photo** button,
  check it on a full-page preview, then Send, Retake or Cancel. Photos, reels and
  posts you receive show as thumbnails in their bubbles, and photos you send from
  WhatsApp carry their own preview.
- **Sign in on the phone.** WhatsApp links as a companion device with a pairing
  code. Instagram and Messenger sign in with your account on the phone; passwords
  are masked and never leave it.
- **Nothing in the background.** A service connects only while its glasses page
  is open, and again when you come back. A sign-in or pairing left waiting gets
  up to three minutes to finish after you leave its screen.
- **Unofficial clients.** Meta Messages is not made or endorsed by Meta. Meta may
  sign the device out, or restrict or ban an account that uses third-party apps.
  The plugin says so before you sign in.

Messenger's end-to-end encrypted chats are listed but cannot be answered yet.
WhatsApp shows thumbnails for photos received from this version on; older
messages keep their caption.

Meta Messages is licensed under the AGPL-3.0, as the engines it embeds require;
the other Nexus components keep their own licenses.
