# Brain Capture: technical specification (rebuild handover)

For an agent or developer who must rebuild this app exactly as it is and prove it works end to end, without the original code. This file is the **how it is built**. Read it together with:

| Document | Answers |
|---|---|
| [FEATURES.md](FEATURES.md) | **What** exists: every user-visible feature, as a checklist |
| [FLOWS.md](FLOWS.md) | **How it connects**: every path F01–F34, from the phone's home screen to the saved note |
| SPEC.md (this file) | **How it is built**: stack, manifest, data, storage, vault formats, pipelines, AI prompts, UI, widgets |
| [E2E-TESTS.md](E2E-TESTS.md) | **How to prove it**: the full test run on a real phone, with coverage tables |
| Obsidian canvas "Brain Capture Wireframes" | **What it looks like**: every screen drawn with canvas cards, arrows from each control to the screen it opens, a spec card under each screen |
| Design canvas "Brain Camera button" (claude.ai artifact) and `docs/screens/F01–F34.png` | Pixel mockups of the screens |

## How to rebuild with these documents

1. Set up the toolchain and project exactly as in **Part A §1** (versions, flags, dependencies).
2. Create the manifest, resources and file map of **Part A §2–3**.
3. Build the data layer (**Part A §4–5**): database, preferences, capture types, on-device folders.
4. Build the vault writer and note formats (**Part A §6**), then undo and edit backup (**§7**), housekeeping (**§8**).
5. Build recording, transcription and AI cleanup (**Part B §1–3**), then text, share, photo and attach (**§4**).
6. Build the UI (**Part B §5**) screen by screen from FLOWS.md and the wireframe canvas; use the exact texts in FEATURES.md.
7. Build widgets, tile, shortcuts (**Part B §6**) and keep the concurrency rules (**§7**).
8. Run E2E-TESTS.md completely. Every FEATURES item and every flow F01–F34 must pass.

Long strings that must match exactly (the AI instructions of the built-in types and the cleanup prompt rules) are quoted in Part B §3. Where a part says "take it verbatim from <file>", the text is in that file of the original repo.

## Visual language and colour meaning

The app has one dark theme: grey surfaces with one amber accent. Hex values and fonts are in Part B §5.

| Colour | Means in the app | On the wireframe canvas |
|---|---|---|
| Amber | The one main action on a screen (big mic, Save, the chosen option); recording on | Same |
| Red | Delete, discard, errors, the recording dot | Same |
| Green | Saved, done, installed, the just-saved card border | Same |
| Blue | Private zone: kept on the phone only, never in the vault | Same |
| Grey | Ordinary controls and content | Grey cards |
| Light grey | Hints, secondary text | Hints, backgrounds and Android's own UI (launcher, camera, system dialogs) |
| — | — | Dark frame = one phone screen; teal = spec card (function, tech, tests); purple = headings and notes |

Rules the UI keeps: at most one filled amber button per screen; every control shows its state the same way everywhere; nothing happens silently (every save, wait and failure has visible text); Undo instead of confirmations, except for discarding a recording of 20 s or more and deleting from the bin icon.

---

# Part A: build, manifest, data, storage, vault, undo

Scope: build, file map, manifest, data model, on-device storage, vault output, undo and edit backup, housekeeping.
User-visible behaviour is in `FEATURES.md`, step-by-step flows in `FLOWS.md`, tests in `E2E-TESTS.md`; this part does not repeat them.
All values are taken from branch `playground` (Brain Play). Paths below are relative to `app/src/main/java/org/brain/capture/` unless they start with `app/`.

---

## 1. Tech stack and build

### 1.1 Toolchain

| Item | Value | Where |
|---|---|---|
| Gradle wrapper | 8.11.1 (`gradle-8.11.1-bin.zip`, `networkTimeout=10000`, `validateDistributionUrl=true`) | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | `com.android.application` 8.7.3 | root `build.gradle.kts` |
| Kotlin | `org.jetbrains.kotlin.android` 2.0.21 | root `build.gradle.kts` |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` 2.0.21 | root `build.gradle.kts` |
| KSP | `com.google.devtools.ksp` 2.0.21-1.0.28 (Room compiler only) | root `build.gradle.kts` |
| Version catalog | none (`gradle/libs.versions.toml` does not exist; versions are inline) | |
| Java | source/target `JavaVersion.VERSION_17`, `jvmTarget = "17"` | `app/build.gradle.kts` |

`settings.gradle.kts`: `rootProject.name = "android-capture"`, `include(":app")`; plugin repos `google()`, `mavenCentral()`, `gradlePluginPortal()`; dependency repos `google()`, `mavenCentral()` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.

`gradle.properties`: `org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `kotlin.code.style=official`, `android.nonTransitiveRClass=true`.

### 1.2 Android config (`app/build.gradle.kts`)

| Key | Value |
|---|---|
| `namespace` | `org.brain.capture` |
| `compileSdk` | 35 |
| `minSdk` / `targetSdk` | 31 / 35 |
| `applicationId` | `org.brain.capture` |
| `applicationIdSuffix` | `.play` (playground only; comment says drop before merging to main) → installed id `org.brain.capture.play` |
| `versionCode` / `versionName` | 5 / `1.3` |
| `versionNameSuffix` | `-play` (playground only) → `1.3-play` |
| `ndk.abiFilters` | `arm64-v8a` only |
| `buildFeatures.compose` | true |
| `packaging.jniLibs.useLegacyPackaging` | false (native libs stay uncompressed in the APK) |
| Kotlin compiler flag | `freeCompilerArgs += "-Xskip-metadata-version-check"` (LiteRT-LM is built with a newer Kotlin) |

Main branch is the same file without the two `.play` / `-play` suffix lines; the launcher label also differs (`app_name` is `Brain Play` here; icon `@mipmap/ic_brainplay`).

### 1.3 Dependencies (all `implementation` unless noted)

| Artifact | Version |
|---|---|
| `androidx.compose:compose-bom` (platform) | 2024.12.01 |
| `androidx.compose.material3:material3` | from BOM |
| `androidx.compose.ui:ui` | from BOM |
| `androidx.compose.material:material-icons-core` | from BOM |
| `androidx.activity:activity-compose` | 1.9.3 |
| `androidx.core:core-ktx` | 1.15.0 |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.7 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 |
| `androidx.room:room-runtime` | 2.6.1 |
| `androidx.room:room-ktx` | 2.6.1 |
| `androidx.room:room-compiler` (`ksp`) | 2.6.1 |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.19.0 |
| `com.microsoft.onnxruntime:onnxruntime-extensions-android` | 0.12.4 |
| `com.google.ai.edge.litertlm:litertlm-android` | 0.19.1 (Gemma, on-device) |
| `junit:junit` (`testImplementation`) | 4.13.2 |

No networking library; the manifest has no `INTERNET` permission. `FileProvider` comes from `androidx.core`. JSON uses platform `org.json`.

### 1.4 Build types and signing

