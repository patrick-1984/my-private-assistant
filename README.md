# TV Voice Search

A small Android TV / Google TV app that replaces Google's voice search on the remote's mic button with
**your own Whisper-compatible speech-to-text server**, and hands the recognized text straight into the
search of the app you choose (SmartTube by default).

- No Google account, no analytics, no Google Play Services.
- Any language your transcription server supports.
- Pure Kotlin, no native code, about 3 MB.

Package id: `com.aistudio.tvvoicesearch.qkmzpx` - app name on the TV: **TV Voice Search**.

---

## Part 1 - For humans

### Why this app exists

On Google TV, the mic button on the remote opens Google's own voice search
(package `com.google.android.katniss`). That has several limits:

- It supports only certain languages. Google's own list of languages for the Google TV Assistant has
  no Polish, for example.
- It sends results to YouTube or to Google's results page.
- On devices where YouTube has been removed, picking a result leads you to the Play Store.
- It cannot use your own speech-to-text server.

TV Voice Search replaces it. It records your voice, sends the audio to **your own**
Whisper-compatible transcription endpoint, and hands the text directly into the search of the app you
choose:

- **SmartTube** (default),
- **NewPipe**,
- other apps through the standard Android search or share intents.

### How it works

```
  remote mic button
        |
        +--> on the home screen: system "assistant" role ------------+
        |      (AssistService / AssistSessionService)                |
        |                                                            v
        +--> inside another app: KEYCODE_SEARCH key ---------> MainActivity
               caught by MicKeyService (accessibility,          (search box + mic)
               needs a version that includes it)                     |
                                                                     | record 16 kHz mono WAV
                                                                     | (stops after ~1.2 s silence, max 10 s)
                                                                     v
                                                  your transcription server
                                                  http://<your-server>:<port>
                                                                     |
                                                                     | text
                                                                     v
                                                  target app search
                                                  (SmartTube / NewPipe / other)
```

Other apps that use Android's standard speech recognizer (their own in-app mic button) also get your
endpoint, through `RecognizerService`.

### Architecture

All code lives in `app/src/main/java/com/example/`.

