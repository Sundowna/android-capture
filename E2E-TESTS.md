# End-to-end tests (full run on a real phone)

For any session, coding agent or person. A full run checks every feature in [FEATURES.md](FEATURES.md) along every path in [FLOWS.md](FLOWS.md), on the phone, from tap to the finished note in the vault. Each test names the flow (F01–F34) and the FEATURES section it covers; the coverage table at the end shows nothing is left out.

Rule: a change that adds or changes a feature or flow adds or updates its test here in the same change.

## 1. Before you start

**Phone and builds**
- A real Android phone (Pixel, Android 14+; Android 16 for the Live Update chip). Emulators can't test the mic, camera or widgets properly.
- The build under test installed: `./gradlew.bat installDebug` (playground = "Brain Play", main = "Brain Capture"). Before installing, check that nothing is recording on the phone.
- Obsidian on the phone, the vault folder synced (Syncthing) so vault files can be checked on the PC too.
- Whisper speech model installed. For the AI tests, a Gemma `.litertlm` model; for "no AI" tests, run them in a second install without the model, or note the result before importing it.

**Test data and house rules**
- Every test note's spoken or typed text starts with **"E2E test"** plus the test ID, e.g. "E2E test T03-2 raised beds". This makes them easy to find and clean up.
- Never edit, move or delete the owner's own notes. Use only notes you created.
- Clipboard: put test text or a link (e.g. `https://example.com/e2e`) in the clipboard before Paste and Link tests.
- Have one short test photo and one small PDF in Downloads for share and Attach tests.