- `debug`: AGP defaults; adds source set `app/src/debug/` (manifest + `CleanupProbeActivity`).
- `release`: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`, `signingConfig = signingConfigs.findByName("release")` (null → unsigned APK).
- Signing: if `rootProject.file("keystore.properties")` exists it is loaded as `java.util.Properties` and a `release` signing config is created from four keys: `storeFile` (path to the keystore, outside the repo), `storePassword`, `keyAlias`, `keyPassword`. The file is git-ignored (`.gitignore`: `keystore.properties`, `*.keystore`, `*.jks`); it is not present in the playground working tree.

`app/proguard-rules.pro`:
```
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { native <methods>; }
-dontwarn ai.onnxruntime.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
```

### 1.5 Lint (`app/lint.xml`)

Only these are set to `severity="ignore"`, everything else default: `Recycle`, `GradleDependency`, `ChromeOsAbiSupport`, `ExifInterface`, `UsableSpace`. No `lintOptions` block in Gradle.

### 1.6 Unit tests (`app/src/test/java/org/brain/capture/`, JUnit 4, pure JVM)

| File / class | Covers |
|---|---|
| `CoreTest.kt` → `NoteTextTest` | `NoteText.captureId` (only inside frontmatter), `display` drops frontmatter and `![[...]]` lines but keeps additions, `withBody` keeps frontmatter byte-exact, CRLF handling |
| `CoreTest.kt` → `NoteBuilderTest` | `baseName` has seconds and no colons, strips forbidden characters, name without text; typed `appendBlock` has no audio embed |
| `CoreTest.kt` → `NonSpeechTest` | `NonSpeech.strip` for `[BLANK_AUDIO]`, `[ Silence ]`, `(heartbeat)`, speech kept, parentheses inside speech kept |
| `CoreTest.kt` → `LoopGuardTest` | repeat detection and removal; normal sequence not a loop |
| `CoreTest.kt` → `ChunkerTest` | short audio = 1 piece, pieces ≤ limit, cut inside a pause (`Chunker.split`) |
| `CoreTest.kt` → `ShareParseTest` | Firefox link + subject title, "Title\nURL", quote + link, plain text not a link, `domain`, `fileKind` |
| `CoreTest.kt` → `ShareNoteTest` | `linkNote` fields, `fileNote` embeds PDF / links others, `sharedLine`, card text non-empty |
| `CoreTest.kt` → `StreamingTest` | `WavReader.profile` + `Chunker.plan` ranges equal the in-memory `Chunker.split` |
| `CleanupTextTest.kt` → `CleanupTextTest` (31 tests) | sentence segmentation and long-sentence cutting, prompt numbering, `check` (accept, missing refs, out-of-range refs, prose, invented lines, partial drop, paraphrase + due dates, title-only, mixed paragraphs), editing clean version keeps sources, `replaceWords` once, refs on own line, feedback lines (Fix/Feature boxes, leading refs), dropping unsaid "should", merged ref groups, code fences, `rewrite` note, cleaning an addition in a cleaned note, whole-line requirement, title + tasks parsing, bold title, task prompt has today, `forDisplay` hides refs and quote, share addition block, re-clean swaps block + `capture_type`, `withoutAddition`, edited note left alone, `replaceWords("")` terminates (timeout 2000 ms) |

No instrumented (`androidTest`) tests.

---

## 2. Package and file map

### `org.brain.capture` (root)
| File | Responsibility / main declarations |
|---|---|
| `CaptureState.kt` | In-memory process-wide state as `StateFlow`s: `sealed interface Phase` (`Idle`, `LoadingModel`, `Transcribing(done,total,etaSeconds)`, `WritingVault`, `Saved(notePath,warning,appended,captureId,appendTo,atMs)`, `Failed(message,wavPath)`); `RecordingInfo` (start, file, append label/target, type, pause bookkeeping, `elapsedMs()`); `LiveText`; `LastResult` (benchmark, `realTimeFactor`); `object CaptureState` (`phase`, `recording`, `level`, `live`, `last`, `processingId`, `processingFile`, `isRecording`, `isProcessing`). |
| `Prefs.kt` | `class Prefs` wrapper over SharedPreferences `settings` (see §4.3). |
| `Housekeeping.kt` | `object Housekeeping`: `usage()` → `Usage(model, staging, thumbs, database)`; `clean()` removes leftovers (§8). |

### `audio/`
| File | Responsibility |
|---|---|
| `CaptureService.kt` | Foreground service (`foregroundServiceType=microphone`) owning `AudioRecord` and the processing queue. Actions `start`, `stop`, `toggle`, `cancel`, `process_pending`, `pause`, `resume`; extras `type`, `retry`, `append_to`, `append_label`. Notification channel `capture` ("Capture status", `IMPORTANCE_LOW`), id 1. Partial wake lock tag `BrainCapture:capture`. Constants `BYTES_PER_SECOND=32_000`, `MIN_FREE_TO_START=50_000_000`, `STOP_BELOW=20_000_000`. Companion: `stagingDir()`, `pendingRecordings()`, `formatEta()`, `send()` (stop/cancel/pause/resume via `startService`, others via `startForegroundService`). |
| `OpusEncoder.kt` | `object OpusEncoder.encode(wav, ogg)`: 16 kHz mono WAV → Opus in Ogg with platform `MediaCodec`/`MediaMuxer`; `RATE=16_000`, `BITRATE=24_000`, `TIMEOUT_US=2_000`. |
| `WavFile.kt` | `WavWriter` (16 kHz mono 16-bit PCM, 44-byte little-endian header, `write`, `sync`, `close`); `WavReader` (`profile(file, frame=480)` → `Profile(samples, energy[], mutedFraction)`, `readRange`, `readFloats`). |

### `cleanup/`
| File | Responsibility |
|---|---|
| `CleanupStep.kt` | Post-save Gemma cleanup. `runPending` (voice rows saved in the last 24 h with `cleanup IS NULL`, via `pendingCleanup`), `cleanNow` (after an edit or "Save with AI"), `reclean` + `recleanOne` (type change), `undoReclean`, `lastReclean: MutableStateFlow<RecleanUndo?>`, `cleaning: MutableStateFlow<String?>`, `NO_CLEANUP = "skipped: no AI cleanup for"`. Writes vault via PendingEdit backup. |
| `CleanupText.kt` | Pure text logic (unit-tested): `segments` (sentence split, `MAX_SEGMENT=280`), `prompt`, `check` → `Result.Ok(title, bullets)` / `Result.Rejected(reason, retry)`, grounding (`MIN_GROUNDED=0.5`), `cleanBlock`, `bulletLines`, `stored`/`parseStored`/`bulletsFromStored`, `editable`/`fromEditable`, `replaceBlock`, `replaceWords`, `rewrite`, `withCaptureType`, `forDisplay`, `storedForDisplay`. |
| `GemmaHolder.kt` | Single LiteRT-LM `Engine`: `modelFile` (first `files/gemma/*.litertlm` by name), `import`, `remove`, `modelName`, `warm` (after `WARM_DELAY_MS=1500`), `generate` (fresh conversation, topK=1, topP=1.0, temperature=0.0, seed=0, thinking off), GPU then CPU fallback, unload after `IDLE_MS=10 min`; `background {}` runs a block at `THREAD_PRIORITY_BACKGROUND`. |

### `data/`
| File | Responsibility |
|---|---|
| `CaptureDb.kt` | Room: `CaptureStates`, `Capture` entity, `CaptureTypes`, `PendingEdit` entity, `CaptureDao`, `CaptureDb` (v5, migrations, singleton `get(ctx)`). See §4. |

### `pipeline/`
| File | Responsibility |
|---|---|
| `VoicePipeline.kt` | `class VoicePipeline.run(wav)`: transcribe → audio to vault → note / append, resumable per step; `savePrivate`; `PRIVATE_DIR="private"`, `SPEECH_LEVEL=0.004`; model label `"whisper-small-int8 (onnx)"`. Also `object NonSpeech.strip` and `object AudioCheck` (`loudestFrame`, `mutedFraction`, 100 ms frames). Muted > 0.9 → no transcription + warning; > 0.3 → partial warning. |
| `Notes.kt` | `NoteView`; `object Notes`: per-id card cache (`cached`, `forget`), `locate`, `load`, `openForEdit`, `saveEdit`, `appendTyped`, `recoverEdits`, `importHistory`, `ShareBundle` + `shareBundle`, `deleteAddition`, `delete`, `restore`. |
| `NoteEdits.kt` | `object NoteEdits`: section edits on the note screen — `wordsEditable`, `editWords`, `editClean`; private `write` (PendingEdit-protected overwrite) and `done` (touch, forget cache, `Undo.drop()`, widget refresh). |
| `TextCapture.kt` | `object TextCapture`: `save` (typed/pasted text as new note via the share path; private stays in DB), `append` (typed addition), constants `TYPED="skipped: typed"`, `AI_PENDING="pending: typed, with AI"`. |
| `Undo.kt` | `object Undo`: one-step undo model (§7). |

### `share/`
| File | Responsibility |
|---|---|
| `Shares.kt` | `object AppScope` (`SupervisorJob + Dispatchers.IO`, app-wide work). `object Shares`: `stage` (copy share into `files/shares/<id>/`, SHA-256, thumbnails), `duplicateOf` (24 h), `discard`, `save` (Mutex-serialised, resumable), `resumePending`, private `buildNote`, `addition`, `details`, `noteText`, `streams`. |
| `ShareParse.kt` | Pure: `SharedLink(url,title,extraText)`, `ShareParse.link`, `domain`, `fileKind` ("Image"/"PDF"/"File"). |
| `Previews.kt` | `object Previews`: `appName(pkg)`, `make(file, mime, out)` → JPEG thumb (max side 320, quality 80, EXIF rotation; PDF first page on white) + page count. |
| `ShareActivity.kt` | Share-sheet panel (Compose): stages, duplicate check, note picker ("add to"), voice comment hand-off to `CaptureService` with `append_to`/`append_label`. `EXTRA_SOURCE_NAME = "org.brain.capture.SOURCE_NAME"` (honoured only from own package or no referrer). Source package from `referrer` with scheme `android-app`. |

### `transcribe/`
| File | Responsibility |
|---|---|
| `WhisperOnnx.kt` | Whisper small int8 via ONNX Runtime; files `Whisper_initializer.onnx`, `Whisper_encoder.onnx`, `Whisper_cache_initializer.onnx`, `Whisper_decoder.onnx`, `Whisper_detokenizer.onnx` (`REQUIRED`); `SAMPLE_RATE=16_000`, 12 layers/heads, head dim 64, `MAX_TOKENS=445`, token ids SOT 50258, EOT 50257, languages 50259–50357, TRANSCRIBE 50359, NO_TIMESTAMPS 50363; `transcribe(samples, language)`, `languageToken`. |
| `Chunker.kt` | Splits audio into ≤ 28 s pieces cut at pauses (search window 8 s, 30 ms frames, min pause 6 frames = 180 ms): `split`, `plan(energy,total)`, `FRAME_SAMPLES`. |
| `LoopGuard.kt` | `isLooping(tokens)`: last 1–8 tokens repeated 4× → drop repeats. |
| `ModelStore.kt` | Whisper ZIP import into `files/whisper/` with size + SHA-256 check (`MODEL_URL` huggingface DocWolle/whisperOnnx `whisper_small_int8.zip`, `MODEL_SIZE=242_722_488`, `MODEL_SHA256=97ac1216…ca19e3`), `isInstalled`. |

### `types/`
| File | Responsibility |
|---|---|
| `Profiles.kt` | `CaptureProfile` data class and `object Profiles` (built-ins, storage, upgrade of untouched instructions, widget slots, default type, Photo pseudo-type). See §4.4. |

### `ui/`
| File | Responsibility |
|---|---|
| `MainActivity.kt` | Launcher activity; `EXTRA_HOME="home"`; `App` (first run / settings / home ↔ capture shared-element transition); `HomeScreen` (cards, progress, undo bar, startup jobs §8); `RecordingBar`, `MiniLevel`; `openInObsidian` + `isVaultRoot` (§6.9); `OpenInDownloads` picker contract; `FLING_BOOST=1.6f`. |
| `CaptureActivity.kt` | Stand-alone capture screen (widget/tile/shortcut). Extras `mode` (`voice`/`type`/`paste`/`edit`), `start`, `type`. `CaptureRequest(mode,type,start,noteId,at)`. `CaptureScreen` and its parts (type chip, Clean/Raw view tabs, sections, edit card, text card, big button, controls). `HEARTBEAT_MS=1100`. |
| `NoteCards.kt` | Home-list cards: `NoteCard`, `ProgressCard`, `SharedFiles` thumbnails, share/copy actions, `relativeTime`. |
| `StatusViews.kt` | `RecordHero`, `LevelBars`, `PulsingDot`, `phaseLabel`, `Phase.isBusy`, `DiscardButton`. |
| `SwipeDelete.kt` | `object SwipeDeletes` (`UNDO_MS=5000`, hidden set, delayed real delete) and `SwipeToDelete` composable (`LINE=0.45f`, `STICKY=0.6f`). |
| `Attach.kt` | `AttachSheet` (Gallery up to 20, Camera, Files & audio, Contact, Link, Location), `PickPhoneContact`, `LinkDialog`, `addLocation` (20 s timeout). |
| `TakePhoto.kt` | `object Photos` (`SOURCE="Camera"`, `newTarget`, `uri`, `saveShared`, `save`, `isPhoto`), `PhotoActivity` (camera → ShareActivity), `rememberTakePhoto`. |
| `SettingsScreen.kt` | Settings menu/pages, `VaultSetup`, `ModelSetup`, `GemmaSetup`, `LanguageSetup`, storage usage + "clean up". |
| `TypesSettings.kt` | `typeIcon(name)` → drawable `t_<name>` (fallback `t_star`); `TypesPart`; `CaptureTypesSettings`; `DefaultTypePicker`; `ProfileEditor`; `object Shortcuts` (§3.5). |
| `FirstRunScreen.kt` | First-run steps (vault folder, Whisper model). |
| `BrainWidget.kt` | 4-slot widget provider; `refresh(ctx)` redraws widget, 1x1 widgets and requests tile listening; `describe`, `status`. |
| `ButtonWidget.kt` | 1x1 widget provider + `ButtonWidgetSetupActivity`; type per widget in prefs `button_widgets`. |
| `WidgetSetupActivity.kt` | Configure / "Edit widget" for the 4-slot widget (shows `CaptureTypesSettings(widgetOnly=true)`). |
| `BrainTile.kt` | Quick Settings `TileService`; renders from `CaptureState.recording` and latest capture. |
| `QuickRecordActivity.kt` | "Talk" launcher icon: if recording → send `stop`; opens `CaptureActivity` with `Profiles.primary` and `start = !recording`. |
| `TextFields.kt` | `CursorFollowingTextField`, `Modifier.thinScrollbar`. |
| `Theme.kt` | `object Ink` colour tokens (Ground `#3A3E44`, Panel `#44484F`, Amber `#F4B860`, OnAmber `#2A2118`, …), `Brand`, fonts `Inter`/`Grotesk`/`Mono` from `res/font`, `CaptureTheme`. |

### `vault/`
| File | Responsibility |
|---|---|
| `VaultWriter.kt` | SAF writer over the persisted tree URI (§6.3); `VaultUnavailableException`. |
| `NoteBuilder.kt` | File names and note templates (§6.4–6.7). |
| `NoteText.kt` | Pure: `split`, `captureId`, `display`, `withoutAddition`, `withBody`. |

### Debug source set
`app/src/debug/java/org/brain/capture/cleanup/CleanupProbeActivity.kt`: runs cleanup on `--es text "…" [--es type <id>]` and logs under tag `CleanupProbe`.

---

## 3. AndroidManifest

### 3.1 Permissions (`app/src/main/AndroidManifest.xml`)
`RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS`, `VIBRATE`, `WAKE_LOCK`, `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`. No `INTERNET`, no contacts, no camera, no storage permission.

`<queries>`: one `<intent>` with `action.MAIN` + `category.LAUNCHER` (for app-name lookup of share sources).

`<uses-native-library>` (both `required="false"`): `libvndksupport.so`, `libOpenCL.so` (GPU for LiteRT-LM).

### 3.2 `<application>`
`allowBackup="false"`, `dataExtractionRules="@xml/data_extraction_rules"`, `icon` and `roundIcon` = `@mipmap/ic_brainplay`, `label="@string/app_name"` (`Brain Play`), `supportsRtl="true"`, `theme="@style/Theme.Capture"`, `tools:targetApi="35"`.

### 3.3 Components

| Component | exported | Attributes | Intent filters / meta |
|---|---|---|---|
| `.ui.MainActivity` | true | `launchMode=singleTop`, `windowSoftInputMode=adjustResize` | MAIN / LAUNCHER |
| `.ui.CaptureActivity` | false | `launchMode=singleTask`, `taskAffinity=${applicationId}.capture`, `excludeFromRecents=true`, `adjustResize`, theme `Theme.Capture` | — |
| `.ui.BrainWidget` (receiver) | false | `label="Brain"` | `APPWIDGET_UPDATE`; meta `android.appwidget.provider` → `@xml/widget_brain_info` |
| `.ui.ButtonWidget` (receiver) | false | `label="Brain button"` | `APPWIDGET_UPDATE`; meta → `@xml/widget_button_info` |
| `.ui.ButtonWidgetSetupActivity` | true | `excludeFromRecents`, `taskAffinity=${applicationId}.buttonsetup`, `Theme.Capture` | `APPWIDGET_CONFIGURE` |
| `.ui.WidgetSetupActivity` | true | `excludeFromRecents`, `taskAffinity=${applicationId}.widgetsetup`, `Theme.Capture` | `APPWIDGET_CONFIGURE` |
| `.ui.BrainTile` (service) | true | `icon=@drawable/ic_mic`, `label=@string/tile_label` ("Brain"), `permission=BIND_QUICK_SETTINGS_TILE` | `android.service.quicksettings.action.QS_TILE`; meta `ACTIVE_TILE=false` |
| `.ui.QuickRecordActivity` | true | `icon=@mipmap/ic_talk`, `label=@string/quick_record` ("Talk"), `launchMode=singleInstance`, `noHistory=true`, `taskAffinity=""`, `excludeFromRecents`, theme `Theme.Capture.Panel` | MAIN / LAUNCHER (second launcher icon) |
| `.ui.PhotoActivity` | false | `excludeFromRecents`, `taskAffinity=${applicationId}.photo`, `Theme.Capture.Panel` | — |
| `.share.ShareActivity` | true | `configChanges=orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode`, `excludeFromRecents`, `taskAffinity=""`, `Theme.Capture.Panel` | `SEND` + DEFAULT + mime `text/plain` and `*/*`; `SEND_MULTIPLE` + DEFAULT + `*/*` |
| `androidx.core.content.FileProvider` | false | `authorities=${applicationId}.files`, `grantUriPermissions=true` | meta `FILE_PROVIDER_PATHS` → `@xml/share_paths` |
| `androidx.startup.InitializationProvider` | false | `authorities=${applicationId}.androidx-startup`, `tools:node=merge` | meta `androidx.emoji2.text.EmojiCompatInitializer` with `tools:node=remove` (no emoji font download) |
| `.audio.CaptureService` | false | `foregroundServiceType=microphone` | — |

Debug manifest adds `org.brain.capture.cleanup.CleanupProbeActivity`, `exported=true`, theme `@android:style/Theme.Translucent.NoTitleBar`.

### 3.4 Themes (`res/values/themes.xml`, `values-night` identical for `Theme.Capture`)
- `Theme.Capture` parent `android:Theme.Material.NoActionBar`, `windowBackground=#FF3A3E44`.
- `Theme.Capture.Panel` parent `android:Theme.Translucent.NoTitleBar`, `windowAnimationStyle=@null` (values only).

Strings (`res/values/strings.xml`): `app_name`=Brain Play, `quick_record`=Talk, `widget_description`, `tile_label`=Brain, `widget_button_description`, `widget_title`=Brain, `widget_hint`=Tap to talk, `widget_empty`=No captures yet, `widget_button_default`=Idea.

### 3.5 Widgets, shortcuts, file provider, backup

`res/xml/widget_brain_info.xml`: `initialLayout`/`previewLayout=@layout/widget_brain`, `minWidth=250dp`, `minHeight=180dp`, `targetCellWidth=4`, `targetCellHeight=3`, `minResizeWidth=220dp`, `minResizeHeight=160dp`, `resizeMode=horizontal|vertical`, `updatePeriodMillis=0`, `configure=org.brain.capture.ui.WidgetSetupActivity`, `widgetFeatures=reconfigurable|configuration_optional`, `widgetCategory=home_screen`.
Layout `widget_brain.xml`: root `widget_root`; header (title + `widget_hint`); rows `w_row1`/`w_row2` with slots `w_slot1..4` (each `w_iconN`, `w_titleN`, `w_subN`), gaps `w_gap1`, `w_gap2`, `w_rowgap`; footer `widget_last`. Backgrounds `@drawable/w_bg`, `w_panel`, `w_amber`.
Pending intent request codes: type slots `20 + i`, Open Brain `30`, Photo `31`; 1x1 widget `1000 + appWidgetId`. All `FLAG_IMMUTABLE` (+ `FLAG_UPDATE_CURRENT` except Open Brain).

`res/xml/widget_button_info.xml`: layout `widget_button` (ids `wb_root`, `wb_icon`, `wb_label`), `minWidth/minHeight=40dp`, `targetCell 1×1`, `resizeMode=horizontal|vertical`, `updatePeriodMillis=0`, `configure=org.brain.capture.ui.ButtonWidgetSetupActivity`, `widgetFeatures=reconfigurable`, `home_screen`.

Shortcuts: no static `shortcuts.xml`. `ui/TypesSettings.kt` `object Shortcuts` builds dynamic ones at runtime:
- `update(ctx)`: `max = min(maxShortcutCountPerActivity, 4)`; dynamic = first `max-1` named types (rank i) + Photo (rank `max-1`); updates pinned ones whose type still exists; disables pinned `type-*` whose type was deleted with message "This capture type was deleted".
- Type shortcut id `type-<profileId>`, short label = name (or "Talk"), long label `"<name>: start talking"`, intent `CaptureActivity.intent(ctx, id, start=true)` with action `VIEW`.
- Photo shortcut id `photo`, labels "Photo" / "Photo: take a picture", intent `PhotoActivity.intent` (action VIEW, `NEW_TASK|CLEAR_TASK`).
- Icon: 216×216 adaptive bitmap, background Amber (private: Raised), 72 px glyph tinted OnAmber (private: TextStrong).
- `pin(ctx, p)` / `pinPhoto(ctx)` via `requestPinShortcut`.

`res/xml/share_paths.xml`: `<cache-path name="outgoing" path="outgoing/"/>`, `<cache-path name="camera" path="camera/"/>`. Authority `${applicationId}.files` (`Photos.uri` uses `"${ctx.packageName}.files"`).

`res/xml/data_extraction_rules.xml`: both `<cloud-backup>` and `<device-transfer>` exclude domains `root`, `file`, `database`, `sharedpref`, `external` (nothing is backed up).

Other resources: `res/font/inter.ttf`, `jetbrainsmono.ttf`, `spacegrotesk.ttf` (OFL texts in `assets/licenses/`); `mipmap-anydpi/ic_brainplay.xml`, `ic_talk.xml`; drawables `ic_*` (UI) and `t_*` (32 type icons).

---

## 4. Data model

### 4.1 Database (`data/CaptureDb.kt`)
Room, file name `captures.db`, `version = 5`, `exportSchema = false`, entities `Capture`, `PendingEdit`. Singleton `CaptureDb.get(ctx)` (double-checked, `applicationContext`). No destructive fallback.

Table `captures` (entity `Capture`):

| Column | Kotlin type | Default | Meaning |
|---|---|---|---|
| `id` (PK) | `String` | — | UUID; written to note as `capture_id` |
| `createdAt` | `Long` | — | epoch ms; note timestamp |
| `state` | `String` | — | see 4.2 |
| `kind` | `String` | `"voice"` | `voice`, `link`, `text`, `file` |
| `fingerprint` | `String?` | null | link URL, file SHA-256 (single) or SHA-256 of concatenated hashes (multi), SHA-256 of trimmed text |
| `details` | `String?` | null | JSON `{"source": <sourceName>, "files": [{"name": <vaultName>, "mime", "size", "pages", "thumb": <abs path>}]}` |
| `stagingPath` | `String?` | null | abs path of staging WAV, or share folder |
| `appendTo` | `String?` | null | id of the note this row is added to |
| `transcript` | `String?` | null | exact words / share fallback text |
| `audioSeconds` | `Double` | 0.0 | |
| `modelLoadMs` | `Long` | 0 | |
| `transcribeMs` | `Long` | 0 | |
| `chunks` | `Int` | 0 | |
| `warning` | `String?` | null | |
| `audioVaultName` | `String?` | null | audio file name in vault Attachments; private: `private/<id>.ogg|wav` (relative to `filesDir`) |
| `notePath` | `String?` | null | vault-relative note path (null for additions) |
| `appended` | `Boolean` | false | addition written into target note |
| `error` | `String?` | null | last failure message |
| `lastActivityAt` | `Long` | `createdAt` | card sort key |
| `cleanup` | `String?` | null | null = not tried; else `done…`, `rejected: …`, `skipped: …`, `failed: …`, `pending: typed, with AI`, `edited by hand` |
| `type` | `String` | `"idea"` | capture profile id |
| `clean` | `String?` | null | stored clean version: optional `# Title` line, then bullet lines with `[refs]` |

Derived: `Capture.isPrivate = Profiles.isPrivateId(type)`.
`object CaptureTypes`: `IDEA="idea"`, `TASK="task"`, `PRIVATE="private"`.

Table `pending_edits` (entity `PendingEdit`): `captureId` String PK, `notePath` String, `original` String, `replacement` String.

Migrations (all additive `ALTER TABLE captures ADD COLUMN`):
| From→To | SQL |
|---|---|
| 1→2 | `kind TEXT NOT NULL DEFAULT 'voice'`; `fingerprint TEXT` |
| 2→3 | `details TEXT` |
| 3→4 | `cleanup TEXT` |
| 4→5 | `type TEXT NOT NULL DEFAULT 'idea'`; `clean TEXT` |
(No migration creates `pending_edits`; see the open points at the end.)

DAO queries (`CaptureDao`):
| Method | Query / effect |
|---|---|
| `upsert(c)` | `@Upsert` |
| `get(id)` / `watch(id)` (Flow) | by id |
| `byStaging(path)`, `byNote(path)` | `LIMIT 1` |
| `cards(limit)` (Flow) | `state='saved' AND appendTo IS NULL AND (notePath IS NOT NULL OR type='private') ORDER BY lastActivityAt DESC LIMIT :limit` |
| `cardCount()` (Flow) | same filter, COUNT |
| `count()` | all rows |
| `inProgress()` (Flow) | `kind='voice' AND state IN ('recording','recorded','transcribed','failed') ORDER BY createdAt DESC` |
| `deleteDiscarded()` | `DELETE … WHERE state='discarded'` |
| `deleteWithAdditions(id)` | `DELETE … WHERE id=:id OR appendTo=:id` |
| `additions(id)` | `appendTo=:id` |
| `recentShare(fp, since)` | `fingerprint=:fp AND state='saved' AND createdAt>=:since ORDER BY createdAt DESC LIMIT 1` |
| `pendingShares()` | `kind!='voice' AND state!='saved' ORDER BY createdAt` |
| `touch(id, at)` | sets `lastActivityAt` |
| `moveNote(id, path)` | sets `notePath` |
| `pendingCleanup(since)` | `kind='voice' AND state='saved' AND cleanup IS NULL AND ((appendTo IS NULL AND (notePath IS NOT NULL OR type='private')) OR (appendTo IS NOT NULL AND appended=1)) AND createdAt>=:since ORDER BY createdAt` |
| `setCleanup`, `setClean`, `setType`, `setAppendTo` | single-column updates |
| `clearClean(id)` | `clean=NULL, cleanup=NULL` |
| `restoreClean(id, clean, cleanup)` | both columns |
| `notesToAddTo(limit)` | `state='saved' AND appendTo IS NULL AND notePath IS NOT NULL AND type!='private' ORDER BY lastActivityAt DESC LIMIT :limit` |
| `watchAdditions(id)` (Flow) | `appendTo=:id AND state!='discarded' ORDER BY createdAt` |
| `latest()` | `state='saved' ORDER BY createdAt DESC LIMIT 1` |
| `putEdit` (REPLACE), `clearEdit(id)`, `pendingEdits()` | `pending_edits` |

### 4.2 States (`CaptureStates`) and transitions
Values: `recording`, `recorded`, `transcribed`, `saved`, `failed`, `discarded`.

Voice (`CaptureService.startRecording` → `VoicePipeline.run`):
```
(row inserted at start) recording
  ├─ cancel → discarded → row deleted (deleteDiscarded), WAV deleted
  └─ record() ends → recorded (warning "Recording stopped early because the phone storage was full." if storage ran out)
recorded → transcribeStep → transcribed (transcript, timings, warning)
transcribed → [private: savePrivate] → saved
            → audioStep (audioVaultName) → noteStep (notePath, or appended=true) → saved
any step throws → failed (error); retry re-enters run() and skips finished steps
```
Steps are skipped when their result is present: `transcript != null`, `audioVaultName != null`, `appended`, `notePath != null`. A WAV with no DB row (legacy) gets a row with `state=recorded`, `createdAt` from file name, `appendTo` from a legacy `<stem>.append` file (contains a note path, resolved via `byNote`).
If the addition's target note cannot be located, the addition becomes its own note (`appendTo` set to null, body prefixed `Addition to [[<target note name>]]`).

Shares / attach / typed text (`Shares`): `stage` → `recorded` (+ `stagingPath` = share folder) → `save` → `saved` (folder deleted) or `failed` (retried by `resumePending`); `discard` → `discarded`, `stagingPath=null`.
Private typed text: inserted directly as `saved`, `kind=text`.
Typed addition (`TextCapture.append`): inserted `saved`, `kind=text`, `appendTo`, `appended=true`.

`cleanup` column values written: `done in <ms> ms`, `done in <ms> ms (private, app only)`, `done (re-cleaned as <name>)`, `done (re-cleaned as <name>, app only)`, `done in the app only (note was edited)`, `rejected: <reason>`, `rejected, kept the previous version: <reason>`, `skipped: no speech`, `skipped: note not found`, `skipped: no vault`, `skipped: note was changed`, `skipped: no AI cleanup for <type name>`, `skipped: typed`, `pending: typed, with AI`, `failed: <message>`, `edited by hand`.

### 4.3 SharedPreferences

File `settings` (MODE_PRIVATE):

| Key | Type | Default | Owner | Meaning |
|---|---|---|---|---|
| `vault` | String (URI) | null | `Prefs.vaultUri` | SAF tree URI of the chosen vault folder |
| `language` | String | `"auto"` | `Prefs.language` | Whisper language code or `auto` |
| `cards` | Int | 5 | `Prefs.cardCount` | cards before "Load more" |
| `audio` | String | `"opus"` | `Prefs.audioFormat` | `opus` or `wav` |
| `share_audio` | Boolean | true | `Prefs.shareAudio` | include audio when sharing a card |
| `inbox` | String | `"Inbox"` | `Prefs.inboxFolder` | vault-relative inbox folder |
| `capture_profiles` | String (JSON array) | absent → built-ins | `Profiles` | all capture types in order |
| `capture_profiles_added` | StringSet | empty | `Profiles` | ids of `ADDED` types already merged once |
| `widget_open_brain` | Boolean | true | `Profiles` | widget shows "Open Brain" slot |
| `widget_photo` | Boolean | false | `Profiles` | widget shows Photo slot |
| `default_type` | String | absent | `Profiles.primary` | default (non-private) type id |

File `button_widgets`: key `type_<appWidgetId>` → profile id or `photo`; default `Profiles.primary(ctx).id`; removed in `ButtonWidget.onDeleted`.

### 4.4 Capture types (`types/Profiles.kt`)

```kotlin
data class CaptureProfile(
  id: String, name: String, subtitle: String, icon: String /* one of ICONS */,
  instruction: String, checklist: Boolean, private: Boolean, onWidget: Boolean,
  mixed: Boolean = false, base: String? = null, noCleanup: Boolean = false)
