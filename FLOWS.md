# Flows (how the user moves through Brain)

For any session or coding agent. Every user path through the app, step by step: where it starts, what each step shows, and where it ends. Read it with [FEATURES.md](FEATURES.md), which lists every single feature; this file shows how they connect.

The same flows are drawn as screens on the design canvas "Brain Camera button" (claude.ai design artifact): the artboard named in each heading, for example `F03`. Rows on the canvas: in-app capture (F01–F07), photo and outside the app (F08–F11), other in-app flows (F12–F18), settings (F19–F26), share and background (F27–F28).

Each screen is also saved as a picture in `docs/screens/` (`F03-Recording.png` …). End-to-end tests for every flow: [E2E-TESTS.md](E2E-TESTS.md).

Rule: a change that adds, removes or reorders a step updates, in the same change, this file, the matching artboard, its picture in `docs/screens/` (render the artboard at 390×844) and its tests in E2E-TESTS.md.

Vault copy: after every commit a Git hook copies FEATURES.md, FLOWS.md, E2E-TESTS.md and the screens into the owner's Obsidian vault (Android Voice App → Documentation) and rebuilds two Obsidian canvases: "Brain Capture Flows" (pictures + steps, from this file) and "Brain Capture Wireframes" (every screen drawn with coloured canvas cards, arrows from the tapped control to the screen it opens, from `tools/wireframes.py`). A changed screen or flow also updates its wireframe in `tools/wireframes.py`. Never edit the vault copy; edit here. New computer: `sh tools/install-git-hooks.sh` once; by hand: `python tools/sync-vault-docs.py`.

Words used: **Home** = the app's main screen. **Note screen** = the screen that shows one note (also used for recording, typing, pasting, editing). **Vault** = the Obsidian folder chosen in setup; new notes go to its inbox folder (`<inbox>/Voice`, `/Text`, `/Links`, `/Documents`, files in `/Attachments`).

## Map

```
First run (F12) ──► Home (F01)
Home ── tap a card ───────────────► Note screen (F02)
     ── big mic / card mic+ ──────► Recording (F03) ─ stop ─► Note (F02)
     ── Type / card T+ ───────────► Type (F04) ─ save ─► Note (F02)
     ── Paste ────────────────────► Paste (F06, type Exact words) ─ save ─► Note (F02)
     ── Photo ────────────────────► Phone camera (F08) ─► Photo note (F07)
     ── Attach ───────────────────► Attach sheet ─► Gallery / Camera / Files & audio / Contact / Link (F16) / Location ─► Note (F02)
     ── hold a card's text ───────► Edit (F05)
     ── bin / swipe left ─────────► Delete + Undo (F15)
     ── Settings icon ────────────► Settings menu (F19) ─► F20–F26
Note screen ── tap any text ──────► Edit (F05)
            ── type chip ▾ ───────► Clean again as… (F14)
            ── Open / Private ────► Private note (F18)
            ── Discard (recording)► Discard confirm (F13)
Outside the app:
Widget / Talk icon / tile / app-icon menu (F11) ─► Recording (F03)
Widget Photo / Photo icon (F11) ─► Phone camera (F08) ─► Save to (F09) ─► Saved + voice comment (F10)
Share from any app ─► Save to (F09) or Already saved (F27) ─► Saved (F10)
While recording or transcribing ─► Notification (F28)
```

## In-app capture

### F01 · Home
- Entry: opening the app (after first run). While a recording runs, opening the app jumps straight to that note screen (F02/F03).
- Shows: "Brain", Undo pill (only when there is something to undo), Settings icon; big amber mic with "Tap to talk · nothing to decide now"; row Type · Paste · Photo · Attach; "RECENT" note cards (default 5, "Show N more").
- Card actions: tap card → F02; hold card text → F05; bin → F15; swipe left → F15; Obsidian icon → opens the note in Obsidian; T+ → F04 on that note; mic+ → F03 adding to that note; Share → Android share sheet (voice recording included or not, per Settings → Audio & display); Copy → text copied, icon turns to a green check.
- Just saved: the card gets a green border and "Saved to <folder> · just now" for 5 s.

### F02 · Note screen (tap a card)
- Entry: tap a card, or after any save.
- Shows: Back, Open/Private switch; for voice and photo notes the tabs "Exactly what I said" / "Clean"; for typed, link and file notes a title ("Typed note", "Saved link", "Shared file"); the capture type chip on every note; the note text; footer "Filed to <folder> · open in Obsidian"; waveform; "Talk to add to this note · New note"; controls (time, big button, Undo); bottom row Paste · Photo · Attach.
- From here: tap any text → F05; big button → F03 (adds to this note); type chip → F14; Paste → F06 into this note; Photo → F08 then the photo is added here; Attach → sheet, added here; "New note" → empty note screen; Undo → takes back what was done on this screen; Back → Home.

