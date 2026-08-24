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


The Agent has a free-form per-model-turn output-token budget (default 256). The native session clamps
that request after tokenizing the rendered prompt, so a large value is safe: it uses only the context
room that remains after the prompt. GPT-OSS models use their native Harmony message/template path, while
Qwen2/Qwen3/Qwen3.5 models use their native Qwen tool template. The saved Agent message is a developer
instruction for GPT-OSS and a system instruction for Qwen, the console exposes Harmony reasoning effort
(`low`, `medium`, `high`), enabled tools are passed as function schemas, and tool results return as structured
messages using the shared `bmoe.tool_result.v1` envelope. GPT-OSS arrays, Qwen XML calls and the legacy
JSON fallback all normalize into the same executor contract. If a model emits an unparsable or disabled
call, it is reported as unexecuted; an empty tool return becomes an explicit error result instead of
being silently treated as a successful turn.
The Agent workspace includes a persisted `首轮强制调用工具` switch. When enabled, the first native
turn requires one enabled function call to establish evidence; when disabled, the model may answer
directly and call a tool only when fresh device evidence is needed. Later turns use `auto` so the model
can finish after the evidence is sufficient. Other architectures keep the bounded JSON prompt fallback.
If a model still emits a reasoning wrapper, the app extracts only a strictly validated tool object and
removes complete or truncated reasoning from the visible answer.
The Agent workspace supports **快速任务**, **深入任务** and **仅回答** modes, plus an explicit **GPT-OSS**
or **Qwen** protocol profile. The profile is saved and shown independently of the selected model file;
GPT-OSS uses its developer-role/reasoning configuration and Qwen uses its system-role/template behavior.
Objective, known information, constraints and output format are optional per run; empty fields are omitted
from the model prompt. Policy controls tool rounds, parallel read-only calls, capability groups and
first-turn evidence. Built-in network, performance and device templates remain available as explicit
selections, while user templates retain their selected protocol profile.

The choice of toolkits is stored locally; only selected tools that pass the Agent policy are described to
the model or accepted by the registry. Choose a loaded model, review the task and press **开始任务**;
merely opening the workspace never starts inference or a tool. Agent turns use a separate transcript,
with injected tools, plan state, evidence and raw JSON details visible in the workspace. The existing chat
screen also retains an opt-in tool mode. It is a bounded foreground workflow: the already-loaded
`bmoe-cli --session` remains the only inference owner, so the model and MoE expert cache remain warm
across its model → tool → model turns. The app never starts a gateway, scheduler or background shell
process for this feature. Script execution is available only when explicitly enabled and runs in the app's
private files directory with a bounded timeout and output size.

The Agent workspace also accepts an optional user-authored **SystemMessage**. It is stored in the
app's local preferences, can be restored to the built-in rules, and is applied to later Agent turns.
The app's safety rules and the selected tool definitions are appended after that message, so the
tool contract remains visible and follows the configured SystemMessage. Starting a task saves
the current editor value as well as the explicit save action.

The model can request at most **five** tool rounds and, when it emits independent registered observations,
up to two may execute in parallel. Every call plus its raw result remains visible in the chat UI. The optional Performance observation,
Model catalog and Web search groups add their own tools; disabling a group removes its tools
from both the prompt and executor. Device exploration also exposes memory, process, thermal, display
and application metadata; network diagnostics exposes capability and interface-address snapshots;
log analysis exposes bounded history metadata without reading prior log contents. Baidu and Bing
search need no key; Exa is available after an API key is entered in 工具集.
When the prompt history approaches the configured session context, the Agent asks the loaded model to
compress tool evidence into a short fact ledger, clears the native KV context, and continues from that
ledger instead of repeatedly injecting raw search or file output.

- `network_state` — active transport, validation/captive-portal state, interface, routes and DNS;
- `network_capabilities` — metered/roaming/VPN flags and advertised link bandwidth;
- `network_addresses` — current interface addresses and prefix lengths;
- `dns_lookup` — a public `A` or `AAAA` lookup through Android's resolver;
- `ping_host` — 1–4 pings to a resolved public address with a 0.5–2 second per-packet deadline;
- `http_probe` — `HEAD` or a range-limited `GET` to a public HTTPS URL on port 443;
- `network_diagnose` — one endpoint pass covering DNS resolution, TCP connect and HTTPS response;
- `wifi_info` — current Wi-Fi SSID/link speed/frequency/RSSI when Android exposes them;
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
- `search_baidu` — public Baidu search titles, links and snippets;
- `search_bing` — public Bing search titles, links and snippets;
- `search_exa` — Exa semantic search results when its local API key is configured;
- `run_script` — a short shell script in the app-private files directory, only when the group is enabled.
- `file_list` — list app-private files and sizes;
- `file_read` — read a text file in chunks using `offset`, `max_bytes` and the returned `next_offset`.