// builtIn = id in BUILT_IN = {idea, task, meeting, private}
```
Stored as JSON array in `settings/capture_profiles`; each object: `id`, `name`, `subtitle`, `icon`, `instruction`, `checklist`, `private`, `onWidget`, `mixed`, `noCleanup`, and `base` only when non-null. Parse defaults: icon `star`, instruction `IDEA_INSTRUCTION`, `mixed`/`noCleanup` from the built-in default with that id, else false; `base` empty → null. Unparseable or empty → built-in list.

`ICONS` (order): idea, task, bug, wrench, sparkle, rocket, code, flag, target, pen, chat, people, phone, mic, calendar, clock, pin, book, cart, money, gift, home, car, plane, camera, music, heart, health, leaf, question, lock, star.

Built-in list `DEFAULTS` (order = initial list order):

| id | name | subtitle | icon | instruction | checklist | private | onWidget | mixed | noCleanup |
|---|---|---|---|---|---|---|---|---|---|
| `idea` | Idea | tidy into bullets | idea | `IDEA_INSTRUCTION` | false | false | true | false | false |
| `task` | Task | checklist + due date | task | `TASK_INSTRUCTION` | true | false | true | false | false |
| `meeting` | Meeting | who, decisions, to-dos | people | `MEETING_INSTRUCTION` | false | false | false | false | false |
| `private` | Private | stays on device | lock | `IDEA_INSTRUCTION` | false | true | true | false | false |
| `c-fixfeature` | Fix / Feature | sorted into fixes and features | wrench | `FIX_FEATURE_INSTRUCTION` | false | false | false | false | false |
| `c-fix` | Fix | what's broken, where | bug | `FIX_INSTRUCTION` | false | false | false | false | false |
| `c-feature` | Feature | what to add or change | sparkle | `FEATURE_INSTRUCTION` | false | false | false | false | false |
| `c-voicenote` | Voice note | cleaned up, nothing left out | mic | `VOICE_NOTE_INSTRUCTION` | false | false | false | true | false |
| `c-exact` | Exact words | no AI cleanup, fastest | pen | `IDEA_INSTRUCTION` | false | false | false | false | true |

The last five are `ADDED`: appended once to an existing stored list (tracked in `capture_profiles_added`), then ordinary deletable types. Instruction texts are the `const val`s in `Profiles.kt` (multi-line English prompts; Fix/Feature share `FEEDBACK_RULES`); copy them verbatim from that file.

Upgrade rule in `all()`: for each stored type with a built-in default of the same id: if `instruction == default` → set `base = default` if different; else if untouched (`instruction == base` or in `OLD_INSTRUCTIONS[id]`) → replace `instruction`, `base`, `mixed`, `checklist` with the default's; else leave. Persist if anything changed or types were added. Result cached in a `MutableStateFlow` (`flow(ctx)`).

Other API: `get(ctx,id)` (unknown → Idea; unknown private id → first private type), `save`, `put`, `delete` (never empties the list), `move(id, by)`, `newProfile(private)` (id `c-<8 hex>` or `private-<8 hex>`, icon `star` / `lock`, `IDEA_INSTRUCTION`), `isPrivateId(id)` (`== "private"` or starts with `"private-"`), `WIDGET_SLOTS = 4`, `widgetSlots(ctx)` (onWidget types, limited to `4 - open - photo`, then `PHOTO_SLOT`, then `null` = Open Brain), `primary(ctx)` (`default_type` if exists and not private, else `idea`, else first non-private), `setPrimary`.
Pseudo type `PHOTO = "photo"`, `PHOTO_SLOT = CaptureProfile("photo","Photo","take a picture","camera","",false,false,false)`.

---

## 5. On-device storage layout (`/data/data/<appId>/`)

| Path | Content / naming | Written by | Removed by |
|---|---|---|---|
| `databases/captures.db` (+ `-wal`, `-shm`) | Room DB | Room | never |
| `shared_prefs/settings.xml` | §4.3 | `Prefs`, `Profiles` | — |
| `shared_prefs/button_widgets.xml` | `type_<id>` | `ButtonWidget` | `onDeleted` |
| `files/staging/yyyy-MM-dd_HHmmss.wav` | recording in progress / waiting (16 kHz mono PCM16, 44-byte header; files ≤ 44 bytes are ignored) | `CaptureService` (`SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT)`) | `VoicePipeline` after vault save; cancel |
| `files/staging/<stem>.ogg` | Opus encoder output | `VoicePipeline.audioStep` / `savePrivate` | after save; Housekeeping if WAV gone |
| `files/staging/<stem>.append` | legacy append marker (note path) | old app version | after save; Housekeeping if WAV gone |
| `files/shares/<captureId>/meta.json` | staged share metadata (below) | `Shares.stage` / `save` | after save; `discard`; Housekeeping |
| `files/shares/<captureId>/f<i>` | staged copy of shared file i (0-based) | `Shares.stage` | same |
| `files/thumbs/<captureId>-<i>.jpg` | 320 px JPEG preview | `Previews.make` | Housekeeping (capture missing or discarded) |
| `files/private/<captureId>.ogg` or `.wav` | private recording audio | `VoicePipeline.savePrivate` | `Notes.delete` (moved to undo-hold) |
| `files/undo-hold/<name>` | private audio held while delete is undoable | `Notes.delete` | `Undo.purge`, `Undo.cleanupOnStart` |
| `files/whisper/*.onnx` | 5 Whisper files | `ModelStore.import` | replaced on re-import |
| `files/whisper.importing/` | import in progress | `ModelStore.import` | renamed to `whisper/`; Housekeeping |
| `files/gemma/<name>.litertlm` | Gemma model (one) | `GemmaHolder.import` | `remove`, replaced on import |
| `files/gemma/<name>.litertlm.part` | import in progress | `GemmaHolder.import` | `finally` |
| `files/history.jsonl` | legacy transcript history (one JSON per line: `note`, `at`, `text`, `audio`, `load`, `tx`, `chunks`) | old version | renamed `history.jsonl.imported` by `Notes.importHistory`; that removed by Housekeeping |
| `files/*.tmp` | stray temp files | — | Housekeeping |
| `cache/gemma/` | LiteRT-LM compiled cache (`EngineConfig.cacheDir`) | `GemmaHolder` | on model import/remove |
| `cache/outgoing/<file name>` | copies for sharing a card (FileProvider `outgoing/`) | `Notes.shareBundle` | wiped at the start of every share bundle |
| `cache/camera/yyyy-MM-dd HH.mm.ss Photo.jpg` | camera target (FileProvider `camera/`) | `Photos.newTarget` | after staging; files older than 24 h on each `newTarget` |

`meta.json` keys: `sourceApp` (package or ""), `sourceName` (readable or ""), `text`, `title`, `url` (link only), `files` (array of `{staged:"f<i>", name:<display name or "">, thumb:<abs path or "">, pages:<int, -1 unknown>, ext, mime, size, sha256, vaultName (added once copied to vault)}`).

---

## 6. Vault output

### 6.1 Folder layout (relative to chosen SAF tree; `inbox` = `Prefs.inboxFolder.trim('/')`, default `Inbox`)
```
<inbox>/Voice/        voice notes (.md)
<inbox>/Attachments/  audio (.ogg/.wav), shared/attached/photo files
<inbox>/Links/        link notes
<inbox>/Text/         typed/pasted/shared text notes (contacts too)
<inbox>/Documents/    file / image / PDF / photo notes
.trash/               at the tree root (Obsidian's trash when the tree is the vault root)
```
Folders are created on demand (`ensureDir`).

### 6.2 File naming (`NoteBuilder`)
- `FILE_STAMP = "yyyy-MM-dd HH.mm.ss"` (`Locale.ROOT`).
- `baseName(at, kind, text)`: first 6 whitespace-separated words of `text`, joined by a space, `safe()`, `take(50)`, trimmed. Result `"<stamp> <kind>"` or `"<stamp> <kind> – <title>"` (en dash U+2013 with spaces). Example `2026-11-10 08.53.12 Voice – Remember to call the dentist`.
- `safe(s)`: remove `\ / : * ? " < > | # ^ [ ]`, then trailing dots.
- `kind` words: `Voice` (note: transcript; audio: no text), `Link` (title or `ShareParse.domain(url)`), `Text` (title or text), `Image` / `PDF` / `File` (`ShareParse.fileKind`, label = first file's name without extension, else subject), `Photo` (Photo button source "Camera": no label).
- `fileBaseName(at, original)`: `safe(original).take(80).trim()`, else `"<stamp> Shared"`; extension = original's, else from MIME via `MimeTypeMap`, else `bin`.
- Collision: `uniqueName` checks the folder's children case-insensitively and uses `"<base>.<ext>"`, then `"<base> 2.<ext>"`, `" 3"`, …
- Timestamps: voice note `at` = `createdAt`; voice audio name = time parsed from the staging WAV name; shares `at` = `createdAt`.

### 6.3 Write procedure (`VaultWriter`)
- Constructor checks `contentResolver.persistedUriPermissions` has the tree with write permission and the root document exists; else `VaultUnavailableException("Vault folder not available (moved, renamed or access removed). Choose it again in the app")`.
- Uses `DocumentsContract` directly (no `DocumentFile`). `children(dirId)` queries `DOCUMENT_ID`, `DISPLAY_NAME`, `MIME_TYPE`.
- `write(folder, baseName, ext, body)`: `ensureDir(folder)` → create `application/octet-stream` document named `.capture-<UUID>.tmp` → write through a counting stream (`"w"` mode) → compare `COLUMN_SIZE` with bytes written (`"Write incomplete (x of y bytes)"`) → `uniqueName` → `renameDocument` to final name → return name. On any failure the temp document is deleted and the error rethrown. Never overwrites an existing file.
- `cleanupTemp(folder)`: deletes children named `.capture-*.tmp`. Called before writing in `<inbox>/Attachments` (voice audio), `<inbox>/Voice`, and the share target folder.
- `append(path, text)`: open `"wa"`, write UTF-8 bytes; false if note missing.
- `overwrite(path, text)`: open `"wt"`, write, then `read(path) == text` check ("Saving the note could not be verified"). Callers always store a `PendingEdit` first (§7.2).
- `read(path)`, `uriOf(path)`, private `locate(path)` (walk by display name; files only).
- Order guarantees: share files are copied before the note; each file's `vaultName` is persisted in `meta.json` immediately so a retry skips it. Voice audio before note.
- Idempotency: before creating a note, `findNoteById(id, maxFiles=500, startFolder=<target folder>)` reuses an existing note with that `capture_id`; voice append is skipped if the note already contains `![[<audioVaultName>]]`; share append is skipped if the note already contains its heading line.

### 6.4 Voice note (`NoteBuilder.voiceNote`)
```
---
type: capture
source: voice
capture_type: <type>              ← only when type != "idea"
captured: <ISO_OFFSET_DATE_TIME, seconds precision>
duration: HH:MM:SS
audio: "[[<audioFile>]]"
transcription: <model | none>     ← "whisper-small-int8 (onnx)"; "none" when transcript is null
language: <Prefs.language>        ← e.g. auto, de, en
capture_id: <uuid>
status: inbox
---

> [!warning] <warning>            ← only with a warning, followed by a blank line

<transcript>                      ← "_(no speech recognised)_" if blank; "_(transcription pending)_" if null

![[<audioFile>]]
```
Orphaned addition body: `Addition to [[<target file name without .md, or "earlier note">]]` + blank line + transcript.
Private captures are never written to the vault.

After cleanup (`CleanupText.rewrite`): the transcript line block (must occur exactly once as whole lines) is replaced by the clean block, and `cleanup: <modelName>` (Gemma file name without `.litertlm`, or `gemma`) is inserted as the last frontmatter line if not present. Clean block:
```
- <bullet> [1,2]
- [ ] <task> [3]            ← checklist types; "(due YYYY-MM-DD)" may be inside text
<paragraph> [4,5]          ← mixed types; paragraphs separated by blank lines

> [!quote]- Exactly what I said
> [1] <segment 1>
> [2] <segment 2>
```
Title (`# Title` from Gemma) is kept only in the DB `clean` column, not written to the note.
Re-clean with another type: old block swapped (`replaceBlock`, exact single match) and `capture_type:` line rewritten after `source:` (removed for `idea`).

### 6.5 Additions (appended to an existing note)
Voice (`appendBlock`, `HH:mm:ss` of the addition's `createdAt`):
```

**Added HH:mm:ss**

> [!warning] <warning>          ← optional, + blank line
<transcript or "_(no speech recognised)_">

![[<audioFile>]]
```
Typed (`appendTyped` / `TextCapture.append`): heading `**Added HH:mm:ss (typed)**`, no audio part.
Share / attach / photo (`shareAddition`): leading blank line, heading `**Added HH:mm:ss · shared from <sourceName>**` (no ` · shared from …` when no source name), blank line, then:
- link: `[<title with [ ] → ( )>](<url>)` or `<url>`; optional quoted extra text (`> ` lines after a blank line)
- text: the text
- files: optional text + blank line, then one line per file: `![[name]]` for PDF/images, `[[name]]` otherwise.

### 6.6 Share notes (`shareNote` frontmatter, shared by link/text/file)
```
---
type: capture
source: <link|text|file>
captured: <ISO offset, seconds>
<field>: <yaml(value)>          ← kind-specific fields, in order below
capture_id: <uuid>
status: inbox
---

<body>
```
`yaml(v)`: unquoted if non-empty and only letters/digits/`-_./`; else `"…"` with `\` and `"` escaped, newlines → spaces.
Source fields (when known): `source_app: <readable name>`, `source_package: <package>`.

| Kind | Folder | Fields (order) | Body |
|---|---|---|---|
| link | `Links` | `url`, `title` (if any), source fields | `# <title or url>`, blank, `<url>`, then optional blank + `> ` quoted extra text |
| text | `Text` | source fields | the text |
| file | `Documents` | `files: "[[a]], [[b]]"`, if exactly one file: `mime`, `size`, `sha256`; source fields | `Shared [from <app> ]on d MMM yyyy, HH:mm — <describe>` (Locale.ENGLISH, em dash), blank, optional text + blank, then per file `![[name]]` (PDF/image) or `[[name]]`, each followed by a blank line |

`describe(files)`: `1 image` / `N images`; `PDF` (+ `, N page(s)` when known) / `N PDFs`; `1 file` / `N files`.

### 6.7 Specific capture sources
| Source | How it is saved | sourceName → `source_app` | Result |
|---|---|---|---|
| Typed / pasted in app (`TextCapture.save`) | `Shares.stage` with `ACTION_SEND text/plain`, sourceApp null | none | text note (or link note if a URL is found) |
| Photo (in-app or `PhotoActivity`) | `image/jpeg` stream from `cache/camera/…Photo.jpg`, sourceApp = own package | `Camera` | file note named `<stamp> Photo`, attachment `<stamp> Photo.jpg` |
| Gallery / Files & audio (Attach) | `SEND`/`SEND_MULTIPLE` `*/*` | `Gallery` / `Files` | file note |
| Contact (Attach) | text `Contact: <display name> · <number>` (ACTION_PICK on `Phone.CONTENT_TYPE`, no permission) | `Contacts` | text note |
| Location (Attach) | text `Location at HH:mm: https://maps.google.com/?q=<lat>,<lon>` (6 decimals, `Locale.ROOT`); provider order fused, gps, network; 20 s timeout; last known fallback | `Location` | link note (title `Location at HH:mm:` from the text before the URL) |
| Link (Attach) | the URL text | `""` (no source_app line) | link note |
| Other apps (ShareActivity) | intent as received; package from `referrer` `android-app://` | `Previews.appName(pkg)` | per kind |
`source_package` is written whenever `sourceApp` is non-empty (own package for in-app sources).
With `appendTo` set (Attach / photo on a note, or "add to" in the share panel) the share becomes an addition (6.5) instead.

### 6.8 Trash, restore, finding by id
- `trashTo(path)`: `ensureDir(".trash")` at tree root; `moveInto` renames to a free name in `.trash` if needed, then `DocumentsContract.moveDocument`; if the provider cannot move: create copy in target, stream copy, delete original. Returns `.trash/<name>`.
- `restore(trashPath, original)`: re-creates the original folder, moves back under a free variant of the original name; returns the actual path.
- Delete of a note (`Notes.delete`): own attachments = its `audioVaultName`, files in `details.files[].name`, and those of all additions; each is looked up at `<noteFolder>/Attachments/<name>`, `<noteFolder>/<name>`, `<inbox>/Attachments/<name>` (first hit trashed); then the note; DB rows of note + additions deleted. Embeds added by hand in Obsidian are not touched.
- `findNoteById(id, maxFiles=5000, startFolder="")`: BFS over folders; skips names starting with `.` (so `.trash`, `.obsidian`), non-`.md`, and names containing `.sync-conflict-`; reads the first 2048 bytes and looks for `capture_id: <id>`; stops after `maxFiles`.
- `Notes.locate(c)`: read `c.notePath`; accept if it exists and its frontmatter `capture_id` equals `c.id` (or has none); else `findNoteById` over the whole tree and update `notePath` (`moveNote`).
- `NoteText.captureId`: regex `(?m)^capture_id:\s*(\S+)\s*$` inside the frontmatter only (`---\n … \n---\n`, CRLF normalised).

