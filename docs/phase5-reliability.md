# Phase 5 – reliability (2026-11-10)

| Change | Device check (Pixel 7 Pro) |
|---|---|
| Long recordings are streamed: one loudness pass, then each piece is read on its own | 21-minute recording: 55 pieces, transcribed in 8.4 min (0.40× real time); memory stayed at ~1 GB (mostly the speech model) and did not grow |
| Opus for the vault (setting: Opus or WAV) | 21 min → 5.0 MB Opus instead of 39.4 MB WAV; encoding 27 s |
| Wake lock while recording/transcribing (max. 1 h) | Held during the run, released afterwards |
| Storage: no recording below 50 MB free; warning with minutes left below ~1 h; recording stops and is kept below 20 MB | Not simulated on the device (would need filling the phone); logic is small and covered by review |
| Settings screen (vault, inbox folder, model, language, cards shown, audio format, storage use, clean-up) | Builds; UI check pending in the UX pass |
| Clean-up of leftovers (previews of discarded shares, stale share folders, encoder leftovers, old import files); never touches captures not yet in the vault | Pending in the UX pass |
| Privacy audit, emoji font fetch removed | See privacy-audit.md |

Unit tests: 31 pass (incl. streaming profile/ranges matching the in-memory split).

## UX pass (2026-11-10)

Built from the approved redesign mockup (https://claude.ai/artifact/BGaX1n6EYoVXkw6G35YxBo):

- Recording and processing are independent: a new recording can start while earlier ones are transcribed; recordings queue and are processed in order. Device check: a 2-minute clip and a recording made while it was processing were both saved in order; queue, service and wake lock cleared afterwards.
- Brand theme in light and dark mode; home screen with one big record button that turns into a red stop button with rings, large timer and live level bars; list dims while recording.
- New recordings appear at once as a card with real progress (part x of y, time left from finished parts) and the text filling in part by part; failed ones show "Try again" in the card.
- Saved: card header turns green for a few seconds, plus a short "Saved to your vault · Open in Obsidian" message.
- Cards: type tiles (voice, link, text, file), relative times, link title and site, file previews, long text folded with "Show all", timings only when expanded.
- First-run screen with two steps; share panel shows what was saved and offers "Add a voice comment".
