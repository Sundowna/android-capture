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

Run on 2026-11-16. Pixel 7 Pro (GrapheneOS, Syncthing-Fork 2.1.3, Brain Capture 1.3) and PC
"Tower" (Syncthing 2.1.7), both on the home Wi-Fi.

Throwaway share `capture-test`: phone `Documents/Test Obsidian Input/Test Mobile Inputs` with PC
`D:\Syncthing\CaptureTest`, Send & Receive on both sides, `.capture-*.tmp` ignored on both sides.
The first sync copied the 116 existing files (63.9 MB) with 0 errors.

| # | Result | What was seen |
|---|---|---|
| 1 | Pass | 9 s voice note recorded at 09:26:02. Note and `.ogg` were written on the phone at 09:26:17 to 09:26:20 and were on the PC at 09:26:30, about 10 s later, untouched by hand. The `.ogg` is byte-identical on both sides (same MD5). Walter played it on the PC. Played from File Explorer, not in Obsidian: the test folder is not opened as a vault on the PC. |
| 2 | Pass (embeds not viewed in Obsidian) | Photo shared from Gallery at 09:29:44, on the PC at 09:29:56. PDF (3 pages) shared from Amaze at 09:30:24, on the PC at 09:30:37. Both notes landed in `Inbox/Documents`, both files in `Inbox/Attachments`. The SHA-256 of each file on the PC equals the `sha256` in its note, and each `![[...]]` embed names a file that exists. Rendering inside Obsidian on the PC was not looked at, for the same reason as in test 1. |
| 3 | Pass at 1 min 11 s; the 3+ min length was not run | Recording started 09:33:30 and came out at 1 min 11 s (281 KB), not 3+ min. Note and `.ogg` were on the PC at 09:35:07, 13 s after they were written. A watcher looked at the phone folder and the PC folder once a second: no `.capture-*.tmp` was seen on either side. Not repeated at 3+ min, by decision: `VaultWriter.write` creates the temp file only when the finished file is copied into the vault folder, so the temp file lives for the length of that copy, not for the length of the recording. A longer recording only makes the copy slightly longer. The ignore rule covers that case on both sides. |
| 4 | Pass | At 09:32 a line was appended to the 09:26 voice note on the PC. It was in the note file on the phone within 20 s, and Walter saw the new line on the card in Brain Capture. |
| 5 | Pass | At 09:36:07 the 09:29 photo note was moved on the PC from `Inbox/Documents` to a new folder `Inbox/Moved`. The phone had it at the new place by 09:37:09. Walter checked the card: it still shows the note and the pencil opens it. |
| 6 | Pass | At 09:36:07 the 09:30 PDF note was deleted on the PC (the PDF itself was left). The note was gone on the phone by 09:37:09. Walter checked the card and it looked right. Side note: Syncthing waits about 1 min before passing on deletions, and Syncthing-Fork on the phone keeps the removed version in `.stversions` for 14 days (Trashcan versioning). |
| 7 | Pass | Mic+ on the 09:26 voice card at 09:43:15, PC online. The note on the PC had the new block ("**Added 09:43:15**", text and embed) and the new `2026-11-16 09.43.15 Voice.ogg` at 09:44:37, 11 s after the phone wrote them. The line added on the PC in test 4 was still in the note, above the addition. |
| 8 | Pass | Phone in flight mode at 09:49:08. The 09:33 voice note got a line on the PC at 09:49:11 and a typed T+ addition on the phone at 09:50:01. Back online, Syncthing settled at 09:50:22: the phone's version (the newer one) stayed as the note, and the PC's version was kept next to it as `… Okay,.sync-conflict-20261116-095022-GOXCE7H.md`. Both files are on both devices, 0 sync errors, nothing lost. Consequence to know: the PC's line is now only in the conflict copy, so the two have to be merged by hand. Card checked on the phone's screen at 10:07: the 09:33 card shows the real note with "Added 09:50:01 (typed) / Airplane mode", and there is no card for the conflict copy. Syncthing-Fork lists the conflict under the folder ("1 conflict"). |

## Real vault

Run on 2026-11-16, same devices, after the real share was applied. Two Syncthing folders: `brain-read`
(PC vault `Brain`, Send Only, to phone `Documents/Brain`, Receive Only; a `.stignore` lets through the
folders `00 Inbox` to `40 Wiki` and the `.md` files of `30 Sources`) and `brain-mobile` (`00 Inbox/Mobile`
inside it, Send & Receive on both sides, `.capture-*.tmp` ignored). Brain Capture writes to
`Documents/Brain`, inbox folder `00 Inbox/Mobile`. Tests 1, 4 and 8 were repeated.

| # | Result | What was seen |
|---|---|---|
| 1 | Pass | 8 s voice note recorded at 10:27:12. Note and `.ogg` were written on the phone at 10:27:28 and synced to the PC at 10:27:38 (Syncthing log), 10 s later, untouched by hand. The note landed in `00 Inbox\Mobile\Voice`, the `.ogg` in `00 Inbox\Mobile\Attachments`. Both files have the same MD5 on both sides. |
| 4 | Fail as first set up; pass after a settings change | A line appended to the note on the PC at 10:27:58 had not reached the phone after 2 min: Syncthing on the PC did not register the local change (no `LocalChangeDetected` event, database still held the old size). A manual rescan at 10:30:45 delivered it in 2 s. A second edit at 10:31:01 was not picked up either within 90 s, nor a third after pausing and resuming the folder. Fix: `rescanIntervalS` of `brain-mobile` on the PC set from 3600 to 30. A fourth edit at 10:34:45 was then on the phone at 10:35:07 (22 s) with no manual step. The same turned out to be true for `brain-read`: chat notes written into the vault at 10:25 were still missing on the phone at 10:48, and a manual rescan (3 s for the whole folder) sent them. `rescanIntervalS` of `brain-read` on the PC was set from 3600 to 60. A probe file in the throwaway folder at the same time was picked up by the watcher within 25 s, so the PC's file watcher works in general and fails only for the two Brain folders. Cause not found; the two differ from the throwaway folder in being nested in each other and in the include-style ignore list. Phone to PC was not affected. |
| 8 | Pass | Phone in flight mode at 10:40:44. The 10:27 note got a line on the PC at 10:40:47 and a typed T+ addition on the phone at 10:41:07. Back online, Syncthing settled at 10:42:11: the phone's version (the newer one) stayed as the note, and the PC's version was kept next to it as `… short test.sync-conflict-20261116-104211-GOXCE7H.md`. Both files are on both devices with matching MD5, 0 sync errors, nothing lost. The four earlier PC lines are in both files; the offline PC line is only in the conflict copy and has to be merged by hand. While reconnecting, the PC log showed four short "folder is not running" connection drops within 4 s; they cleared by themselves. Card checked on the phone's screen at 10:43: one card for the note, showing the four PC lines and "Added 10:41:07 (typed)", and no card for the conflict copy. |

Obsidian on the phone (checked 2026-11-16 10:47 to 10:50, taps over USB): `Documents/Brain` is open as a vault, 3,544 files in 198 folders. The file search found chat notes by title and one opened with its properties. The wiki page `40 Wiki/Research/GPU for local AI` rendered with callouts and tables, and its link to the chat note `2026-11-03 Find the best GPU for local AI` opened that note. Links to files that are not shared (images, `.json`, `.jsonl`) show as not yet created, as designed.
