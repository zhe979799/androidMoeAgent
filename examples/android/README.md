# BigMoeOnEdge — Android example

A minimal chat app that validates the throughput claim on a real phone: pick a pushed
`.gguf`, type a prompt, and watch the answer stream in while a live panel shows tok/s and
the per-token compute-vs-flash-I/O split and cache hit rate.

It runs the engine as the `bmoe-cli` binary (shipped as `libbmoe-cli.so`) via
`ProcessBuilder` from a foreground service — no JNI. This is the same pattern used by the
research harness and keeps the app a thin driver over the CLI.

## Foreground performance comparison

Metrics also has **Tune**, a user-invoked, recommendation-only comparison of four bounded variants:
current settings, a 3000 MiB cache, two I/O lanes and eight I/O lanes. It runs each variant twice in
balanced order with a short thermal cooldown between trials. The prompt, greedy decoding, token limit
and quality-affecting settings remain fixed; top-k overrides, expert dropping, route-ahead, prefetch
and speculation are excluded. Every trial starts a fresh session and writes its own engine CSV. The
additional `autotune-*.csv` records the order, repetition, output equality, start/end temperatures,
exact resolved settings and the recommendation. A winner is never applied automatically. Review the
engine CSVs and report together, especially when thermal readings are unavailable or a trial's output
differs.


The Agent uses a short non-reasoning generation budget for control turns and has a three-minute foreground deadline. If a model still emits a reasoning wrapper, the app extracts only a strictly validated tool object and removes complete or truncated reasoning from the visible answer.
 Open **工具集** first to compose a scenario from the Network diagnostics, Device exploration, Log analysis, Performance observation and Model catalog groups. The choice is stored locally and only selected tools are described to the model or accepted by the registry. Choose a loaded model, review the task and press **开始诊断**; merely opening the workspace never starts inference or a network observation. Agent turns use a separate transcript from ordinary chat. The agent page keeps compact status summaries visible and lets you expand raw JSON only when inspecting details. The existing chat screen also retains an opt-in **Network analysis** switch. It is a bounded foreground workflow,
not a general Android agent: the already-loaded `bmoe-cli --session` remains the only inference
owner, so the model and MoE expert cache remain warm across its model → tool → model turns. The app
never starts a gateway, scheduler or background shell process for this feature.

The model can request at most **five** of the currently enabled read-only tools, one per turn, and every
call plus its raw result remains visible in the chat UI. The optional Performance observation and
Model catalog groups add `runtime_metrics` and `model_catalog`; disabling a group removes its tools
from both the prompt and executor. Device exploration also exposes memory, process, thermal, display
and application metadata; network diagnostics exposes capability and interface-address snapshots;
log analysis exposes bounded history metadata without reading prior log contents.

- `network_state` — active transport, validation/captive-portal state, interface, routes and DNS;
- `network_capabilities` — metered/roaming/VPN flags and advertised link bandwidth;
- `network_addresses` — current interface addresses and prefix lengths;
- `dns_lookup` — a public `A` or `AAAA` lookup through Android's resolver;
- `ping_host` — 1–4 pings to a resolved public address with a 0.5–2 second per-packet deadline;
- `http_probe` — `HEAD` or a range-limited `GET` to a public HTTPS URL on port 443;
- `device_info` — Android release/SDK, device and app build metadata;
- `app_info` — package, version, target SDK and whether app-owned directories are present;
- `battery_state` — battery level, charging state, temperature and charge-counter reading;
- `thermal_state` — Android thermal status and battery temperature;
- `memory_state` — total/available system memory and low-memory status;
- `display_state` — pixel dimensions, density, orientation and font scale;
- `device_storage` — app-visible total/free/used storage totals, without listing files;
- `app_files` — up to 200 relative names and sizes under this app's own files directory;
- `runtime_metrics` — current session state, token rate, cache hit, I/O and temperature summary;
- `process_memory` — this app process PSS, private dirty memory and Java heap;
- `model_catalog` — locally discovered MoE model names and sizes;
- `read_selected_log` — up to 8 KiB of text the user explicitly pasted into the visible per-run log field;
- `agent_history` — metadata of retained diagnostic logs; prior log contents are omitted.

Open **社区** on the home screen for the Chinese **ModelScope** discovery page. It fetches public model metadata in a bounded request, shows rank/download/like/update fields when supplied by the source, and links back to the community page. Results are discovery data rather than invented benchmark claims; download and install still require an explicit action in **Get a model**.