### 6.9 `obsidian://` URI (`ui/MainActivity.kt` `openInObsidian`)
- `docId = DocumentsContract.getTreeDocumentId(tree)` (e.g. `primary:Documents/Vault`); `relRoot` = part after `:`; `file = notePath.removeSuffix(".md")`.
- If the tree root contains a child named `.obsidian`: `obsidian://open?vault=<Uri.encode(last segment of relRoot)>&file=<Uri.encode(file)>`.
- Else: volume = part before `:`; root `/storage/emulated/0` for `primary`, else `/storage/<volume>`; `obsidian://open?path=<Uri.encode(root/relRoot/notePath)>` (empty parts skipped).
- Started as `ACTION_VIEW` with `FLAG_ACTIVITY_NEW_TASK`; returns false on failure.

---

## 7. Undo and edit backup

### 7.1 Undo model (`pipeline/Undo.kt`)
One step only, process-memory (`MutableStateFlow<Action?> last`); lost when the process dies.

| Action | Recorded by | Label | Undo does |
|---|---|---|---|
| `PendingDelete(id)` | `SwipeDeletes.start` (swipe) | Undo delete | `SwipeDeletes.undo(id)` (cancel timer, unhide) → "Note kept" |
| `Deleted(id, rows, moves, held)` | `Notes.delete(undoable=true)` | Undo delete | `Notes.restore`: move every `.trash` file back (row `notePath` follows a renamed note), move held private audio back, upsert rows → "Note restored" |
| `Created(id)` | `VoicePipeline` (new note / private), `Shares.save` (new note), `TextCapture.save` (private) | Undo new note | `Notes.delete(undoable=false)` → "Private note deleted" / "New note moved to the vault's .trash" |
| `Added(id)` | `VoicePipeline` (addition), `Shares.save` (addition), `TextCapture.append` | Undo addition | `Notes.deleteAddition` → "Addition taken out of the note" |
| `Recleaned(noteId)` | `CleanupStep.reclean` | Undo re-clean | `CleanupStep.undoReclean` (only if `lastReclean.noteId` matches) → "Previous clean version back" |

Rules:
- `KEEP_MS = 10 * 60_000` (10 min); each `record` restarts the expiry job (in `AppScope`); expiry clears only if the same action is still last.
- `record` of a new action purges a previous `Deleted` (held private audio files deleted for good).
- `forget(id)` clears if `last.id == id`; `drop()` clears unconditionally (called after any hand edit, `NoteEdits.done`).
- `undo()` takes the action atomically; on failure returns `"Could not undo: <msg>"` and, for `Deleted`, puts it back if nothing replaced it; always refreshes the widget. With nothing: `"Nothing to undo"`.
- `cleanupOnStart` (home screen first composition): if nothing is undoable, empties `files/undo-hold/`.
- Swipe delete: card hidden at once, real `Notes.delete` after `SwipeDeletes.UNDO_MS = 5000`, with `undoable = Undo.isPending(id)` so the real delete takes over the Undo slot only if the swipe is still the last action.
- `deleteAddition`: private owner → rows deleted only. Else removes the section via `NoteText.withoutAddition(note, "HH:mm:ss")`: the heading `\n**Added <time>` must occur exactly once; removes from that heading up to the next `\n**Added ` or end, trims, rejoins with a blank line, ends with `\n`; null → error "This addition was changed in the note, so it was left as it is". Then PendingEdit-protected overwrite; addition's audio and shared files trashed (same three candidate paths); rows deleted; owner touched.
- Re-clean undo data: `RecleanUndo(noteId, rows, path, before, after)`; vault restored only if the note still equals `after`; rows upserted back.

### 7.2 Edit backup and repair (`pending_edits`)
Every full-note rewrite follows:
```
dao.putEdit(PendingEdit(key, path, original, replacement))
vault.overwrite(path, replacement)   // "wt" + read-back verify
dao.clearEdit(key)
```
Users: `Notes.saveEdit` (key = capture id; only if the note still equals the text read when the editor opened, else returns false), `Notes.deleteAddition` (owner id), `NoteEdits.write` (section id), `CleanupStep.clean`/`recleanOne` (section id), `CleanupStep.undoReclean` (note id). `Notes.delete` also clears any pending edit for the note.
Repair: `Notes.recoverEdits` (home screen start): for each row, read the note; if it exists and equals neither `original` nor `replacement` (partial write) → `overwrite(original)` and log; then delete the row in all cases (no vault → skip all, rows kept).

Section edits (`NoteEdits`): change only where the app's own text still stands exactly once — words: old clean block (rebuilt from stored `clean` + segments of old words) replaced by new words, else `replaceWords` (old words as whole lines, once); clean: `replaceBlock(old block, new block)`. If not found → error "This part was changed in Obsidian, so it was left as it is. Edit it there." Private owners: DB only. Voice words edit clears `clean`/`cleanup` and runs `CleanupStep.cleanNow` in `AppScope`; clean edit stores `cleanup = "edited by hand"`. Empty result → error "Nothing left. To remove this, use Undo or delete the note."

---

## 8. Housekeeping

Automatic:
- Home screen start (`MainActivity` `LaunchedEffect(Unit)`): `Undo.cleanupOnStart`; `Notes.importHistory` (one-time, renames file to `.imported`); `Notes.recoverEdits`; `Shortcuts.update`; `Shares.resumePending` (retries every non-voice row not `saved`, skipping `discarded` or missing staging folder); if not busy/failed and `pendingRecordings` not empty → `CaptureService` `process_pending`. `ShareActivity` also calls `Shares.resumePending`.
- `CaptureService.ensureProcessing`: processes staging WAVs oldest first (sorted by name), stops the loop on `Phase.Failed`, then `CleanupStep.runPending` (rows from the last 24 h) and `BrainWidget.refresh`; leaves foreground and releases the wake lock when idle.
- Recording start refuses below 50 MB free; recording stops when free space < 20 MB (checked on each WAV sync); "minutes left" shown when < 60.
- After a successful voice save: staging `.wav`, `.ogg` and `.append` deleted. After a share save: its `files/shares/<id>/` deleted. Cancel: WAV deleted, row marked discarded then `deleteDiscarded`.
- Vault temp files `.capture-*.tmp` removed from the target folder before each write.
- `cache/outgoing/` wiped on each card share; `cache/camera/` files older than 24 h removed whenever a new camera target is made; `cache/gemma/` wiped on Gemma import/remove.
- Gemma engine unloaded after 10 idle minutes.
- Undo-held private audio deleted when its delete can no longer be undone.

Manual (`Housekeeping.clean`, Settings → storage "clean up"), returns `"Nothing to clean up."` or `"Freed <size>."`:
1. `files/thumbs/*`: capture id = file name without extension, cut at the last `-`; delete if no row or row `discarded`.
2. `files/shares/<id>/`: delete if no row, or row `saved` or `discarded`.
3. `files/staging/*`: delete any non-`.wav` file whose `<stem>.wav` does not exist.
4. `files/whisper.importing`, `files/history.jsonl.imported`, and `files/*.tmp` (top level).
5. `dao.deleteDiscarded()`.
Never deletes a recording or share that has not reached the vault.

`Housekeeping.usage`: model = `files/whisper` (Gemma not counted), staging = `files/staging` + `files/shares`, thumbs = `files/thumbs`, database = `captures.db` + `-wal` + `-shm`.

---

## Could not determine / notes
- Exact `keystore.properties` values and keystore location (file absent; secret by design).
- Whether `pending_edits` existed in DB v1 (no migration creates it; assumed present since v1). Original v1 schema is not in the repo (`exportSchema = false`).
- If `record()` throws and the WAV is ≤ 44 bytes, the row stays in state `recording` (no code moves it on); behaviour of such leftover rows on the home screen was not traced.
- `Notes.delete`/`deleteAddition` use the untrimmed `Prefs.inboxFolder` for the inbox candidate path, while writers trim `/`; only differs if the user enters slashes.
- Bodies of the instruction constants and `CleanupText` prompt/rule strings are not quoted here; take them verbatim from `types/Profiles.kt` and `cleanup/CleanupText.kt`.
- Drawable vector contents, mipmap XMLs and `widget_button.xml` details beyond ids were not transcribed.

---

# Part B: runtime logic

Scope: what the app does at runtime, with the exact values from the code on branch `playground`.
Not repeated here: user-visible features (`FEATURES.md`), flows (`FLOWS.md`), tests (`E2E-TESTS.md`),
and part A (build, manifest, Room schema and DAO queries, prefs keys, storage layout, vault note formats
from `NoteBuilder`/`VaultWriter`/`NoteText`, `Undo`). Package root: `org.brain.capture`.

---

## 1. Recording (`audio/CaptureService.kt`, `audio/WavFile.kt`, `CaptureState.kt`)

### 1.1 Service intents

`CaptureService` is a started (not bound) foreground service, `foregroundServiceType="microphone"`.
`onBind` returns null; `onStartCommand` always returns `START_NOT_STICKY`.

| Action const | String | Effect |
|---|---|---|
| `ACTION_START` | `"start"` | `startRecording(appendTo = EXTRA_APPEND_TO, appendLabel = EXTRA_APPEND_LABEL, type = EXTRA_TYPE ?: "idea")` |
| `ACTION_PAUSE` | `"pause"` | `setPaused(true)` |
| `ACTION_RESUME` | `"resume"` | `setPaused(false)` |
| `ACTION_STOP` | `"stop"` | recording → `stopRequested = true`; else `maybeFinish()` |
| `ACTION_CANCEL` | `"cancel"` | recording → `cancelRequested = true; stopRequested = true`; else `maybeFinish()` |
| `ACTION_TOGGLE` | `"toggle"` | recording → stop; else `startRecording()` with defaults (no caller in the code) |
| `ACTION_PROCESS_PENDING` | `"process_pending"` | if phase is `Failed` → set `Idle`; then pending recordings exist → `ensureProcessing()`, else `maybeFinish()` |

