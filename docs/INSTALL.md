# TV Voice Search - install guide

This is the short version. Every step, with Windows / macOS / Linux commands, troubleshooting and the
undo steps, is in [`AGENT-INSTALL.md`](AGENT-INSTALL.md). You can also hand that file to an AI coding
agent (see the README).

You need: a computer with `adb` (Android platform-tools), your TV on the same network (or a USB cable),
and a speech-to-text (transcription) endpoint the TV can reach, for example `http://<your-server>:<port>`.
See [`ENDPOINTS.md`](ENDPOINTS.md) for how to choose one.
No root is needed.

1. **Install adb** on your computer: https://developer.android.com/tools/releases/platform-tools
   (or `winget install Google.PlatformTools`, `brew install android-platform-tools`, `sudo apt install adb`).
2. **Enable debugging on the TV:** Settings > System > About > press **Android TV OS build** 7 times.
   Then Settings > System > Developer options > turn on **USB debugging** or **Network / Wireless debugging**.
   (Menu names vary by vendor.)
3. **Connect:** find the TV's IP (Settings > Network & Internet > your network), then
   `adb connect <tv-ip>:5555` (or use Wireless debugging pairing, or a USB cable). Accept
   "Allow debugging" on the TV. `adb devices` must show `device`.
4. **Install the app:** check the CPU type with
   `adb shell getprop ro.product.cpu.abilist`, then download the matching APK
   (`tv-voice-search-<version>-arm64-v8a.apk` for modern 64-bit devices,
   `...-armeabi-v7a.apk` for older 32-bit TVs, or `...-universal.apk` if unsure) and `SHA256SUMS` from the
   [latest release](https://github.com/patrick-1984/my-private-assistant/releases/latest), check the hash,
   then `adb install -r <that file>`. If an AI Studio build is already installed, see
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE` below.
5. **Set it up:** open **TV Voice Search** on the TV, allow the microphone, open Settings (gear icon),
   enter your endpoint under **1. Transcription Endpoint** (turn on **Provide API key** if it needs one)
   and press **Test Connection**. Under **2. Target & Current-App Search**, pick the default target app
   (SmartTube unless you choose another app with search, such as NewPipe or a browser) and decide whether
   **Search in the current app** stays on (it is on by default).
6. **Make it the assistant.** First write down the current values:

   ```sh
   adb shell settings get secure voice_interaction_service
   adb shell settings get secure assistant
   adb shell settings get secure voice_recognition_service
   ```

   Then:

   ```sh
   adb shell settings put secure voice_interaction_service cc.nasz.myprivateassistant/com.example.AssistService
   adb shell settings put secure assistant cc.nasz.myprivateassistant/com.example.AssistService
   adb shell settings put secure voice_recognition_service cc.nasz.myprivateassistant/com.example.RecognizerService
   ```

7. **Mic button inside other apps and current-app search** (app version 1.1 and newer): add
   `cc.nasz.myprivateassistant/com.example.MicKeyService` to `enabled_accessibility_services`,
   keeping the existing entries. See step 6 of [`AGENT-INSTALL.md`](AGENT-INSTALL.md). This service only
   watches the mic / search keys and the name of the app in front; it reads no screen content. Do not
   paste the accessibility line from the app's "ADB Setup Commands" box: it replaces the whole list.
8. **Test:** press the mic button on the home screen and speak. Then open SmartTube (or another app with
   search), press the mic button and speak: the text should appear in that app's search.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`:** the installed copy was signed with a different key (for
example an AI Studio build, and now a GitHub release). Uninstall it
(`adb uninstall cc.nasz.myprivateassistant`, this deletes its settings), install again, and repeat
steps 5 to 7.

**Undo:** put back the values you wrote down in step 6 (`adb shell settings put secure ...`), remove the
accessibility entry, and `adb uninstall cc.nasz.myprivateassistant`. Details: step 8 of
[`AGENT-INSTALL.md`](AGENT-INSTALL.md).
