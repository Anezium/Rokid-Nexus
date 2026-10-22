# Sealed-answer loss integration regression

Run from the repository root on the host, without a device or ADB:

```powershell
.\gradlew.bat -I phone-hub/src/test/bridge/files_answer_loss.init.gradle :phone-hub:testDebugUnitTest --tests '*GlassesFilesAnswerLossTest' --console=plain
```

The init script adds the real glasses lane server, upload receiver, download streamer,
and file store to this test's sources. Neither application gains a module dependency;
the ordinary build configuration is unchanged. Generated test sources live under the
phone module's normal build directory. Run this command explicitly in addition to the
ordinary module suites.

The phone runs its actual controller workers and lane client. A loopback TCP proxy
forwards the hello, verifies the real sealed answer, and either drops the answer or
forwards all but its final byte before closing the socket. The bus stays available.
The drop schedule delivers resync while `connect()` is waiting; the truncation schedule
delivers it after the failed attempt sends `LaneDeclined`. All eight combinations
(upload/download, shared/hotspot, drop/truncate) require receiver-confirmed resync,
successful completion, exact bytes, and matching SHA-256. Hotspot cases stage bus bytes
before offering the lane. Android services are supplied by Robolectric; this does not
test real Wi-Fi association, a LocalOnlyHotspot reservation, or the physical Bluetooth link.

The suite also holds original bus sends through the snapshot exchange in both
directions and admits resumed data before JSON confirmation. Two upload schedules
pause the actual worker after its final resume check but before epoch capture,
then overlap BusResume between resync start and snapshot installation. Two download
schedules pause before epoch capture, prove the pending hotspot handshake, and
delay the phone snapshot until the stream returns to its snapshot wait. Test-only
Robolectric shadows pause those method boundaries and call through to production
code. The buffered chunk must wait for the snapshot and worker rewind. All 17
cases require completion with exact bytes and SHA-256.

# Resync schedule table

```powershell
.\gradlew.bat -I phone-hub/src/test/bridge/files_answer_loss.init.gradle :phone-hub:testDebugUnitTest --tests '*GlassesFilesScheduleTest' --console=plain
```

`GlassesFilesScheduleTest` uses the same init script and the same real classes on both
ends, with a loopback proxy that opens every sealed lane frame so a schedule can name the
frame where the lane is cut, which end notices first, where a cancel, link loss, camera
takeover or hotspot deadline lands, and whether resync messages repeat. Every schedule
must end as it demands within 12 s with exact bytes and digest, no timeout or invalid
request, a single snapshot offset, both lanes closed and no staging left behind. Run it
explicitly, like the answer-loss suite; it takes several minutes.