Extras: `EXTRA_TYPE="type"`, `EXTRA_RETRY="retry"` (boolean, sent to `MainActivity`), `EXTRA_APPEND_TO="append_to"`
(capture id of the note to add to), `EXTRA_APPEND_LABEL="append_label"` (that note's vault path, display only).

`CaptureService.send(ctx, action, extra)`: builds the intent; `STOP`, `CANCEL`, `PAUSE`, `RESUME` go through
`ctx.startService` (only ever to a running foreground service); all others through `ctx.startForegroundService`.

Companion helpers:
- `stagingDir(ctx)` = `filesDir/staging` (mkdirs).
- `pendingRecordings(ctx)`: files in staging with extension `wav`, length > 44, path ≠ current recording file,
  path ≠ `CaptureState.processingFile`; sorted by name (= start time) ascending.
- `formatEta(s)`: `s < 60` → `"${max(5, (s+4)/5*5)} s"` (rounded up to 5 s, min 5 s); else `"${(s+30)/60} min"`.

### 1.2 Start recording (`startRecording`)

In order:
1. Already recording → return.
2. `RECORD_AUDIO` not granted → `Phase.Failed("Microphone permission missing", null)`, `maybeFinish()`.
3. `free = stagingDir.usableSpace`. If `free < MIN_FREE_TO_START` (50 000 000 B) →
   `Failed("Phone storage is full (${free/1_000_000} MB free). Free up space, then record again.", null)`, `maybeFinish()`.
4. `minutesLeft = ((free - STOP_BELOW) / BYTES_PER_SECOND / 60)` as Int, kept only if `< 60` (else null).
   `STOP_BELOW` = 20 000 000 B, `BYTES_PER_SECOND` = 32 000.
5. File = `staging/<yyyy-MM-dd_HHmmss>.wav` (`SimpleDateFormat`, `Locale.ROOT`, start time). `id = UUID`.
6. `CaptureState.recording(RecordingInfo(started, file.path, appendLabel, minutesLeft, captureId=id, type, appendTo))`.
7. `holdWakeLock()`, `goForeground()` (`startForeground(1, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)`), `vibrate()`.
8. If `Profiles.get(type).noCleanup` is false → `GemmaHolder.warm(applicationContext)`.
9. Reset `stopRequested`, `cancelRequested`, `pauseRequested` to false.
10. `scope.launch(Dispatchers.IO)`:
    - Upsert row `Capture(id, createdAt=started, state="recording", stagingPath, appendTo, type)` before any audio.
    - `storageFull = record(file)` inside `runCatching`; on success update row by staging path to state `"recorded"`,
      warning = `"Recording stopped early because the phone storage was full."` if `storageFull` (else unchanged).
    - `CaptureState.level(0f)`.
    - If `cancelRequested`: delete file; row → state `"discarded"`; `dao.deleteDiscarded()`; `recording(null)`; vibrate; `maybeFinish()`; stop.
    - Else `recording(null)`, vibrate. If record failed and `file.length() <= 44`: delete file,
      `Failed("The microphone could not be started.", null)`, `maybeFinish()`, stop.
    - Else `ensureProcessing()`.

### 1.3 Audio capture (`record`)

| Setting | Value |
|---|---|
| Source | `MediaRecorder.AudioSource.VOICE_RECOGNITION` |
| Rate / channels / encoding | 16 000 Hz, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT` |
| AudioRecord buffer | `max(getMinBufferSize(...), 16000)` bytes |
| Read buffer | `ShortArray(800)` (= 50 ms) |
| Init check | `state != STATE_INITIALIZED` → throw `"Microphone not available"` |

Loop while `!stopRequested`: `n = read(buf)`; `n < 0` → throw `"AudioRecord.read returned $n"`; `n == 0` → continue.
Paused → `level(0f)`, discard the samples, continue (mic stays open, nothing written).
Otherwise write samples; level = `clamp(sqrt(Σx²/n) / 6000, 0, 1)` on raw 16-bit values → `CaptureState.level`.
Every > 1000 ms (`SystemClock.elapsedRealtime`): `wav.sync()`; if `parentFile.usableSpace < STOP_BELOW` →
`storageFull = true; stopRequested = true`. `finally`: `recorder.stop(); release()`. `WavWriter.close()` syncs once more.

### 1.4 WAV writer / reader

`WavWriter(file, sampleRate=16000)`: `RandomAccessFile "rw"`, `setLength(0)`, writes a 44-byte header with data size 0.
`write(samples, count)`: little-endian shorts appended; `dataBytes += count*2`.
`sync()`: remember position, seek 0, rewrite header with real sizes, seek back, `raf.fd.sync()` (fsync).
Header (LE): `"RIFF"`, `36+data`, `"WAVE"`, `"fmt "`, 16, PCM=1, channels=1, rate, byteRate=rate*2, blockAlign=2, bits=16, `"data"`, data.

`WavReader`:
- `profile(file, frame=480)`: one streaming pass (64 KiB `BufferedInputStream`, skip 44). Per 30 ms frame: RMS of
  `sample/32768.0`. In parallel counts 100 ms "tenths" (1600 samples, counter runs across frames): a tenth whose
  samples are all exactly 0 counts as muted. Returns `Profile(samples=(len-44)/2, energy[], mutedFraction)`;
  `mutedFraction = 1.0` when no full tenth.
- `readRange(file, from, to)`: seek `44 + from*2`, read, → floats `/32768f`.
- `readFloats(file)`: whole file; trusts file length over header.

### 1.5 Pause, discard

`setPaused(on)`: no recording or same state → return. Sets `pauseRequested`. Pausing: `recording.copy(pausedAtMs=now)`;
resuming: `copy(pausedAtMs=null, pausedTotalMs += now - pausedAtMs)`. Vibrates.
`RecordingInfo.elapsedMs(now) = max(0, now - startedAtMs - pausedTotalMs - (pausedAtMs?.let { now - it } ?: 0))`.
Discard = `ACTION_CANCEL` (see 1.2). UI asks for confirmation only from 20 000 ms of elapsed time (`Controls`).

### 1.6 Wake lock and vibration

`PARTIAL_WAKE_LOCK`, tag `"BrainCapture:capture"`, `setReferenceCounted(false)`, `acquire(3_600_000)` (1 h).
Acquired at recording start and at processing start (skipped if already held); released in `maybeFinish` and `onDestroy`.
Vibration: `VibratorManager.defaultVibrator.vibrate(VibrationEffect.createPredefined(EFFECT_CLICK))`, wrapped in
`runCatching`; at start, at stop, at discard, at each pause/resume.

### 1.7 Processing queue (`ensureProcessing`)

`synchronized(procLock)`: if `processingActive` return, else set true. `holdWakeLock()`, `goForeground()`.
`scope.launch` (Default):
```
pipeline = VoicePipeline(app)
loop: next = pendingRecordings().firstOrNull() ?: break
      pipeline.run(next)
      if phase is Failed → break
CleanupStep.runPending(app)        // all pending AI cleanups, after all raw notes are saved
BrainWidget.refresh(app)
finally: CaptureState.processing(null, null); processingActive = false
then: if phase !is Failed && pendingRecordings not empty → ensureProcessing() else maybeFinish()
```
The service therefore stays in the foreground (and keeps the wake lock) during AI cleanup too.

`maybeFinish()`: busy if `processingActive || isRecording` → return. Else release wake lock,
`stopForeground(STOP_FOREGROUND_DETACH)`, if phase ≠ `Idle` post the final notification (detached, dismissable), `stopSelf()`.
`onDestroy`: release wake lock, `stopRequested = true`, `scope.cancel()`.

### 1.8 Notification

Channel id `"capture"`, name `"Capture status"`, `IMPORTANCE_LOW`. Notification id `1`. Small icon `R.drawable.ic_mic`.
Content intent: `PendingIntent.getActivity(req 0, MainActivity, FLAG_IMMUTABLE)`. All: `setOnlyAlertOnce(true)`,
`setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`.

Updates: in `onCreate`, `combine(CaptureState.recording, CaptureState.phase).collectLatest`: if a recording exists or
phase ≠ `Idle` → `notify(1, buildNotification(r, p))`.

Recording (takes priority over the phase):

| Field | Value |
|---|---|
| Title | paused → `"Paused"`; `appendLabel != null` → `"Recording · adding to note"`; else `"Recording"` |
| Text | `minutesLeft != null` → `"Storage low: about $minutesLeft min of recording left"`; else if `CaptureState.isProcessing` → `"Earlier recording is being transcribed"`; else none |
| Extras (Live Update) | `"android.requestPromotedOngoing" = true`; when paused also `"android.shortCriticalText" = "Paused"` (no short text otherwise, so the Android 16 status-bar chip shows the timer) |
| Timer | `setUsesChronometer(!paused)`, `setWhen(now - elapsedMs())`, `setShowWhen(!paused)`, `setOngoing(true)` |
| Actions | `"Stop"` → service `ACTION_STOP`; `"Discard"` → service `ACTION_CANCEL` (`PendingIntent.getService(requestCode = action.hashCode(), FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`) |

Manifest permission `POST_PROMOTED_NOTIFICATIONS` backs the Live Update request.

Phase states (no recording):

| Phase | Title | Text | Other |
|---|---|---|---|
| `LoadingModel` | `"Preparing transcription…"` | – | indeterminate progress, ongoing |
| `Transcribing(done,total,eta)` | `"Transcribing"` | parts joined with `" · "`: `"Part ${done+1} of $total"` if total > 1; `"about ${formatEta(eta)} left"` if eta ≠ null; none if both empty | `setProgress(total, done, indeterminate = total <= 1)`, ongoing |
| `WritingVault` | `"Saving to vault…"` | – | indeterminate, ongoing |
| `Saved` | warning ≠ null → `"Saved, with a warning"`; appended → `"✓ Added to note"`; else `"✓ Saved"` | `warning ?: notePath` | `setAutoCancel(true)`, `setTimeoutAfter(4000)` |
| `Failed(msg, wav)` | `"⚠ $msg"` | wav ≠ null → `"Recording kept. Tap Retry."` | auto-cancel; if wav ≠ null action `"Retry"` = `PendingIntent.getActivity(req 1, MainActivity + EXTRA_RETRY=true, IMMUTABLE or UPDATE_CURRENT)` (goes through the activity because a microphone FGS may only start while visible, Android 14+) |
| `Idle` | `"Ready"` | – | – |

### 1.9 State holders (`CaptureState.kt`)

Global `object CaptureState` with `MutableStateFlow`s exposed read-only:

| Flow / field | Type | Writer |
|---|---|---|
| `phase` | `Phase` (`Idle`, `LoadingModel`, `Transcribing(done,total,etaSeconds?)`, `WritingVault`, `Saved(notePath, warning?, appended, captureId?, appendTo?, atMs=now)`, `Failed(message, wavPath?)`) | service, pipeline, UI (OK button sets `Idle`) |
| `recording` | `RecordingInfo?` (`startedAtMs, file, appendLabel?, minutesLeft?, captureId?, appendTo?, type="idea", pausedAtMs?, pausedTotalMs=0`; `paused` = `pausedAtMs != null`) | service |
| `level` | `Float` 0..1, ~20 updates/s | service |
| `live` | `LiveText(captureId, text)?` – transcript so far | pipeline |
| `last` | `LastResult(transcript, audioSeconds, modelLoadMs, transcribeMs, chunks)` + `realTimeFactor` | pipeline |
| `processingId`, `processingFile` | `@Volatile String?` | `processing(file, id)` |

Derived: `isRecording = recording != null`; `isProcessing = phase is LoadingModel|Transcribing|WritingVault`;
`Phase.isBusy` (in `ui/StatusViews.kt`) is the same set.

Other app-wide flows: `CleanupStep.cleaning: MutableStateFlow<String?>` (id being cleaned),
`CleanupStep.lastReclean: MutableStateFlow<RecleanUndo?>`, `Profiles.flow(ctx)` (types list),
`SwipeDeletes.hidden: StateFlow<Set<String>>`, `Undo.last` (part A). Screens additionally collect Room `Flow`s
(`dao.cards(limit)`, `cardCount()`, `inProgress()`, `watch(id)`, `watchAdditions(id)`).

Screens never poll the service; they `collectAsState()` these flows. The level is read only inside draw lambdas
(`val level = remember { { levelState.value } }`) so the list does not recompose 20×/s.

---

## 2. Transcription

### 2.1 `VoicePipeline.run(wav)` (`pipeline/VoicePipeline.kt`)

Idempotent: every finished step is stored in the row, a retry resumes.
1. Row = `dao.byStaging(wav.path)`, or a new one for a legacy file: id UUID, `createdAt` from the file name
   (`yyyy-MM-dd_HHmmss`, system zone; parse failure → now), state `"recorded"`, `appendTo` from a legacy
   `<name>.append` sidecar (text = note path → `dao.byNote(path)?.id`).
2. `CaptureState.processing(wav.path, c.id)`, `live(null)`.
3. `transcript == null` → `transcribeStep`.
4. `isPrivate` → `savePrivate` and return.
5. `Phase.WritingVault`; `VaultWriter(prefs.vaultUri ?: throw VaultUnavailableException("No vault folder selected"))`.
6. `audioVaultName == null` → `audioStep`. Then `noteStep`. Then row state `"saved"`, `error=null`.
7. Delete `wav`, `<name>.ogg`, `<name>.append`.
8. `Undo.record(Added(id))` if the append target row exists, else `Created(id)`. `live(null)`.
   `Phase.Saved(target?.notePath ?: notePath ?: "", warning, appended = target != null, captureId = target?.id ?: id)`.
- `VaultUnavailableException` → row `"failed"`, error=message, `Phase.Failed("${message}. Recording kept.", wav.path)`.
- Other throwable → row `"failed"`, `Phase.Failed(message ?: simpleName, wav.path)`.

`transcribeStep`:
- `profile = WavReader.profile(wav, 480)`; `seconds = samples/16000`.
- Muted warnings: `muted > 0.9` → `"No sound was recorded. Is the microphone turned off in Quick Settings?"`;
  `muted > 0.3` → `"The microphone was off for part of this recording."`.
- `muted > 0.9` → empty result, no Whisper. Else `transcribe(...)`.
- Row → state `"transcribed"`, `transcript = NonSpeech.strip(text)`, `audioSeconds`, `modelLoadMs`, `transcribeMs`,
  `chunks`, warning = existing + new joined by `" "` (null if empty).

`transcribe`: model not installed → error `"Speech model not installed"`. `Phase.LoadingModel`. Runs inside
`GemmaHolder.background { }` (thread priority `THREAD_PRIORITY_BACKGROUND`, restored afterwards; ORT worker threads inherit it).
`transcribeWith`:
```
WhisperOnnx(store.dir).use { loadMs measured
  chunks = Chunker.plan(profile.energy, profile.samples)
  lang = prefs.language unless "auto"
  for i, range: eta = i==0 ? null : (elapsed/i * (chunks.size - i) / 1000)   // seconds
                Phase.Transcribing(i, chunks.size, eta)
                chunk = readRange(range)
                text = AudioCheck.loudestFrame(chunk) < 0.004 ? "" : NonSpeech.strip(whisper.transcribe(chunk, lang))
                non-blank → append to done; CaptureState.live(LiveText(id, done.join(" ")))
  text = non-blank parts joined by " "; LastResult(...); CaptureState.result(r); log benchmark
}
```
`SPEECH_LEVEL = 0.004` (RMS of the loudest 100 ms ≈ −48 dBFS). `AudioCheck.loudestFrame`: max RMS over
non-overlapping 1600-sample frames. (`AudioCheck.mutedFraction` exists but the pipeline uses the profile's value.)

`audioStep`: folder `"$inbox/Attachments"` (`inbox = prefs.inboxFolder.trim('/')`), `vault.cleanupTemp(folder)`.
If `prefs.audioFormat != "wav"` try `OpusEncoder.encode(wav, <name>.ogg)`; failure → log, use WAV.
`vault.write(folder, NoteBuilder.baseName(at, "Voice", null), ext) { copy 64 KiB }` → `audioVaultName`. Row upserted.

`noteStep`: if `appendTo` target exists and not yet `appended`: `Notes.locate` → path; if the note already contains
`![[audioVaultName]]` or `vault.append(path, NoteBuilder.appendBlock(at, transcript, audioVaultName, warning))`
succeeds → `dao.touch(target)`, row `appended=true`, return. Target gone → falls through to a new note.
New note (if `notePath == null`): `vault.cleanupTemp("$inbox/Voice")`; `existing = vault.findNoteById(id, maxFiles=500,
startFolder="$inbox/Voice")`; else body = transcript, or for an orphaned addition
`"Addition to [[<target file name without .md> | "earlier note">]]\n\n$transcript"`;
`NoteBuilder.voiceNote(captureId, at, durationSec, audioFile, transcript=body, model="whisper-small-int8 (onnx)",
language=prefs.language, warning, type)`; name `NoteBuilder.baseName(at, "Voice", transcript)`. Row: `notePath`,
`appendTo=null`, `lastActivityAt=now`.

`savePrivate`: audio to `filesDir/private/<id>.ogg` (Opus unless format `"wav"` or encoding fails → copy WAV to
`<id>.wav`), `audioVaultName = "private/<file>"`; row `"saved"`, `lastActivityAt=now`; delete wav/ogg; Undo
`Added`/`Created`; `Phase.Saved("", warning, captureId=id)`.

### 2.2 `OpusEncoder`

`MediaCodec` encoder `audio/opus`, 16 000 Hz mono, `KEY_BIT_RATE=24000`, `KEY_MAX_INPUT_SIZE=16384`; `MediaMuxer`
`MUXER_OUTPUT_OGG`. Input read in 8 192-byte chunks (even byte count), pts = `samplesIn*1e6/16000`; EOS on read ≤ 0.
Output drained with timeout 0 if input was fed, else 2 000 µs; track added on `INFO_OUTPUT_FORMAT_CHANGED`;
codec-config buffers skipped. No track → `"Opus encoder produced no output"`; empty file → `"Opus file is empty"`.
On any failure the output is deleted and the error rethrown.

### 2.3 `NonSpeech.strip`

1. Replace every `\[[^\]]*\]` with a space; collapse `\s+` to one space; trim.
2. If the result fully matches `^(\s*(\([^)]*\)|\*[^*]*\*|♪+)\s*)*$` (only parenthesised tags, `*…*`, notes) → `""`.

### 2.4 `Chunker` (`transcribe/Chunker.kt`)

Constants: `RATE=16000`, `MAX=28*RATE` (448 000), `SEARCH=8*RATE` (128 000), `FRAME=480` (30 ms),
`MIN_PAUSE_FRAMES=6` (180 ms). `FRAME_SAMPLES = FRAME`.

`plan(energy, total)`:
- `total <= MAX` → one range `0 until total` if `total > 4000` (0.25 s), else none.
- `threshold = max(0.002, min(2 * pct(energy, 0.1), 0.3 * pct(energy, 0.5)))`, `pct(v,p) = sorted[floor((n-1)*p)]` (0 if empty).
- `start = 0`; while `total - start > MAX`: `cut = cutPoint(energy, threshold, start+MAX-SEARCH, start+MAX)`;
  emit `start until cut`; `start = cut`. Tail emitted if `> 4000` samples.

`cutPoint(from, to)`: frames `f0 = from/480` to `f1 = min(to/480, energy.size)` (exclusive). Find runs of
`energy < threshold`; keep the longest, ties → the later (`len >= bestLen`). If `bestLen >= 6` →
`(bestStart + bestLen/2) * 480 + 240`. Else the quietest frame `q` (first minimum, starting from `f1-1`) → `q*480 + 240`.
`split(samples)` is the in-memory variant (computes RMS frames itself).

### 2.5 `WhisperOnnx` (`transcribe/WhisperOnnx.kt`)

Model directory `filesDir/whisper` with five files (`REQUIRED`):
`Whisper_initializer.onnx`, `Whisper_encoder.onnx`, `Whisper_cache_initializer.onnx`, `Whisper_decoder.onnx`,
`Whisper_detokenizer.onnx`. Missing → `"Model file missing: <name>"`. Library: ONNX Runtime Android + onnxruntime-extensions
(`OrtxPackage.getLibraryPath()` registered as custom op library on every session).

Session options:

| Session | Arena | Mem pattern | Opt level | Other |
|---|---|---|---|---|
| initializer, cache initializer, decoder | off | off | `NO_OPT` | – |
| detokenizer | off | off | default (optimised) | – |
| encoder | on | on | `NO_OPT` | `setSymbolicDimensionValue("batch_size", 1)` |

Constants: `LAYERS=12`, `HEADS=12`, `HEAD_DIM=64`, `MAX_TOKENS=445`, `PREFIX_TOKENS=4`, `EOT=50257`, `SOT=50258`,
`FIRST_LANGUAGE=50259` (en), `LAST_LANGUAGE=50357`, `TRANSCRIBE=50359`, `NO_TIMESTAMPS=50363`.
Language list (token = `SOT + 1 + index`): `en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no`.
`languageToken(code)` → null for `"auto"` or any code not in the list (→ auto-detect).

`transcribe(samples, language)`:
- `< 4000` samples → `""`.
- `maxTokens = min(441, ceil(samples/16000 * 30))`.
- `initializer.run({"audio_pcm": float[1,N]})` → `encoder.run({"input_features": out[0]})` →
  `cacheInit.run({"encoder_hidden_states": out[0]})` → cross KV map `past_key_values.{i}.encoder.{key|value}` =
  `present.{i}.encoder.{key|value}`.
- Decode (greedy argmax over `logits`):
  - Forced queue: `[langToken?] , TRANSCRIBE, NO_TIMESTAMPS`; `detectLanguage = langToken == null`.
  - Initial self-attention cache: empty float tensors `[1,12,0,64]` for `past_key_values.{i}.decoder.{key|value}`.
  - First input token `SOT`. Each step: inputs `input_ids` (int64 `[1,1]`) + past decoder KV + encoder KV; run;
    close the previous step's result (or the empty tensors on the first step); next past = `present.{i}.decoder.*`.
  - Next token: if `detectLanguage` → argmax if within language range else `FIRST_LANGUAGE` (English), flag off;
    else if forced not empty → pop; else if `best == EOT || out.size >= maxTokens || LoopGuard.isLooping(out)` → stop;
    else append `best` to `out` and feed it.
- Detokenize: `detokenizer.run({"sequences": int32[1,1,n]})`; result is `Array<Array<String>>` or similar → first string.
- `clean`: remove `<\|[^>]*\|>\s?`, trim, uppercase the first character.

`LoopGuard.isLooping(out)`: for `len` 1..8, need `out.size >= len*4`; if the last `len` tokens equal each of the
3 preceding `len`-blocks → drop `len*3` tokens (keep one copy) and return true.

### 2.6 `ModelStore` (`transcribe/ModelStore.kt`)

| Constant | Value |
|---|---|
| `MODEL_URL` | `https://huggingface.co/DocWolle/whisperOnnx/resolve/main/whisper_small_int8.zip?download=true` |
| `MODEL_SIZE` | `242_722_488` bytes |
| `MODEL_SHA256` | `97ac121610693e8c9adaef9c6db9066ef0cbebba53ca6e47f6d1541068ca19e3` |

The app has no INTERNET permission: the URL is opened in the browser (`ACTION_VIEW`); the user imports the ZIP with
`OpenInDownloads` (an `OpenDocument` contract with `EXTRA_INITIAL_URI` = `primary:Download` on
`com.android.externalstorage.documents`; MIME filter `application/zip`, `application/octet-stream`).
`isInstalled()` = all five files exist with length > 0.

`import(uri, progress)`:
1. Query `OpenableColumns.SIZE`; if known and ≠ `MODEL_SIZE` → error
   `"This is not the expected model file (X MB instead of 242 MB). If the download was interrupted, download it again."`.
2. Temp dir `filesDir/whisper.importing` (wiped, created).
3. Stream through a SHA-256-hashing `InputStream` → `ZipInputStream` (64 KiB buffer). For each non-directory entry whose
   last path segment ends in `.onnx`, write to temp dir by that name. Progress every > 1 000 000 bytes read:
   `min(count/MODEL_SIZE, 1)`.
4. `drain()` the rest so the hash covers the whole file.
5. Hash ≠ expected → `"The file is damaged or not the official model (checksum mismatch). Download it again."`.
   Missing required file → `"The model file is incomplete (missing a, b)."`. Any error deletes the temp dir.
6. Delete `whisper`, rename temp → `whisper` (failure → `"Could not install the model"`), `progress(1f)`.
UI (`ModelSetup`, `FirstRunScreen`) runs it on `Dispatchers.IO`; progress `null` = indeterminate before the first update.

---

## 3. AI cleanup

### 3.1 `GemmaHolder` (`cleanup/GemmaHolder.kt`)

Library: Google AI Edge LiteRT-LM (`com.google.ai.edge.litertlm`: `Engine`, `EngineConfig`, `Backend`, `ConversationConfig`,
`SamplerConfig`, `ThinkingConfig`). One engine per process.

- Model file: first file (sorted by name) in `filesDir/gemma` ending `.litertlm` with length > 0.
  `modelName(ctx)` = its name without `.litertlm`, or `"gemma"`.
- Scope: `CoroutineScope(SupervisorJob() + Dispatchers.IO)`; `lock = Mutex()` guards engine, import, remove.
- `ensureLoaded` (caller holds lock): engine present → `scheduleUnload()` and return it. Else
  `Engine(EngineConfig(modelPath, backend, cacheDir = cacheDir/gemma)).initialize()` with `Backend.GPU()`; any throwable →
  `Backend.CPU()`. Then `scheduleUnload()`.
- `IDLE_MS` = 600 000 (10 min): `scheduleUnload()` cancels and relaunches a job: `delay(IDLE_MS)` then under the lock
  `engine.close(); engine = null`.
- `warm(ctx)`: only if a model file exists; `scope.launch { delay(1500); lock { background { ensureLoaded } } }`
  (1 500 ms lets the capture screen finish animating; GPU init stalls drawing). Failures are logged only.
- `generate(ctx, prompt)`: under the lock; `ensureLoaded` in `background{}`; cancel idle job; new conversation per call:
  `ConversationConfig(samplerConfig = SamplerConfig(topK=1, topP=1.0, temperature=0.0, seed=0),
  thinkingConfig = ThinkingConfig(enableThinking=false), extraContext = mapOf("enable_thinking" to false))`;
  `conversation.sendMessage(prompt).toString().trim()` in `background{}`; conversation closed (`use`);
  `finally scheduleUnload()`.
- `import(ctx, uri, progress)` (IO, under lock): display name must end `.litertlm` (case-insensitive) else
  `"That is not a model file. Pick the .litertlm file (for example gemma-4-E2B-it.litertlm)."`. Cancel idle job, close
  engine. Copy to `filesDir/gemma/<name>.part` (1 MiB buffer, progress = done/size if size known) at background priority.
  Empty, or size known and different → `"The copy is incomplete. Try again."` Could not open → `"Could not open the file."`.
  Delete every existing `.litertlm`, rename part (failure `"Could not save the model."`), delete `cacheDir/gemma`.
  `finally` deletes the part file.
- `remove(ctx)` (IO, under lock): cancel idle, close engine, delete all files in `filesDir/gemma`, delete `cacheDir/gemma`.
- `background(block)`: set current thread to `THREAD_PRIORITY_BACKGROUND`, run, restore previous priority.

### 3.2 Prompt (`cleanup/CleanupText.kt`, `types/Profiles.kt`)

