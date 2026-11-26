# TV Voice Search - install guide

This is the short version. Every step, with Windows / macOS / Linux commands, troubleshooting and the
undo steps, is in [`AGENT-INSTALL.md`](AGENT-INSTALL.md). You can also hand that file to an AI coding
agent (see the README).

You need: a computer with `adb` (Android platform-tools), your TV on the same network (or a USB cable),
and a Whisper-compatible transcription server the TV can reach (`http://<your-server>:<port>`).
No root is needed.

1. **Install adb** on your computer: https://developer.android.com/tools/releases/platform-tools
   (or `winget install Google.PlatformTools`, `brew install android-platform-tools`, `sudo apt install adb`).
2. **Enable debugging on the TV:** Settings > System > About > press **Android TV OS build** 7 times.
   Then Settings > System > Developer options > turn on **USB debugging** or **Network / Wireless debugging**.
   (Menu names vary by vendor.)
3. **Connect:** find the TV's IP (Settings > Network & Internet > your network), then
   `adb connect <tv-ip>:5555` (or use Wireless debugging pairing, or a USB cable). Accept
   "Allow debugging" on the TV. `adb devices` must show `device`.
4. **Install the app:** download `tv-voice-search-<version>.apk` and `SHA256SUMS` from the
   [Releases](https://github.com/patrick-1984/my-private-assistant/releases) page, check the hash, then
   `adb install -r tv-voice-search-<version>.apk`.
5. **Set it up:** open **TV Voice Search** on the TV, allow the microphone, open Settings, enter your
   server address under **Transcription Endpoint** and press **Test**. Pick the target app (default
   SmartTube).
6. **Make it the assistant.** First write down the current values:

   ```sh
   adb shell settings get secure voice_interaction_service
   adb shell settings get secure assistant
   adb shell settings get secure voice_recognition_service
   ```

   Then:

   ```sh
   adb shell settings put secure voice_interaction_service com.aistudio.tvvoicesearch.qkmzpx/com.example.AssistService
   adb shell settings put secure assistant com.aistudio.tvvoicesearch.qkmzpx/com.example.AssistService
   adb shell settings put secure voice_recognition_service com.aistudio.tvvoicesearch.qkmzpx/com.example.RecognizerService
   ```

7. **Mic button inside other apps** (only for an app version that includes `MicKeyService`): add
   `com.aistudio.tvvoicesearch.qkmzpx/com.example.MicKeyService` to `enabled_accessibility_services`,
   keeping the existing entries. See step 6 of [`AGENT-INSTALL.md`](AGENT-INSTALL.md). This service only
   watches the mic / search keys and reads no screen content.
8. **Test:** press the mic button on the home screen and speak.

**Undo:** put back the values you wrote down in step 6 (`adb shell settings put secure ...`), remove the
accessibility entry, and `adb uninstall com.aistudio.tvvoicesearch.qkmzpx`. Details: step 8 of
[`AGENT-INSTALL.md`](AGENT-INSTALL.md).
