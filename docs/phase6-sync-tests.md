# Phase 6: sync testing with Syncthing

Goal: notes and attachments written by Brain Capture reach the PC vault intact, and changes
made on the PC show up on the phone's cards, without data loss or stray files.

Setup: Syncthing(-Fork) on the phone shares the vault folder (or the folder Brain Capture writes
to) with the PC. Obsidian is open on the PC.

## Recommended `.stignore` entry

Brain Capture writes each file as a hidden `.capture-<id>.tmp` first and renames it when it is
complete. Syncthing waits about 10 s after a change before scanning, so a temp file is normally
gone before then; for very long recordings or large shares it might be caught mid-write. Add:

```
.capture-*.tmp
```

## Tests

| # | What to do | Expected |
|---|---|---|
| 1 | Record a short voice note on the phone | Note and `.ogg` appear on the PC within ~1 min; audio plays in Obsidian |
| 2 | Share a photo and a PDF to Brain Capture | Note and files arrive; embeds render on the PC |
| 3 | Record a 3+ min note | No `.capture-*.tmp` file ever shows up on the PC |
| 4 | Edit a captured note on the PC, wait for sync | The phone's card shows the edited text |
| 5 | Move a captured note to another folder on the PC | Card still shows it; pencil opens it at the new place |
| 6 | Delete a captured note on the PC | Card says the note is no longer in the vault and shows the app's copy |
| 7 | Mic+ on a card (append), with the PC online | Addition and new audio arrive on the PC |
| 8 | Conflict: phone offline (flight mode), edit note on the PC, append on the phone with T+, go online | Syncthing keeps both versions (one as `.sync-conflict-…`); nothing is lost; card follows the real note, not the conflict copy |

## Results

(filled in during testing)
