# Phase 1 test plan (Pixel 7 Pro, GrapheneOS)

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug build, arm64 only). Build with `./gradlew assembleDebug`.

## Setup
1. Install the APK (adb, or copy to the phone and open it).
2. Download `whisper_small_int8.zip` (243 MB) from Hugging Face `DocWolle/whisperOnnx` on the phone or PC, and put it on the phone.
3. Open **Brain Capture** → *Import ZIP* → pick the file. Watch the progress bar until it shows "Installed".
4. *Choose* vault folder → pick the vault's root folder → allow access.
5. Pick the language (Deutsch for the first test).

## Go/no-go: speed and quality
| # | Test | Record |
|---|---|---|
| 1 | Record ~20 s, stop | Transcript correct? Time for load, transcription, and real-time factor shown on the card |
| 2 | Record the same text in Whisper+ | Whisper+ time (stopwatch) vs. ours |
| 3 | Record ~2 min | Number of parts, any words cut at the joins, total time |

**Pass:** quality equal to Whisper+, and transcription time within ~1.5× of Whisper+.

## Platform checks
| # | Test | Expected |
|---|---|---|
| 4 | Drag the red **Record** icon to the home screen. Tap it once, speak, tap again | Recording starts with no screen; notification with timer; second tap stops |
| 5 | Lock the phone while recording, then stop from the notification | Recording continues; transcription runs while locked |
| 6 | Check the vault in Obsidian | `Inbox/Voice/<date> Voice – <words>.md` and `Inbox/Attachments/<date> Voice.wav`; audio plays in Obsidian; no `.capture-*.tmp` left |
| 7 | Record twice within the same minute | Second files get a " 2" suffix; nothing overwritten |
| 8 | Turn the microphone off in GrapheneOS Quick Settings, record | Note what happens (expected: silence / empty transcript; phase 2 will detect this) |
| 9 | Revoke the vault folder access (Settings → Apps → Brain Capture → or pick another folder), record | "Vault access was revoked. Recording kept." and the recording appears under *Recordings waiting* with Retry |
| 10 | Kill the app from Recents while transcribing | Recording appears under *Recordings waiting*; Retry works |

## Logs (with adb)
```bash
adb logcat -s VoicePipeline CaptureService
```
The `Benchmark:` line gives exact timings.

## Results

### 2026-11-10, run 1 (Pixel 7 Pro, Android 17 / SDK 37)
- Model: Whisper+'s files copied with adb (same sizes); ZIP import not tested yet.
- Test 1 (spoken English, language "auto"): 31.4 s audio, model load 0.83 s, transcription 7.8 s for 2 parts, **0.25× real time**. Transcript accurate, punctuation correct, no visible damage at the join between the parts.
- Test 6: WAV (1 004 844 bytes, valid header) in `Inbox/Attachments`, note in `Inbox/Voice`. The temp-file rename worked through SAF; no `.capture-*.tmp` left behind. Staging emptied.
- Still open: Whisper+ timing for comparison, tests 3–5 and 7–10.
