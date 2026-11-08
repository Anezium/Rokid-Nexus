# Meta Messages — licensing

The Meta Messages plugin APK combines Rokid Nexus code (Apache License 2.0) with
[mautrix-meta](https://github.com/mautrix/meta) (GNU Affero General Public
License v3), whatsmeow and mautrix-go (Mozilla Public License 2.0), libsignal
(GNU General Public License v3), the Kotlin standard library (Apache License 2.0),
SQLite (public domain) and the permissively licensed Go modules listed in
[`src/main/assets/licenses/NOTICE.txt`](src/main/assets/licenses/NOTICE.txt).

Because it includes mautrix-meta, the plugin as a whole is distributed under the
GNU Affero General Public License, version 3 or later ([`LICENSE`](LICENSE)).
The rest of Rokid Nexus — the hubs, the SDK and the other plugins — are separate
programs and stay under the Apache License 2.0. Rokid Nexus source files in this
directory remain available under Apache 2.0 as well; the AGPL governs the
combined plugin.

Anyone who receives the APK must be able to get its complete corresponding
source: this directory, the Go module in [`native/`](native) with its `go.mod`
and `go.sum`, and the build script [`native/Build-Android.ps1`](native/Build-Android.ps1).
The plugin shows the bundled notice under Settings › Plugin › Open-source
licenses.

Regenerate `NOTICE.txt` whenever `native/go.mod` changes: it must list exactly
the modules linked into the engine (`go list -deps ./mobile/...` for
`GOOS=android GOARCH=arm64`) with their full license texts.

WhatsApp, Instagram and Messenger are trademarks of their owners. This plugin is
not affiliated with, endorsed or sponsored by Meta Platforms, Inc.
