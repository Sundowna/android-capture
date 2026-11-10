# Privacy and security audit

Date: 2026-11-10 · Build: debug APK, versionName 0.1-poc · Checked from the built APK with `aapt2 dump`.

## Permissions

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Voice capture (runtime permission, asked on first recording) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Recording and transcribing with the screen off |
| `POST_NOTIFICATIONS` | Recording / progress / saved notification |
| `VIBRATE` | Haptic feedback on start and stop |
| `WAKE_LOCK` | CPU stays on while transcribing with the screen off (released when done, max. 1 h) |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by AndroidX; app-internal only |

**No `INTERNET` permission.** Nothing in the app or its libraries can open a network connection. The speech model is downloaded by the user's browser and imported from a local file, with its SHA-256 checked.

`<queries>` for launcher apps is declared only to show readable app names ("Shared from Files"). It is not a permission and grants no data access.

## Libraries

ONNX Runtime + extensions (speech model), AndroidX (Compose, Room, Activity, Lifecycle), Kotlin coroutines. None of them include analytics, crash reporting or ads.

- Removed `EmojiCompatInitializer`, which would otherwise try to fetch emoji fonts through Google services at startup.
- `ProfileInstallReceiver` (AndroidX) is exported but protected by the system `DUMP` permission; only `adb`/the system can call it.

## Entry points other apps can reach

| Component | Exported | What another app could do | Assessment |
|---|---|---|---|
| `MainActivity` | yes (launcher) | Open the app; the `retry` extra only re-runs waiting captures | Harmless |
| `QuickRecordActivity` | yes (launcher icon) | Start/stop a recording | Only from the foreground (Android blocks background activity starts); always visible: panel, notification, GrapheneOS mic indicator. Accepted |
| `ShareActivity` | yes (Share target) | Send text/files to be saved in the vault inbox | Same as any Share target; always visible as a panel; only writes new notes, never reads or changes existing ones. Accepted |
| `CaptureService` | no | — | — |

## Data at rest

| What | Where | Lifetime |
|---|---|---|
| Recording before it is in the vault | app storage `files/staging` | Deleted once audio + note are saved |
| Shared files before they are in the vault | `files/shares/<id>` | Deleted once saved |
| Card previews of shared images/PDFs | `files/thumbs` | Until "Clean up leftovers" removes unused ones |
| Capture database (state, transcript copy, note path) | app database | Kept; the transcript copy is the fallback when the vault is unreadable |
| Speech model | `files/whisper` | Until replaced or the app is uninstalled |

All of this is app-private (other apps cannot read it) and covered by Android's file-based encryption. `allowBackup="false"` keeps it out of device backups (including Seedvault). Uninstalling the app removes it; notes and attachments in the vault are not affected.

## Vault access

Only through the folder the user picked (Storage Access Framework); no broad storage permission. The app creates new files, appends to notes the user chose to add to, and rewrites a note only when the user edits it in the app and the note is unchanged since the editor opened (backup kept until the write is verified).

## Open points

- The build is a **debug** build (`debuggable=true`), signed with the debug key. For daily use, a release build with its own signing key should replace it (planned before phase 6).