### F03 · Recording (big mic, mic+, widget, Talk icon, tile)
- Entry: Home big mic (new note, default type); card mic+ or the note's big button (adds to that note); widget type button (that type); Talk icon or quick settings tile (default type).
- First time only: Android asks for the microphone (and notifications on Android 13+); recording starts once allowed.
- Shows: "Listening…" (or "Paused"); live waveform; running time; Discard (left), stop button (middle), Pause/Resume (right). A type chip is shown while a new note records, so the type can still change.
- Stop: tap the big button, the Talk icon again, the tile, the notification's Stop, or Home's big button. The note screen then shows "Transcribing…", the exact words type out, and 1 s later it glides to the Clean version ("Cleaning on this phone…" first). Types without AI cleanup stay on the exact words.
- Discard → F13.
- Leaving the screen does not stop it; Home shows a recording bar at the top once the big button scrolls away.

### F04 · Type (Home Type, card T+)
- Entry: Home Type (new note, default type) or card T+ / note screen while on a note (adds to it).
- Shows: header "Type a thought" or "Type to add to this note"; type chip for a new note; text box with the keyboard (follows the cursor, thin scrollbar); line "Goes to your inbox as <Type>" / "Adds to this note" / "Kept on this phone only"; buttons "Save with AI" (outlined, robot; only when the AI model is installed) and "Save, no AI" (filled amber, disk).
- Save → F02 showing the saved text (with AI: the clean version follows). Back → leaves Type mode first.

### F05 · Edit (tap any text, or hold a card's text)
- Entry: on a finished note, tap its text (clean lines, exact words, typed or pasted text); on Home, hold a card's text. Not while recording, transcribing or cleaning.
- Shows: "Correct your exact words" or "Edit the clean version" (whichever view was on screen); one field per section; help line ("The clean version is made again from your corrected words." / "Your corrected words are saved as they are (no AI cleanup)." / bullet rule); Cancel and Save (enabled once something changed).
- Save → F02; corrected voice words are cleaned again in the background. Cancel or Back → F02 unchanged.

### F06 · Paste
- Entry: Home Paste (new note with type **Exact words**), or Paste on a note screen (into that note).
- Shows: header "Paste from clipboard" / "Paste into this note"; box filled with the clipboard ("The clipboard is empty." if nothing); type chip; the two save buttons as in F04.
- A pasted link becomes a link note in `<inbox>/Links`. Save → F02.

### F07 · Photo note (Photo inside the app)
- Entry: Home Photo or note screen Photo, after the camera (F08).
- From Home: "Saving photo…" then the new photo note opens, with the voice tabs and type chip, ready to talk or type about it. From a note: the photo is added under "Added HH:mm". Undo takes it back.
- Cancelled camera: nothing changes ("No photo taken").

### F08 · Phone camera (not Brain's screen)
- The phone's own camera app (Brain has no camera permission). One photo per tap. OK returns to Brain; ✕ cancels.

## Photo and outside the app

### F09 · Save to (Photo from widget/icon, or Share)
- Entry: widget Photo, 1×1 Photo widget, Photo app-icon shortcut, pinned Photo icon (after F08); or sharing anything to Brain from another app.
- Shows a bottom panel: "Save to", search "Search your notes", "New note · Saved to your inbox" first, then recent notes. Tap one → F10. Closing while picking still saves as a new note. No notes yet → saved as a new note straight away.
- Duplicate within 24 h → F27.

### F10 · Saved + voice comment
- Shows: green check, "Saved" / "Link saved" / "Added to note", folder, a small card; "Add a voice comment".
- Add a voice comment → "Recording your comment", timer, red Stop → "Your comment will be added to this note." → closes. Without it the panel closes by itself after 3.5 s.
- Failure: "⚠ Not saved yet", reason, "It is kept on the phone and will be saved automatically later."

### F11 · Widget, app-icon menu, Talk icon, tile
- Brain widget (4×3): up to 4 buttons (types, then Photo, then Open Brain); each type starts recording (F03) with that type; Photo → F08 → F09; Open Brain → Home. Last line shows the last capture. Long-press → Edit widget (F22).
- 1×1 button widget: one type (or Photo), chosen when placed.
- App-icon long-press menu: the first types (up to 3) plus "Photo: take a picture".
- Talk icon: tap starts recording with the default type; tap again stops it and opens the note.
- Quick settings tile "Brain": tap starts talking (unlocks first on the lock screen); tap again stops and saves.
- Pinned one-tap icons for a type (from the type editor) or Photo (from Capture types).

## Other in-app flows

### F12 · First run
- Entry: first start, or when the vault or speech model is missing.
- Step 1 "Your vault": Choose folder (Android folder picker). Step 2 "Speech model" (243 MB, once): "Download in browser", then "Import downloaded file" (checked against its fingerprint, "Installing… N %"). Each step shows a check when done; then Home (F01).

