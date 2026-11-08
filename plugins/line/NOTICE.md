# LINE Messages — licensing

The LINE Messages plugin APK combines Rokid Nexus code (Apache License 2.0)
with the [beeper/line](https://github.com/beeper/line) Go library (MIT
License), zerolog and its terminal helpers (MIT License), the Go standard
library and `golang.org/x` modules (BSD 3-Clause), and the Kotlin standard
library (Apache License 2.0). The exact modules linked
into the engine, with their full license texts, are in
[`src/main/assets/licenses/NOTICE.txt`](src/main/assets/licenses/NOTICE.txt);
the plugin shows that file under Settings › LINE Messages › Open-source
licenses.

The plugin as a whole stays under the Apache License 2.0 of the repository's
[`LICENSE`](../../LICENSE). Regenerate `NOTICE.txt` with
[`native/Write-Notices.ps1`](native/Write-Notices.ps1) whenever
`native/go.mod` changes: it must list exactly the modules linked for
`GOOS=android GOARCH=arm64`.

LINE is a trademark of LY Corporation. This plugin is not affiliated with,
endorsed or sponsored by LY Corporation or LINE Plus Corporation.
