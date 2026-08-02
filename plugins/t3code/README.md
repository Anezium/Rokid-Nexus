# T3 Code

T3 Code is a headless Rokid Nexus plugin that turns the glasses HUD into a LAN remote for a T3 Code server. It shows the live thread board, opens live conversations, and creates a new thread through a project/provider/model/reasoning picker plus Nexus speech-to-text.

Pair it from the plugin settings screen with the server host, port, and a single-use code created on the computer:

```text
t3 auth pairing create
```

The access token remains in this plugin's private `SharedPreferences`. The plugin does not log bearer tokens, pairing codes, or dictated prompts, and it connects only while its Nexus surface is open.

Build and test from the repository root:

```powershell
.\gradlew :plugin-t3code:test :plugin-t3code:assembleDebug
```