### F13 · Discard a recording
- Entry: Discard under the time while recording.
- Under 20 s: thrown away at once. From 20 s: "Discard this recording?" — "It will not be transcribed or saved, and can't be recovered." — Keep recording / Discard.
- After discarding a new note the screen resets to an empty note; an addition leaves the note as it was.

### F14 · Clean again as another type
- Entry: the type chip ▾ on a finished note (dimmed while recording, transcribing, cleaning, or without the AI model).
- Shows a list of the zone's types with subtitles ("Clean again as…"); types without AI cleanup are not offered except the note's own.
- Pick one → the view switches to Clean, "Cleaning on this phone…", then the new version; the vault note's clean block is swapped. Undo (note screen or Home pill "Undo re-clean") puts the previous version back.

### F15 · Delete a note and Home Undo
- Bin icon → "Delete this note?" (moves to the vault's `.trash`, or deleted for good for private) → Delete / Cancel.
- Swipe left past the line (45 % width, red strip, bin grows, tick) → card hides, snackbar "Note moved to the vault's .trash · Undo" for 5 s.
- Home Undo pill: one step, last action of any kind ("Undo delete", "Undo new note", "Undo addition", "Undo re-clean"); lapses after 10 minutes or when the app closes; the result shows in a snackbar.

### F16 · Attach (sheet and Link)
- Entry: Attach on Home ("New note from…") or on a note ("Add to this note").
- Tiles: Gallery (photo picker, up to 20), Camera (F08), Location, Contact, Link, Files & audio. Footer: "Saved to your vault's Attachments and added like a share. Undo takes it back."
- Link → dialog "Add a link", filled from the clipboard if it holds a link → Add → link note / added link.
- Location → asks for location the first time → "Location at HH:mm: <Google Maps link>". Refused: "No location access, so nothing was added".
- Contact → Android contact picker → "Contact: <name> · <number>".
- Nothing picked → "Nothing picked".

### F17 · Saving messages and errors (inside the app)
- Home snackbars: "Saving photo…", "Saving…", then the note opens; vault problem: "…kept on the phone and will be saved automatically later".
- Error banner on Home (mic permission missing, storage full, mic could not start) with OK.
- Progress cards on Home for recordings still being transcribed ("Transcribing · part x of y", "Queued…"); a failed one has a red border and "Try again".

### F18 · Private note
- Entry: switch Open → Private on the note screen (a finished note starts a fresh private note; switching back returns to it).
- Private notes stay in the app only (never the vault): "Kept on this phone only". No Photo, Attach, T+, mic+ or Obsidian on them. Delete is for good (Undo holds it for a while).

## Settings

### F19 · Settings menu
- Entry: Home Settings icon. Rows with their current value: Capture types, Widget, Vault, Transcription, Audio & display, Storage, About & privacy. Back on a page → menu; Back on the menu → Home.

### F20 · Capture types
- Default type drop-down; list of types (default amber), "↑" moves up, bin deletes at once with "Deleted <name> · Undo" in its place for 8 s; tap a type → F21; "Add a one-tap Photo icon to the home screen"; "+ New type", "+ New private type".

### F21 · Type editor
- Cancel · title · Save; Name, Short description, Icon grid; "Clean up with AI" switch (off hides the instruction and format); Instruction for the AI ("Reset to the original instruction" for changed built-ins); Format Bullets / Checklist / Text + lists; "Add a one-tap icon to the home screen"; "Delete this type" (with Undo); unsaved changes → "Discard your changes?".

### F22 · Widget page
- "The Brain widget shows up to N buttons"; switch types on/off the widget; "Open Brain button" and "Photo button" switches (turning one on when full takes the last type off). Also opened by long-pressing the widget → Edit widget.

### F23 · Vault
- Vault folder Choose / Change; inbox folder name with the folders below.

### F24 · Transcription and AI model
- Speech model (Replace, or 1. Download / 2. Import); Language Auto / Deutsch / English; AI cleanup model: name and size with Replace and "Remove model" (asks first), or "Import model file" with a % bar.

### F25 · Audio & display
- Audio in the vault: Opus / WAV; Notes on the home screen: 5 / 10 / 20; Sharing a note: With voice recording / Without.

### F26 · Storage and About
- Storage: sizes (speech model, waiting recordings and shares, previews, database); "Clean up leftovers" → "Freed N" or "Nothing to clean up." About & privacy: no internet, no accounts, no tracking.

## Share and background

### F27 · Share: already saved
- Same link or file shared again within 24 h: "Already saved at HH:mm" with "Open note" (opens the earlier one in Obsidian) and "Save again". Closing means don't save.

### F28 · Notification
- While recording: "Recording" / "Paused", timer, Stop and Discard; Android 16+ also a status-bar chip. Then "Transcribing · Part x of y · about N left", "Saving to vault…", "✓ Saved" (gone after 4 s). Failure: "⚠ …", "Recording kept. Tap Retry." with Retry.
