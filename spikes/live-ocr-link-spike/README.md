# Live OCR link spike

This standalone Phase A measurement project streams the glasses camera as hardware-encoded H.264 over an app-owned Wi-Fi Direct group. The phone joins that group by credentials, decodes to a latest-frame holder, runs one Latin ML Kit OCR request at a time, and returns an `OCR_ACK` for the frame it recognized.

The authoritative end-to-end measurement is entirely on the glasses clock:

```text
latencyMs = (ackNanos - captureNanos) / 1_000_000
```

Do not compare phone timestamps with glasses timestamps. The Phase A gate is median glasses-clock latency at or below 600 ms with no thermal throttling during a 10-minute run. Report a result above 800 ms or thermal degradation as the plan's STOP condition.

## Build

Prerequisites are JDK 17 or newer, Android SDK 36 (discoverable through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or an ignored `local.properties`), and internet access for a first dependency download. Run Gradle from this directory, not from the repository root:

```powershell
cd spikes/live-ocr-link-spike
.\gradlew.bat :glasses-app:assembleDebug :phone-app:assembleDebug
```

On macOS/Linux:

```sh
cd spikes/live-ocr-link-spike
./gradlew :glasses-app:assembleDebug :phone-app:assembleDebug
```

Outputs:

- `glasses-app/build/outputs/apk/debug/glasses-app-debug.apk`
- `phone-app/build/outputs/apk/debug/phone-app-debug.apk`

The glasses APK is minSdk 31 / targetSdk 32. The phone APK is minSdk 31 and contains the bundled Latin ML Kit recognizer.

## Install

Connect both devices and identify their serials rather than relying on ADB's default device:

```powershell
adb devices
adb -s <GLASSES_SERIAL> install -r glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
adb -s <PHONE_SERIAL> install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk
```

The apps request their runtime permissions on first launch. Grant camera and location to the glasses app. Grant Nearby Wi-Fi Devices on Android 13+, or location on Android 12, to the phone app. Wi-Fi and location services must be enabled on both devices for Wi-Fi Direct.

## Run a 10-minute soak

1. Clear old logcat if desired, then start the glasses app:

   ```powershell
   adb -s <GLASSES_SERIAL> logcat -c
   adb -s <GLASSES_SERIAL> shell am start -n com.anezium.liveocr.glasses/.MainActivity
   ```

2. Grant the prompted permissions. Wait for the glasses screen to show `SSID`, `PASS`, `PORT`, `TOKEN`, and `GO IP`. The same offer is logged once under `LiveOcrGlasses`:

   ```powershell
   adb -s <GLASSES_SERIAL> logcat -d -s LiveOcrGlasses:V LiveOcrGlassesLink:V LiveOcrGlassesCodec:V
   ```

   The passphrase and token authenticate this temporary spike link. Do not publish or retain the credential log.

3. Start the phone app:

   ```powershell
   adb -s <PHONE_SERIAL> shell am start -n com.anezium.liveocr.phone/.MainActivity
   ```

4. Configure the phone by either method:

   - UI: enter all five values shown by the glasses, then tap **Join and run**.
   - ADB broadcast: substitute the current offer exactly. If the activity is open it connects immediately; if it is closed, the receiver saves the values and the app connects when launched.

   ```powershell
   adb -s <PHONE_SERIAL> shell am broadcast -a com.anezium.liveocr.CONFIGURE -n com.anezium.liveocr.phone/.ConfigReceiver --es ssid "<SSID>" --es passphrase "<PASS>" --ei port 38401 --es token "<TOKEN>" --es goIp "<GO_IP>"
   ```

5. Confirm the glasses says `Phone linked / streaming`. Confirm the phone shows decode fps, OCR cadence, and changing OCR preview text. Aim the glasses at representative high-contrast Latin text at the intended working distance.

6. Leave both activities foreground for at least 10 continuous minutes. Do not restart either activity during the run: each launch replaces that app's CSV. Avoid plugging/unplugging power mid-run unless charger operation is the measurement you intend to characterize.

7. At 10 minutes, note the visible final counters and stop both apps so the writers close cleanly:

   ```powershell
   adb -s <PHONE_SERIAL> shell am force-stop com.anezium.liveocr.phone
   adb -s <GLASSES_SERIAL> shell am force-stop com.anezium.liveocr.glasses
   ```

## Logs and CSV files

Useful live logs:

```powershell
adb -s <GLASSES_SERIAL> logcat -v time -s LiveOcrGlasses:V LiveOcrGlassesLink:V LiveOcrGlassesCodec:V
adb -s <PHONE_SERIAL> logcat -v time -s LiveOcrPhoneLink:V LiveOcrPhoneDecoder:V LiveOcrPhoneOcr:V
```

Pull the three app-external files before launching another run:

```powershell
New-Item -ItemType Directory -Force live-ocr-results | Out-Null
adb -s <GLASSES_SERIAL> pull /sdcard/Android/data/com.anezium.liveocr.glasses/files/live_ocr_glasses_latency.csv live-ocr-results/
adb -s <GLASSES_SERIAL> pull /sdcard/Android/data/com.anezium.liveocr.glasses/files/live_ocr_glasses_telemetry.csv live-ocr-results/
adb -s <PHONE_SERIAL> pull /sdcard/Android/data/com.anezium.liveocr.phone/files/live_ocr_phone.csv live-ocr-results/
```

CSV schemas are stable and have one header row:

```text
live_ocr_glasses_latency.csv
frameId,captureNanos,ackNanos

live_ocr_glasses_telemetry.csv
sampleNanos,batteryTempC,thermalStatus,batteryLevel

live_ocr_phone.csv
frameId,recvNanos,decodeDoneNanos,ocrDoneNanos,blockCount,charCount
```

`thermalStatus` is Android's `PowerManager` integer status: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. Telemetry is sampled every 10 seconds. The phone timestamps are useful for receive-to-decode and decode-to-OCR stage timing only; they must not be subtracted from `captureNanos`.

## Fixed spike settings

- AVC/H.264 surface-input hardware encoder
- 960 x 1280 portrait (720p-class pixel count)
- 20 fps target
- 5 Mbit/s CBR default; runtime bitrate API retained in the streamer
- 1-second I-frame interval
- bounded, non-blocking glasses network queue
- decoder queue drops stale backlog and resumes on a key frame
- latest decoded YUV frame replaces the previous unclaimed frame
- exactly one ML Kit recognition in flight; completion samples the newest available frame
- OCR ACK writes use a dedicated network writer and never block the OCR callback or encoder
