"""Wireframes of every screen (F01-F34) drawn with Obsidian canvas parts, plus the flow arrows.

Each screen is a canvas group (the phone) holding coloured text cards (the controls), and under
the phone a spec card (function, tech, tests). An arrow starts at the control you tap and ends at
the screen it opens. The canvas also carries the colour legend and the spec documents (FEATURES,
FLOWS, SPEC, E2E-TESTS) as embedded file cards. Built into "Brain Capture Wireframes.canvas" by
tools/sync-vault-docs.py. Keep this in step with FLOWS.md: a changed flow changes its screen here.

Spec: SCREENS[id] = (title, rows, spec). A row = (height, [cells]). A cell = C(text, weight, colour,
links). spec = (function, tech, tests).
"""

# Colours (hex so Obsidian shows exactly these). Meaning is explained on the canvas legend.
AMBER = "#E8A33D"   # the main action on a screen (the app's accent), recording on
RED = "#D9534F"     # delete, discard, error, recording dot
GREEN = "#4CAF6A"   # saved, done, installed
BLUE = "#4A90D9"    # private zone (stays on the phone)
PLAIN = "#6B7078"   # an ordinary control or content
DIM = "#9AA0A8"     # background, hint, system UI that is not Brain
PHONE = "#3A3E44"   # the phone frame (canvas group)
HEAD = "#B07CC6"    # headings and notes
SPEC = "#3FA7A0"    # function / tech / tests card under each phone

LEGEND = [
    (AMBER, "Amber", "The main action on a screen, the app's accent colour (big mic, Save, the chosen option). Also 'recording on'."),
    (RED, "Red", "Delete, discard, errors, the recording dot."),
    (GREEN, "Green", "Saved, done, installed, freed."),
    (BLUE, "Blue", "Private zone: notes that stay on the phone and never reach the vault."),
    (PLAIN, "Grey", "An ordinary control or piece of content."),
    (DIM, "Light grey", "Background, hints, and Android's own UI (launcher, camera, system dialogs)."),
    (PHONE, "Dark frame", "One phone screen. Its label is the flow number and name from FLOWS.md."),
    (SPEC, "Teal", "Spec card under each screen: what it does, which code does it, which tests check it."),
    (HEAD, "Purple", "Headings and notes."),
]


class C:
    def __init__(self, text, w=1, c=None, to=None, gap=False):
        self.text, self.w, self.c, self.gap = text, w, c, gap
        if to is None:
            self.to = []
        elif isinstance(to, str):
            self.to = [(to, "")]
        else:
            self.to = [(t, "") if isinstance(t, str) else t for t in to]


def G(w=1):
    """Empty space in a row."""
    return C("", w, gap=True)


NOTE_TOP = (60, [C("‹ Back", 1, to=[("F01", "Back")]), G(1), C("Open | Private", 2, to=[("F18", "Private")])])
NOTE_TABS = (60, [C("Exactly what I said", 1), C("**Clean**", 1, AMBER)])
NOTE_BOTTOM = (60, [C("Paste", to=[("F06", "Paste")]), C("Photo", to=[("F08", "Photo")]),
                    C("Attach", to=[("F16", "Attach")])])
KEYBOARD = (200, [C("Keyboard", 1, DIM)])
DIMMED_APP = "The app you shared from, dimmed"

