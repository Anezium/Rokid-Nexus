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
