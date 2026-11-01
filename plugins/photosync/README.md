# Photos Sync

> **Deprecated.** The Hi Rokid app now syncs photos and videos from the glasses
> to the phone itself, so this plugin is no longer developed. It stays in the
> Store for existing users and its source stays here as the reference for a
> binary transfer over the bus.

Copies the photos and videos you shoot with the glasses' camera button into your
phone gallery, on their own, the way a phone camera backs itself up.

## How it works

- Everything travels over the **Bluetooth connection the glasses already have**.
  No Wi-Fi, on either device, at any point. That costs speed — about 4-5 s for a
  photo, minutes for a video — and buys a transfer that simply works whenever the
  glasses are connected.
- The **glasses hub** watches `/sdcard/DCIM/Camera` and serves captures on
  request. A file only becomes eligible once two scans agree on its size and
  mtime, so a video that is still recording is never sent.
- The **phone hub** pulls the catalog, diffs it against a persistent ledger, and
  writes each new capture into `Download/Hi Rokid/` through MediaStore — the same
  album Hi Rokid's own imports use, with the same filenames. Every file is
  SHA-256 verified before it is published, and the ledger is authoritative:
  deleting a photo from your gallery does not bring it back on the next sync.
- Because the link is shared with everything else the glasses do, the transfer is
  **polite**: it sends one 32 KiB chunk at a time, steps aside whenever other bus
  traffic is flowing, and stops immediately when the camera opens. Anything it
  interrupts resumes from where it stopped, so a long video finishes across
  several sessions rather than restarting.
- This plugin is the control surface. It holds no sync state of its own: sync
  keeps running while the plugin process is dormant. `PhotoSyncRuntime` mirrors
  the hub's `/mediasync/status` pushes and turns the screen's actions into
  `/mediasync/settings` and `/mediasync/now` sends; `PhotoSyncSettingsActivity`
  renders them on the Nexus design kit.

## Settings

- **When to sync** — *Always* (as soon as you capture), *While charging* (the
  default), or *Manual only*. *Sync now* works in all three, at any time.
- **Delete from glasses after sync** (off by default) — frees glasses storage. A
  capture is deleted only after it is safely published on the phone. If the
  glasses ROM refuses the delete, the screen says so instead of pretending: the
  files stay where they are.

## Requirements

- The `mediasync` capability, approved once in Rokid Nexus → Plugin access. That
  approval is what switches photo sync on at all.
- Storage access granted to the glasses hub (it asks once, on the glasses).
