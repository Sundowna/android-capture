# Android Second Brain Capture App — Feasibility Report

Date: 2026-11-10 · Status: **awaiting approval, no code written**

Legend: ✅ confirmed (read from source/manifest) · 🧪 needs on-device testing · ❌ not supported · 📖 from general platform knowledge, not re-verified for this report

---

## Summary

- **Option A (calling the installed Whisper+) does not meet the brief.** Whisper+ does provide a `RecognitionService` and a `RECOGNIZE_SPEECH` activity, so another app can call it. But it caps each recording at **30 seconds** and **refuses callers that pass their own audio** (`EXTRA_AUDIO_SOURCE` returns `ERROR_CLIENT`). So we could not keep the original recording, and longer thoughts would be cut off.
- **Recommended: Option B.** Our app records the audio itself and saves it first. Then it transcribes the saved file on the phone using **the same engine and model Whisper+ uses**: ONNX Runtime with RTranslator's Whisper-small int8 model. Recordings over 30 s are split into chunks at pauses in speech.
- **Vault access through SAF works without Obsidian running.** We write into the vault under temporary hidden filenames, then rename them, so a crash or sync never sees a half-written file. Notes are never overwritten.
- **The app can work with no INTERNET permission at all.** You load the model once by picking the ZIP file from your phone's storage.
- **Estimated size:** a medium project, roughly 6 phases. The riskiest part is whether transcription is as fast as Whisper+ on the Pixel 7 Pro, so the proof of concept tests that first.

---

## A. Technical feasibility

| Capability | Status | Notes |
|---|---|---|
| Launcher icon / shortcut → recording starts at once | ✅📖 | The tap opens a tiny activity with no visible screen, which starts a microphone foreground service. This counts as a user action, so Android 14+ allows microphone use. |
| Quick Settings tile / home-screen widget to start recording | 🧪 | Android 14+ limits starting a microphone service from the background. Widgets and tiles are allowed in principle, but must be tested on GrapheneOS. |
| Tap to stop (no holding a button) | ✅📖 | Stop from the notification action, the tile, or the in-app button. |
| Save audio before transcription | ✅ | Our own `AudioRecord` writes the audio straight to disk while you speak, so it survives if the app is killed. |
| Call Whisper+ via `RecognitionService` | ✅ exists / ❌ for our needs | 30 s cap. It does not hand back the audio and refuses audio we pass in. |
| Call Whisper+ via `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` | ✅ exists / ❌ for our needs | Same 30 s cap. Whisper+ records the audio, so we cannot keep the original. |
| Embed the Whisper+ engine (ONNX Runtime 1.19 + RTranslator Whisper ONNX) | ✅ components exist / 🧪 speed | arm64 only, which is fine for the Pixel 7 Pro (Tensor G2). |
| Transcribe recordings longer than 30 s | 🧪 | Whisper only takes 30 s at a time. We split at pauses using voice-activity detection (android-vad, MIT licence, the same library Whisper+ uses) and join the text. |
| Share target: URLs, text, PDF, images, other files | ✅📖 | `ACTION_SEND` / `ACTION_SEND_MULTIPLE` with suitable file types. |
| Identify the source app of a share | 🧪 | Only `Activity.getReferrer()` or `EXTRA_REFERRER`, and many apps don't set them. Treat as best effort. |
| Page title from Firefox shares | 🧪 | Firefox usually sends `EXTRA_SUBJECT` = page title. Fetching the title ourselves would need internet, so it is out of scope. |
| Write to vault via SAF without Obsidian | ✅📖 | `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission`. |
| Run with no INTERNET permission | ✅ | Whisper+'s own manifest declares none. The model is imported from a local file. |
| GrapheneOS compatibility | ✅📖 / 🧪 | No Google Play Services needed. Google's planned developer-verification rule applies to certified devices. GrapheneOS is not certified, so sideloading or F-Droid keeps working (to confirm). |

---

## B. Architecture

A single-module Kotlin app to start with. Packages are split along clear boundaries so they can become separate modules later. Jetpack Compose + Material 3, with dynamic colour and light/dark themes.

```
 Entry points                       Core                                 Storage
 ────────────                       ────                                 ───────
 LauncherShortcut ─┐
 QS Tile / Widget ─┼─► CaptureService (foreground, mic) ──► AudioRecorder ──► Staging (app-private)
 MainActivity ─────┘          │                                                   │
                              ▼                                                   ▼
 ShareActivity ─────────► CaptureRepository (Room DB: one row per capture, state machine)
                              │
                              ▼
                     WorkManager pipeline (resumable, retryable steps)
                     ├─ TranscribeWorker ──► Transcriber (interface)
                     │                         └─ OnnxWhisperTranscriber (+ VAD chunker)
                     ├─ AttachmentWorker ──► copies shared files / audio into vault
                     └─ NoteWorker ────────► MarkdownNoteBuilder ──► VaultWriter (SAF)
                              │
                              ▼
                     StatusBus (Flow) ──► notification, in-app list, tile/widget state
```

