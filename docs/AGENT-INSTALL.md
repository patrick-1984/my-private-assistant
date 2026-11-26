# TV Voice Search - install guide for AI agents

This guide is written **for an AI coding agent** (Claude Code, Codex, Gemini CLI, ...) that runs on the
user's computer. A human can follow it too. The shorter human guide is [`INSTALL.md`](INSTALL.md).

Goal: install TV Voice Search on the user's Android TV / Google TV, make it the default assistant and
speech recognizer, and (if the APK supports it) turn on the mic-key accessibility service.

Assume **only `adb`** on the user's computer. There are no other remote tools.

Constants used below:

| Name | Value |
|---|---|
| Package | `com.aistudio.tvvoicesearch.qkmzpx` |
| Assistant service | `com.aistudio.tvvoicesearch.qkmzpx/com.example.AssistService` |
| Recognizer service | `com.aistudio.tvvoicesearch.qkmzpx/com.example.RecognizerService` |
| Accessibility service (newer versions only) | `com.aistudio.tvvoicesearch.qkmzpx/com.example.MicKeyService` |
| Releases | https://github.com/patrick-1984/my-private-assistant/releases |

## Ground rules

1. **Ask the user before every step that changes the TV** (install, `settings put`, `pm grant`,
   uninstall). Read-only commands (`getprop`, `settings get`, `dumpsys`) need no confirmation.
2. **Never put secrets into logs, commands or chat.** The user types the transcription server URL and API
   key on the TV, in the app. Do not ask for the API key, and do not echo it.
3. **Record the original values** before you change any setting (step 5 and step 6). Show them to the
   user and keep them in this session (or a local file the user agrees to), so they can be restored.
4. **Everything must be reversible.** Step 8 undoes every change. No root is needed anywhere. Do not
   use `su`, do not flash anything, do not disable system apps.
5. Use the commands for the user's OS. Where they differ, this guide gives **Windows (PowerShell)**,
   **macOS** and **Linux** variants. `adb shell ...` commands are the same everywhere. In PowerShell,
   quote arguments that contain `|`, for example `adb shell "dumpsys package X | grep Y"`.
6. If more than one device is connected, add `-s <serial>` to every `adb` command (see step 1).
7. Shell probes that may find nothing (for example `grep`) can exit non-zero. That is not a failure.

## Step 0 - Check or install Android platform-tools (`adb`)

Check first:

```sh
adb version
```

If it is missing, install it (ask the user first):

| OS | Command |
|---|---|
| Windows (PowerShell) | `winget install Google.PlatformTools` (then open a new terminal) |
| macOS | `brew install android-platform-tools` |
| Debian / Ubuntu | `sudo apt install adb` (older releases: `sudo apt install android-tools-adb`) |
| Fedora | `sudo dnf install android-tools` |
| Any OS | official ZIP: https://developer.android.com/tools/releases/platform-tools (unzip, then run `adb` from that folder or add it to `PATH`) |

Verify again with `adb version`.

## Step 1 - Connect the TV over ADB

### 1a. The user enables Developer options and debugging (on the TV)

Tell the user what to do. The menu wording below is for Google TV and varies by vendor:

1. **Settings > System > About**. Scroll to **Android TV OS build** and press OK on it **7 times**,
   until "You are now a developer" appears. (Some vendors: Settings > Device Preferences > About > Build.)
2. Go back to **Settings > System > Developer options**.
3. Turn on **USB debugging** and/or **Network debugging** (older/vendor builds) or **Wireless debugging**
   (Android 11+).

### 1b. Find the TV's IP address

Any one of these:

- On the TV: **Settings > Network & Internet >** (the Wi-Fi network or Ethernet) **> IP address**.
- In the router's list of connected devices.
- If USB already works: `adb shell ip -4 addr show wlan0` (or `eth0` for wired).

Refer to it below as `<tv-ip>`.

### 1c. Pick one connection route

**Route A - USB cable**

```sh
adb devices
```

The TV shows "Allow USB debugging?". The user selects **Always allow from this computer** and **OK**.
`adb devices` must then show the device as `device`.

**Route B - legacy network debugging (port 5555)**

```sh
adb connect <tv-ip>:5555
adb devices
```

The TV shows "Allow debugging?". The user selects **Always allow** and **OK**.

**Route C - Android 11+ Wireless debugging (pairing code)**

1. The user opens **Developer options > Wireless debugging > Pair device with pairing code**. The TV shows
   a 6-digit code and `<tv-ip>:<pair-port>`. The main Wireless debugging screen also shows
   `<tv-ip>:<port>` (a different port).
2. Run:

```sh
adb pair <tv-ip>:<pair-port>      # enter the 6-digit code when asked
adb connect <tv-ip>:<port>
adb devices
```

### Troubleshooting

