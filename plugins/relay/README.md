# Relay

Relay forwards directly repliable Android notifications to a Nexus notice band,
keeps a menu-launched in-memory inbox, and sends an explicitly confirmed reply
through the source notification's `RemoteInput` action — dictated, or, with
*Reply by typing* on, typed into an editable card on the glasses from a bonded
keyboard or the phone's Keyboard & remote screen. Typing needs a glasses hub
that announces the editable-surface bit; without it the switch falls back to
dictation. A notification arriving while the wearer is dictating, typing, or
reviewing a transcript is held and shown once that exchange resolves, and the
band's own timeout is kept alive for as long as the input lasts.

Inbox conversations open as native reader documents. When the source
notification adds a message to the conversation already being read, Relay
updates that document in place without interrupting an active reply flow.

Notification content, sender names, images, and speech text are process-memory
only. The settings screen stores only feature flags and the thread message
limit.