SCREENS = {
    # ---------------------------------------------------------------- start: the phone itself
    "F29": ("Phone home screen", [
        (50, [C("Android home screen · 12:58", 1, DIM)]),
        (190, [C("**Brain widget (4×3)**\nIdea · Task\nPhoto · Open Brain\nLast capture: Idea · 0:48", 1, PLAIN,
                 to=[("F11", "tap a widget button")])]),
        (100, [C("**Idea**\n1×1 button", 1, AMBER, to=[("F03", "1×1 button")]),
               C("**Photo**\n1×1 button", 1, PLAIN, to=[("F08", "Photo button")]),
               C("**Task**\npinned icon", 1, AMBER, to=[("F03", "pinned type icon")])]),
        (100, [C("**Brain Capture**\napp icon", 1, PLAIN, to=[("F01", "tap app icon"), ("F31", "long-press icon")]),
               C("**Talk**\nlauncher icon", 1, AMBER, to=[("F03", "Talk")]),
               C("**Photo**\npinned icon", 1, PLAIN)]),
        (60, [C("Swipe down: Quick settings", 1, DIM, to=[("F30", "swipe down")])]),
        (60, [C("Share from any app → Brain", 1, DIM, to=[("F09", "Share")])]),
        (90, [C("Long-press empty space → Widgets → **Brain**", 1, DIM, to=[("F32", "add Brain widget")]),
              C("… → **Brain button**", 1, DIM, to=[("F33", "add 1×1 button")])]),
    ], (
        "Where every capture starts outside the app. Brain gives the launcher: the app icon, a second launcher icon 'Talk', the 4×3 Brain widget, 1×1 button widgets (one type or Photo each), pinned one-tap icons per type and for Photo, app-icon shortcuts, a Quick settings tile and a Share target.",
        "Launcher entries in AndroidManifest.xml (MainActivity, QuickRecordActivity 'Talk'); widgets BrainWidget.kt + widget_brain_info.xml, ButtonWidget.kt + widget_button_info.xml; shortcuts and pins in TypesSettings.kt (Shortcuts); tile BrainTile.kt; share target ShareActivity.",
        "T08-1…7, T05-9, T05-15")),
    "F30": ("Quick settings tile", [
        (50, [C("Quick settings (Android)", 1, DIM)]),
        (90, [C("Wi-Fi", 1, DIM), C("Bluetooth", 1, DIM), C("**Brain**\nTap to talk", 1, PLAIN, to=[("F03", "tap tile")])]),
        (90, [C("Torch", 1, DIM), C("Do not disturb", 1, DIM),
              C("**Brain** ●\nRecording · tap to save", 1, AMBER, to=[("F02", "tap again: saved")])]),
        (60, [C("On the lock screen: unlock first, then it starts", 1, DIM)]),
        (60, [C("Subtitle: Recording / Paused · tap to save, last capture, or Tap to talk", 1, DIM)]),
        (60, [C("Add it once: edit tiles, drag **Brain** in", 1, DIM)]),
    ], (
        "One tap starts talking with the default type, a second tap stops and saves. Active (lit) while recording. Locked phone: Android asks to unlock first.",
        "BrainTile.kt (TileService, label 'Brain'); starts CaptureService with the default type (Profiles.primary); refreshed from BrainWidget.refresh.",
        "T08-6, T10-1")),
    "F31": ("App-icon menu", [
        (100, [G(1), C("**Brain Capture**\n(app icon)", 2, PLAIN), G(1)]),
        (60, [C("**Idea**", 1, AMBER, to=[("F03", "type shortcut")])]),
        (60, [C("Task")]),
        (60, [C("Meeting")]),
        (60, [C("Photo", 1, PLAIN, to=[("F08", "Photo shortcut")])]),
        (60, [C("App info · Widgets (Android)", 1, DIM)]),
        (60, [C("Drag an entry out to pin it", 1, DIM, to=[("F29", "pinned")])]),
    ], (
        "Long-press on the app icon shows the first types (up to 3) and Photo last. A type starts talking with that type at once; Photo opens the camera, then the share panel.",
        "Dynamic shortcuts built in TypesSettings.kt (Shortcuts), refreshed on app start and type changes; type glyph on amber (private on grey), adaptive icons.",
        "T08-7")),
    "F32": ("Widget setup", [
        (60, [C("**Widget**", 3), C("**Done**", 1, AMBER, to=[("F29", "widget ready")])]),
        (60, [C("The Brain widget shows up to 4 buttons", 1, DIM)]),
        (55, [C("Idea · bullets", 3), C("**On**", 1, AMBER)]),
        (55, [C("Task · checklist", 3), C("**On**", 1, AMBER)]),
        (55, [C("Private · stays on phone", 3, BLUE), C("Off", 1)]),
        (55, [C("Open Brain button", 3), C("**On**", 1, AMBER)]),
        (55, [C("Photo button", 3), C("Off", 1)]),
        (55, [C("+ New type"), C("+ New private type")]),
    ], (
        "Shown when the Brain widget is placed (optional) and on long-press → Edit widget. Tap a type to edit it, switch it on or off the widget (switches disabled when full), Open Brain and Photo switches, new types. Back keeps the widget.",
        "WidgetSetupActivity.kt (configure activity of widget_brain_info.xml, also the reconfigure entry); same list as Settings → Widget (TypesSettings.kt); Profiles.widgetSlots decides the order: types, then Photo, then Open Brain.",
        "T08-3")),
    "F33": ("One-tap button (1×1)", [
        (60, [C("**One-tap button**")]),
        (80, [C("Which type should this button start? One tap on it starts talking; Photo opens the camera.", 1, DIM)]),
        (55, [C("**Idea**", 1, AMBER, to=[("F29", "button placed")])]),
        (55, [C("Task")]),
        (55, [C("Meeting")]),
        (55, [C("Fix / Feature")]),
        (55, [C("Exact words")]),
        (55, [C("Private", 1, BLUE)]),
        (55, [C("Photo")]),
    ], (
        "Placing a 1×1 'Brain button' asks which type it starts (all types plus Photo). Open types are amber, private grey, Photo shows the camera icon. The button follows later renames and icon changes; a removed widget forgets its setting.",
        "ButtonWidget.kt (AppWidgetProvider + its configure activity), widget_button_info.xml; choice stored per appWidgetId.",
        "T08-4")),
    "F34": ("Pin a one-tap icon", [
        (200, [C("Type editor or Capture types page, dimmed", 1, DIM)]),
        (120, [C("**Add to home screen?**\n[Task icon] Brain: Task", 1, DIM)]),
        (60, [C("Cancel", 1, DIM), C("**Add**", 1, AMBER, to=[("F29", "icon on home screen")])]),
        (60, [C("Launcher can't pin: a short message", 1, DIM)]),
    ], (
        "'Add a one-tap icon to the home screen' (type editor) and 'Add a one-tap Photo icon to the home screen' (Capture types) ask Android to pin a shortcut. The icon follows renames; when the type is deleted it is disabled with 'This capture type was deleted'.",
        "ShortcutManager.requestPinShortcut in TypesSettings.kt; Android draws the dialog.",
        "T08-7")),
    "F11": ("Brain widget (4×3)", [
        (55, [C("**Brain · Tap to talk**")]),
        (80, [C("**Idea**\nbullets", 1, AMBER, to=[("F03", "type button")]), C("**Task**\nchecklist", 1)]),
        (80, [C("**Photo**\ntake a picture", 1, to=[("F08", "Photo")]),
              C("**Open Brain**\nnotes and recent captures", 1, to=[("F01", "Open Brain")])]),
        (60, [C("Last capture: Idea · 0:48 · cleaned on phone", 1, DIM)]),
        (60, [C("Long-press widget → Edit widget", 1, DIM, to=[("F32", "Edit widget")])]),
    ], (
        "Up to 4 buttons in two rows, first type amber, others grey, each with icon, name and subtitle. A type button opens the note screen and starts talking with that type. Photo (off by default) opens the camera, then the share panel. Open Brain (on by default) opens the app. Refreshes after saves, edits, undo and type changes.",
        "BrainWidget.kt (RemoteViews, res/layout/widget_brain.xml, widget_brain_info.xml), PendingIntents to CaptureActivity / PhotoActivity / MainActivity; refresh also updates 1×1 widgets and the tile.",
        "T08-1, T08-2, T08-3")),
    # ---------------------------------------------------------------- in-app capture
    "F12": ("First run", [
        (90, [G(1), C("Brain app tile", 1, AMBER), G(1)]),
        (60, [C("**Capture anything into your vault**")]),
        (100, [C("**1 Your vault** ✓\nDocuments/Brain · Change", 1, GREEN)]),
        (150, [C("**2 Speech model**\nDownload in browser\nImport downloaded file\nInstalling… 62 %")]),
        (60, [C("No internet, no accounts. Everything stays on the phone and in your vault.", 1, DIM)]),
        (60, [C("**Both done → Home**", 1, AMBER, to=[("F01", "setup done")])]),
    ], (
        "Shown until a vault folder is chosen and the speech model is installed. Folder via Android's folder picker; model downloaded by the browser (243 MB) and imported from Downloads, checked by size and SHA-256.",
        "FirstRunScreen.kt; VaultSetup (SettingsScreen.kt); ModelStore.kt import; MainActivity decides first run vs Home.",
        "T01-1, T01-2")),
    "F01": ("Home", [
        (60, [C("**Brain**", 2), C("Undo delete", 2), C("Settings", 1.4, to=[("F19", "Settings")])]),
        (150, [G(1), C("**● Mic**\nbig amber button", 2, AMBER, to=[("F03", "tap mic")]), G(1)]),
        (50, [C("Tap to talk · nothing to decide now")]),
        (60, [C("Type", to=[("F04", "Type")]), C("Paste", to=[("F06", "Paste")]),
              C("Photo", to=[("F08", "Photo")]), C("Attach", to=[("F16", "Attach")])]),
        (40, [C("RECENT")]),
        (150, [C("**Voice · 5 min ago · 0:42**\n- Raised bed along the south fence\n- Ask the neighbour about the boxes",
                 to=[("F02", "tap card"), ("F05", "hold text")])]),
        (55, [C("Bin", 1, RED, to=[("F15", "bin / swipe left")]), C("Obsidian", 2), C("T+", 1, to=[("F04", "T+")]),
              C("Mic+", 1.4, to=[("F03", "mic+")])]),
        (50, [G(2), C("Share", 1), C("Copy", 1)]),
        (110, [C("**Photo from Camera · 20 min ago**\nphoto thumbnail")]),
    ], (
        "The app's main screen: big mic (default type), Type · Paste · Photo · Attach, Undo pill for the last action, recent note cards (5 by default, 'Show N more'), progress cards for recordings not yet saved, recording bar when the mic scrolls away. Cards re-read the vault on return.",
        "MainActivity.kt (HomeScreen), NoteCards.kt, SwipeDelete.kt, pipeline/Undo.kt; shared-element big button to CaptureActivity.",
        "T01-3…9, T06-1, T06-2, T06-9")),
    "F02": ("Note screen", [
        NOTE_TOP, NOTE_TABS,
        (55, [C("Idea ▾", 1, AMBER, to=[("F14", "type chip")]), G(2)]),
        (200, [C("**Raised bed and boxes**\n- Raised bed along the south fence\n- Ask the neighbour about the old wooden boxes",
                 to=[("F05", "tap any text")])]),
        (50, [C("Filed to 00 Inbox/Mobile · open in Obsidian")]),
        (40, [C("waveform ▁▃▅▂▆▃▁▄▂")]),
        (50, [C("Talk to add to this note · New note")]),
        (100, [C("0:42", 1), C("**● Mic**", 1, AMBER, to=[("F03", "add to note")]), C("Undo", 1)]),
        NOTE_BOTTOM,
    ], (
        "One note with everything added to it ('Added HH:mm'). Voice and photo notes: tabs Exactly what I said / Clean; typed, link, file notes: a title instead. Type chip re-cleans. Tap any text to edit. Big button records an addition. Undo takes back what was done on this screen.",
        "ui/CaptureActivity.kt (CaptureScreen, CleanView, TypeText, Controls, EditCard); same screen inside the app and as its own activity for widgets/tile/shortcuts.",
        "T03-9…15, T06-7")),
    "F03": ("Recording", [
        NOTE_TOP,
        (50, [C("**Listening…** words appear after each pause", 1, AMBER)]),
        (220, [C("Raised bed along the south fence, and ask the neighbour about the|")]),
        (45, [C("live level ▁▅▇▃▆▂▇▅▃", 1, AMBER)]),
        (50, [C("Adding to this note")]),
        (100, [C("0:42\n**Discard**", 1, RED, to=[("F13", "Discard")]),
               C("**■ Stop**", 1, AMBER, to=[("F02", "stop")]), C("Pause", 1)]),
        (60, [C("Notification + status-bar timer", 1, DIM, to=[("F28", "while recording")])]),
    ], (
        "Records 16 kHz mono WAV in a foreground service; words appear piece by piece (up to 28 s pieces) while you talk; pause, discard (confirm from 20 s), stop. Works with the screen off and survives the app being killed.",
        "audio/CaptureService.kt, pipeline/VoicePipeline.kt, transcribe/WhisperOnnx.kt + Chunker.kt; UI in CaptureActivity.kt. See SPEC.md (recording, transcription).",
        "T03-1…8, T03-16")),
    "F13": ("Discard a recording", [
        (300, [C("Recording screen, dimmed\nDiscard under 20 s: gone at once", 1, DIM)]),
        (120, [C("**Discard this recording?**\n0:42 of talking will be lost.")]),
        (60, [C("Keep recording", 1, to=[("F03", "Keep recording")]), C("**Discard**", 1, RED, to=[("F02", "Discard")])]),
    ], (
        "Under 20 s Discard throws the recording away at once; from 20 s on this dialog asks first. Discard deletes the audio and forgets the row; a new note's screen resets.",
        "CaptureActivity.kt Controls (askDiscard), CaptureService ACTION discard.",
        "T03-4, T03-5")),
    "F28": ("Notification", [
        (50, [C("12:58", 2, DIM), C("● 0:42", 1, RED)]),
        (110, [C("**Recording · adding to note**\n0:42")]),
        (55, [C("Stop", 1, AMBER, to=[("F02", "Stop")]), C("Discard", 1, to=[("F13", "Discard")])]),
        (100, [C("**Transcribing**\nPart 2 of 3 · about 20 s left")]),
        (70, [C("**✓ Saved** · gone after 4 s", 1, GREEN)]),
        (110, [C("**⚠ Vault folder not available**\nRecording kept. Tap Retry.", 1, RED, to=[("F17", "Retry")])]),
    ], (
        "One notification on channel 'Capture status': recording (timer, Stop, Discard), processing (part x of y, time left), done (gone after 4 s), failed (Retry through the app). Android 16+: Live Update chip with the timer in the status bar.",
        "audio/CaptureService.kt (foreground service type microphone, notification builder).",
        "T10-1…3")),
    "F17": ("Messages and errors", [
        (100, [C("**Voice · 0:48 · Transcribing · part 2 of 3**\n████████░░░░ about 20 s left")]),
        (70, [C("Voice · 0:12 · Queued · starts when the one before is done", 1, DIM)]),
        (130, [C("**⚠ Vault folder not available**\nThe recording is safe on the phone.\nTry again", 1, RED,
                 to=[("F02", "Try again")])]),
        (80, [C("Microphone permission missing · OK", 1, RED)]),
        (70, [C("Kept your exact words: the clean version didn't pass the check.", 1, DIM)]),
    ], (
        "Progress cards for recordings not yet in the vault, queue, failed card with Try again, Home error banner, cleanup outcome lines. Nothing is ever lost: recordings and shares wait on the phone.",
        "NoteCards.kt (progress card), MainActivity.kt (banner), StatusViews.kt, VaultWriter errors.",
        "T06-8, T03-10, T05-14")),
    # ---------------------------------------------------------------- typing, editing, note actions
    "F15": ("Delete and Undo", [
        (60, [C("**Brain**", 2), C("**Undo delete**", 2, AMBER, to=[("F01", "Undo")]), C("Settings", 1.4)]),
        (120, [C("**Delete**", 1, RED), C("Voice · 5 min ago\n- Raised bed…", 3)]),
        (40, [C("swipe past 45 % → deleted on release", 1, DIM)]),
        (130, [C("**Delete this note?**\nIt moves to the vault's .trash with its recordings.\nDelete · Cancel")]),
        (70, [C("Note moved to the vault's .trash", 3), C("**Undo**", 1, AMBER)]),
    ], (
        "Delete by the bin (dialog) or by swiping left past 45 % (distance only). Note, recordings and shared files go to the vault's .trash; private notes are deleted for good once Undo lapses. Snackbar Undo 5 s; Home Undo pill 10 min.",
        "SwipeDelete.kt, NoteCards.kt, pipeline/Notes.kt (trash/restore), pipeline/Undo.kt, VaultWriter.restore.",
        "T06-3…5")),
    "F04": ("Type", [
        (60, [C("‹ Back", 1, to=[("F02", "Back")]), C("**Type a thought**", 2), C("Idea ▾", 1, AMBER)]),
        (230, [C("Type anything…")]),
        (50, [C("Goes to your inbox as Idea")]),
        (60, [C("**Save, no AI**", 1, AMBER, to=[("F02", "save")]), C("Save with AI", 1)]),
        KEYBOARD,
    ], (
        "Type a new note or add typed text to the open note. 'Save, no AI' keeps it exactly; 'Save with AI' (only with the model) cleans it in the background with the note's type. Private types stay in the app.",
        "CaptureActivity.kt TextCard / SaveButton, pipeline/TextCapture.kt (isPrivateId), cleanup/CleanupStep.kt.",
        "T04-1…3, T04-10")),
    "F05": ("Edit", [
        (60, [C("**Edit the clean version**")]),
        (110, [C("- Raised bed along the south fence|")]),
        (110, [C("Added 14:32\n- Order two boxes")]),
        (70, [C("Lines with “- ” are bullets. The clean version is made again from your corrected words.", 1, DIM)]),
        (60, [C("Cancel", 1, to=[("F02", "Cancel")]), C("**Save**", 1, AMBER, to=[("F02", "Save")])]),
        KEYBOARD,
    ], (
        "Tap any finished text (or hold a card's text on Home). One field per section; corrected exact words are cleaned again; hand-edited clean lines keep their sources. Only when nothing records or processes.",
        "CaptureActivity.kt EditCard, TextFields.kt (CursorFollowingTextField, thinScrollbar), pipeline/NoteEdits.kt with backup and read-back check.",
        "T04-6…9")),
    "F06": ("Paste", [
        (60, [C("‹ Back", 1), C("**Paste from clipboard**", 2), C("Exact words ▾", 1, AMBER)]),
        (230, [C("https://example.com/raised-beds\nHow to build a raised bed")]),
        (50, [C("Goes to your inbox as Exact words")]),
        (60, [C("**Save**", 1, AMBER, to=[("F02", "save")])]),
        (50, [C("Empty clipboard: “The clipboard is empty.”", 1, DIM)]),
    ], (
        "Field pre-filled with the clipboard; type Exact words (falls back to the default type). A pasted link becomes a link note, never AI-cleaned. Tap Paste again to go back.",
        "CaptureActivity.kt (Paste mode), pipeline/TextCapture.kt, share/ShareParse.kt for link detection.",
        "T04-4, T04-5")),
    "F14": ("Clean again as…", [
        (200, [C("Note screen, dimmed", 1, DIM)]),
        (55, [C("**Clean again as…**")]),
        (60, [C("**Idea** ✓ (now)\nbullets", 1, AMBER)]),
        (60, [C("**Task**\nchecklist + due date", to=[("F02", "re-cleaned")])]),
        (60, [C("**Meeting**\nwho, decisions, to-dos")]),
        (60, [C("**Fix / Feature**\none line per point")]),
        (60, [C("**Voice note**\ntext + lists")]),
    ], (
        "The type chip on a finished note lists the zone's types; picking one cleans every voice section again and swaps the clean block in the vault; Undo puts everything back. Dimmed while busy or without a model.",
        "CaptureActivity.kt TypeChip, cleanup/CleanupStep.kt (re-clean), CleanupText.kt.",
        "T06-6")),
    "F18": ("Private note", [
        (60, [C("‹ Back"), G(1), C("Open | **Private**", 2, BLUE, to=[("F02", "Open")])]),
        (55, [C("Private ▾", 1, BLUE), G(2)]),
        (200, [C("- Ask about the test results\n- Call on Thursday")]),
        (50, [C("Kept on this phone only", 1, BLUE)]),
        (100, [C("0:31"), C("**● Mic**", 1, AMBER), C("Undo")]),
        (60, [C("Paste"), C("Photo (off)", 1, DIM), C("Attach (off)", 1, DIM)]),
    ], (
        "Private zone: audio in app storage, text in the database, never in the vault or the share picker; no Photo, Attach, T+, mic+ or Obsidian. Private types have ids starting 'private-'.",
        "CaptureActivity.kt zone switch, pipeline/VoicePipeline.kt + TextCapture.kt (private paths), files/private.",
        "T04-10, T11-4, T09-4")),
    # ---------------------------------------------------------------- photo, attach, share
    "F16": ("Attach (sheet and Link)", [
        (55, [C("**New note from…**")]),
        (80, [C("Gallery"), C("Camera", to=[("F08", "Camera")]), C("Files & audio")]),
        (80, [C("Contact"), C("**Link**", 1, AMBER), C("Location")]),
        (60, [C("Saved to your vault's Attachments and added like a share. Undo takes it back.", 1, DIM)]),
        (130, [C("**Add a link**\nhttps://example.com/e2e")]),
        (60, [C("Cancel"), C("**Add**", 1, AMBER, to=[("F02", "saved")])]),
    ], (
        "Six tiles: Gallery (photo picker, up to 20), Camera, Files & audio, Contact (one number, no permission), Link (dialog, clipboard pre-fill), Location (one fix, 20 s timeout). New note, or added to the open note.",
        "ui/Attach.kt (ActivityResult contracts, fused location), saved through share/Shares.kt.",
        "T05-3…8")),
    "F08": ("Phone camera", [
        (50, [C("The phone's own camera app", 1, DIM)]),
        (450, [C("viewfinder", 1, DIM)]),
        (90, [C("Shutter", 1, DIM), C("**✓ Use photo**", 1, AMBER,
                                      to=[("F07", "from Home / note"), ("F09", "from widget, icon, share")])]),
        (60, [C("Back: “No photo taken”", 1, DIM)]),
    ], (
        "Brain uses the phone's camera app (no camera permission). In the app the photo becomes a photo note; from the widget, 1×1, shortcut or icon it goes to the share panel with source 'Camera'.",
        "ui/TakePhoto.kt (TakePicture into cache/camera, PhotoActivity), FileProvider share_paths.xml.",
        "T05-1, T05-15")),
    "F07": ("Photo note", [
        NOTE_TOP, NOTE_TABS,
        (55, [C("Idea ▾", 1, AMBER), G(2)]),
        (200, [C("photo\nShared from Camera on 21.11.2026", to=[("F05", "tap caption")])]),
        (50, [C("Talk to add to this note · New note")]),
        (100, [C("0:00", 1), C("**● Mic**", 1, AMBER, to=[("F03", "talk about it")]), C("Undo", 1)]),
        NOTE_BOTTOM,
    ], (
        "A photo saved from inside the app opens as a note with the voice-note layout, ready to talk about it. File in <inbox>/Attachments, note in <inbox>/Documents.",
        "TakePhoto.kt + Shares.stage/save (sourceName 'Camera'); CaptureActivity shows it.",
        "T05-1, T05-2")),
    "F09": ("Save to (share)", [
        (180, [C(DIMMED_APP + "\ntap here = save as new note", 1, DIM)]),
        (55, [C("**Save to**")]),
        (55, [C("Search your notes")]),
        (70, [C("**New note**\nSaved to your inbox", 1, AMBER, to=[("F10", "new note")])]),
        (60, [C("Raised bed and boxes · Voice · 5 min", to=[("F10", "add to note")])]),
        (60, [C("Plumber call · Task · yesterday")]),
        (60, [C("Seeds to order · Text · Monday")]),
        (50, [C("Same link or file within 24 h", 1, DIM, to=[("F27", "duplicate")])]),
    ], (
        "Brain in Android's Share menu for text, links, images, PDFs and files. Content is copied into app storage first, then the picker: New note or one of up to 200 recent notes. Closing still saves.",
        "share/ShareActivity.kt, share/Shares.kt (staging, retry), ShareParse.kt, Previews.kt (thumbnails).",
        "T05-9, T05-10, T05-13, T05-14")),
    "F10": ("Saved + comment", [
        (180, [C(DIMMED_APP, 1, DIM)]),
        (60, [C("**✓ Link saved**", 1, GREEN)]),
        (50, [C("00 Inbox/Mobile/Links")]),
        (90, [C("**How to build a raised bed**\nexample.com")]),
        (60, [C("Add a voice comment", 1, AMBER)]),
        (90, [C("● Recording your comment · 0:12\n▁▅▇▃", 2, AMBER), C("Stop", 1, RED)]),
        (50, [C("Closes after 3.5 s", 1, DIM)]),
    ], (
        "Saved confirmation with folder and a small card; closes after 3.5 s. 'Add a voice comment' records an addition to the note just saved.",
        "share/ShareActivity.kt (saved state, comment recording through CaptureService).",
        "T05-9, T05-11")),
    "F27": ("Already saved", [
        (250, [C(DIMMED_APP, 1, DIM)]),
        (110, [C("**Already saved at 14:32**\nHow to build a raised bed")]),
        (60, [C("Open note"), C("**Save again**", 1, AMBER, to=[("F10", "Save again")])]),
        (50, [C("Close = don't save", 1, DIM)]),
    ], (
        "Same link or same file hash within 24 h: Open note (in Obsidian, drops this share) or Save again. Closing means don't save.",
        "share/ShareActivity.kt duplicate check (Shares.kt, sha256).",
        "T05-12")),
    # ---------------------------------------------------------------- settings
    "F19": ("Settings menu", [
        (60, [C("‹ Back", 1, to=[("F01", "Back")]), C("**Settings**", 3)]),
        (60, [C("**Capture types** · 7 types · default Idea ›", to=[("F20", "")])]),
        (60, [C("**Widget** · 4 buttons ›", to=[("F22", "")])]),
        (60, [C("**Vault** · Brain · 00 Inbox/Mobile ›", to=[("F23", "")])]),
        (60, [C("**Transcription** · Whisper small · Auto ›", to=[("F24", "")])]),
        (60, [C("**Audio & display** · Opus · 5 notes ›", to=[("F25", "")])]),
        (60, [C("**Storage** · 412 MB ›", to=[("F26", "")])]),
        (60, [C("**About & privacy** · No internet, no accounts ›")]),
    ], (
        "One menu, each row with its current value. Back on a page returns here; Back here leaves.",
        "ui/SettingsScreen.kt (menu + pages), TypesSettings.kt, Prefs.kt.",
        "T09-1")),
    "F20": ("Capture types", [
        (60, [C("‹ Back"), C("**Capture types**", 3)]),
        (60, [C("Default type", 2), C("Idea ▾", 1, AMBER)]),
        (55, [C("**Idea** · bullets", 3, AMBER), C("Bin", 1)]),
        (55, [C("**Task** · checklist ↑", 3, to=[("F21", "tap a type")]), C("Bin", 1)]),
        (55, [C("**Meeting** · to-dos ↑", 3), C("Bin", 1)]),
        (55, [C("Deleted Fix · **Undo**", 1, AMBER)]),
        (55, [C("**Private** · stays on phone ↑", 3, BLUE), C("Bin", 1)]),
        (55, [C("+ New type", to=[("F21", "new")]), C("+ New private type")]),
        (55, [C("Add a one-tap Photo icon to the home screen", 1, PLAIN, to=[("F34", "pin Photo")])]),
    ], (
        "Default type (open types only), list with ↑ to reorder, delete with an 8 s Undo row (not the last type), new type / new private type, pin a Photo icon.",
        "ui/TypesSettings.kt, types/Profiles.kt (primary, setPrimary, ids, private- prefix).",
        "T09-2")),
    "F21": ("Type editor", [
        (60, [C("Cancel", 1, to=[("F20", "Cancel / Save")]), C("**Edit Task**", 2), C("**Save**", 1, AMBER)]),
        (55, [C("Name: Task")]),
        (55, [C("Short description: checklist")]),
        (90, [C("Icon grid (32)")]),
        (60, [C("Clean up with AI", 3), C("**On**", 1, AMBER)]),
        (110, [C("Instruction for the AI\nTurn what I said into tasks…")]),
        (60, [C("Bullets"), C("**Checklist**", 1, AMBER), C("Text + lists")]),
        (55, [C("Add a one-tap icon to the home screen", 1, PLAIN, to=[("F34", "pin icon")])]),
        (55, [C("**Delete this type**", 1, RED)]),
    ], (
        "Full-screen editor: name (24), description (40), icon, Clean up with AI switch (off hides instruction and format), instruction, format, pin icon, delete. Unsaved changes ask 'Discard your changes?'.",
        "TypesSettings.kt ProfileEditor (full-screen Dialog), Profiles.kt (base instruction tracking).",
        "T09-3, T09-4")),
    "F22": ("Widget page", [
        (60, [C("‹ Back"), C("**Widget**", 3)]),
        (60, [C("The Brain widget shows up to 4 buttons", 1, DIM)]),
        (55, [C("Idea", 3), C("**On**", 1, AMBER)]),
        (55, [C("Task", 3), C("**On**", 1, AMBER)]),
        (55, [C("Meeting", 3), C("Off", 1)]),
        (55, [C("Open Brain button", 3), C("**On**", 1, AMBER)]),
        (55, [C("Photo button", 3), C("Off", 1)]),
        (60, [C("**Done**", 1, AMBER, to=[("F11", "widget updates")])]),
    ], (
        "Same content as the widget setup screen, reached from Settings. Turning a slot on when full takes the last type off.",
        "TypesSettings.kt (widget page), Profiles.widgetSlots, BrainWidget.refresh.",
        "T08-3")),
    "F23": ("Vault", [
        (60, [C("‹ Back"), C("**Vault**", 3)]),
        (80, [C("**Vault folder**\nDocuments/Brain", 3), C("Change", 1)]),
        (80, [C("**Inbox folder inside the vault**\n00 Inbox/Mobile")]),
        (150, [C("Voice · Links · Text · Documents · Attachments", 1, DIM)]),
    ], (
        "Vault folder via the folder picker (persistent access, no storage permission); inbox folder name, blank ignored.",
        "SettingsScreen.kt VaultSetup, Prefs.kt, vault/VaultWriter.kt.",
        "T09-5")),
    "F24": ("Transcription, AI model", [
        (60, [C("‹ Back"), C("**Transcription**", 3)]),
        (80, [C("**Speech model**\nInstalled (Whisper small)", 3, GREEN), C("Replace", 1)]),
        (60, [C("**Auto**", 1, AMBER), C("Deutsch"), C("English")]),
        (90, [C("**AI cleanup model**\ngemma-4-E2B · 2.6 GB", 3, GREEN)]),
        (60, [C("Replace"), C("**Remove model**", 1, RED)]),
        (70, [C("Copying… 48 %\n██████░░░░░░", 1, AMBER)]),
    ], (
        "Speech model status and Replace; language Auto / Deutsch / English; AI cleanup model import (.litertlm, % bar), Replace, Remove (asks first).",
        "SettingsScreen.kt (GemmaSetup), transcribe/ModelStore.kt, cleanup/GemmaHolder.kt (import/remove).",
        "T09-6, T07-7")),
    "F25": ("Audio & display", [
        (60, [C("‹ Back"), C("**Audio & display**", 3)]),
        (50, [C("Audio in the vault", 1, DIM)]),
        (60, [C("**Opus** (small)", 1, AMBER), C("WAV (lossless)")]),
        (50, [C("Notes on the home screen", 1, DIM)]),
        (60, [C("**5**", 1, AMBER), C("10"), C("20")]),
        (50, [C("Sharing a note", 1, DIM)]),
        (60, [C("**With voice**", 1, AMBER), C("Without")]),
    ], (
        "Audio format in the vault (Opus ~24 kbit/s or WAV), notes per Home page, whether Share includes the voice recording.",
        "SettingsScreen.kt, Prefs.kt, audio/OpusEncoder.kt.",
        "T09-7")),
    "F26": ("Storage and About", [
        (60, [C("‹ Back"), C("**Storage**", 3)]),
        (130, [C("Speech model 243 MB\nWaiting recordings and shares 0 MB\nCard previews 12 MB\nDatabase 1 MB")]),
        (60, [C("Clean up leftovers"), C("Freed 8 MB", 1, GREEN)]),
        (50, [C("Waiting items are never deleted", 1, DIM)]),
        (130, [C("**About & privacy**\nNo internet permission. No accounts, no tracking. Files stay on the phone and in your vault.")]),
    ], (
        "Sizes of what the app keeps; Clean up leftovers never touches anything not yet in the vault. About & privacy states the no-internet promise.",
        "SettingsScreen.kt, Housekeeping.kt.",
        "T09-8")),
}

