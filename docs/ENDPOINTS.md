# Choosing a transcription endpoint

TV Voice Search does not turn speech into text by itself. It records a short clip and sends it to a
**speech-to-text (transcription) endpoint** that you choose. The endpoint sends the text back, and the
app hands it to the search of an app on your TV.

You can run that endpoint yourself (private, no cost per request) or use a hosted service.

## What the app sends

The app speaks the common **OpenAI-style transcription API**, which many servers and services copy:

- `POST <base>/v1/audio/transcriptions`, as `multipart/form-data`
- field `file`: the recording, `audio.wav` (16 kHz, 16-bit, mono WAV, at most about 10 seconds)
- field `model`: `whisper-1`, unless you set another one (see below)
- field `response_format`: `json`
- field `language`: only when you pick a language in **Settings > 3. Language** (`pl` or `en`; Auto
  sends nothing and lets the server detect it)
- header `Authorization: Bearer <key>`: only when **Provide API key** is on

It expects a JSON answer with a `text` field (`{"text": "..."}`). **Test Connection** only accepts that
JSON form, so a server that answers with plain text cannot be set up.

**Model name, important:** on a fresh install the app tests the endpoint with the model `whisper-1`,
and the field to change the model appears only **after** a successful test. A service that rejects
`whisper-1` therefore cannot be set up directly. Use a service that accepts (or ignores) `whisper-1`, or
a proxy that maps the name `whisper-1` to the real model (see LiteLLM below).

The app also understands two other self-hosted formats: the whisper.cpp server (`/inference`) and
whisper-asr-webservice (`/asr`). It cannot send other custom headers and does not speak gRPC.

## Setting it up in the app

1. Open the app, press the **gear** icon, go to **1. Transcription Endpoint**.
2. Type the address. Any of these work:
   - `<your-server>:<port>` (the app tries `http://` first, then `https://`)
   - a base URL: `http://<your-server>:<port>` (the app tries `/v1/audio/transcriptions`,
     `/audio/transcriptions`, `/inference`, `/asr` in that order)
   - a URL ending in `/v1`, or the full path (`.../v1/audio/transcriptions`)
3. If the service needs a key: turn on **Provide API key** and type the key. For a service on the
   internet, always type the address with `https://`: without a scheme the app tries `http://` first,
   which would send the key and your voice unencrypted. If you switch to a different endpoint later,
   turn off or replace the old key, or the app sends it to the new endpoint too.
4. Press **Test Connection**. It sends half a second of silence and shows which address worked.
5. Optional, shown after a successful test: **Pick custom model / endpoint** lets you choose a model the
   server lists at `/v1/models`, or type a model name. Without it the app sends `whisper-1`.
   **Live transcription** shows words while you speak, at the cost of more requests.

Other options in that section: **Override server IP address** (connect to a fixed IP but keep the
hostname, for when the TV cannot resolve your server's name) and **Ignore SSL certificate checks** (for
self-signed certificates; only on a network you trust).

## Option 1: self-hosted (your own computer or NAS)