The Agent workspace keeps a live **观测** panel visible while the model works: it reports whether the engine is loading, prefilling or generating, the number of tool calls, current token progress and available I/O/cache/temperature signals. Long raw tool JSON is constrained to a scrollable region, while the model still receives the complete structured result. The final diagnosis is prompted to distinguish facts, likely causes, confidence and safe next steps.
The app validates tool JSON rather than giving the model a shell. Tool responses have a compact Chinese summary for the UI while the complete JSON remains expandable and is still passed to the model. It rejects private, loopback,
link-local, multicast, reserved and unspecified destinations; pins each HTTPS TCP connection to
its validated address (preventing DNS rebinding); disables redirects; and bounds DNS, time, ping,
HTTP header and output sizes. The log tool has no filesystem access: it can see only text explicitly
pasted into the UI for that diagnosis. Tool output is framed as untrusted data, not as instructions.
After the Agent reads pasted log text, network tools are removed from the remaining turn contract so
log contents cannot authorize a DNS, ping or HTTPS request.
It cannot scan a network, change Wi-Fi/VPN/DNS/route settings, read credentials, access other apps,
invoke Accessibility, use Root/Shizuku/SSH, or execute Python.

For a diagnosis, describe the symptom and target in the prompt, for example: “Wi-Fi is connected,
but `https://example.com` times out while mobile data works. Find the smallest safe next check.”
The final answer is model assistance, not evidence of an intrusion or a substitute for an
administrator's confirmation.

Each diagnosis also writes a bounded local JSONL audit under the app's external-files directory.
It records the build, model name, request, tool arguments/results and timings, terminal status and
final answer; pasted log text is never copied into the audit (only its byte count is recorded).
The newest 20 files are retained. No root, adb or storage permission is needed to export them:
open **Metrics**, then use the **Share** action on the Agent logs row to send all retained logs to
Mail, Files or Drive through Android's one-shot `content://` grant. **Delete all** removes them.

The Metrics screen also has **Export and share** for a single diagnostic ZIP. It contains the newest
20 Agent JSONL files and newest 20 performance CSV files, plus `manifest.json` with app version,
versionCode and build SHA. Input is capped at 12 MiB and the resulting archive at 10 MiB; only the
newest three archives are retained. CSV model paths are reduced to basenames, and local filesystem
paths in Agent JSONL are omitted. The manifest contains no device identifiers or absolute paths, and
the archive never includes model files or pasted log source text. Requests and network metadata
from the Agent audit may be included; when a pasted log was supplied, the final answer is omitted
from its audit to prevent a partial log echo. Review the archive before sending it. If storage or
the share panel fails,
the screen reports the failure and keeps the archive for retry. Sharing uses the existing scoped
FileProvider and Android system chooser, with no root, adb, new permission or third-party dependency.

## Build

The sideloadable `dev` flavor uses the separate package ID
`io.bigmoeonedge.example.devagent`, so it can be installed alongside an older app without
uninstalling that app or deleting its private data.

1. Cross-compile and stage the engine binaries (needs the Android NDK):

   ```powershell
   pwsh ../../scripts/build-android.ps1
   ```

   This fills `app/src/main/jniLibs/arm64-v8a/` with `libbmoe-cli.so` and the
   `libllama`/`libggml` shared libraries.

2. Build and install the APK. Open this folder in Android Studio, or use the committed
   Gradle wrapper directly. The app has two distribution flavors (see below); build the one
   you want:

   ```bash
   ./gradlew assembleDevDebug
   adb install app/build/outputs/apk/dev/debug/app-dev-debug.apk
   ```

   Development builds use the committed shared development key, so an APK built on one workstation
   updates an install from another without losing downloaded models. The APKs attached to a GitHub
   release are built by the `release-apk` workflow from a clean checkout of the tag when the release
   is published, using a separate release key from repository secrets — no locally built artifact is
   uploaded by hand.

## Flavors

Two build flavors differ only in how a model reaches the device:

- **dev** — sideloaded (this is what CI attaches to releases). Keeps all-files access, so it
  can also read a model adb-pushed to shared storage. Application id `…​.example.dev`.
- **play** — Play-Store-compliant. No broad storage permission: models come only through the
  in-app downloader or the file picker. `./gradlew assemblePlayDebug`.

Both declare `android:appCategory="game"`. That is a performance decision rather than a claim
about what the app is: vendor layers read the attribute to pick a CPU governor profile, and on the
OxygenOS test device it lifted the foreground ceiling from 1.9/1.65 GHz to the hardware maximum of
3.32/3.80 GHz. Decode is the most CPU-hungry thing a phone does outside a game. The effect is the
vendor's, not Android's — neutral on stock builds, and Samsung's game service has historically
throttled apps it classifies this way — so treat any figure as a per-device measurement. It cannot
be toggled at runtime; a manifest attribute is fixed at install, and the only lever would be a
per-flavor manifest. **Numbers measured in the app before this landed are not comparable with
numbers measured after it.**

## Getting a model onto the device

The picker lists every MoE `.gguf` it finds (dense models are filtered out by a gguf-header
check). Nothing below needs a storage permission except the last option.