# Where each phone sits (column, row): screens that lead to each other sit side by side.
LAYOUT = {
    "F29": (0, 0), "F30": (1, 0), "F31": (2, 0), "F32": (3, 0), "F33": (4, 0), "F34": (5, 0), "F11": (6, 0),
    "F12": (0, 1), "F01": (1, 1), "F02": (2, 1), "F03": (3, 1), "F13": (4, 1), "F28": (5, 1), "F17": (6, 1),
    "F15": (0, 2), "F04": (1, 2), "F05": (2, 2), "F06": (3, 2), "F14": (4, 2), "F18": (5, 2),
    "F16": (0, 3), "F08": (1, 3), "F07": (2, 3), "F09": (3, 3), "F10": (4, 3), "F27": (5, 3),
    "F19": (0, 4), "F20": (1, 4), "F21": (2, 4), "F22": (3, 4), "F23": (4, 4), "F24": (5, 4), "F25": (6, 4),
    "F26": (7, 4),
}
ROW_NAMES = ["Start: the phone's home screen, widgets, tile and icons", "Capture and recording",
             "Typing, editing and note actions", "Photo, attach and share", "Settings"]
SKIP_LABELS = {"Back"}  # Back always returns to where you came from; arrows for it only add clutter

PHONE_W, PAD, CELL_GAP, ROW_GAP_IN = 440, 20, 10, 10
SCREEN_GAP_X, ROW_GAP_Y, HEADER_H = 340, 420, 170
DOCS = [("FEATURES.md", "Every feature (regression checklist)"),
        ("FLOWS.md", "Every flow step by step"),
        ("SPEC.md", "Technical specification: rebuild the app from this"),
        ("E2E-TESTS.md", "End-to-end tests on the phone")]