| Component | What it does |
|---|---|
| `AssistService` (`VoiceInteractionService`) + `AssistSessionService` | Holds the system **assistant** role. Receives the remote's mic press from the Google TV home screen, takes the remote's audio stream if the system passes one, and opens `MainActivity`. |
| `RecognizerService` (`RecognitionService`) | System **speech recognizer**: when another app asks Android to recognize speech, the audio goes to your endpoint. |
| `MainActivity` | Jetpack Compose UI, D-pad friendly: a search box, a mic button and Settings. Also handles `android.intent.action.ASSIST`. |
| `network/EndpointClient.kt` | Endpoint normalization and auto-detection, multipart upload, response parsing. |
| `handoff/AppHandoff.kt` | Hands the recognized text to the target app. |
| `audio/VoiceRecorder.kt` + `audio/WavEncoder.kt` | Records 16 kHz, 16-bit, mono PCM (from the remote's stream or the built-in mic) and wraps it as WAV. Stops on silence. |
| `data/SettingsRepository.kt` | Stores settings (endpoint, API key, target app, language, mic source) in private app preferences. |
| `AssistantStatus.kt` | Shows in Settings whether this app is the current assistant / recognizer, with the matching ADB setup and undo commands. |
| `MicKeyService` (`AccessibilityService`) | **Requires a version that includes MicKeyService** (planned for the next app version). Catches the mic key inside other apps, see below. |

**Endpoint handling (`EndpointClient`):**

- You can type a bare host (`<your-server>:<port>`), a base URL, or a full path.
- Auto-detection tries, in order: OpenAI-compatible `/v1/audio/transcriptions`, `/audio/transcriptions`,
  whisper.cpp `/inference`, whisper-asr-webservice `/asr`.
- Without a scheme it uses `http://` first and falls back to `https://`. It never doubles `/v1`
  (`.../v1` becomes `.../v1/audio/transcriptions`).
- Optional API key, sent as `Authorization: Bearer <key>`. Optional model name (default `whisper-1`).
- Optional **IP override**: the app connects to a fixed IP but keeps the hostname for TLS SNI and the
  `Host` header. This helps when the TV's DNS cannot resolve your server.
- Optional **"Ignore SSL certificate checks"** for self-signed certificates. Use it only on networks you
  trust.
- Language: Auto (the server detects it), or a fixed language sent to the server.

**Text handoff (`AppHandoff`):**

| Target | Method |
|---|---|
| SmartTube (`org.smarttube.stable`, also other `org.smarttube.*` / `com.liskovsoft.smarttube*` builds) | `ACTION_VIEW` on `https://www.youtube.com/results?search_query=<q>` with `setPackage` (SmartTube opens it as a search) |
| NewPipe (`org.schabi.newpipe`) | `ACTION_SEND` `text/plain` with `setPackage` |
| Other apps | `ACTION_SEARCH`, then `ACTION_WEB_SEARCH`, then `ACTION_SEND`, otherwise launch the app and copy the text to the clipboard |

### The mic button inside other apps (measured behaviour)

- **From the Google TV home screen**, the mic button goes through the system assistant role. This works
  once TV Voice Search is the default assistant.
- **Inside another app** (for example SmartTube), the remote sends a plain `KEYCODE_SEARCH` key event
  (HID usage `0x0C0221`). The app in front consumes that key, so the assistant is never called.
- **The fix** is an AccessibilityService, `com.example.MicKeyService`. It filters only the
  `KEYCODE_SEARCH`, `KEYCODE_ASSIST` and `KEYCODE_VOICE_ASSIST` keys and opens TV Voice Search.
  It reads no screen content.
  This service is being added in the next app version. The install guides skip this step if your APK
  does not include it.

### Requirements

- An Android TV / Google TV device with **Android 10 (API 29) or newer**. Target SDK is 34.
- A Whisper-compatible transcription server that the TV can reach, for example an OpenAI-compatible
  server, whisper.cpp server or whisper-asr-webservice: `http://<your-server>:<port>`.
- A computer with `adb` (Android platform-tools) for the one-time setup. Making an app the default
  assistant has no on-TV menu on most Google TV devices, so it is done once over ADB. No root is needed.
- Optional: SmartTube or NewPipe as the target app.

**Device notes:** tested on a Google TV projector with Android 14 (API 34) and a 32-bit ARM CPU
(`armeabi-v7a` only). The app is pure Kotlin with no native code, so the same APK runs on `armeabi-v7a`,
`arm64-v8a` and `x86` devices.

### Install

- Step-by-step for humans: [`docs/INSTALL.md`](docs/INSTALL.md)
- Detailed version, written for AI agents (also readable by humans): [`docs/AGENT-INSTALL.md`](docs/AGENT-INSTALL.md)

APKs are published on the [Releases](https://github.com/patrick-1984/my-private-assistant/releases) page,
with a `SHA256SUMS` file.

### Status

Early, working prototype (version 1.0):

- Home-screen mic button through the assistant role: works.
- Recognizer service for other apps: included.
- Mic button inside other apps: needs `MicKeyService`, coming in the next version.

### Screenshots

Screenshots: coming soon.

### Building

- The app is developed in **Google AI Studio** (Android app builder). Do not change the `signingConfigs`
  block in `app/build.gradle.kts`: AI Studio needs it.
- **CI build:** [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) builds a signed
  release APK. It runs **only when started by hand** (Actions -> "Build APK" -> Run workflow), with the
  inputs `version` (for example `1.0.1`) and `publish` (create a GitHub release `v<version>`).
  It runs the unit tests, builds `:app:assembleRelease`, and uploads `tv-voice-search-<version>.apk`
  and `SHA256SUMS`.
- The workflow needs these repository secrets:
  - `ANDROID_KEYSTORE_BASE64`: the upload keystore (`.jks`), base64-encoded. The key alias must be `upload`.
  - `ANDROID_STORE_PASSWORD`: the keystore password.
  - `ANDROID_KEY_PASSWORD`: the key password.
- Local build: Gradle 9.3.1, JDK 21 and the Android SDK (compileSdk 36). The repo has no `gradlew`
  script, so run `gradle :app:assembleDebug` with a matching Gradle install.

### License

License: not yet chosen.

---

## Part 2 - For AI agents (Claude Code, Codex, Gemini CLI, ...)

Copy this prompt into your coding agent:

> Go to https://github.com/patrick-1984/my-private-assistant and follow `docs/AGENT-INSTALL.md` step by step
> to install TV Voice Search on my Android TV and make it my voice assistant. Ask me before every step
> that changes my TV.

**This is not magic.** What it needs:

- The agent must run on a **computer with command-line access** and **`adb`** (Android platform-tools).
  The guide shows how to install it.
- The TV must be **reachable over ADB**: by USB cable, or by Wi-Fi / network debugging on the same network.
- **You** must do a few things on the TV yourself: turn on Developer options, turn on USB or network
  debugging, accept the "Allow debugging" prompt, and find the TV's IP address. You must also type your
  transcription server address into the app.

Agent guide: [`docs/AGENT-INSTALL.md`](docs/AGENT-INSTALL.md) - human guide: [`docs/INSTALL.md`](docs/INSTALL.md).
