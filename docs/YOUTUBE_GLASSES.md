# YouTube on glasses

Open **Glasses apps → Set up YouTube** in the phone hub. Update both Nexus hubs
to a build containing this feature before starting.

1. Connect the glasses through Hi Rokid, start Nexus on them, and enable phone Wi-Fi.
2. Choose **Get latest MicroG-RE**, then **Install / update on glasses**. Nexus uses
   the latest stable release from <https://github.com/MorpheApp/MicroG-RE>, selecting
   the arm64 APK with a launcher icon, or the universal one when no arm64 asset exists.
   The package remains `app.revanced.android.gms`.
3. Choose **Get YouTube 21.04.223** to open the APKMirror page of the stock build
   the Rokid controls patch targets, and download the APK variant (not a bundle).
   Choose **Patch with Morphe** to open Morphe Manager, or its releases page when it
   is not installed, and patch that APK with your Rokid patches: include **GmsCore
   support**, **Hide ads**, **SponsorBlock**, and **Rokid controls**. Then choose the
   patched APK on the phone and install it. Supported package names are
   `app.morphe.android.youtube` and the existing prototype's
   `app.morphe.android.youtube.rokidtest`. A patched APK built from another stock
   version is accepted but flagged, since the Rokid controls patch is written for
   one build.
4. Open MicroG on the glasses, choose **Add account**, and open **Keyboard & remote**
   on the phone. Complete Google's sign-in and verification on the glasses. Then
   open YouTube and check your account and Morphe/SponsorBlock settings.

Nexus does not patch YouTube on the phone or redistribute a patched YouTube APK.
Use the existing Morphe/Rokid patching workflow to produce that APK. Package and
signature validation cannot establish that every requested patch was included;
verify the patching report and playback behavior. The setup screen never reads
Google accounts, tokens, passwords, or whether authentication succeeded. Sign-in
is not marked complete automatically. There is no phone-account import.

## Updates and failure handling

For MicroG, download the latest official release again. Nexus requires the GitHub
asset's SHA-256 digest and exact size, checks the package, and validates Android
compatibility before transfer. For YouTube, select the newly patched APK signed
with the same key. Both updates reject downgrades and signer changes; they never
uninstall the previous app or clear its data. Same-version reinstalls are allowed.

APK staging uses the phone's private cache and a bounded file copy. Only a file
chosen in the phone picker can be imported. Installer commands stay inside the
phone process; plugins and callers of the exported hub service cannot issue them.
Inventory uses existing trusted `/core/native-apps/*` routes. APK transfer uses
CXR, serialized with Nexus hub app operations because the SDK shares one callback.
After an unconfirmed upload, reconnect before retrying; a timeout does not prove
that installation failed. A post-install inventory confirms the package version
and signer before Nexus reports success.

The sign-in shortcut keeps the phone remote window secure for its entire lifetime,
including non-password fields, and asks the keyboard not to learn its input. The
existing remote IME sends transient editing operations, never the editor's existing
text. Credentials are entered only by the user.

## Device acceptance checks

- With both packages absent, install MicroG followed by patched YouTube. Confirm
  both are reported on the phone and appear in the glasses launcher. Test a MicroG
  `noicon` installation too: setup must detect it and offer the icon-enabled release.
- Complete Google sign-in using Nexus navigation/pointer and keyboard. Verify that
  the phone blocks screenshots in this remote session, including after rotation or
  an automatic keyboard prompt. Confirm account persistence after restarting apps.
- Play a video while signed in, check ad blocking, and use a video with known
  SponsorBlock segments. Check swipe/tap/back, play/pause, seeking and fullscreen.
- Update with the same signing key and confirm account/settings retention. Try a
  different signer, downgrade, stock YouTube APK, bundle, and incompatible Android
  minimum: each must be rejected without uninstalling anything.
- Disconnect during preparation, inventory and transfer. Reconnect and refresh;
  verify the actual installed state. Reopen/rotate setup during transfer and verify
  there is one operation. Check a hub update cannot replace CXR's active app callback.

Unit tests cover contract validation, release selection, update policy, and setup
timeout/disconnect/late-callback handling. Device sign-in, CXR transfer, playback,
and third-party patch behavior still require the checks above on real hardware.
