# Phase 2 plan (agreed 2026-11-10)

Production voice capture, plus decisions made while testing phase 1.

1. **Capture database** (Room): one row per capture with its state, `capture_id`, note path, audio path and a fingerprint of what the app last wrote.
2. **Live cards:** transcript cards read the note from the vault, showing the whole body minus frontmatter and audio embeds. Notes that were moved or renamed are found again by `capture_id`. Results are cached.
3. **Short list:** show the last 5 cards and a "Load more" button; the number becomes a setting later.
4. **Audio format:** transcribe from the full-quality staging WAV, then store Opus (`.ogg`, ~24 kbit/s, ~0.18 MB/min, about a tenth of WAV) in the vault.
5. **In-app editing** of the transcript, writing back only if the note is unchanged since the app last wrote it (fingerprint check); otherwise ask the user to edit in Obsidian.
6. **Typed additions** to a note (keyboard button next to mic-plus), using the existing append path.
7. Proper voice-activity detection for splitting long recordings.
8. No duplicate audio when a retry follows a partly saved capture.
9. Unit tests: note builder, file names, chunking, silence filter, history.

## Status (2026-11-10, first pass)

Built and unit-tested (20 tests pass); items 1–9 are in the code. Device results so far:
- 126 s German recording: transcribed in 51 s (6 pieces, split at pauses), Opus file 512 KB instead of 3.9 MB WAV. The phone's Opus encoder runs at about 33 kbit/s even when asked for 24.
- Opus encoding first took 104 s (one packet drained per loop); fixed, now ~4 s.
- Card UI, in-app edit, typed addition (T+ icon) and Obsidian edits showing on the cards: checked by the user, all good.
- **Phase 2 complete.**
