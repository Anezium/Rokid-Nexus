# YouTube on glasses

Open **Glasses apps → Set up YouTube** in the phone hub. Update both Nexus hubs
before starting. Connect the glasses through Hi Rokid, start Nexus on them, and
turn on phone Wi-Fi. Keep both devices connected during installation.

## Four steps

### 1. MicroG on the glasses

Tap **Install MicroG**. Nexus downloads, validates and installs the latest stable
[MicroG-RE](https://github.com/MorpheApp/MicroG-RE) release in one action. It selects
the arm64 APK with a launcher icon, or the universal APK if no arm64 asset exists.
The package is `app.revanced.android.gms`.

The card shows **Done** when the glasses report MicroG installed. A `noicon` build
shows **Needs attention**: install the icon-enabled release offered by Nexus so
that **Open MicroG on glasses** works. Use this same button to update MicroG later.

### 2. YouTube APK

Tap **Download YouTube 21.04.223** and download the stock build from APKMirror.
An APK or APKMirror bundle is fine for **YouTube Patcher**; select that file in
the plugin in the next step. It must include `arm64-v8a`, the glasses' ABI:
32-bit-only builds are refused. Nexus does not inspect browser downloads and does
not mark this step done simply because a download link was opened. The card is
done when YouTube is installed, or Nexus has accepted a patched YouTube APK in
this setup session. Stock input validation and split preparation belong to the
plugin; the hub only receives and validates its final APK.

### 3. Patch and install

If the plugin is missing, **Get YouTube Patcher** opens its specific Nexus Store
entry. Install it, return here, and approve the plugin in Nexus Plugin access
when prompted. Automatic installation requires an enabled approval bound to the
plugin's current signing certificate. The hub validates that identity and the
explicit exported activity before launch and again when the result returns;
replacing or updating the plugin during patching invalidates that hand-off.
That approval is your decision about the plugin installed on this phone, not
independent verification of its publisher. A Store install is pinned to the
registry entry's SHA-256 and signer; a sideloaded build is trusted only through
your approval. The patch bundle the plugin fetches from the Rokid fork has no
signature; its SHA-256 is shown for inspection only.
This adds no bus access or glasses installer capability. Manual patched APK
import remains a separate, explicit user action.

In YouTube Patcher, choose your download and review the patches. Patching adds
the glasses controls to YouTube. Keep the patch screen open until it finishes.
Nexus receives a read-granted `content://` URI, copies and validates the APK,
then installs it on the glasses without another tap. The card is done only when
fresh glasses inventory reports patched YouTube with a signer. Package and
signature checks cannot prove which patches were included: check the plugin's
patching report and the controls/playback on the glasses.

Supported final packages are `app.morphe.android.youtube` and the existing
prototype's `app.morphe.android.youtube.rokidtest`. An APK from a different stock
version is accepted with a warning because the Rokid controls target 21.04.223.
Under **More**, refresh glasses apps, visit the plugin's Store entry, or retry an
already prepared install if the connection was unavailable.

### 4. Sign in and open

Tap **Open MicroG on glasses**, choose **Add account** there, then expand **More**
and use **Keyboard & remote** on the phone. Complete Google's sign-in and
verification on the glasses. Choose **Open YouTube** and check your account and
Morphe/SponsorBlock settings. Tap **Done** when finished. This manual checklist
choice survives reopening Nexus and can be reset with **Mark sign-in to do**.
It is not account detection: Nexus never reads accounts, tokens, passwords, or
whether authentication succeeded. There is no phone-account import.

Opening MicroG or YouTube from Nexus turns glasses Wi-Fi on if it is off; the
ROM boots with the radio off. Wi-Fi enabled this way stays on afterwards.
The secure keyboard/remote session blocks screenshots throughout its lifetime,
including non-password fields, and asks the keyboard not to learn input. The
remote IME sends transient editing operations, not the editor's existing text.
Credentials are entered only by the user.

## Phone keyboard

**Auto-open keyboard** is off by default and remembers your choice. Enable it
to open the phone keyboard when a field in Morphe YouTube or its Rokid test
build takes focus. If Android blocks opening it, use the keyboard notification.
When disabled, use **Keyboard & remote** manually. Changes apply to the next
field; the setting does not affect MicroG, other apps, or plugin editable surfaces.

## Advanced: Morphe Manager fallback

Expand **Advanced → Patch with Morphe Manager instead** if you already use
Manager or need a manual workflow:

1. **Add Rokid patches to Morphe** adds the
   [Rokid glasses source](https://github.com/Anezium/morphe-patches/tree/rokid#readme).
2. **Patch with Morphe** opens Manager's patch dialog for YouTube. Patch the
   supported stock build with Rokid controls, GmsCore support, Hide ads and
   SponsorBlock. Manager's Expert mode lets you change that selection. These
   shortcuts open Manager's releases page if it is not installed.
3. Save the final single patched APK. **Choose patched YouTube APK** imports it
   into Nexus for the same validation. Then **Install prepared APK** transfers it.
   Bundles are stock input for the plugin, not final installable hub imports.

## Updates and failure handling

Patch updates with the same signing key. Export/back up the plugin's key before
uninstalling it: losing that key prevents updates over its previous output.
If YouTube Patcher produced the installed app, import the key backup you
exported from it. YouTube patched with Morphe Manager uses Manager's key, which
YouTube Patcher cannot import: keep updating it with Manager, or remove YouTube
from the glasses yourself only if you accept losing its data. Nexus never
uninstalls an app or clears data to bypass a signer conflict. Downgrades and
signer changes are rejected; same-version reinstalls are allowed.

MicroG downloads require the official GitHub asset's SHA-256 digest and exact
size. Both paths check the real package, signer and Android compatibility. APK
staging uses private phone cache and a bounded copy. Plugin result metadata is
informational only: Nexus computes the hash and reads the APK itself. Cancellation,
a missing read grant, or a non-content result cannot trigger automatic install.

Installer commands stay inside the phone process. No bus route, capability or
AIDL change is added, and exported plugin bus callers cannot issue them. Inventory
uses the existing trusted `/core/native-apps/*` routes. Before transfer, Nexus
requests fresh inventory, checks signer continuity, and rehashes the staged file.
CXR transfer is serialized with hub app operations because the SDK shares one
callback. A fresh post-install inventory must confirm the version and signer
before success is reported. The installation confirmation deadline remains 180
seconds. After an unconfirmed upload, reconnect and refresh before retrying: a
timeout does not prove that installation failed.

## Device acceptance checks (not yet verified on hardware)

- Start with no MicroG, YouTube or plugin. Check all four cards, the targeted
  Store link, return-to-setup plugin detection, and the single-action MicroG chain.
- Download a stock APK and a supported bundle. Patch each on the phone, record
  duration/peak memory, and confirm automatic validated installation on glasses.
  Cancel patching and confirm nothing is imported or installed.
- Try a forged/malformed activity result, stock final APK, final bundle,
  incompatible Android minimum, downgrade and different signer. They must fail
  without uninstalling anything. Repeat with a changed staged file.
- Verify a second patch with the same key retains the account and settings.
  Confirm MicroG `noicon` is detected and can be updated to the icon-enabled build.
- Complete Google verification using the secure keyboard/remote. Check screenshot
  blocking after rotation and an automatic keyboard prompt. Mark Done, reopen,
  confirm persistence, then reset it. No automatic account detection should occur.
- Check auto-open defaults off, persists, affects only YouTube fields, and provides
  a notification if Android blocks the activity. Manual typing must still work.
- Play signed-in video, check ad blocking/SponsorBlock and Rokid rail controls:
  swipe/tap/back, pause, seeking and fullscreen.
- Disconnect during preparation, inventory and transfer. Refresh after reconnect;
  check actual installed state. Rotating the phone during patching must keep the
  job running; back, cancel or closing the patch screen must stop it. Reopen during
  patching and transfer and confirm there is one operation. Check competing hub
  updates cannot replace the active CXR callback, and late callbacks cannot turn
  a timeout into success.

Unit tests cover explicit activity/Store targeting, URI/read-grant checks,
manual Done persistence, automatic preparation/install chains and the existing
inventory, signer, timeout and disconnect guards. Hardware sign-in, CXR transfer,
plugin patching and playback require the device checks above; no device testing
was possible during this implementation.
