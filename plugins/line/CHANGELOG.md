# Changelog

## 0.1.0

First release. Needs **both hubs at Nexus 2.4.0**: older hubs still read and
send, but a refused send shows as unconfirmed instead of staying editable.

- **LINE on the glasses.** Your conversations, groups included, as launcher
  pages: open one and read its latest messages in one scrolling column. Photo
  and sticker thumbnails show in their bubbles; sealed photos are decrypted and
  authenticated on the phone.
- **Reply from the glasses.** Dictate or type in the conversation's composer.
  Nothing is sent before you tap Send, and the result says the message was
  handed to LINE, never that it was read. A send LINE refuses keeps your draft.
- **Photos.** Take a picture from a conversation with the **Photo** button,
  check it on a full-page preview, then Send, Retake or Cancel. The photo and
  its preview leave the phone sealed with Letter Sealing; the key travels only
  inside the sealed message, never beside it.
- **Sign in on the phone.** Email and password, then the PIN LINE shows: a
  **Copy PIN** button puts it on the clipboard, marked sensitive, and the
  screen clears it again, the next time it is in front, once the sign-in is
  over. The email and password are used only to authenticate with LINE; the
  password is masked and never saved.
- **Letter Sealing required.** Conversations without an encryption key cannot be
  written to from the glasses; the plugin says so instead of sending plaintext.
  An ambiguous send is kept, never retried on its own.
- **LINE's own mark.** The LINE bubble, not a generic chat glyph: stroked on the
  glasses launcher and notification cards, filled for the Android app icon and
  the Nexus plugin list.
- **Nothing in the background.** LINE connects only while its glasses page is
  open, and again when you come back.
- **Unofficial client.** LINE Messages is not made or endorsed by LY
  Corporation. LINE may sign the device out or restrict an account that uses
  third-party clients; the plugin says so before you sign in.

Not covered by this version: direct chats were verified for reading only, the
sealed send was exercised in a group; Keep Memo is not listed; unsend and
token expiry were not tested.

LINE Messages is licensed under the Apache License 2.0, like the rest of Rokid
Nexus; the embedded engine's MIT-licensed dependencies are listed in the
plugin's Open-source licenses screen.