```
prompt(segments, instruction, checklist, today, mixed):
  appendLine(instruction.trim())
  appendLine(checklist ? checklistRules(today) : mixed ? MIXED_RULES : BULLET_RULES)
  appendLine()
  appendLine("Transcript:")
  for each segment i (1-based): appendLine("[i] segment")
```
`today` = `LocalDate.now().toString()` (ISO `YYYY-MM-DD`). `checklist` takes precedence over `mixed`.
Retry prompt = first prompt + `"\nImportant: end EVERY line with its segment numbers in square brackets, e.g. [3]."`.
A legacy overload `prompt(segments, type, today)` uses `TASK_INSTRUCTION`+checklist for `"task"`, else `IDEA_INSTRUCTION`.

Fixed rules (not editable), verbatim:

`BULLET_RULES`
```
After each bullet, give the transcript segment numbers it came from, e.g. [2,3].
First line: "Title: " and a short title of at most 8 words, made from the speaker's own words, in the language of the transcript.
Then the bullet points only, one per line, each starting with "- ".
If the transcript holds nothing to list, write only the title line. Never make up content.
```

`MIXED_RULES`
```
End every paragraph and every bullet with the transcript segment numbers it came from, e.g. [2,3].
First line: "Title: " and a short title of at most 8 words, made from the speaker's own words, in the language of the transcript.
Then the text only. Sentences that belong together form a paragraph: one line of its own, with no "- " in front.
Use "- " only for the items of a real list, each on its own line.
Layout (placeholders, not content):
Title: <title>
<paragraph> [1,2]
<what the list is for>: [3]
- <item> [3]
- <item> [3]
<paragraph> [4]
If the transcript holds nothing to keep, write only the title line. Never make up content.
```

`checklistRules(today)` (`$today` substituted)
```
If the speaker gives a day or date for a task, add " (due YYYY-MM-DD)". Today is $today.
After each task, give the transcript segment numbers it came from, e.g. [2,3].
First line: "Title: " and a short title of at most 8 words, made from the speaker's own words, in the language of the transcript.
Then the tasks only, one per line, each starting with "- [ ] ".
If the transcript holds nothing to list, write only the title line. Never make up content.
```

Built-in instructions (editable per type; stored copies auto-upgrade while unedited, see 3.2.1):

`IDEA_INSTRUCTION` (also used by Private, Exact words, and new types)
```
Rewrite this voice note as clean bullet points.
Rules: remove filler words and repeats, fix grammar, put related points together.
Use only what the speaker said. Add no facts, opinions or conclusions.
Keep the language of the transcript.
```

`TASK_INSTRUCTION`
```
Rewrite this voice note as a checklist of tasks.
Rules: one task per line, short and starting with a verb; remove filler words and repeats.
Leave out remarks that are not tasks, such as "okay", "task list" or questions to yourself.
Use only what the speaker said. Add no tasks, facts or opinions.
Keep the language of the transcript.
```

`MEETING_INSTRUCTION`
```
Rewrite this voice note about a meeting as clean bullet points.
Rules: start with who was there if it is said, then what was decided, then open points and who does them.
Remove filler words and repeats. Use only what the speaker said. Add no facts, names or conclusions.
Keep the language of the transcript.
```

`VOICE_NOTE_INSTRUCTION`
```
Clean up this voice note so it reads well, without changing what it says.
Rules: keep every point and keep the speaker's order.
Write connected thoughts as short paragraphs, and lists, steps or separate items as bullet points.
Remove filler words, false starts, repeats and sign-offs such as "okay" or "that is it"; when the speaker corrects themselves, keep only the corrected version.
Keep words and remarks that change the meaning, such as not, maybe, only, almost, probably, or "not what I said before".
Keep names, numbers, dates and examples exactly.
When the speaker counts things ("one…, two…", "first…, then…"), write a short line saying what they are for, then each item as its own bullet without the counting word.
Do not summarise or shorten the content. Add no facts, opinions or conclusions.
Keep the language of the transcript.
```

`FEEDBACK_RULES` (private constant, appended to the three feedback instructions)
```
Where: the screen, button or flow in the speaker's own words, with the steps that lead there.
One line per point, in the speaker's order: a point spread over several sentences becomes one line, with its segment numbers together, e.g. [3,4,6].
Write short, plain lines: drop spoken filler inside a point ("you know", "or whatever", "let me just"), but keep every detail exactly: numbers, names, order, and how it should look or feel ("smoother", "softer", "a bit slower").
In this app "node" is a mishearing of "note", "Whiskr" or "Wispers" of "Whisper", and "keen" of "clean".
Leave out everything else: filler, greetings, thinking aloud, and remarks that are not a fix, feature, praise or question.
Use only what the speaker said. Add no fixes, features, causes or solutions.
Keep the language of the transcript.
```

`FIX_FEATURE_INSTRUCTION` (`$FEEDBACK_RULES` expanded in place)
```
This voice note is feedback on an app: what is broken, what to add, what already works, and questions.
Write each point as one of these lines:
- Fix: <where> — <what goes wrong>; should <what it should do instead>
- Feature: <where> — <what to add or change, with every detail given>
- Works: <only what the speaker praises or confirms works>
- Question: <what the speaker asks>
Leave out "; should …" when the speaker does not say what it should do.
$FEEDBACK_RULES
```

`FIX_INSTRUCTION`
```
This voice note is about things that are broken or wrong in an app.
Write each point as one of these lines:
- Fix: <where> — <what goes wrong>; should <what it should do instead>
- Works: <only what the speaker praises or confirms works>
- Question: <what the speaker asks>
Leave out "; should …" when the speaker does not say what it should do.
$FEEDBACK_RULES
```

`FEATURE_INSTRUCTION`
```
This voice note is about features to add or change in an app.
Write each point as one of these lines:
- Feature: <where> — <what to add or change, with every detail given>
- Works: <only what the speaker praises or confirms works>
- Question: <what the speaker asks, e.g. whether it is doable>
Mark a feature the speaker leaves for later with "(later)" at the end.
$FEEDBACK_RULES
```

Built-in types (`Profiles.DEFAULTS`, in this order):

| id | name | subtitle | icon | instruction | checklist | private | onWidget | mixed | noCleanup |
|---|---|---|---|---|---|---|---|---|---|
| `idea` | Idea | tidy into bullets | idea | IDEA | – | – | yes | – | – |
| `task` | Task | checklist + due date | task | TASK | yes | – | yes | – | – |
| `meeting` | Meeting | who, decisions, to-dos | people | MEETING | – | – | – | – | – |
| `private` | Private | stays on device | lock | IDEA | – | yes | yes | – | – |
| `c-fixfeature` | Fix / Feature | sorted into fixes and features | wrench | FIX_FEATURE | – | – | – | – | – |
| `c-fix` | Fix | what's broken, where | bug | FIX | – | – | – | – | – |
| `c-feature` | Feature | what to add or change | sparkle | FEATURE | – | – | – | – | – |
| `c-voicenote` | Voice note | cleaned up, nothing left out | mic | VOICE_NOTE | – | – | – | yes | – |
| `c-exact` | Exact words | no AI cleanup, fastest | pen | IDEA | – | – | – | – | yes |

The last five are `ADDED`: merged once into stored lists (tracked by string set `capture_profiles_added`).

#### 3.2.1 Instruction upgrade (`Profiles.all`)

For each stored type with a built-in default: if `instruction == default.instruction` → set `base = default` when
different; else if `instruction == base` or it equals an entry in `OLD_INSTRUCTIONS[id]` (earlier built-in texts for
`c-voicenote` (2 versions), `c-fixfeature`, `c-fix`, `c-feature`) → replace instruction, base, mixed, checklist with the
default's; else keep (user-edited). Changes or missing `ADDED` types are written back to prefs. Other `Profiles`
details (storage keys, slots, primary type) are part A.

### 3.3 Segmenting

`segments(transcript)`: trim, split on `(?<=[.!?…])\s+`, trim, drop empty, then `cutLong` each:
`MAX_SEGMENT = 280`. While `rest.length > 280`: window = first 280 chars; cut after the last `", "` if its index
> 93 (`280/3`), else at the last space if > 0, else at 280; emit trimmed head, continue with trimmed rest.

### 3.4 Answer parsing (`check(answer, segmentCount, mixed)`)

Regexes:
- `BULLET` `^\s*(?:[-*•]|\d+[.)])\s+(.*)$`
- `TASK` `^\[[ xX]?]\s+(.*)$`
- `TITLE` `^(?:#+\s*)?\**(?:title|titel)\**\s*:\s*\**(.+?)\**$` (case-insensitive)
- `REFS` `\s*((?:\[\s*\d+(?:\s*[,–-]\s*\d+)*\s*]\s*)+)[.]?\s*$` (one or more bracket groups at line end, optional dot)
- `LEAD_REFS` `^(?:[-*•]\s+)?((?:\[\s*\d+(?:\s*[,–-]\s*\d+)*\s*]\s*)+)(\S.*)$`
- `ONLY_REFS` `^(?:\[\s*\d+(?:\s*[,–-]\s*\d+)*\s*]\s*)+[.]?$`
- `TODO_LINE` `^(?:Fix|Feature)\s*:` (case-insensitive)

Steps:
1. Normalise `\r\n`; split lines; trim; drop empty lines and lines starting with ```` ``` ````.
2. Fold: a line matching `ONLY_REFS` whose previous line has no `REFS` is appended to the previous line with a space.
3. Empty → `Rejected("empty answer")`.
4. Title: `TITLE` on line 0 → group 1, trimmed, `"` trimmed, max 80 chars, null if empty; remove that line.
   Nothing left → `Rejected("title only: nothing to list", retry=false)`.
5. For each line `n`:
   - Lead-in: ends with `:` and has no `REFS` → append the `REFS` match of line `n+1` (if any).
   - `LEAD_REFS` full match → line = `"- " + rest` (+ `" " + refs` if rest has no trailing refs).
   - `bullet = BULLET` group 1. If none and not `mixed` → `Rejected("not a bullet: \"<first 60>\"")`.
   - `body = bullet ?: line`; `TASK` match → body = task text.
   - `REFS` in body required → else `Rejected("bullet without segment numbers: \"<60>\"")`.
   - `parseRefs`: replace `[`/`]` with `,`, split by `,`; each part split on `-`/`–`: one number → it; two → inclusive
     range if `b >= a` and `b - a < 200`, else nothing; distinct, sorted. Empty or any outside `1..segmentCount` →
     `Rejected("segment number out of range in \"<60>\"")`.
   - `text` = body before the refs, trimmed; empty → `Rejected("bullet with numbers only")`.
   - `task = (TASK matched) || (bullet && TODO_LINE matches text)`; `para = bullet == null`.
6. `Ok(bullets, title)`.

All `Rejected` above have `retry = true` unless noted.

### 3.5 Grounding check and `dropUnsaidShould`

`check(answer, segments, mixed)` runs 3.4, then:
- `words(s)` = matches of `\p{L}+` with length ≥ 3, excluding (lower-case) `FORMAT_WORDS` =
  `{fix, feature, title, titel, due, works, question, should, later}`.
- `stem(w)` = `w.lowercase().take(4)`. `said` = set of stems of all segment words.
- `grounded(line) = |{w ∈ words(line) : stem(w) ∈ said}| / |words(line)|`, or `1.0` when the line has no words.
- Keep bullets with `grounded >= 0.5` (`MIN_GROUNDED`), each passed through `dropUnsaidShould`. None kept →
  `Rejected("no line matches what was said", retry=false)`. Title kept only if `grounded(title) >= 0.5`, else null.

`dropUnsaidShould(text, said)`: only for lines matching `TODO_LINE`. `SHOULD` = `\s*[;,—–-]?\s*\bshould\b(.*)$`
(case-insensitive). If found after the first `:` and `grounded(group 1) < 0.5` → cut the line at the match start and
trim trailing `' ' ; , — – -`. Otherwise unchanged.

### 3.6 Run flow (`cleanup/CleanupStep.kt`)

`runPending(ctx)` (IO): no Gemma file → return. For each row of `dao.pendingCleanup(now - 24 h)` (voice, saved,
`cleanup IS NULL`, own note with `notePath` or private type, or an appended addition; oldest first):
`result = clean(c)` (throwable → `"failed: ${message ?: simpleName}"`), `dao.setCleanup(id, result)`.

`clean(c, again=false)`:
1. `transcript` empty → `"skipped: no speech"`.
2. `owner` = append target row (missing → `"skipped: note not found"`) or the row itself.
3. `profile = Profiles.get(owner.type)`; `noCleanup` → `"skipped: no AI cleanup for <name>"` (`NO_CLEANUP` prefix).
4. `cleaning.value = c.id`; prompt; `generate`; `check`; if `Rejected && retry` → one retry with the suffix; `finally cleaning = null`.
5. `Rejected` → `"rejected: <reason>"`.
6. `dao.setClean(c.id, stored(title, bullets))` (app copy first).
7. Private owner → `touch(owner)`, `"done in <ms> ms (private, app only)"`.
8. No vault → `"skipped: no vault"`; `Notes.locate` or `vault.read` null → `"skipped: note not found"`.
9. `rewrite(note, transcript, cleanBlock(bullets, segments), GemmaHolder.modelName, addition = owner !== c || again)`
   null → `"skipped: note was changed"`.
10. `dao.putEdit(PendingEdit(c.id, path, note, updated))` (crash backup), `vault.overwrite`, `dao.clearEdit`,
    `Notes.forget(owner.id)`, `touch(owner)`, return `"done in <ms> ms"`.

`cleanNow(ctx, id)`: same as one `runPending` item for any age, with `again = true` (used after hand-correcting
words and for typed text saved "with AI").

### 3.7 Vault rewrite and block layout

`cleanBlock(bullets, segments)`:
```
<bulletLines(bullets)>

> [!quote]- Exactly what I said
> [1] <segment 1>
> [2] <segment 2>
```
(trailing whitespace trimmed). `bulletLines`: separator `"\n\n"` when this or the previous line is a paragraph, else
`"\n"`; paragraph → `text [r,r]`; bullet → `- text [r,r]`; task → `- [ ] text [r,r]` (refs joined by `,`, no spaces).
The title is not written into the note; it is kept in the app copy only: `stored(title, bullets)` =
`"# <title>\n"` (if any) + `bulletLines`.

`rewrite(note, transcript, block, model, addition)`: split frontmatter (`NoteText.split`); no frontmatter or blank
transcript → null. `marked` = front contains `"\ncleanup:"`; marked and not `addition` → null. The transcript must occur
exactly once as whole lines in the body (`lineOccurrences`: starts at body start or after `\n`, ends at body end or
before `\n`). Replace it with the block; if not marked, front gets `cleanup: <model>` inserted before the closing `---\n`.

`replaceBlock(note, old, new)`: `old` non-blank and found exactly once (anywhere) → replaced, else null.
`replaceWords(note, old, new)`: like `rewrite` matching (once, whole lines, body only), no frontmatter change.
`withCaptureType(note, type)`: drop any `capture_type:` line; for type ≠ `idea` insert `capture_type: <type>` after the
`source:` line (or at the end of the frontmatter).

### 3.8 Re-clean with another type (`reclean`)

1. New type has `noCleanup` → `setType` on the note and all additions; done.
2. `rows` = note + additions (for Undo). Vault path via `Notes.locate` (not for private), `before` = note text.
3. `sections` = rows with `kind == "voice"` and non-blank transcript. `setType(newType)` on all rows. `clearClean` on
   each section (UI shows "Cleaning").
4. For each section `recleanOne`; throwable → `restoreClean(id, oldClean, oldCleanup)`, logged as failed.
5. `touch(note)`; `lastReclean = RecleanUndo(noteId, rows, path, before, after = vault.read(path))`;
   `Undo.record(Recleaned(noteId))`; `BrainWidget.refresh`.

`recleanOne`: `answer()` = prompt with the new profile, one retry as in 3.6 (sets `cleaning`). Rejected →
`restoreClean(old)`, result `"rejected, kept the previous version: <reason>"`. Private → `restoreClean(id, stored,
"done (re-cleaned as <name>, app only)")`. Else read note; `oldBlock` = `cleanBlock(bulletsFromStored(old clean), segments)`;
`swapped = replaceBlock(note, oldBlock, newBlock) ?: rewrite(note, transcript, newBlock, model, addition=true)`;
for the owner section also `withCaptureType`. Null → `restoreClean(id, stored, "done in the app only (note was edited)")`.
Else `putEdit` → `overwrite` → `clearEdit`, `Notes.forget(owner)`, `restoreClean(id, stored, "done (re-cleaned as <name>)")`.

`undoReclean`: take and clear `lastReclean`; `Undo.forget(noteId)`; if the vault note still equals `after` → write
`before` (with `putEdit`/`clearEdit`); upsert the saved rows; `Notes.forget`; `touch`.

### 3.9 Hand edits (`pipeline/NoteEdits.kt`)

`wordsEditable(c)` = kind `voice` or `text`. Conflict message `CHANGED` =
`"This part was changed in Obsidian, so it was left as it is. Edit it there."`.

`editWords(id, text)`: `new = text.trim()`; equal to old → no-op; empty → error
`"Nothing left. To remove this, use Undo or delete the note."`. Non-private owner: change = replace the section's clean
block (rebuilt from stored clean + segments of the *old* words) with the new words, or else `replaceWords(old, new)`;
null → error `CHANGED`. Row: transcript = new; for voice `clean = null, cleanup = null`. Then `done()`; for voice
`AppScope.launch { CleanupStep.cleanNow(id); Notes.forget(owner) }`.

`editClean(id, text)`: requires a stored clean version (`"There is no clean version to edit"`).
`fromEditable(text, old)`: per non-empty trimmed line: `- [ ] `/`- [x] ` → task (drop 6 chars); `- `, `* `, `• ` →
bullet (drop 2); else paragraph. Refs = refs of the old line with identical text, else old line at the same index, else
the last old line's, else `[1]`. Empty-text lines dropped. Equal → no-op; empty → same error as above. Vault: replace
`cleanBlock(old)` with `cleanBlock(new)` (segments of the transcript). Row: `restoreClean(id, stored(title, new), "edited by hand")`.
`editable(stored)` = `bulletLines(bullets, withRefs = false)` (what the editor shows).

`write()` wraps every vault change in `putEdit`/`overwrite`/`clearEdit`; missing vault/note →
`VaultUnavailableException("No vault folder selected" | "Note not found")`. `done()`: `touch(owner)`, `Notes.forget`,
`Undo.drop()`, `BrainWidget.refresh`.

### 3.10 Outcome strings (stored in `Capture.cleanup`)