**Tools (from the PC)**
- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` (in Git Bash prefix path commands with `MSYS_NO_PATHCONV=1`).
- Screenshot: `adb exec-out screencap -p > shot.png`. Text on screen: `adb shell uiautomator dump /sdcard/ui.xml` then `adb pull /sdcard/ui.xml`.
- Tap/type: `adb shell input tap X Y`, `adb shell input text "E2E%stest"`.
- App data: `adb shell run-as org.brain.capture.play ls files/staging` (debug builds only; use `org.brain.capture` for main).
- Kill the app: `adb shell am force-stop <app id>`. Screen off: `adb shell input keyevent 26`.

**States to vary** (run the marked tests in each)
| State | How |
|---|---|
| Open / Private zone | Switch top right on the note screen |
| AI model on / off | Settings → Transcription → Import / Remove model |
| Type with / without AI | Idea vs Exact words |
| Language | Auto / Deutsch / English |
| Screen off, app killed | Power key; `am force-stop` |
| Vault missing | Rename the vault folder on the phone, then rename it back |

**Recording results:** for each test write `PASS`, `FAIL: <what happened>` or `SKIP: <why>`, plus a screenshot for anything visual that failed.

## 2. First run and Home

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T01-1 | F12 | Fresh install (or clear app data). Open the app. | First-run screen: "Capture anything into your vault", step 1 "Your vault", step 2 "Speech model", privacy line. Home not reachable yet. |
| T01-2 | F12 | Choose the vault folder; Download in browser; Import the downloaded file. | Steps get a check; "Installing… N %" counts up; then Home shows. Wrong file → clear error, nothing installed. |
| T01-3 | F01 | Look at Home with no notes. | "Brain", no Undo pill, Settings icon; big amber mic; "Tap to talk · nothing to decide now"; Type · Paste · Photo · Attach; the empty text. |
| T01-4 | F01 | Make 7 notes (any kind). | "RECENT", 5 cards newest first, "Show 2 more" loads the rest. Setting 10/20 changes the page size. |
| T01-5 | F01 | Edit a test note in Obsidian, switch back to Brain. | The card shows the Obsidian edit. Moving/renaming the note in Obsidian: card still found. Deleting it there: red "no longer in the vault" line. |
| T01-6 | F01 | Fling the list. | Moves noticeably further than a normal list (1.6×). |
| T01-7 | F01 | Start recording, scroll the list so the big button leaves. | Hero shrinks/fades; recording bar slides down with level lines and timer; tap opens the note; amber square stops it. |
| T01-8 | F01 | While recording, close and reopen the app. | Opens straight on the recording's note screen. |
| T01-9 | F01 | Deny the mic permission, tap the mic. | Error banner with OK; nothing saved. |

## 3. Recording and the note screen

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T03-1 | F03 | Tap the big mic, say "E2E test T03-1 …" for 10 s, tap stop. | Glides into the note screen; heartbeat halo; "Listening…"; timer; waveform amber. Stop → exact words type out letter by letter, then glide to Clean ~1 s after the last letter (AI model on). Vibration on start and stop. |
| T03-2 | F03 | Record 90 s with pauses. | "Slicing what you said into 30-second bites…" between pieces; progress "part x of y · about N left"; text scrolls to the newest words. |
| T03-3 | F03 | Pause 10 s, Resume, stop. | "Paused" label; heartbeat still; paused time not in the timer or audio length. |
| T03-4 | F13 | Record 5 s, tap Discard. | Gone at once, no note, no file in staging; screen resets. |
| T03-5 | F13 | Record 25 s, tap Discard. | Dialog "Discard this recording?" — Keep recording continues; Discard throws it away. |
| T03-6 | F03 | Record with the screen off for 60 s (power key), then stop from the notification. | Recording continues; notification Stop saves it; note appears. |
| T03-7 | F03 | Record, `am force-stop` the app, open it again. | The leftover recording is processed and becomes a note (nothing lost, no duplicate). |
| T03-8 | F03 | Record while an earlier recording is still transcribing. | Both saved, oldest first; progress card says "Queued · starts when the one before is done". |
| T03-9 | F02 | On a finished open note tap the big button, say an addition. | "Adding to this note"; the note gets "Added HH:mm"; vault note has "**Added HH:mm:ss**" with audio embed at the end. |
| T03-10 | F02 | Turn the mic off in Quick Settings, record 10 s. | Warning "No sound was recorded. Is the microphone turned off…"; partly muted → "The microphone was off for part of this recording." |
| T03-11 | F02 | Record 10 s of silence. | "No speech recognised"; no invented text. |
| T03-12 | F02 | Open a voice note; switch tabs Exactly what I said / Clean; reopen. | Chosen tab sticks. Long-press a clean line: exact words in amber quotes. |
| T03-13 | F02 | Footer and controls on a finished note. | Footer lines per kind; "Filed to <folder> · open in Obsidian" opens it; "Talk to add to this note · New note"; Undo on the right. |
| T03-14 | F02 | While note A records, open note B. | "Recording another note · Go to note" jumps to A. |
| T03-15 | F02 | Undo on the note screen after a new note / an addition / a re-clean. | New note → vault .trash; addition section removed; previous clean version back. |
| T03-16 | F03 | Fill the phone to under ~1 h of recording space (or use a test build), record. | Warning with minutes left; below 50 MB refuses to start; below 20 MB stops and keeps the recording with a warning. |

## 4. Type, Paste, Edit

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T04-1 | F04 | Home → Type, type "E2E test T04-1", Save, no AI. | Keyboard opens; "Goes to your inbox as <Type>"; saved as typed in `<inbox>/Text`; note shows "Typed note" title and type chip. |
| T04-2 | F04 | Type with AI model on → "Save with AI". | Cleaned in the background with the note's type. Without the model there is one "Save" button. |
| T04-3 | F04 | Card T+ on an open note, type, save. | "Type to add to this note"; vault gets "**Added HH:mm:ss (typed)**"; Undo works. |
| T04-4 | F06 | Copy text, Home → Paste, save. | Field pre-filled; type Exact words; saved as is. Empty clipboard → "The clipboard is empty." Paste tapped again → back to the note. |
| T04-5 | F06 | Paste a link. | Link note in `<inbox>/Links`, never AI-cleaned. |
| T04-6 | F05 | Tap any text on a finished note (clean, exact words, typed). | Edit opens with the right header; keyboard in the tapped section; Save disabled until something changes; cursor line stays visible while typing long text; thin scrollbar. |
| T04-7 | F05 | Correct exact words, Save. | View switches to Clean; a new clean version is made from the corrected words. |
| T04-8 | F05 | Edit the clean version, Save. | Lines keep their sources; marked "edited by hand". |
| T04-9 | F05 | Long-press a card's text on Home. | Note opens in Edit mode. System Back leaves Edit first. |
| T04-10 | F18 | Private zone: type a note, save. | Stays in the app only (no vault file); "Kept on this phone only"; same for a custom private type. |

## 5. Photo, Attach, Share

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T05-1 | F08→F07 | Home → Photo, take a photo. | Phone camera opens; "Saving photo…"; photo note opens ("Talk to add to this note"); file in `<inbox>/Attachments`, note in `/Documents`, source "Camera". Back out of the camera → "No photo taken". |
| T05-2 | F07 | On a finished open note → Photo. | Photo added to that note. Disabled while recording and in Private. |
| T05-3 | F16 | Attach → Gallery (2 images). | Sheet "New note from…"; both saved; thumbnails scroll sideways; source "Gallery". |
| T05-4 | F16 | Attach → Files & audio (PDF + audio file). | Saved; PDF first-page thumbnail and page count. |
| T05-5 | F16 | Attach → Contact. | "Contact: <name> · <number>" text note; no contacts permission asked. |
| T05-6 | F16 | Attach → Link with a link in the clipboard. | "Add a link" pre-filled; Add saves a link note with no "from …". |
| T05-7 | F16 | Attach → Location (first time deny, then allow). | Deny → "No location access, so nothing was added". Allow → "Location at HH:mm: https://maps.google.com/?q=…". Location off → "Could not find where you are…". |
| T05-8 | F16 | Open each Attach tile and back out. | "Nothing picked" / "No contact picked". |
| T05-9 | F09→F10 | Share a link from Firefox to Brain. | Panel over dimmed backdrop; "Save to" picker with search; New note → "Link saved", card, closes after 3.5 s. Source "Firefox". |
| T05-10 | F09 | Share text, an image, a PDF, several files; pick an existing note for one. | Right "Saving …" labels and progress; "Added to note" for the picked note. |
| T05-11 | F10 | After a share, "Add a voice comment", talk, Stop. | "Recording your comment", timer, level bar; then "Your comment will be added to this note."; addition in the vault. |
| T05-12 | F27 | Share the same link again within 24 h. | "Already saved at HH:mm" — Open note opens the earlier note; Save again saves a new one; closing saves nothing. |
| T05-13 | F09 | Close the panel while the picker is open. | Saved as a new note anyway. |
| T05-14 | F09 | Share with the vault missing. | "⚠ Not saved yet … will be saved automatically later"; after restoring the vault and opening the app it is saved. |
| T05-15 | F11 | Share a photo taken with the widget/1x1/shortcut Photo. | Camera, then the share panel with source "Camera". |

## 6. Home cards, delete, undo, types

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T06-1 | F01 | Check each card kind (voice, link, text, file, private, just saved). | Right tile icon and header line; long text folds at 4 lines with Show all/less; voice card timings when expanded. |
| T06-2 | F01 | Card actions: Obsidian, Share (with and without voice setting), Copy. | Obsidian opens the note; share sheet without Brain, audio per setting; Copy → green check 1.5 s, only note text copied. Private card: no Obsidian, T+, mic+. |
| T06-3 | F15 | Bin icon → Delete. | Dialog explains .trash; note, its audio and files move to the vault's `.trash`. |
| T06-4 | F15 | Swipe a card left halfway and let go; then past the line. | Short of 45 % springs back. Past it: red strip, bin grows, haptic tick; snackbar with Undo for 5 s; Undo restores. |
| T06-5 | F15 | Delete, then Home Undo pill. | "Undo delete" pill; restores the note (another name if taken). Private delete: deleted for good after Undo lapses. |
| T06-6 | F14 | Voice note → type chip → "Clean again as…" Task. | Whole note re-cleaned as a checklist; `capture_type` updated; Undo brings the old type and clean version back. Chip dimmed while recording/cleaning/no model. |
| T06-7 | F02 | Type chip on a new recording: change type while recording. | Note goes to the new type. |
| T06-8 | F17 | Force a failure (vault missing) during a voice save. | Progress card red border, "The recording is safe on the phone.", Try again saves it once the vault is back. |
| T06-9 | F01 | Home Undo after: new note, addition, re-clean. | Right label and result message; lapses after 10 min or app close. |

## 7. AI cleanup checks (model on)

| ID | Steps | Expected |
|---|---|---|
| T07-1 | Record an Idea note with clear points. | Bullets with [n] sources in the vault; "> [!quote]- Exactly what I said" block; `cleanup: <model>` in frontmatter; title ≤ 8 words. |
| T07-2 | Task type: "Call the plumber on Friday". | Checklist with "(due YYYY-MM-DD)". |
| T07-3 | Fix / Feature type with "should …" ideas not said. | Unsaid "; should …" part dropped; Fix/Feature lines get task boxes. |
| T07-4 | Exact words type. | Model not loaded; note keeps exact words; "Exact words, as you chose…". |
| T07-5 | Edit the vault note in Obsidian before cleanup finishes. | Vault note left alone ("note was changed"). |
| T07-6 | Remove the model (Settings), record. | "No cleanup model on this phone yet."; Type has one "Save" button. |
| T07-7 | Settings → Transcription → Import model file (a non-.litertlm file, then a real one). | Wrong file → plain error. Real file → % bar, name and size shown; Replace and Remove model (asks first). |

## 8. Widgets, tile, shortcuts

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T08-1 | F11 | Add the Brain widget (4x3). | "Brain · Tap to talk"; up to 4 buttons, first amber; Open Brain on; last line "No captures yet" / last capture. |
| T08-2 | F11 | Tap a type button on the widget. | Note screen opens recording with that type. |
| T08-3 | F22 | Long-press widget → Edit widget: switch types, Photo, Open Brain. | Widget updates; turning one on when full takes the last type off. |
| T08-4 | F11 | Add a 1x1 button widget (a type, then Photo). | Type chosen when placed; tap starts talking / opens the camera; follows renames. |
| T08-5 | F11 | Talk launcher icon: tap, talk, tap again. | First tap records with the default type; second tap stops and opens the note. |
| T08-6 | F11 | Quick settings tile: tap (also on the lock screen), tap again. | Unlocks, records; subtitle "Recording · tap to save"; second tap saves. |
| T08-7 | F11 | App-icon long-press menu; pinned one-tap icons. | First 3 types + Photo; pinned icon follows renames; deleted type → "This capture type was deleted". |
| T08-8 | F29 | From the phone's home screen try every Brain entry: app icon, Talk, 4×3 widget buttons, 1×1 type and Photo buttons, pinned type and Photo icons. | Each opens what F29 says: Home, recording with the right type (default for Talk), or camera then the share panel. |
| T08-9 | F32 | Place a new Brain widget; on the setup screen switch Task off and Photo on; Done. Then Back without changes on Edit widget. | Widget shows the new buttons; Back keeps the widget unchanged. |
| T08-10 | F33 | Place a "Brain button" 1×1 widget, pick Private; place another, pick Photo. Rename the Private type in Settings; remove one widget. | Private button grey, Photo with camera icon; rename shows on the button; a removed widget's choice is forgotten (placing again asks). |
| T08-11 | F30 | Add the Brain tile (edit tiles). Lock the phone, tap the tile; unlock; talk; tap the tile again. Pause from the note screen and look at the tile. | Asks to unlock, then records with the default type; subtitle "Recording · tap to save" / "Paused · tap to save"; second tap saves. |
| T08-12 | F34 | Pin a type icon (type editor) and the Photo icon (Capture types); delete that type. | Android's add dialog; icons work; after deleting the type its icon is disabled with "This capture type was deleted". |

## 9. Settings

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T09-1 | F19 | Open Settings. | Menu rows with current values; Back on a page → menu; Back on the menu → leaves. |
| T09-2 | F20 | Capture types: change default, move a type up, delete one and Undo. | Default used by mic/Type/Talk/tile; "Deleted <name> · Undo" row for 8 s puts it back in place. Last type can't be deleted. |
| T09-3 | F21 | New type: name, description, icon, AI off, then on with format; Save. Edit, change, Back. | Save needs a name; AI off hides instruction and format; "Discard your changes?" on Back. Reset to original for edited built-ins. |
| T09-4 | F21 | New private type. | Blue private line; notes of it never reach the vault. |
| T09-5 | F23 | Vault page: change inbox folder name. | New notes go to the new inbox; blank is ignored. |
| T09-6 | F24 | Transcription: switch language Auto/Deutsch/English, record German and English. | Correct language transcribed. |
| T09-7 | F25 | Audio & display: WAV vs Opus; notes on home 5/10/20; share with/without voice. | Vault audio `.wav` / `.ogg`; Home page size; Share includes audio or not. |
| T09-8 | F26 | Storage: Clean up leftovers. | Sizes shown; "Freed N" or "Nothing to clean up."; waiting items never deleted. About & privacy text. |

## 10. Notifications and background

| ID | Flow | Steps | Expected |
|---|---|---|---|
| T10-1 | F28 | Record and watch the shade. | "Recording" with timer, Stop and Discard; Android 16: status-bar chip with timer ("Paused" when paused). |
| T10-2 | F28 | After stop. | "Transcribing" with "Part x of y · about N left" and progress; "✓ Saved" disappears after 4 s. |
| T10-3 | F28 | Fail with the vault missing. | "⚠ …" with "Recording kept. Tap Retry."; Retry (after restoring) saves it. |
| T10-4 | — | Lock the phone during a long transcription. | Finishes; wake lock released (no battery warning). |

## 11. Vault output and privacy

| ID | Steps | Expected |
|---|---|---|
| T11-1 | Look at a voice note, a link, a text, a file note and an addition in the vault. | File names "yyyy-MM-dd HH.mm.ss <Kind> – <words>" (≤ 50 chars, " 2" on clashes); frontmatter fields as in FEATURES §12; no leftover `.capture-*.tmp`. |
| T11-2 | Delete the target note in Obsidian, then record an addition to it. | New note "Addition to [[…]]". |
| T11-3 | Rename the vault folder, try to save, rename back. | "Vault folder not available… Choose it again"; nothing lost; saved later. |
| T11-4 | Make private notes (voice, typed). | Nothing in the vault, not in the share picker, no Photo/Attach/T+/mic+/Obsidian on them. |
| T11-5 | `adb shell dumpsys package <app id> \| grep permission` | No INTERNET; only the permissions in FEATURES §16. |
| T11-6 | `./gradlew.bat lintDebug testDebugUnitTest` | Lint clean; all unit tests pass. |
| T11-7 | Android backup: `adb shell bmgr backupnow <app id>` | Nothing backed up (allowBackup false, extraction rules exclude all). |

## 12. After the run

- Delete every "E2E test" note from the app (moves them to the vault's `.trash`), then empty only those from `.trash`. Remove test types, widgets and pinned icons you added; set the default type and settings back.
- Report: totals (pass / fail / skip), each FAIL with screenshot and steps, and which states (§1) were covered.

## 13. Coverage

| FEATURES section | Tests |
|---|---|
| 1 Home | T01-1…9, T06-1, T06-9 |
| 2 Note screen | T03-1…15, T04-6…9, T06-6, T06-7 |
| 3 Recording | T03-1…11, T03-16, T10-4 |
| 4 Type and Paste | T04-1…5, T04-10 |
| 5 Photo and Attach | T05-1…8 |
| 6 Note cards | T06-1…5, T06-8 |
| 7 Capture types | T06-6, T09-2…4, T07-4 |
| 8 Widgets and shortcuts | T08-1…12, T05-15 |
| 9 Share | T05-9…14 |
| 10 AI cleanup | T07-1…7, T04-7, T04-8, T06-6 |
| 11 Transcription | T03-2, T03-10, T03-11, T09-6, T01-2 |
| 12 Vault export | T11-1…3, T01-5 |
| 13 Settings | T09-1…8, T07-7 |
| 14 Storage, trash, undo | T06-3…5, T06-9, T03-15, T09-8 |
| 15 Notifications | T10-1…4, T03-6 |
| 16 Privacy | T11-4…7, T04-10 |
| 17 Build differences | Check the app name and id on the installed build |

| Flow | Tests |
|---|---|
| F01 Home | T01-3…9, T06-1, T06-2, T06-9 |
| F02 Note screen | T03-9…15, T06-7 |
| F03 Recording | T03-1…8, T03-16 |
| F04 Type | T04-1…3 |
| F05 Edit | T04-6…9 |
| F06 Paste | T04-4, T04-5 |
| F07 Photo note | T05-1, T05-2 |
| F08 Phone camera | T05-1 |
| F09 Save to | T05-9, T05-10, T05-13, T05-14 |
| F10 Saved + voice comment | T05-9, T05-11 |
| F11 Widget, icons, tile | T08-1, T08-2, T08-4…7, T05-15 |
| F29 Phone home screen | T08-8 |
| F30 Quick settings tile | T08-6, T08-11 |
| F31 App-icon menu | T08-7 |
| F32 Widget setup | T08-3, T08-9 |
| F33 One-tap button | T08-4, T08-10 |
| F34 Pin a one-tap icon | T08-7, T08-12 |
| F12 First run | T01-1, T01-2 |
| F13 Discard | T03-4, T03-5 |
| F14 Clean again | T06-6 |
| F15 Delete + Undo | T06-3…5 |
| F16 Attach | T05-3…8 |
| F17 Messages and errors | T06-8, T03-10, T05-14 |
| F18 Private note | T04-10, T11-4, T09-4 |
| F19 Settings menu | T09-1 |
| F20 Capture types | T09-2 |
| F21 Type editor | T09-3, T09-4 |
| F22 Widget page | T08-3 |
| F23 Vault | T09-5 |
| F24 Transcription | T09-6, T07-7 |
| F25 Audio & display | T09-7 |
| F26 Storage and About | T09-8 |
| F27 Already saved | T05-12 |
| F28 Notification | T10-1…3 |