def spec_height(spec):
    chars = sum(len(s) for s in spec) + 60
    return 70 + 22 * (chars // 46 + 6)


def build(note, doc_dir=""):
    import json
    nodes, edges, frames = [], [], {}

    def phone_h(rows):
        return PAD * 2 + sum(h for h, _ in rows) + ROW_GAP_IN * (len(rows) - 1)
    n_rows = max(r for _, r in LAYOUT.values()) + 1
    row_ui_h = [max(phone_h(SCREENS[s][1]) for s, (_, r) in LAYOUT.items() if r == row) for row in range(n_rows)]
    row_spec_h = [max(spec_height(SCREENS[s][2]) for s, (_, r) in LAYOUT.items() if r == row) for row in range(n_rows)]

    # Title, how to read, legend, documents.
    nodes.append({"id": "w-title", "type": "text", "x": 0, "y": -2700, "width": 1500, "height": 330, "color": HEAD,
                  "text": "# Brain Capture: the whole app on one canvas\n\n"
                          "Every screen is drawn with canvas cards. **Arrows start at the control you tap and end "
                          "at the screen it opens**; each arrow has the colour of that control. Start at the top "
                          "left (the phone's home screen) and follow the arrows. Under each screen a teal card says "
                          "what it does, which code does it and which tests check it. Back always returns, so it "
                          "has no arrow.\n\nTo rebuild or test the app, read the four documents on the right: "
                          "FEATURES (what), FLOWS (how it connects), SPEC (how it is built), E2E-TESTS (how to "
                          "prove it works).\n\n" + note.lstrip("> ")})
    lx, ly = 0, -2300
    nodes.append({"id": "w-legend", "type": "group", "label": "Colours", "x": lx, "y": ly, "width": 1500,
                  "height": 80 + 90 * ((len(LEGEND) + 1) // 2), "color": HEAD})
    for i, (col, name, meaning) in enumerate(LEGEND):
        nodes.append({"id": "w-leg%d" % i, "type": "text", "color": col,
                      "x": lx + 20 + (i % 2) * 740, "y": ly + 30 + (i // 2) * 90, "width": 720, "height": 80,
                      "text": "**{}**: {}".format(name, meaning)})
    for i, (fname, label) in enumerate(DOCS):
        x = 1700 + i * 1000
        nodes.append({"id": "w-doclabel%d" % i, "type": "text", "x": x, "y": -2700, "width": 960, "height": 80,
                      "color": HEAD, "text": "## {}\n".format(label)})
        nodes.append({"id": "w-doc%d" % i, "type": "file", "file": (doc_dir + "/" if doc_dir else "") + fname,
                      "x": x, "y": -2600, "width": 960, "height": 2300, "color": SPEC})

    row_top, y = [], 0
    for row in range(n_rows):
        row_top.append(y + HEADER_H)
        nodes.append({"id": "w-row%d" % row, "type": "text", "x": 0, "y": y, "width": 1600, "height": 90,
                      "text": "## " + ROW_NAMES[row], "color": HEAD})
        y += HEADER_H + row_ui_h[row] + 20 + row_spec_h[row] + ROW_GAP_Y

    for sid, (col, row) in LAYOUT.items():
        name, rows, spec = SCREENS[sid]
        x, top = col * (PHONE_W + SCREEN_GAP_X), row_top[row]
        ui_h = row_ui_h[row]
        gh = ui_h + 20 + row_spec_h[row]
        frames[sid] = (x, top, PHONE_W, gh)
        nodes.append({"id": "w-" + sid, "type": "group", "label": "{} · {}".format(sid, name),
                      "x": x, "y": top, "width": PHONE_W, "height": gh, "color": PHONE})
        ry = top + PAD
        for ri, (h, cells) in enumerate(rows):
            inner = PHONE_W - PAD * 2 - CELL_GAP * (len(cells) - 1)
            total = sum(c.w for c in cells)
            cx = x + PAD
            for ci, cell in enumerate(cells):
                cw = round(inner * cell.w / total)
                if not cell.gap:
                    nid = "w-{}-{}-{}".format(sid, ri, ci)
                    nodes.append({"id": nid, "type": "text", "text": cell.text, "x": cx, "y": ry,
                                  "width": cw, "height": h, "color": cell.c or PLAIN})
                    for target, label in cell.to:
                        if label not in SKIP_LABELS:
                            edges.append((nid, (cx, ry, cw, h), target, label, cell.c or PLAIN))
                cx += cw + CELL_GAP
            ry += h + ROW_GAP_IN
        fn, tech, tests = spec
        nodes.append({"id": "w-{}-spec".format(sid), "type": "text", "color": SPEC,
                      "x": x + PAD, "y": top + ui_h + 10, "width": PHONE_W - PAD * 2, "height": row_spec_h[row] - 10,
                      "text": "**Function**: {}\n\n**Tech**: {}\n\n**Tests**: {}".format(fn, tech, tests)})

    out_edges = []
    for n, (nid, (sx, sy, sw, sh), target, label, colour) in enumerate(edges):
        tx, ty, tw, th = frames[target]
        scx, scy = sx + sw / 2, sy + sh / 2
        src = nid.split("-")[1]
        same_row = abs(ty - frames[src][1]) < 1
        neighbour = abs(LAYOUT[src][0] - LAYOUT[target][0]) == 1
        if same_row and neighbour:
            fs, ts = ("right", "left") if tx > scx else ("left", "right")
        elif same_row:  # go under the screens in between instead of through them
            fs, ts = "bottom", "bottom"
        else:
            fs = "right" if tx + tw / 2 > scx + 200 else "left" if tx + tw / 2 < scx - 200 else (
                "bottom" if ty > sy else "top")
            ts = "top" if ty > sy else "bottom"
        e = {"id": "w-e{}".format(n), "fromNode": nid, "fromSide": fs, "toNode": "w-" + target,
             "toSide": ts, "toEnd": "arrow", "color": colour}
        if label:
            e["label"] = label
        out_edges.append(e)
    return json.dumps({"nodes": nodes, "edges": out_edges}, ensure_ascii=False, indent="\t")