| String | Meaning |
|---|---|
| `done in <ms> ms` | vault note rewritten |
| `done in <ms> ms (private, app only)` | private note cleaned |
| `done (re-cleaned as <name>)` / `done (re-cleaned as <name>, app only)` | re-clean |
| `done in the app only (note was edited)` | re-clean, vault note untouched |
| `edited by hand` | clean version edited |
| `rejected: <reason>` | check failed (reasons in 3.4/3.5) |
| `skipped: no speech`, `skipped: note not found`, `skipped: no vault`, `skipped: note was changed` | – |
| `skipped: no AI cleanup for <type name>` | type with `noCleanup` |
| `skipped: typed` (`TextCapture.TYPED`) | typed text saved without AI |
| `pending: typed, with AI` (`TextCapture.AI_PENDING`) | typed text waiting for cleanup |
| `failed: <message>` | exception |

Card display helpers (`forDisplay`, `storedForDisplay`, `parseStored`) hide the quote block and refs, map
`- [ ] `→`☐ `, `- [x] `→`☑ `, `- `→`• `, and `**Added HH:MM(:SS)…**` → `Added HH:MM…`; 3+ newlines collapse to 2.

---

## 4. Text, shares, photo, attach

### 4.1 `TextCapture` (`pipeline/TextCapture.kt`)

`save(ctx, text, type, ai)` (IO):
- Private type (`Profiles.isPrivateId`: `"private"` or `"private-…"`): row `Capture(id, now, "saved", kind="text",
  transcript=text, type, cleanup = ai ? AI_PENDING : TYPED)`, `Undo.record(Created)`, widget refresh, `ai` → `cleanLater`.
- Else: `Shares.stage(ACTION_SEND text/plain EXTRA_TEXT=text, sourceApp=null)`; `setType`; `Shares.save`; widget refresh.
  If the saved kind is `"text"` (not a detected link) → `setCleanup(ai ? AI_PENDING : TYPED)`, `ai` → `cleanLater`.
