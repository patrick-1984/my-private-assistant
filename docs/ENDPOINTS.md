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

The app also understands two other self-hosted HTTP formats: the whisper.cpp server (`/inference`)
and whisper-asr-webservice (`/asr`), **plus native NVIDIA Riva gRPC** (`grpc.nvcf.nvidia.com:443`
or self-hosted `grpc://<host>:50051` / `grpcs://<host>:443`) using `Recognize`, `StreamingRecognize`,
and `GetRivaSpeechRecognitionConfig`.

## Setting it up in the app

1. Open the app, press the **gear** icon, and go to **2. Transcription Endpoints** (or edit a preset in **1. Configuration Presets**).
2. Type the address. Any of these work:
   - `<your-server>:<port>` (the app tries `http://` first, then `https://`)
   - a base URL: `http://<your-server>:<port>` (the app tries `/v1/audio/transcriptions`,
     `/audio/transcriptions`, `/inference`, `/asr` in that order)
   - a URL ending in `/v1`, or the full path (`.../v1/audio/transcriptions`)
   - a **gRPC** endpoint: `grpc.nvcf.nvidia.com:443`, `grpcs://<host>:443`, or `grpc://<host>:50051`
3. If the service needs a key: make sure **API key not needed** is unchecked and paste the key. For
   HTTP services on the internet, always include `https://`; for gRPC on port 443 (`grpc.nvcf.nvidia.com:443`
   or `grpcs://`), TLS is always used.
4. For NVIDIA Cloud gRPC (`grpc.nvcf.nvidia.com:443`), pick a built-in Parakeet preset or paste any
   model's **Function ID (`function-id` UUID)**.
5. Press **Test Connection**. It sends half a second of silence (or checks Riva config + 0.5s audio)
   and shows which address worked.
6. Optional, shown after a successful test: **Pick custom model / endpoint** lets you choose a model
   returned by `/v1/models` (or Riva config), or type a model name. **Live transcription** streams
   interim results (`StreamingRecognize` on gRPC, SSE or progressive chunks on HTTP).

Other options in that section: **Override server IP address** (connect to a fixed IP while keeping
the hostname for `Host` and TLS SNI) and **Ignore SSL certificate checks** (for self-signed
certificates on a trusted network only).

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
works (e.g. OpenAI, Groq), or **NVIDIA Cloud Functions (gRPC)** directly via `grpc.nvcf.nvidia.com:443`.
Check the service's prices and privacy terms first: your voice clips are sent there.

## NVIDIA build.nvidia.com (free API key, native gRPC)

NVIDIA offers a free trial API key on [build.nvidia.com](https://build.nvidia.com) that covers its
hosted speech-to-text models.

### Important note on `integrate.api.nvidia.com`

NVIDIA's OpenAI-style REST endpoint (`https://integrate.api.nvidia.com/v1`) **does not serve ASR /
transcription models**: `GET /v1/models` returns only chat/LLM and translation models, and
`POST https://integrate.api.nvidia.com/v1/audio/transcriptions` returns **HTTP 404** for any API key.
Instead, NVIDIA's hosted cloud speech-to-text (Parakeet, Canary, Whisper) runs over **gRPC + TLS** at
`grpc.nvcf.nvidia.com:443` with `authorization: Bearer nvapi-...` and `function-id: <UUID>` gRPC
metadata headers.

### Getting a key

1. Open an ASR model page on build.nvidia.com, for example
   [parakeet-1.1b-rnnt-multilingual](https://build.nvidia.com/nvidia/parakeet-1_1b-rnnt-multilingual),
   and click **Get API Key**.
2. Enter your email. This signs you up for the free NVIDIA Developer Program. Sign in, or set a
   password for a new NVIDIA account.
3. Click **Copy Key** and keep the key private.

Source: [NVIDIA API quickstart](https://docs.api.nvidia.com/nim/docs/api-quickstart). Rate limits and
how long the free trial lasts are not published on an official page we could find (users report about
40 requests per minute). The trial is meant for development and evaluation; read NVIDIA's terms.

### Default NVIDIA gRPC endpoint and models

The built-in **NVIDIA** endpoint preset in **Settings > 2. Transcription Endpoints** is pre-configured
for direct native gRPC:

- **Endpoint**: `grpc.nvcf.nvidia.com:443` (gRPC over TLS)
- **Default Function ID (`function-id`)**: `71203149-d3b7-4460-8231-1be2543a1fca` (**Parakeet 1.1B RNNT Multilingual** — 25 languages including Polish `pl-PL` and English `en-US`)

Built-in one-tap Function ID presets in the endpoint editor:

| Model | NVCF `function-id` UUID | Languages |
|---|---|---|
| `parakeet-1.1b-rnnt-multilingual` (**default**) | `71203149-d3b7-4460-8231-1be2543a1fca` | 25 languages incl. `pl-PL`, `en-US` |
| `parakeet-tdt-0.6b-v2` | `d3fe9151-442b-4204-a70d-5fcc597fd610` | English |
| `parakeet-ctc-1.1b` | `1598d209-5e27-4d3c-8079-4751568b1081` | English |
| `parakeet-ctc-0.6b` | `d8dd4e9b-fbf5-4fb0-9dba-8cf436c8d965` | English |

Simply edit the pre-configured **NVIDIA** entry in **Settings > 2. Transcription Endpoints**, paste your
`nvapi-…` API key, and press **Test Connection** or **Done**. You can also paste any other NVCF ASR
model's `function-id` UUID from its **API** tab on `build.nvidia.com`.

### Testing from a computer

You can test the exact same `nvapi-...` key and `function-id` against `grpc.nvcf.nvidia.com:443` with
NVIDIA's Python Riva client (`pip install nvidia-riva-client`) or `grpcurl` without saving the key in
shell history:

```sh
read -rs NVIDIA_API_KEY && export NVIDIA_API_KEY
python python-clients/scripts/asr/transcribe_file_offline.py \
  --server grpc.nvcf.nvidia.com:443 --use-ssl \
  --metadata function-id "71203149-d3b7-4460-8231-1be2543a1fca" \
  --metadata "authorization" "Bearer $NVIDIA_API_KEY" \
  --language-code pl-PL --input-file test.wav
unset NVIDIA_API_KEY
```

### Self-hosted NVIDIA NIM (needs an NVIDIA GPU)

NVIDIA's speech-to-text containers ("NIM for Speech", for example Parakeet) can run on your own machine
and expose both **HTTP** (`POST /v1/audio/transcriptions` on port `9000`) and **gRPC** (`port 50051`).
They need an NVIDIA GPU (NVIDIA lists at least 16 GB of video memory) and an NGC key to download the
container. Docs:
[getting started](https://docs.nvidia.com/nim/riva/asr/latest/getting-started.html),
[Parakeet TDT deployment](https://docs.nvidia.com/nim/speech/latest/asr/deploy-asr-models/parakeet-tdt.html).
In the app: use `http://<gpu-host>:9000` (HTTP) or `grpc://<gpu-host>:50051` (local gRPC, leave Function ID blank).
