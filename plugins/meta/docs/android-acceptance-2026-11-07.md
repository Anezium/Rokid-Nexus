# Meta Messages — Android acceptance, 2026-11-07

Samsung SM-S916B, Android 16 / API 36, debug-signed `plugin-meta-debug.apk` from
`proto/meta-messages` (one gomobile AAR binding `mobile/whatsapp`,
`mobile/instagram` and `mobile/messenger`, 9.9 MB).

## Observed

- Installed as a new package and approved in Nexus → Plugin access.
- The first build could not be opened from Nexus: `START_CLASS_NOT_FOUND` (-92).
  Nexus resolves a leading-dot `SETTINGS_ACTIVITY` against the application id
  (`…plugin.metamessages`), while the classes live in the `…plugin.meta`
  namespace. The descriptor now names the activity in full. Nexus kept the old
  descriptor until its home activity was recreated.
- The hub opened from Nexus → Plugins → Meta Messages with `FLAG_SECURE`
  (`fl=0x81812100`) and listed WhatsApp, Instagram and Messenger as ready.
- Instagram, with the TEST session copied by `run-as`, connected automatically
  on its page. After returning to the hub, its row read "Signed in as @…". Reopening
  the page reconnected without a password.
- Messenger's page opened its engine and showed "Not signed in" with the sign-in
  form. No login was attempted.
- WhatsApp's page opened its engine and showed "Unlinked · offline" with the link
  form. No pairing was attempted.
- After the pages closed, the plugin ran no service.
- The Telegram prototype (separate APK, branch `t3/telegram-plugin-prototype`)
  opened from Nexus with `FLAG_SECURE` and reported "API credentials needed".

## Owner logins, 16:30

The owner then signed in to all three services from this plugin on the phone,
typing credentials and codes only into the plugin's own fields.

- Messenger: the native Messenger Lite login completed on the phone and stored a
  session; reopening the page showed "Connected", "Signed in and connected." This
  is the first service whose login completed on the phone itself; the Instagram
  login on the phone still fails with an expired approval request.
- WhatsApp: paired as a linked device; the page showed "Linked · authenticated".
- Instagram: still connected with the copied session.

At 16:40, with the owner's go, one Messenger self-test was sent from the page:
the dialog showed the exact text "Test Nexus Messenger — envoi depuis le
téléphone." and was confirmed once. Messenger returned a message id for the
admitted offline threading id ("Messenger accepted the text."); the plugin holds
exactly one admission and one acknowledged result. The account's own thread
accepted this non-encrypted Lightspeed send. The owner then saw the text in the official
Messenger app.

## Glasses dictation, 17:20–17:26

The first glasses flow (plain surface cards) listed services and conversations
and prefetched every signed-in service when opened. Ready times logged on the
phone: WhatsApp 1251 ms, Instagram 2455 ms, Messenger 7410 ms.

- First dictation: "Lost the glasses link". The hub logged
  `Hi Rokid glass BT connected=false` while SPP stayed up: speech needs the Hi
  Rokid Bluetooth (CXR-L) audio path, not only SPP.
- After the owner reconnected Hi Rokid, a dictated WhatsApp message to a
  conversation the owner picked was reviewed, sent with Send, and the owner saw
  it arrive. The WhatsApp outbox changed at 17:26; the Instagram and Messenger
  send records did not change.

The owner judged this card-based glasses UI off-system; it is being rebuilt on
Nexus 2.0 pages.

## Not tested

Messenger image and Instagram sends from this APK, Messenger encrypted chats
with other people, the
grace services in this APK, Telegram login and sends (need the owner's api_id and
api_hash), receiving messages, endurance, and the glasses.