`append(ctx, note, text, ai)`: non-private → `vault.append(path, NoteBuilder.appendBlock(now, text, null, null, typed=true))`
(fails → `VaultUnavailableException("Note not found")`); new row (kind text, `appendTo=note.id`, `appended=true`,
type = note's, cleanup as above); `touch`, `Notes.forget`, `Undo.record(Added)`, `ai` → `cleanLater`.
`cleanLater` = `AppScope.launch { CleanupStep.cleanNow(id) }`.

### 4.2 `ShareParse` (`share/ShareParse.kt`)

- URL regex `https?://[^\s<>"]+`; first match in trimmed text; trailing chars `.,;:!?)]}'"` trimmed off.
- `rest` = text with the match removed, trimmed, null if empty. `title` = subject (trimmed, null if empty).
- If no subject, `rest` is single-line and ≤ 200 chars → `title = rest`, `extra = null`; else `extra = rest`.
  `extra == title` → `extra = null`. Returns `SharedLink(url, title, extra)`; no URL → null (plain text).
- `domain(url)`: after `://`, before `/` and `?`, `www.` removed.
- `fileKind(mimes)`: all `image/*` → `"Image"`; all `application/pdf` → `"PDF"`; else `"File"`.

### 4.3 `Shares` (`share/Shares.kt`)

`AppScope` = `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (app-wide). `lock = Mutex()` serialises `save`.

`stage(ctx, intent, sourceApp, sourceName?, progress)` (IO):
- `text = EXTRA_TEXT`, `subject = EXTRA_SUBJECT ?: EXTRA_TITLE`.
- Streams: `ACTION_SEND` → `EXTRA_STREAM`; `ACTION_SEND_MULTIPLE` → `EXTRA_STREAM` list; none → `clipData` URIs; distinct.
- Dir `filesDir/shares/<uuid>/`. Meta: `sourceApp`, `sourceName = sourceName ?: Previews.appName(pkg) ?: ""`.
- Files: query name and size; total known only if all sizes ≥ 0 and sum > 0. Copy each to `f<i>` (64 KiB) with SHA-256;
  progress = done/total or null. `mime = cr.getType(uri) ?: intent.type`; `ext` = name extension, else
  `MimeTypeMap` from mime, else `"bin"`. Preview → `filesDir/thumbs/<id>-<i>.jpg`. File JSON: `staged, name, thumb, pages
  (-1 if none), ext, mime, size, sha256`. Fingerprint: one file → its hash; several → SHA-256 of the concatenated hex
  hashes. Meta `text`, `title`. Kind `"file"`.
- Else link (`ShareParse.link`) → kind `"link"`, fingerprint = URL, meta `url`, `title`, `text`=extra.
- Else text: blank → delete dir, error `"There was nothing to save in this share."`; kind `"text"`, fingerprint =
  SHA-256(trimmed text), meta `text`, `title`.
- Write `meta.json`; upsert row `(state "recorded", kind, fingerprint, stagingPath = dir)`.

`duplicateOf(c)`: `dao.recentShare(fingerprint, now - 24 h)` (state saved, same fingerprint).
`discard(c)`: delete dir; row state `"discarded"`, `stagingPath=null`.

`save(ctx, id)` (under `lock`, IO): already `saved` → return `notePath`. Vault missing → `VaultUnavailableException`.
1. Each file without `vaultName`: `vault.write("$inbox/Attachments", NoteBuilder.fileBaseName(at, originalNameWithoutExt), ext)`;
   store `vaultName` in meta and rewrite `meta.json` immediately (resume-safe).
2. `appendTo` set: locate target (else `VaultUnavailableException("The note to add to was not found")`); heading =
   `NoteBuilder.shareAdditionHeading(at, source)`; append `shareAddition(...)` unless the note already contains the heading;
   row saved, `appended=true`, `notePath=null`, `details`, `transcript = noteText`, `lastActivityAt`; `touch(target)`;
   `Undo.record(Added)`; `Notes.forget(target)`; delete dir.
3. Else folder `"$inbox/" + {link: "Links", text: "Text", else: "Documents"}`; reuse `findNoteById(id, 500, folder)` or
   build (`buildNote`) after `cleanupTemp(folder)`; row saved with `notePath`, `details`, `transcript`; `Undo.record(Created)`; delete dir.
4. Any throwable → row `"failed"` with error, rethrow.

`buildNote` names: link → `baseName(at, "Link", title ?: domain)`; text → `baseName(at, "Text", title ?: text)`;
files → kind `"Photo"` when `sourceName == "Camera"` (label null) else `fileKind`, label = first file's name without
extension, else title. Note bodies are part A. `details` JSON: `{source, files:[{name=vaultName, mime, size, pages, thumb}]}`.
`noteText`: link → title, url, extra (non-empty, `\n`); text → text; files → vault names one per line.

`resumePending(ctx)`: `AppScope.launch`; for each `dao.pendingShares()` (non-voice, not saved) that is not discarded and
whose staging dir exists → `save`; failures logged. Called by `MainActivity` on start and after each share panel save.

### 4.4 `Previews`

`SIZE = 320`. Images: decode bounds, `inSampleSize` doubles while `max(w,h)/(sample*2) >= 320`; EXIF rotation 90/180/270
applied. PDF: `PdfRenderer` page 0, width 320, height `320*h/w` clamped 1..640, white background,
`RENDER_MODE_FOR_DISPLAY`; returns page count (0 pages → no thumb). JPEG quality 80. Never throws.
`appName(pkg)` = `PackageManager.getApplicationLabel` (needs the manifest `<queries>` for launcher apps).

### 4.5 `ShareActivity` (`share/ShareActivity.kt`)

Theme `Theme.Capture.Panel` (translucent, no window animation), `taskAffinity=""`, excluded from recents, handles
config changes itself. `EXTRA_SOURCE_NAME = "org.brain.capture.SOURCE_NAME"`.

State `MutableStateFlow<ShareUi>`: `Saving(what, progress?)`, `Duplicate(staged, earlier)`, `Pick(staged, notes)`,
`Saved(notePath, captureId, kind, title, subtitle, addedTo)`, `Commenting(saved, stopped)`, `Failed(message, kept)`.

`start(intent)` (only when `savedInstanceState == null`):
- Initial text: `SEND_MULTIPLE` → `"Saving files…"`; `text/*` without stream → `"Saving…"`; PDF → `"Saving PDF…"`;
  `image/*` → `"Saving image…"`; else `"Saving file…"`.
- `source` = `referrer` host if scheme `android-app`. `sourceName = EXTRA_SOURCE_NAME` only if source is own package or null.
- `AppScope.launch`: stage (failure → `Failed(message ?: "Could not read the shared content", kept=false)`); link →
  `"Saving link…"`, text → `"Saving text…"`; duplicate → `Duplicate`; else `pick`.
- `pick`: `dao.notesToAddTo(200)`; empty → save at once; else `Pick`.
- `choose(target)`: `"Saving…"` or `"Adding to the note…"`; target → `dao.setAppendTo`; `save`.
- `save`: `Shares.save` → `describe` → `Saved`; failure → `Failed("<msg> It is kept on the phone and will be saved
  automatically later.", kept=true)` (vault error message gets a trailing `.`; else message or `"Saving failed"`);
  then `Shares.resumePending`.
- `describe`: added → title = note name after `" – "`, subtitle `"Link added"|"Text added"|"File added"` + `" from <source>"`;
  link → title = first non-http line or URL or `"Link"`, subtitle domain; text → first 120 chars, `"Text"` + from;
  file → first line or `"File"`, subtitle `"<n> files"` | `"Photo"` (source Camera) | `"File"` + from.

Timing: `Saved` closes after 3 500 ms unless the user touched "Add a voice comment"; comment recording ended (seen
`recording == null`, confirmed after 300 ms) → `stopped = true`, close after 1 500 ms.
Voice comment: `CaptureService.send(ACTION_START, EXTRA_APPEND_TO = captureId, EXTRA_APPEND_LABEL = notePath)`; only with
mic permission and no recording running. Timer in the panel refreshes every 200 ms (`now - startedAtMs`, pauses ignored).
`onDestroy` while finishing: in `Duplicate` → discard the staged copy; in `Pick` → save as a new note.
Duplicate buttons: `"Open note"` (discard + `openInObsidian(earlier.notePath)` + finish), `"Save again"` (→ `pick`).

Layout: scrim `Color.Black` alpha 0.32 (tap = finish); panel slides in (`slideInVertically { it }`); `Surface` padding
12 dp, `RoundedCornerShape(28.dp)`, `Ink.Panel`, shadow 8 dp; content padding 24 dp, spacing 14 dp. Picker: title
`"Save to"`, search well (`Ink.Well`, radius 14, padding 14×12, placeholder `"Search your notes"`), `LazyColumn` max
height 420 dp; first row `"New note"` / `"Saved to your inbox"` (amber circle) shown only with an empty query; rows
min 56 dp, icon circle 40 dp; filter on note title or transcript (case-insensitive); none → `"No note matches."`.
Row title = clean title (from stored clean) or note name; subtitle `"<Task|Voice|Link|Text|File> · <relativeTime>"`.

### 4.6 Photo (`ui/TakePhoto.kt`)

`Photos.SOURCE = "Camera"`. `newTarget(ctx)`: dir `cacheDir/camera`, deletes files older than 24 h, returns
`"<yyyy-MM-dd HH.mm.ss> Photo.jpg"`. `uri()` = `FileProvider.getUriForFile(ctx, "<package>.files", f)`.
`save(file, appendTo)`: intent `ACTION_SEND image/jpeg EXTRA_STREAM`; `Shares.stage(…, packageName, "Camera")`; delete the
cache file; `setAppendTo`; `Shares.save`. `saveShared(intent, sourceName, appendTo)` same without the camera file.
`isPhoto(c)` = kind file and `details.source == "Camera"`.

`rememberTakePhoto(onSaving, onDone)`: `TakePicture` contract; `pending` path and `target` in `rememberSaveable`.
Result: no file → ignore; not ok/empty → delete, `onDone(null, "No photo taken")`; else `onSaving()`,
`AppScope.launch { Photos.save }` → main: success `onDone(id, null)`; `VaultUnavailableException` →
`"<msg>. The photo is kept on the phone and will be saved automatically later."`; other → `"Could not save the photo: <msg>"`.
Launch failure → `"No camera app found on this phone"`.

`PhotoActivity` (outside entry points): `Theme.Capture.Panel`, affinity `.photo`. `intent(ctx)` = `ACTION_VIEW` with
`NEW_TASK | CLEAR_TASK`. On create (fresh) launch the camera into `newTarget`; `pending` survives in `onSaveInstanceState`
(`"pending"`). Photo ok → start `ShareActivity` (`ACTION_SEND image/jpeg`, stream URI, `EXTRA_SOURCE_NAME="Camera"`,
`FLAG_GRANT_READ_URI_PERMISSION`); else Toast `"No photo taken"`. No camera → Toast `"No camera app found on this phone"`
(long). Always `finish()`.

### 4.7 Attach sheet (`ui/Attach.kt`)

`AttachSheet(open, appendTo, …)` is always composed (launchers must survive); `ModalBottomSheet(skipPartiallyExpanded)`,
`Ink.Panel`. Title `"Add to this note"` (appendTo) or `"New note from…"`. Tiles in rows of 3, each 96 dp wide, circle 56 dp,
icon 24 dp, label 13 sp; tapping dismisses the sheet first. Footer `"Saved to your vault's Attachments and added like a share. Undo takes it back."`.

| Tile | bg / fg | Launch | Result |
|---|---|---|---|
| Gallery | `#3B4A5C` / `#8DBDF5` | `PickMultipleVisualMedia(20)`, `ImageAndVideo` | files, source `"Gallery"` |
| Camera | `#5A5248` / `#F0C08A` | `rememberTakePhoto` with `appendTo` | photo flow |
| Files & audio | `#4A4360` / `#C6B6F2` | `OpenMultipleDocuments(["*/*"])` | files, source `"Files"` |
| Contact | `#5A4450` / `#F2AECB` | `ACTION_PICK`, type `Phone.CONTENT_TYPE` (no contacts permission) | `"Contact: <DISPLAY_NAME> · <NUMBER>"`, source `"Contacts"`; errors `"No contact picked"`, `"Could not read that contact"` |
| Link | `#3A5458` / `#8ED6DC` | dialog `"Add a link"`, prefilled from clipboard if it starts with `http://`/`https://` | text = URL, source `""` |
| Location | `#5C3B38` / `#FF9C8C` | if neither FINE nor COARSE granted → `RequestMultiplePermissions([FINE, COARSE])` | see below; denied → `"No location access, so nothing was added"` |

Files: empty → `"Nothing picked"`; one → `ACTION_SEND`, several → `ACTION_SEND_MULTIPLE`, type `*/*`.
Text: `ACTION_SEND text/plain EXTRA_TEXT`. Both → `Photos.saveShared` in `AppScope`; errors:
`"<msg>. It is kept on the phone and will be saved automatically later."` or `"Could not save: <msg>"`.

Location (`addLocation`, `AppScope`): provider = first enabled of `FUSED`, `GPS`, `NETWORK`; `getCurrentLocation(provider,
null, mainExecutor)` with `withTimeoutOrNull(20_000)`; fallback `getLastKnownLocation(GPS) ?: (NETWORK)`. None →
`"Could not find where you are. Is location turned on?"`. Text: `"Location at HH:mm: https://maps.google.com/?q=<lat>,<lon>"`
(6 decimals, `Locale.ROOT`), source `"Location"`.

---

## 5. UI architecture

### 5.1 Activities

| Activity | Launch mode / affinity | Theme | Role |
|---|---|---|---|
| `ui.MainActivity` | `singleTop`, launcher | `Theme.Capture` | app; Compose `App()` |
| `ui.CaptureActivity` | `singleTask`, affinity `<appId>.capture`, not exported, no recents | `Theme.Capture` | capture screen for widget / tile / shortcuts |
| `ui.QuickRecordActivity` | `singleInstance`, `noHistory`, affinity `""`, launcher (`"Talk"`, `@mipmap/ic_talk`) | `Theme.Capture.Panel` | toggle (6.5) |
| `share.ShareActivity` | affinity `""` | Panel | share target (4.5) |
| `ui.PhotoActivity` | affinity `<appId>.photo`, not exported | Panel | camera → share panel |
| `ui.WidgetSetupActivity` | affinity `<appId>.widgetsetup`, `APPWIDGET_CONFIGURE` | `Theme.Capture` | Brain widget config |
| `ui.ButtonWidgetSetupActivity` | affinity `<appId>.buttonsetup`, `APPWIDGET_CONFIGURE` | `Theme.Capture` | 1x1 widget type pick |

`Theme.Capture` = `android:Theme.Material.NoActionBar`, `windowBackground #FF3A3E44` (same in `values-night`).
`Theme.Capture.Panel` = `android:Theme.Translucent.NoTitleBar`, `windowAnimationStyle @null`.
All activities call `enableEdgeToEdge()` and wrap content in `CaptureTheme`.

### 5.2 `MainActivity` navigation

No navigation library. State in `App()`:
- `retryRequest: MutableState<Boolean>` ← intent `EXTRA_RETRY` (onCreate and onNewIntent).
- `homeRequest: MutableIntState` ← `EXTRA_HOME = "home"` (onCreate sets 1, onNewIntent increments).
- `setupDone = prefs.vaultUri != null && ModelStore.isInstalled()` (recomputed when settings close or `setupTick` bumps).
- `capture: CaptureRequest?` (null = notes list). On launch, if recording and no home request → `CaptureRequest()`.
  Home request → `capture = null`, settings closed. `BackHandler(capture != null)` → null.

Render: `!setupDone` → `FirstRunScreen`; `showSettings` → `SettingsScreen`; else `SharedTransitionLayout { AnimatedContent(capture,
contentKey = { it != null }) }` with `fadeIn(tween(420, delay 120, FastOutSlowInEasing)) togetherWith fadeOut(tween(260, FastOutSlowIn))`.
Shared element: key `"record"` on the big amber button, `boundsTransform = tween(650, FastOutSlowInEasing)`.
Capture card: `animateEnterExit(enter = fadeIn(tween(480, 160)) + slideInVertically(tween(620, 80)) { it/5 }, exit = fadeOut(tween(220)))`.

`CaptureRequest(mode="voice", type="idea", start=false, noteId=null, at=System.nanoTime())`; `at` makes repeats distinct
(`LaunchedEffect(request.at)`).

`HomeScreen` start-up effects (`LaunchedEffect(Unit)`): `Undo.cleanupOnStart`; `Notes.importHistory`; `Notes.recoverEdits`;
`Shortcuts.update`; `Shares.resumePending`; if phase not busy, not failed and pending recordings exist →
`send(ACTION_PROCESS_PENDING)`. Retry request → `ACTION_PROCESS_PENDING`. `LifecycleResumeEffect` bumps `refresh`
(cards re-read notes) and `resumedAt`.

Home details: card page size `prefs.cardCount` (default 5) with `"Show N more"`; `Saved` highlight for 5 000 ms on the
card `captureId` (only if `atMs >= resumedAt`), ticker 500 ms. Hero collapse: `heroPx = 220.dp`; `collapse` = 1 when
first visible index > 1, `offset/heroPx` (0..1) when index 1, else 0; hero `alpha = 1-f`, scale `1-0.35f` (only while
recording). Recording bar shown when recording and `collapse > 0.35`: enter `slideInVertically(spring(0.8, 380)) {-it} + fadeIn`,
exit `slideOutVertically(tween(180)) + fadeOut(tween(180))`; bar `Ink.Raised`, bottom corners 18 dp, shadow 6 dp, stop
button 44 dp amber circle with 14 dp `OnAmber` square (radius 3). `FLING_BOOST = 1.6` multiplies fling velocity.
Big button tap: idle → `CaptureRequest(start=true, type=Profiles.primary)`; recording → `stopAndShow` (`ACTION_STOP`, then
open `noteId = appendTo ?: captureId`). Swipe delete snackbar lives `SwipeDeletes.UNDO_MS - 300` = 4 700 ms; texts
`"Private note deleted"` / `"Note moved to the vault's .trash"`, action `"Undo"`. Home Paste uses type `c-exact` if it exists.

`openInObsidian(ctx, notePath)`: tree doc id `volume:rel`; if the folder contains `.obsidian` →
`obsidian://open?vault=<last segment of rel>&file=<path without .md>`; else
`obsidian://open?path=<root>/<rel>/<notePath>` with root `/storage/emulated/0` for `primary`, else `/storage/<volume>`.

### 5.3 `CaptureActivity` / `CaptureScreen`

`CaptureActivity.intent(ctx, type="idea", start=true, mode="voice")`: extras `"type"`, `"start"`, `"mode"`;
flags `NEW_TASK | SINGLE_TOP`. Modes: `MODE_VOICE="voice"`, `MODE_TYPE="type"`, `MODE_PASTE="paste"`, `MODE_EDIT="edit"`.
`onNewIntent` replaces the request. Chevron (`onClose`) = `goHome()`: start `MainActivity` with `EXTRA_HOME=true`,
`NEW_TASK | CLEAR_TOP | SINGLE_TOP`, then `finish()`. System back leaves normally.

`CaptureScreen(request, onClose, buttonModifier, cardModifier)` state (saveable where marked S):
`mode` (S), `kind` (S, open-zone type), `privateKind` (S), `private` (S), `noteId` (S; default request note or the
current recording's `appendTo ?: captureId`), `view` (S, `Clean`/`Raw`), `userPickedView` (S), `undone`,
`leftNote`, `editFocus`, `editClean`, `editWanted`, `watched` (ids that arrived on this screen: type-out animation and Undo
eligibility), `revealed` (chars revealed per id), `typedDone`, `photoSaving`, `photoTarget` (S), `attachOpen` (S), `attachTarget` (S).

Key derived values:
- `noteBusy` = any voice section with null transcript or (no clean, no cleanup outcome, Gemma installed, transcript non-blank),
  or a text section with `cleanup == AI_PENDING` and no clean.
- `canAdd` = note saved, not private, not undone. `canEdit` = saved, not undone, no recording, not busy, some editable section.
- `recordingHere` = recording's `captureId` or `appendTo` equals `noteId`.

Effects: new recording → `noteId` follows it, `view = Raw`. Request → mode (edit waits via `editWanted`), zone/type, note,
start talking (`talk(appendTo = saved, non-private note)`). Type switch during a new (non-append) recording → `dao.setType`.
View auto-switch: recording or `noCleanup` type → Raw; watched section → Raw until typed out, then `delay(1000)` → Clean;
otherwise Clean if a clean version exists. `talk()` requests `RECORD_AUDIO` (+ `POST_NOTIFICATIONS` on API ≥ 33).

Controls: big button 84 dp (`onRecord`: recording → stop; `canAdd && !recordingHere && !noteBusy` → add to note; else new
note + talk), left column 76 dp (time `m:ss` in `Mono` 14 sp; `"Discard"` with confirm from 20 s), right column
(`"Pause"/"Resume"` while recording, else `"Undo"` when allowed). Undo order: re-clean undo → last watched addition
(`Notes.deleteAddition`) → watched new note (`Notes.delete(undoable=false)`, `undone = true`).
Bottom row (not in edit): `BarButton` Paste / Photo / Attach (Photo and Attach disabled while recording, in Private, or
saving). Type chip `"<name> ▾"` (amber 13 sp); for finished notes it re-cleans (`AppScope.launch { CleanupStep.reclean }`).

Animations: view switch `fadeIn(tween(520,120)) + slideInHorizontally(tween(560,80)) {±it/8}` vs `fadeOut(260) +
slideOutHorizontally(tween(420)) {∓it/10}`; clean cross-fade `tween(600)`; `TypeText` step = `max(1, remaining/45)` chars per
16 ms with amber `" ▍"` cursor, blink keyframes 1 060 ms (on 0–500, off 530–1030); waiting line
`"Slicing what you said into 30-second bites…"`. `Cleaning` placeholder bars alpha 0.35↔0.75, 700 ms reverse.
`Waveform`: 28 bars, sampled every 70 ms, bar 5 dp, gap 4 dp, radius 3 dp, height `8 + 42·v` dp, smoothing factor 0.55,
canvas 52 dp; amber alpha `0.55 + 0.45·v` when active, else `Ink.Bar`.

`BigButton(recording, paused, level, size)`: box `size*1.6`; heartbeat `HEARTBEAT_MS = 1100` keyframes
(0@0, 1@110, 0.28@250, 0.62@360, 0@1100); echo ring scale `1 + 0.55·ease(echo)`, alpha `on·0.26·(1-echo)²`; glow
scale `1.19 + on·(0.04 + 0.16·beat)`, alpha `0.12 + on·0.14·beat`; button thump `1 + on·0.035·beat`; `on` animates
over 700 ms. Stop square `size*0.31`, radius 6 dp; mic icon `size*0.4`. Content descriptions `"Stop and save"` / `"Start talking"`.
Home uses 120 dp, capture screen 84 dp. `BarButton`: height 52 dp, radius 12 dp, icon 18 dp, label 12 sp.
`SaveButton`: height 48 dp, pill; `"Save with AI"` outlined amber 1.5 dp with robot icon (only if Gemma installed),
`"Save, no AI"`/`"Save"` filled amber.

### 5.4 Theme (`ui/Theme.kt`)

`Ink` colours:

| Token | Hex | Token | Hex |
|---|---|---|---|
| Ground | `#3A3E44` | Text | `#EEF0F2` |
| Panel | `#44484F` | TextStrong | `#F5F6F7` |
| Well | `#33363B` | TextBody | `#E4E7EA` |
| Raised | `#5A606A` | TextSoft | `#DDE1E6` |
| Line | `#5C626B` | TextDim | `#C3C8CF` |
| Bar | `#6A7078` | Amber | `#F4B860` |
| Blue | `#8DBDF5` | OnAmber | `#2A2118` |
| BlueSoft | `#3B4A5C` | Error | `#FFA48F` |

`Brand`: `record=Amber, success=Blue, successBg=BlueSoft, voiceBg=Raised, voice=Amber, linkBg=BlueSoft, link=Blue,
fileBg=#5A5248, file=#F0C08A`.

Material `darkColorScheme` (single theme, day and night): `primary=Amber, onPrimary=OnAmber, primaryContainer=Raised,
onPrimaryContainer=TextStrong, secondary=Blue, onSecondary=Well, background=Ground, onBackground=Text, surface=Panel,
onSurface=Text, surfaceVariant=Raised, onSurfaceVariant=TextDim, surfaceContainer=Panel, surfaceContainerHigh=Panel,
surfaceContainerHighest=Raised, surfaceContainerLow=Well, surfaceContainerLowest=Well, outline=Line, outlineVariant=Line,
error=Error, onError=OnAmber, errorContainer=#5C3B35, onErrorContainer=#FFDAD2, inverseSurface=TextStrong,
inverseOnSurface=Well, inversePrimary=OnAmber`.

Fonts (variable fonts in `res/font`, weight via `FontVariation`): `Inter` (`R.font.inter`, 400/500/600),
`Grotesk` (`R.font.spacegrotesk`, 500/600), `Mono` (`R.font.jetbrainsmono`, 500). Typography: display/headline/titleLarge
→ Grotesk SemiBold; `titleMedium` Inter SemiBold; `titleSmall`, body, labels Inter; `bodyMedium` 15 sp / 23 sp line;
`labelLarge` SemiBold. Material defaults otherwise.

Widget drawables: `w_bg` `#EB2F3237`, radius 24 dp; `w_panel` `#FF44484F`, stroke 1 dp `#FF5C626B`, radius 16 dp, ripple
`#33EEF0F2`; `w_amber` `#FFF4B860`, radius 16 dp, ripple `#332A2118`.

---

## 6. Widgets and entry points

### 6.1 `BrainWidget` (`ui/BrainWidget.kt`, `layout/widget_brain.xml`)

Provider info: min 250×180 dp, target 4×3 cells, min resize 220×160, resizable both ways, `updatePeriodMillis=0`,
configure `WidgetSetupActivity`, features `reconfigurable|configuration_optional`.
Layout: root `w_bg`, padding 16 dp; header `"Brain"` (13 sp bold `#EEF0F2`) + `"Tap to talk"` (12 sp `#C3C8CF`), bottom
padding 12; two rows (weight 1) of two slots (`w_slot1..4`, padding 14 dp, icon 22 dp, spacer, title 16 sp bold
`sans-serif-medium`, sub 12 sp), gaps 10 dp (`w_gap1`, `w_gap2`, `w_rowgap`); footer `widget_last` 12 sp, top padding 10.

`refresh(ctx)` (own `CoroutineScope(SupervisorJob()+IO)`): `last = dao.latest()`; update all ids with `views()`;
`ButtonWidget.refreshAll`; `TileService.requestListeningState(BrainTile)`. `onUpdate` → `refresh`.
Callers: end of the processing queue, after re-clean, hand edits, typed saves, widget setup Done/destroy.

`views()`: `slots = Profiles.widgetSlots(ctx)` (≤ `WIDGET_SLOTS=4`: on-widget types, then Photo slot if on, then Open Brain (null)
if on). Hidden slots `GONE`. First slot (if a type) amber `w_amber` with icon/title `#FF2A2118`, sub `#D92A2118`; others
`w_panel`, `#FFEEF0F2` / `#FFC3C8CF`. Icons via `typeIcon(p.icon)`; Open Brain `t_open`, title `"Open Brain"`, sub
`"notes and recent captures"`. Click intents:

| Slot | PendingIntent |
|---|---|
| Open Brain | `getActivity(30, MainActivity + NEW_TASK, IMMUTABLE)` |
| Photo | `getActivity(31, PhotoActivity.intent, IMMUTABLE or UPDATE_CURRENT)` |
| Type at index i | `getActivity(20 + i, CaptureActivity.intent(type, start=true), IMMUTABLE or UPDATE_CURRENT)` |

Content descriptions: `"Open Brain"`, `"Take a photo"`, `"<name>, start talking"`. `w_gap1` visible if ≥ 2 slots,
`w_gap2` if ≥ 4, `w_row2`/`w_rowgap` if > 2. Footer `"Last capture: <describe> · <status>"` or `"No captures yet"`.
`describe`: voice `"<type name> · m:ss"`, link `"<type> · link"`, else `"<type> · typed"`. `status`: private
`"kept on this phone"`; clean present `"cleaned on phone"`; cleanup starts with `rejected` `"exact words kept"`; else `"saved to vault"`.

`WidgetSetupActivity`: sets `RESULT_OK` immediately (Back keeps the widget); title `"Widget"`, `CaptureTypesSettings(widgetOnly=true)`,
amber pill `"Done"` (52 dp) → `BrainWidget.refresh` + finish; `onDestroy` refreshes too.

### 6.2 `ButtonWidget` (1x1)

Provider: min 40×40 dp, 1×1 cells, resizable, configure `ButtonWidgetSetupActivity`, `reconfigurable`.
Layout `widget_button`: root `w_amber`, padding 6 dp, centered icon 26 dp, label 12 sp bold (`#FF2A2118`), margin 4 dp.
Type per widget in prefs file `"button_widgets"`, key `"type_<appWidgetId>"`; default `Profiles.primary(ctx).id`; removed in `onDeleted`.
`views(id)`: type `"photo"` → amber, `t_camera`, `"Photo"`, description `"Take a photo"`, click `getActivity(1000+id, PhotoActivity.intent)`.
Else profile: private → `w_panel` with `#FFEEF0F2`; else amber with `#FF2A2118`; label = name; description
`"<name>, start talking"`; click `getActivity(1000+id, CaptureActivity.intent(type, start=true), IMMUTABLE or UPDATE_CURRENT)`.
`ButtonWidgetSetupActivity`: `RESULT_CANCELED` by default; invalid id → finish. List = all types + Photo slot; current one
`Ink.Raised`, others `Ink.Panel`; rows min 60 dp, radius 14; title `"One-tap button"` and hint
`"Which type should this button start? One tap on it starts talking; Photo opens the camera."`. Tap → save, update the widget, `RESULT_OK`, finish.

### 6.3 `BrainTile` (quick settings)

Label `@string/tile_label` = `"Brain"`, icon `ic_mic`, not an active tile. Per listening session a
`CoroutineScope(SupervisorJob()+Main)` collects `CaptureState.recording` → `render()`; cancelled in `onStopListening`.
State `STATE_ACTIVE` while recording, else `STATE_INACTIVE`. Subtitle: paused `"Paused · tap to save"`; recording
`"Recording · tap to save"`; else `BrainWidget.describe(latest)` or `"Tap to talk"`.
`onClick`: recording → `send(ACTION_STOP)`; else (via `unlockAndRun` when locked) open `CaptureActivity.intent(primary, start=true)`
with `startActivityAndCollapse(PendingIntent.getActivity(21, …, IMMUTABLE or UPDATE_CURRENT))` on API ≥ 34, the deprecated
intent overload below.

### 6.4 Launcher shortcuts (`Shortcuts` in `ui/TypesSettings.kt`)

`update(ctx)` (called on Home start; wrapped in `runCatching`): types with non-blank names; `max = min(maxShortcutCountPerActivity, 4)`;
dynamic list = first `max-1` types (rank 0..) + Photo (rank `max-1`). Pinned shortcuts whose type still exists are
updated; pinned `type-*` ids of deleted types → `disableShortcuts(ids, "This capture type was deleted")`.

| Shortcut | id | short / long label | intent |
|---|---|---|---|
| Type | `type-<typeId>` | name (or `"Talk"`) / `"<name>: start talking"` | `CaptureActivity.intent(type, start=true)` with action `ACTION_VIEW` |
| Photo | `photo` | `"Photo"` / `"Photo: take a picture"` | `PhotoActivity.intent` |

Icons: adaptive bitmap 216×216 px filled `Amber` (private: `Raised`), glyph `typeIcon` 72 px centred, tinted `OnAmber`
(private: `TextStrong`). `pin(p)` / `pinPhoto()` → `requestPinShortcut`; unsupported → Toast
`"Your home screen doesn't accept pinned icons"`.

### 6.5 `QuickRecordActivity` ("Talk" launcher icon)

No UI. `onCreate`: `recording = CaptureState.isRecording`; if recording → `send(ACTION_STOP)`; start
`CaptureActivity.intent(Profiles.primary(ctx).id, start = !recording)`; `finish()`. (Second tap stops and shows the note.)

---

## 7. Concurrency

| Scope / lock | Where | Dispatcher | Lifetime | Used for |
|---|---|---|---|---|
| `CaptureService.scope` | service | `SupervisorJob + Default` (recording job on `IO`) | service; cancelled in `onDestroy` | notification collector, recording loop, processing queue (pipeline + `CleanupStep.runPending`) |
| `procLock` (`synchronized`) | service | – | – | `processingActive` flag (single queue) |
| `@Volatile` flags | service | – | – | `stopRequested`, `cancelRequested`, `pauseRequested` read by the record loop |
| `GemmaHolder.scope` + `Mutex lock` | object | `SupervisorJob + IO` | process | warm-load, idle unload; lock serialises load/generate/import/remove/unload |
| `GemmaHolder.background {}` | thread priority | – | per call | Whisper and Gemma run at `THREAD_PRIORITY_BACKGROUND` |
| `AppScope` | `share/Shares.kt` | `SupervisorJob + IO` | process | share stage/save, `resumePending`, photo/attach saves, location, `TextCapture.cleanLater`, `NoteEdits` re-clean, type re-clean from the chip, `SwipeDeletes`, `Undo` expiry |
| `Shares.lock` (`Mutex`) | object | – | – | one `Shares.save` at a time |
| `BrainWidget.scope` | companion | `SupervisorJob + IO` | process | widget/tile refresh |
| `BrainTile` scope | per listening | `SupervisorJob + Main` | `onStartListening`→`onStopListening` | tile rendering |
| `CaptureDb.get` | double-checked `synchronized` | – | process | Room singleton `captures.db` |
| `Undo` | `synchronized(this)` | – | – | last-action slot |
| `SwipeDeletes.jobs` | `synchronized(jobs)` | – | – | pending delete jobs |
| `Notes.cache` | `ConcurrentHashMap` | – | process | card note text |
| `rememberCoroutineScope` | screens | Main | composition | snackbars, saves from Type/Paste (`TextCapture` switches to IO), edits, settings imports |

Pipeline work runs as suspend code on `Default` inside the service scope; blocking ONNX/encoder work happens on that thread.
`CleanupStep.runPending`, `reclean`, `cleanNow`, `NoteEdits`, `TextCapture` and `Shares` switch to `Dispatchers.IO` via
`withContext`. UI tickers: elapsed time 250 ms, share panel timer 200 ms, waveform 70 ms, type-out 16 ms, highlight 500 ms.
`Dispatchers.Main` is used to hand picker/photo results back to Compose (`withContext(Dispatchers.Main)`).

App-wide vs per screen: everything that must survive a closed screen (recording, transcription, cleanup, share/photo/attach
saving, swipe deletes, re-clean) lives in the service scope, `GemmaHolder.scope` or `AppScope`; screens only start work
and observe `CaptureState`, `CleanupStep` flows and Room flows.

---

## Could not determine

- Exact library versions (ONNX Runtime, onnxruntime-extensions, LiteRT-LM, Compose BOM): in Gradle files, part A.
- The ORT intra-op thread count: not set in code, so ORT defaults apply.
- LiteRT-LM runtime behaviour beyond the config passed (max tokens, context length): not set in code; library defaults.
- `typeIcon` mapping and `Undo`/`Notes.delete`/`deleteAddition` internals: read only in part where needed; part A covers undo.
- `NoteCards.kt` card internals (`ProgressCard`, share sheet) and `SettingsScreen`/`TypesSettings` editor details beyond
  what is cited: not specified here (user-visible behaviour is in `FEATURES.md`).
- `ACTION_TOGGLE` has no sender in the current code; its purpose is presumably legacy.