Open **社区** on the home screen for the Chinese **ModelScope** discovery page. It fetches public model metadata in a bounded request, shows rank/download/like/update fields when supplied by the source, and links back to the community page. Results are discovery data rather than invented benchmark claims; download and install still require an explicit action in **Get a model**.

The Agent workspace keeps a live **观测** panel visible while the model works: it reports a staged
timeline for preparation, model turns, tool calls, context compaction and finalization, plus the
latest 512 per-token samples. Each sample includes token text, timing decomposition, flash/cache
signals and the requested/effective output budget. Older samples are counted and dropped from the
UI only; the model output budget is not limited to 512. Long raw tool JSON is constrained to a
scrollable region, while the model still receives the complete structured result. The final diagnosis
is prompted to distinguish facts, likely causes, confidence and safe next steps.
The app validates tool JSON and only exposes explicitly enabled tool groups. Tool responses have a compact Chinese summary for the UI while the complete JSON remains expandable and is still passed to the model. It rejects private, loopback,
link-local, multicast, reserved and unspecified destinations; pins each HTTPS TCP connection to
its validated address (preventing DNS rebinding); disables redirects; and bounds DNS, time, ping,
HTTP header and output sizes. The log tool has no filesystem access: it can see only text explicitly
pasted into the UI for that diagnosis. Tool output is framed as untrusted data, not as instructions.
After the Agent reads pasted log text, network tools are removed from the remaining turn contract so
log contents cannot authorize a DNS, ping or HTTPS request.
It cannot scan a network, change Wi-Fi/VPN/DNS/route settings, read credentials, access other apps,
invoke Accessibility, use Root/Shizuku/SSH, or start a background shell process. The opt-in script tool
is limited to a foreground command in the app-private files directory.

For a diagnosis, describe the symptom and target in the prompt, for example: “Wi-Fi is connected,
but `https://example.com` times out while mobile data works. Find the smallest safe next check.”
The final answer is model assistance, not evidence of an intrusion or a substitute for an
administrator's confirmation.

Each diagnosis also writes a bounded local JSONL audit under the app's external-files directory.
It records the build, model name, request, tool arguments/results and timings, terminal status and
final answer; pasted log text is never copied into the audit (only its byte count is recorded).
The newest 20 files are retained. No root, adb or storage permission is needed to export them:
open **Metrics**, select the Agent log rows to share, then use **Share selected** to send only those
files to Mail, Files or Drive through Android's one-shot `content://` grant. **Select all** is
available when the full retained set is needed, and **Delete all** removes them.
Inference traces use the same per-file selection controls, so ordinary-chat and Agent generation
traces can be shared independently.

The Metrics screen also has **Export and share** for a single diagnostic ZIP. It contains the newest
20 Agent JSONL files, newest 30 inference traces and newest 20 performance CSV files, plus `manifest.json` with app version,
versionCode and build SHA. Input is capped at 12 MiB and the resulting archive at 10 MiB; only the
newest three archives are retained. CSV model paths are reduced to basenames, and local filesystem
paths in Agent JSONL are omitted. The manifest contains no device identifiers or absolute paths, and
the archive never includes model files. Inference traces contain the actual prompts/messages, model
answers, reasoning, tool calls and per-turn metrics; review them before sharing. Requests and network metadata
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
   is measured on, each a single tap: **Qwen3.5-122B-A10B-IQ2_M** (~44.4 GB, capability-first),
   **Ling 3.0 Flash IQ2_M** (~43.6 GB, 512-expert `bailingmoe3`),
   **Qwen3-30B-A3B-Q4_K_M** (~18.6 GB, the reference model),
   **Qwen3-Coder-30B-A3B-Instruct-Q4_K_M** (~18.6 GB, coding-focused),
   **Qwen3.6-35B-A3B-Q4_K_M** (~22.3 GB, a hybrid attention/SSM MoE, comfortably past device RAM),
   **Gemma-4-26B-A4B-it-Q4_K_M** (~17 GB), **gpt-oss-20b-Q4_K_M** (~11.7 GB), and
   **gpt-oss-120b-MXFP4** (~63.4 GB, a single-file ModelScope source). Select **Auto** to probe the ordered catalog sources,
   or force **Official**, **Mainland mirror**, or **ModelScope** for the dedicated ModelScope row. Auto fails over on connection/HTTP failure; every
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