Run an OpenAI-compatible speech-to-text server on a machine in your home network and point the app at
`http://<your-server>:<port>`. Common choices (follow each project's own install guide):

- **Speaches** (formerly faster-whisper-server): OpenAI-compatible `/v1/audio/transcriptions`.
- **whisper.cpp server**: lightweight, runs on a CPU; the app uses its `/inference` path.
- **LocalAI**: runs many kinds of models behind an OpenAI-compatible API.
- **whisper-asr-webservice**: the app uses its `/asr` path.

Tips: the TV must reach the server (same network, firewall port open). A small model is fast enough on
a normal CPU for short search phrases. If the server ignores the `model` field, the default `whisper-1`
is fine; if it needs a real model name, set it under **Pick custom model / endpoint**.

## Option 2: a hosted service

Any hosted service with an OpenAI-compatible `/v1/audio/transcriptions` endpoint and a Bearer API key
works, as long as it accepts the model name `whisper-1` for the first test (see above): type its base
URL with `https://`, turn on **Provide API key**, paste the key, and press **Test Connection**. Then
choose the model under **Pick custom model / endpoint** if needed. Check the service's prices and privacy terms first: your voice
clips are sent there.

## NVIDIA build.nvidia.com (free API key)

NVIDIA offers a free trial API key on [build.nvidia.com](https://build.nvidia.com) that covers its
hosted speech-to-text models. Findings (checked 2026-11-26 against NVIDIA's model pages and docs):

### Getting a key

1. Open a model page on build.nvidia.com, for example
   [parakeet-tdt-0.6b](https://build.nvidia.com/nvidia/parakeet-tdt-0_6b), and click **Get API Key**.
2. Enter your email. This signs you up for the free NVIDIA Developer Program. Sign in, or set a
   password for a new NVIDIA account.
3. Click **Copy Key** and keep the key private.

Source: [NVIDIA API quickstart](https://docs.api.nvidia.com/nim/docs/api-quickstart). Rate limits and
how long the free trial lasts are not published on an official page we could find (users report about
40 requests per minute). The trial is meant for development and evaluation; read NVIDIA's terms.

### Default NVIDIA endpoint and models

The default NVIDIA OpenAI-compatible API endpoint (pre-configured in the app's inactive **NVIDIA** Configuration Set) is:

- Default NVIDIA endpoint: `https://integrate.api.nvidia.com/v1` (which resolves to `https://integrate.api.nvidia.com/v1/audio/transcriptions` and `https://integrate.api.nvidia.com/v1/models`)

Simply edit the pre-configured **NVIDIA** entry in **Settings > 1. Configuration Sets**, paste your `nvapi-…` API key, and press **Done** (or **Test Connection**).

If a specific speech model page on `build.nvidia.com` (filter **ASR**, click the **API** tab) shows a dedicated HTTP or gRPC address instead:

| Model (API tab) | Protocol | Usable by this app directly? |
|---|---|---|
| Unified OpenAI-compatible NVIDIA base | HTTP `POST https://integrate.api.nvidia.com/v1/audio/transcriptions`, multipart, Bearer key | **Default preset** in the app (`nvidia/parakeet-tdt-0.6b`) |
| [parakeet-tdt-0.6b](https://build.nvidia.com/nvidia/parakeet-tdt-0_6b/api) (25 European languages, including Polish) | HTTP `POST .../v1/audio/transcriptions`, multipart, Bearer key | Yes (`https://2b940e91-a70e-4483-a958-d50408b96589.invocation.api.nvcf.nvidia.com`) |
| [parakeet-ctc-1.1b-asr](https://build.nvidia.com/nvidia/parakeet-ctc-1_1b-asr/api) (English) | HTTP `POST .../v1/audio/transcriptions`, multipart, Bearer key | Yes (`https://1598d209-5e27-4d3c-8079-4751568b1081.invocation.api.nvcf.nvidia.com`) |
| [whisper-large-v3](https://build.nvidia.com/openai/whisper-large-v3/api), [canary-1b-asr](https://build.nvidia.com/nvidia/canary-1b-asr/api), parakeet-tdt-0.6b-v2, parakeet-1.1b-rnnt-multilingual and others | **gRPC only** (`grpc.nvcf.nvidia.com:443` with a `function-id` header) | **No.** Use a proxy (below) |

### Testing from a computer

For NVIDIA NIM / HTTP ASR endpoints, the app sends BCP-47 language codes (`multi`, `pl-PL`, `en-US`) and omits the default `whisper-1` placeholder unless you select a specific model ID (with automatic fallback if a deployment rejects `model` or `stream`). You can test the default NVIDIA endpoint from macOS or Linux without saving the key in shell history:

```sh
read -rs NVIDIA_API_KEY && export NVIDIA_API_KEY
curl -s https://integrate.api.nvidia.com/v1/audio/transcriptions \
  -H "Authorization: Bearer $NVIDIA_API_KEY" -F file=@test.wav -F model=nvidia/parakeet-tdt-0.6b -F language=pl-PL -F response_format=json
unset NVIDIA_API_KEY
```

- If it returns `{"text": "..."}`: in the app, edit the **NVIDIA** configuration set (or enter `https://integrate.api.nvidia.com/v1`), paste your API key, and press **Test Connection** or **Done**.
- If a model is gRPC-only (or requires mapping a model name): use the LiteLLM proxy below.

Source for the request and answer format:
[NIM for Speech, HTTP ASR API reference](https://docs.nvidia.com/nim/speech/latest/reference/api-references/asr/http-asr.html).

### Working alternative: a small proxy (LiteLLM)

[LiteLLM](https://docs.litellm.ai/docs/providers/nvidia_riva) can run on your computer or NAS, offer an
OpenAI-style `/v1/audio/transcriptions` to the app, and forward the audio to NVIDIA's hosted models over
gRPC. Its `nvidia_riva` provider does this (the `nvidia_nim` provider does **not** support
transcription). This also reaches the gRPC-only models such as Whisper large v3, and lets you name the
model `whisper-1` so the app's default works. Follow the LiteLLM page for install and configuration:
you give it the NVIDIA key, `grpc.nvcf.nvidia.com:443` and the model's function ID (from the model's API
tab). For models that only support offline recognition, such as Whisper large v3, set
`riva_offline: true` (needs LiteLLM v1.108.0 or newer). Name the model `whisper-1` in LiteLLM so the
app's first test works. This setup is documented by LiteLLM but was not tested with this app.

In the app: endpoint `http://<proxy-host>:<port>` on your home network (LiteLLM's default port is 4000),
**Provide API key** with the proxy's own key if you set one (turn off or replace any NVIDIA key you
entered before), model `whisper-1`.

### Self-hosted NVIDIA (needs an NVIDIA GPU)

NVIDIA's speech-to-text containers ("NIM for Speech", for example Parakeet) can run on your own machine
and then offer HTTP `POST /v1/audio/transcriptions` on port 9000, with the same request and answer
format as above and no API key on that port. They need an NVIDIA GPU (NVIDIA lists at least 16 GB of
video memory) and an NGC key to download the container. The same model-name and language-code caveats
apply (if the container rejects `whisper-1`, put the LiteLLM proxy in front of it). Docs:
[getting started](https://docs.nvidia.com/nim/riva/asr/latest/getting-started.html),
[Parakeet TDT deployment](https://docs.nvidia.com/nim/speech/latest/asr/deploy-asr-models/parakeet-tdt.html).
In the app: `http://<gpu-host>:9000`.