**The seven components the brief asks for:**

1. **Voice capture**: `CaptureService` + `AudioRecorder`. Records 16 kHz mono (the rate Whisper expects), saved as WAV, or optionally Opus/M4A to save space. The file header is updated regularly, so even an interrupted recording can be played back.
2. **Offline transcription**: the `Transcriber` interface; the first implementation is `OnnxWhisperTranscriber`. It reports progress honestly: "chunk 3 of 7".
3. **Share reception**: `ShareActivity`. It has no visible screen, copies the shared content into private staging **immediately** (Android's temporary access to shared files can expire), then closes with a short toast or snackbar.
4. **Attachment handling**: `AttachmentWorker`. Handles file types, safe filenames, size checks and hashing (for spotting duplicates).
5. **Markdown note generation**: `MarkdownNoteBuilder`. A pure function: capture record + settings → note text. Easy to unit-test.
6. **Vault access**: `VaultWriter`. SAF via `DocumentsContract`, not `DocumentFile`, which is slow. Writes in two stages: a hidden temporary file, then a rename.
7. **Status and recovery**: `CaptureRepository` (Room) records each capture's state: `RECORDING → RECORDED → TRANSCRIBING → TRANSCRIBED → WRITING → SAVED`, plus `FAILED(step, reason)`. WorkManager restarts unfinished steps after a crash or reboot.

**Adding new input types later** (camera, OCR, clipboard): each becomes a new entry point that creates a capture record, plus an optional new pipeline step. The vault writer and note builder stay as they are.

---

## C. Whisper+ integration: Option A vs Option B

**Facts about Whisper+ (as of 2026-11-10):**
- App: `org.woheller69.whisperplus`, v2.0 (2026-09-01). Active: latest commit 2026-10-24, ~180 commits, on F-Droid (arm64 only, 35 MiB).
- Licence: **GPL-3.0**. It is based on whisperIME (MIT) and uses code and models from **RTranslator** (niedev/RTranslator, Apache-2.0 📖).
- Engine: `com.microsoft.onnxruntime:onnxruntime-android:1.19.0` + `onnxruntime-extensions-android:0.12.4`. No whisper.cpp, no TFLite.
- Model: `whisper_small_int8.zip` (243 MB) from Hugging Face `DocWolle/whisperOnnx` (the model card says Apache-2.0; the README says MIT). Multilingual is likely but not explicitly stated.
- Manifest: `WhisperRecognitionService` (`android.speech.RecognitionService`), `WhisperRecognizeActivity` (`RECOGNIZE_SPEECH`, `WEB_SEARCH`) and an input method (keyboard). **No INTERNET permission.**
- Limits: "a limit of 30s for each recording" (README). There is also a commit that caps audio at 30 s, and one that makes callers passing `EXTRA_AUDIO_SOURCE` fail.

| | **Option A: call installed Whisper+** | **Option B: embed the same engine** |
|---|---|---|
| Transcription quality | Identical | Identical (same model and runtime) 🧪 confirm speed |
| Keeps original audio | ❌ Whisper+ owns the mic. We could record in parallel, but two apps can't reliably share the mic. | ✅ |
| Recordings > 30 s | ❌ | ✅ via VAD chunking |
| Retry after a failed transcription | ❌ no audio to retry with | ✅ re-run on the saved file |
| Effort | Low | Medium (ONNX pipeline: log-mel preprocessing, encoder/decoder loop; RTranslator's code shows how) |
| Licence impact | None | Copying Whisper+ code → our app must be **GPL-3.0**. Taking the pipeline from RTranslator (Apache-2.0) instead leaves us free to choose. For a personal app either is fine. |
| Model distribution | Whisper+ handles it | You import the 243 MB ZIP once via the file picker (no internet). We can't read Whisper+'s private copy. |
| APK size | Tiny | ~35 MB (ONNX Runtime native libraries), model kept outside the APK |
| Depends on another app | Yes (and on its 30 s policy) | No |

**Recommendation: Option B**, with the transcriber behind an interface. If ONNX ever disappoints, we can try **whisper.cpp** (MIT) or **sherpa-onnx** (Apache-2.0) 📖 without touching the rest of the app.

**Optional fallback for Option A:** offer "Quick dictation (≤30 s, via Whisper+)" as a second mode. It is not part of the core.

---

## D. Obsidian integration

**Access:** on first run, you pick the vault folder (`ACTION_OPEN_DOCUMENT_TREE`). We keep that permission. At every app start and before each write, we check it is still in `getPersistedUriPermissions()` and that the folder still exists.

**Folder layout** (configurable, created on demand):
```
<Vault>/Inbox/{Voice,Links,Text,Documents,Attachments}
```
Audio files go in `Inbox/Attachments/` (or a `Voice/` subfolder, as you prefer), linked with `![[…]]`.

**Filenames:** `2026-11-10 0853 Voice – <first words>.md`, limited to safe characters. If the name already exists, we add `-2`, `-3`, … ourselves. We never rely on the storage provider's automatic renaming, and **never overwrite**.

**Example note (voice):**
```markdown
---
type: capture
source: voice
captured: 2026-11-10T08:53:12+01:00
duration: 00:01:42
audio: "[[2026-11-10 0853 Voice.m4a]]"
transcription: whisper-small-int8 (onnx)
capture_id: 01JC…  # ULID, used to detect duplicates
status: inbox
---
<transcript>

![[2026-11-10 0853 Voice.m4a]]
```
Link and PDF notes use the same frontmatter with `url`, `title`, `source_app`, `attachment`, `sha256`.

**Safe writes:**
1. Each capture is first written to app-private staging, which is always available.
2. Copy to the vault as `.capture-<id>.tmp`. Obsidian and Syncthing both ignore dotfiles by default 🧪.
3. Check the byte count, then `renameDocument` to the final name.
4. Mark the capture `SAVED` in the database, then delete the staging copy.

If the app is interrupted at any step, WorkManager resumes from the last state saved in the database. Leftover temporary files are cleaned up at next start. Audio is written **before** the note, so a note never links to a missing file.

**Duplicate prevention:** the `capture_id` is in the frontmatter and in the database. Repeated shares of the same URL or file within a short window are detected by hash and offered as "Already captured – open / save again".

**Revoked permission / unavailable storage:** captures stay in staging and the state changes to `WAITING_FOR_VAULT`. A persistent notification says "3 captures waiting – tap to re-select vault". Nothing is lost.

---

## E. Existing solutions

| Project | What it offers | Licence | Use for us |
|---|---|---|---|
| **Whisper+** (woheller69) | Best transcription on your device; reference for the ONNX pipeline and VAD | GPL-3.0 | Reference / possible code reuse (would make us GPL) |
| **RTranslator** (niedev) | Origin of the Whisper ONNX models and inference code | Apache-2.0 📖 | Best source for the transcription pipeline |
| **ONNX Runtime Android** | Inference runtime | MIT 📖 | Dependency |
| **android-vad** (gkonovalov) | WebRTC/Silero VAD | MIT | Chunking at pauses |
| **whisper.cpp** / **sherpa-onnx** | Alternative engines | MIT / Apache-2.0 📖 | Fallback behind `Transcriber` |
| **Obsidian Android** (built-in share target) | Accepts shares into a note | Proprietary | Must be open or in memory, no audio, no inbox pipeline: doesn't meet the brief |
| **Markor** | Markdown editor that accepts shares into files | Apache-2.0 📖 | Shows how to receive shares, but has no voice pipeline |
| **FUTO Voice Input** | Offline Whisper keyboard | Not OSI ("source-first") 📖 | Not reusable |

**Conclusion:** no existing open-source app combines "record → keep audio → offline Whisper → Markdown into the vault". Adapting Whisper+ itself would mean forking an input method app and fighting its 30 s design. A small new app that reuses RTranslator/Whisper+'s engine is the less complex path.

---

## F. UX proposal

**Status vocabulary** (the same icons and colours in the notification, app, tile and widget):

| State | Indicator |
|---|---|
| Recording | Red pulsing dot + **mm:ss timer** + live level meter (waveform optional) |
| Saving audio | Short indeterminate bar (normally <300 ms, often never visible) |
| Transcribing | **Progress bar "Chunk 2 of 5"**, based on real chunk counts. For a single chunk: an animated indeterminate bar, never a fake percentage. |
| Writing to vault | Indeterminate bar (fast) |
| Saved | ✓ + "Saved to Inbox/Voice" for 2 s, then the notification dismisses itself |
| Failed | ⚠ + one-line reason + **Retry** action. The audio is still kept. |
| Waiting for vault | 🔒 persistent notification: "Re-select vault" |

**1. Voice note:** tap the home-screen "Capture" icon → a short vibration and the recording notification within ~150 ms (target 🧪). Speak. Tap **Stop** in the notification or the full-screen button. The audio is saved and the notification switches to "Transcribing · 1 of 3 ▓▓░". Then "✓ Saved". You can lock the phone at any point; everything continues in the background.

**2. Website (Firefox):** Share → *Second Brain* → a toast "✓ Link saved", and you're back in Firefox in under a second. The note contains the URL, title (from `EXTRA_SUBJECT`), source app if known, timestamp and any selected text.

**3. PDF:** Share → *Second Brain* → "Saving document…" with a determinate progress bar when the file size is known → "✓ Saved". The file is in `Attachments/`, and the note in `Documents/` links to it with `![[file.pdf]]`.

**4. Failed transcription:** the notification says "⚠ Transcription failed – audio kept · Retry". The in-app capture list shows the item with a **Retry** button. You can also choose **Save without transcript**, which writes a note with the audio and an empty transcript section to fill in later. You never need to record again.

**5. Vault unavailable:** captures keep working and queue in the app. A persistent notification says "Vault not accessible – 3 captures waiting". Tap → folder picker → the queue is written out automatically, with progress "Writing 2 of 3".

**App home screen:** a large record button (≥72 dp), and below it a list of recent captures with status chips. Settings: vault, folders, filename template, audio format, model import, language. No onboarding beyond: pick vault → import model → grant microphone.

---

## G. Development phases

| # | Phase | Outcome / exit criterion |
|---|---|---|
| 1 | **Proof of concept** | Throwaway app: record 2 min → ONNX Whisper-small transcribes on the Pixel 7 Pro. **Measure speed against Whisper+ on the same clip.** SAF write test on your real vault folder. Microphone foreground service started from a launcher shortcut on GrapheneOS. |
| 2 | Voice capture + transcription | Production recorder, VAD chunking, `Transcriber` interface, capture database, WorkManager pipeline, retry |
| 3 | Vault integration | `VaultWriter` (temp-file-then-rename), note builder, filename rules, permission-loss queue |
| 4 | Share integration | URL, text, PDF, images, multiple files, immediate staging, duplicate detection |
| 5 | UX, reliability, privacy | Status system everywhere, tile and widget, accessibility pass, kill-during-every-step tests, permission audit (no INTERNET) |
| 6 | Sync compatibility | Test with Syncthing and Obsidian Sync: temp files ignored, no conflicts from our writes, large audio files |

Phase 1 is the go/no-go point for Option B.

---

## H. Risks and alternatives

| Risk | Impact | Mitigation |
|---|---|---|
| ONNX transcription slower than Whisper+ in our implementation | High | Port RTranslator's pipeline closely. Measure in phase 1. whisper.cpp as fallback. |
| Android 14+ background microphone limits (tile/widget start) | Medium | The launcher shortcut path is safe. Tile and widget tested in phase 1. |
| GrapheneOS microphone toggle off | Low | Detect a silent/blocked mic and show "Microphone is disabled in Quick Settings". |
| SAF slow or permission revoked | Medium | `DocumentsContract` directly, staging queue, re-pick flow |
| Sync picks up half-written files | Medium | Hidden temp file + rename. Write audio before the note. |
| Sync conflicts | Low | We only create new, uniquely named files and never edit existing ones, so conflicts are practically impossible from our side. |
| 243 MB model, memory use | Medium | Load the model on demand and release it after a few minutes idle. Run in a foreground service while transcribing. |
| Licences (GPL if copying Whisper+ code) | Low (personal use) | Prefer RTranslator code (Apache-2.0). Decide on a licence before any public release. |
| Whisper+ upstream changes | Low under Option B | We only depend on the model file and ONNX Runtime. |
| Google developer verification | Low on GrapheneOS | Affects only certified devices. |

**Alternatives considered:** (1) Option A only: simplest, but loses the audio and has the 30 s cap. (2) Fork Whisper+: built around a keyboard, GPL, upstream conflicts. (3) Obsidian plugin (Voice Scribe): already shown to be slower and less accurate.

---

## I. Approval checkpoint

**Recommended:** a new native Kotlin app (Compose, Material 3). It does its own recording and saves the audio first, then transcribes offline with **ONNX Runtime + RTranslator/Whisper+ Whisper-small int8**, chunked by VAD. It writes to the vault directly through SAF (temp file, then rename) and queues captures safely, and it has **no INTERNET permission**.

**Complexity:** medium overall. The hardest part is the ONNX Whisper pipeline. Everything else is standard Android work.

**Sequence:** phases 1 → 6 above, with a go/no-go decision after phase 1.

**Decisions (2026-11-10):**
1. Option B: *awaiting explicit approval*.
2. Licence: **private for now.** No Whisper+ (GPL) code is copied; the transcription pipeline is built from RTranslator (Apache-2.0) and our own code.
3. Audio format: **WAV to start with.** M4A/Opus can be added later as a setting.
4. Project location: **`D:\BrainTest\android-capture`, with an online Git repository** (details to confirm). The repository holds app code only, never vault content or real captures.

⛔ **No code will be written until you approve.**