1. **Built-in catalog** (both flavors) — the "Get a model" card offers the models this engine
   is measured on, each a single tap: **Qwen3-30B-A3B-Q4_K_M** (~18.6 GB, the reference model),
   **Qwen3.6-35B-A3B-Q4_K_M** (~22.3 GB, a hybrid attention/SSM MoE, comfortably past device RAM)
   and **Gemma-4-26B-A4B-it-Q4_K_M** (~17 GB). Select **Auto** to probe the ordered catalog sources,
   or force **Official** or **Mainland mirror**. Auto fails over on connection/HTTP failure; every
   redirect stays HTTPS and is checked against public addresses, while an attachment filename
   supplied by the final CDN response and the exact size are checked before it is accepted. Downloads run
   in a foreground worker, survive the app being killed, resume an interrupted transfer instead of
   restarting, validate exact shard sizes, and show the active source in progress.
2. **Any other model** — under **Other model**, paste a direct gguf URL (e.g. a Hugging Face
   `…/resolve/main/model.gguf` link), or pick a `.gguf` already on the device to import it.

   You do **not** need a special file for **Guess ahead → Model's own head (MTP)**: the catalog's
   Qwen3.6 entry already carries the `nextn` block the MTP head lives in, as do Qwen3.6's ordinary
   quantisations generally. A gguf named `-MTP-` is the same head at a different quantisation.
   On a model with no head — anything that is not Qwen3.5/3.6 — the engine refuses to open rather
   than silently decoding one token at a time, so a wrong file fails immediately and says why.
   **Guess ahead → Repeated text (n-gram)** has no such requirement: it guesses from the text
   rather than from the weights, so it works on every model in the catalog.

   In-app downloads and picker imports both land in the app's internal storage (`filesDir`, a
   real f2fs/ext4 volume), so the streamed expert reads use O_DIRECT at full speed. Only models
   read from the emulated external dirs (adb-pushed to `/sdcard/Download`) fall back to buffered
   I/O. A download needs free space equal to the model size — no temporary second copy.
3. **adb push** (dev flavor only — needs all-files access, which the dev build requests):

   ```bash
   adb push Qwen3-30B-A3B-Q4_K_M.gguf /sdcard/Download/
   # /data/local/tmp/bmoe avoids duplicating a model too big to copy, and is on a real
   # filesystem where O_DIRECT works (the emulated dirs fall back to buffered I/O)
   adb push Qwen3-30B-A3B-Q4_K_M.gguf /data/local/tmp/bmoe/
   ```

   This directory was named `shardllm` before v0.8.0. To keep models already pushed there:

   ```bash
   adb shell mv /data/local/tmp/shardllm /data/local/tmp/bmoe
   ```

### Sharded models (gpt-oss-120b, DeepSeek V4 Flash)

Models above Hugging Face's 50 GB per-file limit ship as several shard files
(`-00001-of-0000N.gguf`). The engine streams a split set natively, so these download in-app
like any other catalog entry: the shards are fetched one at a time (each resumable), the row
shows one progress bar over the whole set, and the model list offers the FIRST shard, which is
the file the engine opens; it finds the siblings next to it. A merged single-file gpt-oss from
an earlier release keeps working and still shows as on-device.

For adb-pushed models the same rule applies: push all shards to the same directory and pass
the first one:

```bash
adb push DeepSeek-V4-Flash-0731-UD-IQ2_M-0000*-of-00003.gguf /data/local/tmp/bmoe/
```

Mind the space: DeepSeek V4 Flash UD-IQ2_M is ~91 GB on disk.

## Expected numbers

On a phone with UFS 4.x storage and ~12 GB RAM, streaming Qwen3-30B-A3B-Q4_K_M with the
expert cache at 4000 MiB, 4 I/O lanes and 4 compute threads, decode settles around
**0.55–0.6 s/token (~1.8 tok/s)** — a model ~1.7× the device RAM, lossless. That 4000 MiB is a
sweep point from the benchmark protocol, not the app default: the app ships a fixed 2000 MiB
expert cache. See `../../docs/benchmark-method.md` for the full procedure and the cache/thread
sweep.

## How Settings are organised

Each category shows the recommended configuration first and folds everything else into a collapsed
**Experimental** group: the levers measured on one device, measured once, or still owed a
measurement. They ship in the release build deliberately, because testing them on hardware other
than the one test phone is what this app is for.

Descriptions in the UI say what a setting does, without measured figures or flag names, because a
number needs the device, the model and the day beside it to be worth anything. The mapping to the
CLI flags and the evidence behind each one lives in the docs, and the **metrics screen** keeps the
flag names so a reading there can be matched against a CSV column.

Two worth knowing before you turn them on:

- **"Ask the next layer what it wants"** (`--predict-prefetch`) predicts each layer's experts one
  layer early and fetches or retains what the prediction names. It is markedly more accurate than
  the previous-token guess it replaces, and a better guess still did not buy throughput: in
  thermally matched pairs the read-ahead **lost**, because the flash is already saturated. See
  `../../docs/expert-prediction.md` before drawing conclusions from a run.
- **"Decide the experts early"** (`--route-ahead`) commits each layer's routing before that layer
  runs, so the reads can never be wasted. It changes the reply, and it is refused alongside guessing
  ahead. See `../../docs/route-ahead.md`.