| Symptom | Fix |
|---|---|
| `unauthorized` | The prompt on the TV was not accepted. Ask the user to look at the TV and accept it. If no prompt shows: `adb kill-server`, then connect again. |
| `offline` | `adb reconnect offline`. Re-plug the cable, or `adb disconnect <tv-ip>:5555` and connect again. Last resort: the user selects **Revoke USB debugging authorizations** in Developer options, then connect again and accept the new prompt. |
| `failed to connect` / timeout | Same network? Debugging still on (some TVs turn it off after a reboot)? Correct IP and port? |
| More than one device | `adb devices` lists serials. Use `adb -s <serial> ...` for every command. |

## Step 2 - Read device facts (read-only)

```sh
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.cpu.abilist
```

- `ro.build.version.sdk` must be **29 or higher** (Android 10+). If it is lower, stop and tell the user
  the app does not support this TV.
- Any ABI is fine (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`): the app has no native code.

## Step 3 - Download, verify and install the APK

1. Find the latest release: https://github.com/patrick-1984/my-private-assistant/releases/latest
   (or `gh release view -R patrick-1984/my-private-assistant` if the GitHub CLI is installed).
   Download the `*.apk` asset (named `tv-voice-search-<version>.apk`) and `SHA256SUMS`.
   **If no release exists yet, stop and tell the user**: builds are published by the maintainer.

   ```sh
   # with the GitHub CLI (any OS)
   gh release download -R patrick-1984/my-private-assistant --pattern "*.apk" --pattern SHA256SUMS
   ```

   Without `gh`, download both files in a browser or with `curl -LO <asset-url>`
   (PowerShell: `Invoke-WebRequest <asset-url> -OutFile <file>`).

2. Verify the SHA-256 and compare it with the line for the APK in `SHA256SUMS`:

   | OS | Command |
   |---|---|
   | Windows (PowerShell) | `Get-FileHash .\tv-voice-search-<version>.apk -Algorithm SHA256` (compare with `Get-Content SHA256SUMS`, ignoring case) |
   | Linux | `sha256sum -c SHA256SUMS` |
   | macOS | `shasum -a 256 -c SHA256SUMS` |

   If the hash does not match, stop, delete the file and tell the user.

3. Install (ask first):

   ```sh
   adb install -r tv-voice-search-<version>.apk
   ```

   `-r` keeps the app's data when updating. If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
   an older copy signed with a different key is installed: ask the user before running
   `adb uninstall com.aistudio.tvvoicesearch.qkmzpx` (this deletes the app's settings) and installing again.

## Step 4 - First start, mic permission, endpoint

1. Open the app once:

   ```sh
   adb shell monkey -p com.aistudio.tvvoicesearch.qkmzpx -c android.intent.category.LEANBACK_LAUNCHER 1
   ```

2. Grant the microphone permission (ask first). The user can also press **Grant Mic** in the app.

   ```sh
   adb shell pm grant com.aistudio.tvvoicesearch.qkmzpx android.permission.RECORD_AUDIO
   ```

3. The user opens the app's **Settings** (gear icon) on the TV, enters the transcription endpoint under
   **1. Transcription Endpoint**, and presses **Test**. Explain the input rules:

   - A bare host and port is fine: `<your-server>:<port>`. Without a scheme the app tries `http://` first,
     then `https://`.
   - A base URL (`http://<your-server>:<port>`) makes the app probe, in order:
     `/v1/audio/transcriptions` (OpenAI-compatible), `/audio/transcriptions`, `/inference` (whisper.cpp),
     `/asr` (whisper-asr-webservice).
   - A URL ending in `/v1` gets `/audio/transcriptions` appended (never a second `/v1`). A full path
     such as `.../v1/audio/transcriptions`, `.../inference` or `.../asr` is used as given.
   - **Provide API key**: only if the server needs one. The key is sent as `Authorization: Bearer <key>`.
     The user types it on the TV, not into the agent.
   - **Override server IP address**: only if the TV cannot resolve the hostname. The app connects to that
     IP but keeps the hostname for TLS SNI and the `Host` header.
   - **Ignore SSL certificate checks**: only for a self-signed certificate on a trusted network.
   - Under **2. Target App** the user picks the app that receives the text (default SmartTube).
   - Under **3. Language**: Auto, or a fixed language.

   The Test must report success before you continue. If it fails, check from the TV's point of view:
   same network, port open, correct path.

## Step 5 - Make it the default assistant and speech recognizer

1. Record the current values (read-only) and show them to the user:

   ```sh
   adb shell settings get secure voice_interaction_service
   adb shell settings get secure assistant
   adb shell settings get secure voice_recognition_service
   ```

   Save the three outputs. A value of `null` means the setting was not set.

2. Set the new values (ask first):

   ```sh
   adb shell settings put secure voice_interaction_service com.aistudio.tvvoicesearch.qkmzpx/com.example.AssistService
   adb shell settings put secure assistant com.aistudio.tvvoicesearch.qkmzpx/com.example.AssistService
   adb shell settings put secure voice_recognition_service com.aistudio.tvvoicesearch.qkmzpx/com.example.RecognizerService
   ```

3. Verify:

   ```sh
   adb shell "dumpsys voiceinteraction | grep -E 'name=|packageName='"
   ```

   The output must mention `com.aistudio.tvvoicesearch.qkmzpx`. The app's **Settings >
   5. Assistant & Recognizer Status** (press **Refresh Status**) shows the same.

## Step 6 - Mic key inside other apps (only if the APK has `MicKeyService`)

Why: from the home screen the mic button reaches the assistant (step 5). Inside another app (for example
SmartTube) the remote sends a plain `KEYCODE_SEARCH` key that the app in front consumes, so the assistant
is never called. `MicKeyService` is an AccessibilityService that filters only the `KEYCODE_SEARCH`,
`KEYCODE_ASSIST` and `KEYCODE_VOICE_ASSIST` keys and opens TV Voice Search. **It reads no screen content.**
Tell the user this in plain words before asking for consent.

1. Check whether the installed APK declares it (read-only):

   ```sh
   adb shell "dumpsys package com.aistudio.tvvoicesearch.qkmzpx | grep MicKeyService"
   ```

   or

   ```sh
   adb shell cmd package query-services -a android.accessibilityservice.AccessibilityService
   ```

   (look for `com.aistudio.tvvoicesearch.qkmzpx/com.example.MicKeyService`).
   **If it is not found, skip this step** and tell the user: "This version does not include the mic-key
   service yet. The mic button works from the home screen. Inside other apps it needs a later version."

2. Record the current values:

   ```sh
   adb shell settings get secure enabled_accessibility_services
   adb shell settings get secure accessibility_enabled
   ```

3. Build the new list: the old value plus `:com.aistudio.tvvoicesearch.qkmzpx/com.example.MicKeyService`
   (colon-separated). **Do not drop existing entries.** If the old value is `null` or empty, the new value
   is just `com.aistudio.tvvoicesearch.qkmzpx/com.example.MicKeyService`. If it already contains it, do
   nothing.

4. Apply (ask first). Quote the value so the shell does not split it:

   ```sh
   adb shell settings put secure enabled_accessibility_services "<old-value>:com.aistudio.tvvoicesearch.qkmzpx/com.example.MicKeyService"
   adb shell settings put secure accessibility_enabled 1
   ```

5. Verify with `adb shell settings get secure enabled_accessibility_services`.

## Step 7 - Test with the user

1. Ask the user to go to the **Google TV home screen**, press the remote's mic button and say something.
   TV Voice Search should open, record, and show the text in the target app's search.
2. If step 6 was done: ask the user to open **SmartTube**, press the mic button and speak. TV Voice Search
   should open on top and hand the text to SmartTube's search.
3. Logs (read-only; clear first with `adb logcat -c` if you want a clean run, then reproduce):

   ```sh
   adb logcat -d | grep -E "TVVoice|AtvRemote"
   ```

   PowerShell: `adb logcat -d | Select-String -Pattern "TVVoice|AtvRemote"`.
   The app logs with the tags `TVVoiceAssist` and `TVVoiceRecognizer`. `AtvRemote` lines show the
   remote's key and mic events. Do not paste logs that contain the server URL or keys into public places.

## Step 8 - Undo / restore

Always prefer the values recorded in steps 5 and 6. Ask before each change.

1. Assistant and recognizer:

   ```sh
   adb shell settings put secure voice_interaction_service <recorded-value>
   adb shell settings put secure assistant <recorded-value>
   adb shell settings put secure voice_recognition_service <recorded-value>
   ```

   If a recorded value was `null`, use `adb shell settings delete secure <key>` instead.
   If the values were lost, the typical Google TV defaults are:

   - `voice_interaction_service` and `assistant`:
     `com.google.android.katniss/.search.serviceapi.KatnissVoiceInteractionService`
   - `voice_recognition_service`:
     `com.google.android.katniss/com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService`

   (The app's **ADB Undo Commands** box in Settings shows the same commands.)

2. Accessibility (if step 6 was done): put back the recorded `enabled_accessibility_services` value
   (or remove only the `MicKeyService` entry from the list), and the recorded `accessibility_enabled` value.

3. Remove the app (optional, deletes its settings):

   ```sh
   adb uninstall com.aistudio.tvvoicesearch.qkmzpx
   ```

4. Optionally the user turns off USB / network debugging in Developer options.

## Known limits

- SmartTube receives the text through a YouTube search-results link
  (`https://www.youtube.com/results?search_query=<q>`) sent only to the SmartTube package. Nothing is
  sent to YouTube by this app.
- Some vendors' remotes or firmware may still call Google's own Assistant for some buttons, regardless
  of these settings.
- A system update or factory reset can reset the assistant settings. Repeat step 5 (and step 6) then.
- No root is needed anywhere.
