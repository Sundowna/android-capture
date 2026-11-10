# Share integration (done 2026-11-10)

- Brain Capture is a target in Android's Share menu (SEND, SEND_MULTIPLE).
- Links → `Inbox/Links`, text → `Inbox/Text`, files → `Inbox/Attachments` + note in `Inbox/Documents`.
- Content is staged in app storage first; failed or interrupted shares are retried automatically.
- Same link or file within 24 h → "Already saved" with Open note / Save again.
- File notes start with "Shared from <app> on <date> — <what>"; cards show thumbnails (images, PDF first page), file lines, source app and folder.
- Checked by the user on the phone: Firefox links, selected text, PDFs, photos, duplicates. All good.
