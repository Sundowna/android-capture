"""Wireframes of every screen (F01-F28) drawn with Obsidian canvas parts, plus the flow arrows.

Each screen is a canvas group (the phone) holding text cards (the controls). An arrow starts at
the control you tap and ends at the screen it opens. Built into "Brain Capture Wireframes.canvas"
by tools/sync-vault-docs.py. Keep this in step with FLOWS.md: a changed flow changes its screen here.

Spec: SCREENS[id] = (title, rows). A row = (height, [cells]). A cell = C(text, weight, colour, links).
"""

AMBER, RED, GREEN, BLUE, DIM = "#E8A33D", "#D9534F", "#4CAF6A", "#4A90D9", "#9AA0A8"
PLAIN, PHONE, HEAD = "#6B7078", "#3A3E44", "#B07CC6"  # every card gets a colour


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


# Where each phone sits (column, row): screens that lead to each other sit side by side,
# so the arrows stay short. Row 0 = capture, 1 = writing, 2 = photo/attach/share, 3 = settings.
LAYOUT = {
    "F12": (0, 0), "F01": (1, 0), "F02": (2, 0), "F03": (3, 0), "F13": (4, 0), "F28": (5, 0), "F17": (6, 0),
    "F15": (0, 1), "F04": (1, 1), "F05": (2, 1), "F06": (3, 1), "F14": (4, 1), "F18": (5, 1),
    "F16": (0, 2), "F08": (1, 2), "F07": (2, 2), "F09": (3, 2), "F10": (4, 2), "F27": (5, 2), "F11": (6, 2),
    "F19": (0, 3), "F20": (1, 3), "F21": (2, 3), "F22": (3, 3), "F23": (4, 3), "F24": (5, 3), "F25": (6, 3),
    "F26": (7, 3),
}
ROW_NAMES = ["Capture and recording", "Typing, editing and note actions", "Photo, attach and share",
             "Settings"]
SKIP_LABELS = {"Back"}  # Back always returns to where you came from; arrows for it only add clutter

NOTE_TOP = (60, [C("‹ Back", 1, to=[("F01", "Back")]), G(1), C("Open | Private", 2, to=[("F18", "Private")])])
NOTE_TABS = (60, [C("Exactly what I said", 1), C("**Clean**", 1, AMBER)])
NOTE_BOTTOM = (60, [C("Paste", to=[("F06", "Paste")]), C("Photo", to=[("F08", "Photo")]),
                    C("Attach", to=[("F16", "Attach")])])

SCREENS = {
    "F01": ("Home", [
        (60, [C("## Brain", 2), C("Undo delete", 2), C("Settings", 1, to=[("F19", "Settings")])]),
        (150, [G(1), C("### ● Mic\nbig amber button", 2, AMBER, to=[("F03", "tap mic")]), G(1)]),
        (50, [C("Tap to talk · nothing to decide now")]),
        (60, [C("Type", to=[("F04", "Type")]), C("Paste", to=[("F06", "Paste")]),
              C("Photo", to=[("F08", "Photo")]), C("Attach", to=[("F16", "Attach")])]),
        (40, [C("RECENT")]),
        (150, [C("**Voice · 5 min ago · 0:42**\n- Raised bed along the south fence\n- Ask the neighbour about the boxes",
                 to=[("F02", "tap card"), ("F05", "hold text")])]),
        (55, [C("Bin", 1, RED, to=[("F15", "bin / swipe left")]), C("Obsidian", 2), C("T+", 1, to=[("F04", "T+")]),
              C("Mic+", 1.4, to=[("F03", "mic+")])]),
        (50, [G(2), C("Share", 1), C("Copy", 1)]),
        (110, [C("**Photo from Camera · 20 min ago**\n[photo thumbnail]")]),
    ]),
    "F02": ("Note screen", [
        NOTE_TOP, NOTE_TABS,
        (55, [C("Idea ▾", 1, AMBER, to=[("F14", "type chip")]), G(2)]),
        (200, [C("**Raised bed and boxes**\n- Raised bed along the south fence\n- Ask the neighbour about the old wooden boxes",
                 to=[("F05", "tap any text")])]),
        (50, [C("Filed to 00 Inbox/Mobile · open in Obsidian")]),
        (40, [C("▁▃▅▂▆▃▁▄▂ waveform")]),
        (50, [C("Talk to add to this note · New note")]),
        (100, [C("0:42", 1), C("### ● Mic", 1, AMBER, to=[("F03", "add to note")]), C("Undo", 1)]),
        NOTE_BOTTOM,
    ]),
    "F03": ("Recording", [
        NOTE_TOP,
        (50, [C("**Listening…** words appear after each pause", 1, AMBER)]),
        (220, [C("Raised bed along the south fence, and ask the neighbour about the|")]),
        (45, [C("▁▅▇▃▆▂▇▅▃ live level", 1, AMBER)]),
        (50, [C("Adding to this note")]),
        (100, [C("0:42\n**Discard**", 1, RED, to=[("F13", "Discard")]),
               C("### ■ Stop", 1, AMBER, to=[("F02", "stop")]), C("Pause", 1)]),
        (60, [C("Notification + status-bar timer", 1, DIM, to=[("F28", "while recording")])]),
    ]),
    "F04": ("Type", [
        (60, [C("‹ Back", 1, to=[("F02", "Back")]), C("**Type a thought**", 2), C("Idea ▾", 1, AMBER)]),
        (230, [C("Type anything…")]),
        (50, [C("Goes to your inbox as Idea")]),
        (60, [C("**Save, no AI**", 1, AMBER, to=[("F02", "save")]), C("Save with AI", 1)]),
        (230, [C("[keyboard]", 1, DIM)]),
    ]),
    "F05": ("Edit", [
        (60, [C("**Edit the clean version**")]),
        (110, [C("- Raised bed along the south fence|")]),
        (110, [C("Added 14:32\n- Order two boxes")]),
        (70, [C("Lines with “- ” are bullets. The clean version is made again from your corrected words.", 1, DIM)]),
        (60, [C("Cancel", 1, to=[("F02", "Cancel")]), C("**Save**", 1, AMBER, to=[("F02", "Save")])]),
        (230, [C("[keyboard]", 1, DIM)]),
    ]),
    "F06": ("Paste", [
        (60, [C("‹ Back", 1), C("**Paste from clipboard**", 2), C("Exact words ▾", 1, AMBER)]),
        (230, [C("https://example.com/raised-beds\nHow to build a raised bed")]),
        (50, [C("Goes to your inbox as Exact words")]),
        (60, [C("**Save**", 1, AMBER, to=[("F02", "save")])]),
        (50, [C("Empty clipboard: “The clipboard is empty.”", 1, DIM)]),
    ]),
    "F07": ("Photo note", [
        NOTE_TOP, NOTE_TABS,
        (55, [C("Idea ▾", 1, AMBER), G(2)]),
        (200, [C("[photo]\nShared from Camera on 21.11.2026", to=[("F05", "tap caption")])]),
        (50, [C("Talk to add to this note · New note")]),
        (100, [C("0:00", 1), C("### ● Mic", 1, AMBER, to=[("F03", "talk about it")]), C("Undo", 1)]),
        NOTE_BOTTOM,
    ]),
    "F08": ("Phone camera (not Brain)", [
        (50, [C("The phone's own camera app", 1, DIM)]),
        (450, [C("[viewfinder]")]),
        (90, [C("Shutter", 1), C("**✓ Use photo**", 1, AMBER,
                                 to=[("F07", "from Home / note"), ("F09", "from widget, icon, share")])]),
        (60, [C("Back: “No photo taken”", 1, DIM)]),
    ]),
    "F09": ("Save to (share panel)", [
        (180, [C("[the app you shared from, dimmed]\ntap here = save as new note", 1, DIM)]),
        (55, [C("**Save to**")]),
        (55, [C("Search your notes")]),
        (70, [C("**New note**\nSaved to your inbox", 1, AMBER, to=[("F10", "new note")])]),
        (60, [C("Raised bed and boxes · Voice · 5 min", to=[("F10", "add to note")])]),
        (60, [C("Plumber call · Task · yesterday")]),
        (60, [C("Seeds to order · Text · Monday")]),
        (50, [C("Same link or file within 24 h", 1, DIM, to=[("F27", "duplicate")])]),
    ]),
    "F10": ("Saved + voice comment", [
        (180, [C("[the app you shared from, dimmed]", 1, DIM)]),
        (60, [C("**✓ Link saved**", 1, GREEN)]),
        (50, [C("00 Inbox/Mobile/Links")]),
        (90, [C("**How to build a raised bed**\nexample.com")]),
        (60, [C("Add a voice comment", 1, AMBER)]),
        (90, [C("● Recording your comment · 0:12\n▁▅▇▃", 2, AMBER), C("Stop", 1, RED)]),
        (50, [C("Closes after 3.5 s", 1, DIM)]),
    ]),
    "F11": ("Widget, Talk icon, tile, icon menu", [
        (55, [C("**Brain · Tap to talk** (widget)")]),
        (80, [C("**Idea**\nbullets", 1, AMBER, to=[("F03", "type button")]), C("**Task**\nchecklist", 1)]),
        (80, [C("**Photo**\ntake a picture", 1, to=[("F08", "Photo")]), C("**Open Brain**\nnotes", 1, to=[("F01", "Open Brain")])]),
        (50, [C("Last capture: Idea · 0:48 · cleaned on phone", 1, DIM)]),
        (40, [G()]),
        (80, [C("**Talk** icon", 1, AMBER, to=[("F03", "Talk")]), C("Quick tile **Brain**", 1, AMBER)]),
        (130, [C("App-icon menu\nIdea · Task · Meeting · Photo", 1)]),
        (50, [C("Long-press widget → Edit widget", 1, DIM, to=[("F22", "Edit widget")])]),
    ]),
    "F12": ("First run", [
        (90, [G(1), C("[app tile]", 1, AMBER), G(1)]),
        (60, [C("**Capture anything into your vault**")]),
        (100, [C("**1 Your vault** ✓\nDocuments/Brain · Change", 1, GREEN)]),
        (150, [C("**2 Speech model**\nDownload in browser\nImport downloaded file\nInstalling… 62 %")]),
        (60, [C("No internet, no accounts. Everything stays on the phone and in your vault.", 1, DIM)]),
        (60, [C("**Both done → Home**", 1, AMBER, to=[("F01", "setup done")])]),
    ]),
    "F13": ("Discard a recording", [
        (300, [C("[Recording screen, dimmed]\nDiscard under 20 s: gone at once", 1, DIM)]),
        (120, [C("**Discard this recording?**\n0:42 of talking will be lost.")]),
        (60, [C("Keep recording", 1, to=[("F03", "Keep recording")]), C("**Discard**", 1, RED, to=[("F02", "Discard")])]),
    ]),
    "F14": ("Clean again as another type", [
        (200, [C("[Note screen, dimmed]", 1, DIM)]),
        (55, [C("**Clean again as…**")]),
        (60, [C("**Idea** ✓ (now)\nbullets", 1, AMBER)]),
        (60, [C("**Task**\nchecklist + due date", to=[("F02", "re-cleaned")])]),
        (60, [C("**Meeting**\nwho, decisions, to-dos")]),
        (60, [C("**Fix / Feature**\none line per point")]),
        (60, [C("**Voice note**\ntext + lists")]),
    ]),
    "F15": ("Delete a note and Undo", [
        (60, [C("## Brain", 2), C("**Undo delete**", 2, AMBER, to=[("F01", "Undo")]), C("Settings", 1)]),
        (120, [C("**Delete**", 1, RED), C("Voice · 5 min ago\n- Raised bed…", 3)]),
        (40, [C("swipe past 45 % → deleted on release", 1, DIM)]),
        (130, [C("**Delete this note?**\nIt moves to the vault's .trash with its recordings.\nDelete · Cancel")]),
        (70, [C("Note moved to the vault's .trash", 3), C("**Undo**", 1, AMBER)]),
    ]),
    "F16": ("Attach (sheet and Link)", [
        (55, [C("**New note from…**")]),
        (80, [C("Gallery"), C("Camera", to=[("F08", "Camera")]), C("Files & audio")]),
        (80, [C("Contact"), C("**Link**", 1, AMBER), C("Location")]),
        (60, [C("Saved to your vault's Attachments and added like a share. Undo takes it back.", 1, DIM)]),
        (130, [C("**Add a link**\nhttps://example.com/e2e")]),
        (60, [C("Cancel"), C("**Add**", 1, AMBER, to=[("F02", "saved")])]),
    ]),
    "F17": ("Saving messages and errors", [
        (100, [C("**Voice · 0:48 · Transcribing · part 2 of 3**\n████████░░░░ about 20 s left")]),
        (70, [C("Voice · 0:12 · Queued · starts when the one before is done", 1, DIM)]),
        (130, [C("**⚠ Vault folder not available**\nThe recording is safe on the phone.\nTry again", 1, RED,
                 to=[("F02", "Try again")])]),
        (80, [C("Microphone permission missing · OK", 1, RED)]),
        (70, [C("Kept your exact words: the clean version didn't pass the check.", 1, DIM)]),
    ]),
    "F18": ("Private note", [
        (60, [C("‹ Back"), G(1), C("Open | **Private**", 2, BLUE, to=[("F02", "Open")])]),
        (55, [C("Private ▾", 1, BLUE), G(2)]),
        (200, [C("- Ask about the test results\n- Call on Thursday")]),
        (50, [C("Kept on this phone only", 1, BLUE)]),
        (100, [C("0:31"), C("### ● Mic", 1, AMBER), C("Undo")]),
        (60, [C("Paste"), C("Photo (off)", 1, DIM), C("Attach (off)", 1, DIM)]),
    ]),
    "F19": ("Settings menu", [
        (60, [C("‹ Back", 1, to=[("F01", "Back")]), C("**Settings**", 3)]),
        (60, [C("**Capture types** · 7 types · default Idea ›", to=[("F20", "")])]),
        (60, [C("**Widget** · 4 buttons ›", to=[("F22", "")])]),
        (60, [C("**Vault** · Brain · 00 Inbox/Mobile ›", to=[("F23", "")])]),
        (60, [C("**Transcription** · Whisper small · Auto ›", to=[("F24", "")])]),
        (60, [C("**Audio & display** · Opus · 5 notes ›", to=[("F25", "")])]),
        (60, [C("**Storage** · 412 MB ›", to=[("F26", "")])]),
        (60, [C("**About & privacy** · No internet, no accounts ›")]),
    ]),
    "F20": ("Capture types", [
        (60, [C("‹ Back"), C("**Capture types**", 3)]),
        (60, [C("Default type", 2), C("Idea ▾", 1, AMBER)]),
        (55, [C("**Idea** · bullets", 3, AMBER), C("Bin", 1)]),
        (55, [C("**Task** · checklist ↑", 3, to=[("F21", "tap a type")]), C("Bin", 1)]),
        (55, [C("**Meeting** · to-dos ↑", 3), C("Bin", 1)]),
        (55, [C("Deleted Fix · **Undo**", 1, AMBER)]),
        (55, [C("**Private** · stays on phone ↑", 3, BLUE), C("Bin", 1)]),
        (55, [C("+ New type", to=[("F21", "new")]), C("+ New private type")]),
        (55, [C("Add a one-tap Photo icon to the home screen")]),
    ]),
    "F21": ("Type editor", [
        (60, [C("Cancel", 1, to=[("F20", "Cancel / Save")]), C("**Edit Task**", 2), C("**Save**", 1, AMBER)]),
        (55, [C("Name: Task")]),
        (55, [C("Short description: checklist")]),
        (90, [C("Icon grid (32)")]),
        (60, [C("Clean up with AI", 3), C("**On**", 1, AMBER)]),
        (110, [C("Instruction for the AI\nTurn what I said into tasks…")]),
        (60, [C("Bullets"), C("**Checklist**", 1, AMBER), C("Text + lists")]),
        (55, [C("Add a one-tap icon to the home screen")]),
        (55, [C("**Delete this type**", 1, RED)]),
    ]),
    "F22": ("Widget page", [
        (60, [C("‹ Back"), C("**Widget**", 3)]),
        (60, [C("The Brain widget shows up to 4 buttons", 1, DIM)]),
        (55, [C("Idea", 3), C("**On**", 1, AMBER)]),
        (55, [C("Task", 3), C("**On**", 1, AMBER)]),
        (55, [C("Meeting", 3), C("Off", 1)]),
        (55, [C("Open Brain button", 3), C("**On**", 1, AMBER)]),
        (55, [C("Photo button", 3), C("Off", 1)]),
        (60, [C("**Done**", 1, AMBER, to=[("F11", "widget updates")])]),
    ]),
    "F23": ("Vault", [
        (60, [C("‹ Back"), C("**Vault**", 3)]),
        (80, [C("**Vault folder**\nDocuments/Brain", 3), C("Change", 1)]),
        (80, [C("**Inbox folder inside the vault**\n00 Inbox/Mobile")]),
        (150, [C("Voice · Links · Text · Documents · Attachments", 1, DIM)]),
    ]),
    "F24": ("Transcription and AI model", [
        (60, [C("‹ Back"), C("**Transcription**", 3)]),
        (80, [C("**Speech model**\nInstalled (Whisper small)", 3), C("Replace", 1)]),
        (60, [C("**Auto**", 1, AMBER), C("Deutsch"), C("English")]),
        (90, [C("**AI cleanup model**\ngemma-4-E2B · 2.6 GB", 3)]),
        (60, [C("Replace"), C("**Remove model**", 1, RED)]),
        (70, [C("Copying… 48 %\n██████░░░░░░", 1, AMBER)]),
    ]),
    "F25": ("Audio & display", [
        (60, [C("‹ Back"), C("**Audio & display**", 3)]),
        (50, [C("Audio in the vault", 1, DIM)]),
        (60, [C("**Opus** (small)", 1, AMBER), C("WAV (lossless)")]),
        (50, [C("Notes on the home screen", 1, DIM)]),
        (60, [C("**5**", 1, AMBER), C("10"), C("20")]),
        (50, [C("Sharing a note", 1, DIM)]),
        (60, [C("**With voice**", 1, AMBER), C("Without")]),
    ]),
    "F26": ("Storage and About", [
        (60, [C("‹ Back"), C("**Storage**", 3)]),
        (130, [C("Speech model 243 MB\nWaiting recordings and shares 0 MB\nCard previews 12 MB\nDatabase 1 MB")]),
        (60, [C("Clean up leftovers"), C("Freed 8 MB", 1, GREEN)]),
        (50, [C("Waiting items are never deleted", 1, DIM)]),
        (130, [C("**About & privacy**\nNo internet permission. No accounts, no tracking. Files stay on the phone and in your vault.")]),
    ]),
    "F27": ("Share: already saved", [
        (250, [C("[the app you shared from, dimmed]", 1, DIM)]),
        (110, [C("**Already saved at 14:32**\nHow to build a raised bed")]),
        (60, [C("Open note"), C("**Save again**", 1, AMBER, to=[("F10", "Save again")])]),
        (50, [C("Close = don't save", 1, DIM)]),
    ]),
    "F28": ("Notification", [
        (50, [C("12:58", 2), C("● 0:42", 1, RED)]),
        (110, [C("**Recording · adding to note**\n0:42")]),
        (55, [C("Stop", 1, AMBER, to=[("F02", "Stop")]), C("Discard", 1, to=[("F13", "Discard")])]),
        (100, [C("**Transcribing**\nPart 2 of 3 · about 20 s left")]),
        (70, [C("**✓ Saved** · gone after 4 s", 1, GREEN)]),
        (110, [C("**⚠ Vault folder not available**\nRecording kept. Tap Retry.", 1, RED, to=[("F17", "Retry")])]),
    ]),
}

PHONE_W, PAD, CELL_GAP, ROW_GAP_IN = 440, 20, 10, 10
SCREEN_GAP_X, SECTION_GAP_Y, HEADER_H = 320, 360, 120


def build(note):
    import json
    nodes, edges, frames = [], [], {}
    # Phone height = tallest screen, so every phone in a row lines up.
    def height(rows):
        return PAD * 2 + sum(h for h, _ in rows) + ROW_GAP_IN * (len(rows) - 1)
    phone_h = max(height(rows) for _, rows in SCREENS.values())

    nodes.append({"id": "w-title", "type": "text", "x": 0, "y": -420, "width": 1400, "height": 260,
                  "text": "# Brain Capture wireframes\n\nEvery screen drawn with canvas cards. **Arrows start at the "
                          "control you tap and end at the screen it opens.** Amber = main action, red = delete or "
                          "error, green = saved, blue = private, grey = control, light grey = background or hint. "
                          "Each arrow has the colour of the control it starts from.\n\n" + note.lstrip("> "),
                  "color": HEAD})
    for sid, (col, row) in LAYOUT.items():
        name, rows = SCREENS[sid]
        x = col * (PHONE_W + SCREEN_GAP_X)
        top = row * (phone_h + SECTION_GAP_Y + HEADER_H) + HEADER_H
        if col == 0:
            nodes.append({"id": "w-row%d" % row, "type": "text", "x": 0, "y": top - HEADER_H, "width": 1200,
                          "height": 80, "text": "## " + ROW_NAMES[row], "color": HEAD})
        gid = "w-" + sid
        frames[sid] = (x, top, PHONE_W, phone_h)
        nodes.append({"id": gid, "type": "group", "label": "{} · {}".format(sid, name),
                      "x": x, "y": top, "width": PHONE_W, "height": phone_h, "color": PHONE})
        ry = top + PAD
        for ri, (h, cells) in enumerate(rows):
            inner = PHONE_W - PAD * 2 - CELL_GAP * (len(cells) - 1)
            total = sum(c.w for c in cells)
            cx = x + PAD
            for ci, cell in enumerate(cells):
                cw = round(inner * cell.w / total)
                if not cell.gap:
                    nid = "w-{}-{}-{}".format(sid, ri, ci)
                    node = {"id": nid, "type": "text", "text": cell.text, "x": cx, "y": ry,
                            "width": cw, "height": h, "color": cell.c or PLAIN}
                    nodes.append(node)
                    for target, label in cell.to:
                        if label not in SKIP_LABELS:
                            edges.append((nid, (cx, ry, cw, h), target, label, node["color"]))
                cx += cw + CELL_GAP
            ry += h + ROW_GAP_IN

    out_edges = []
    for n, (nid, (sx, sy, sw, sh), target, label, colour) in enumerate(edges):
        tx, ty, tw, th = frames[target]
        dx = (tx + tw / 2) - (sx + sw / 2)
        dy = (ty + th / 2) - (sy + sh / 2)
        if abs(dy) > th * 0.6:
            fs, ts = ("bottom", "top") if dy > 0 else ("top", "bottom")
        else:
            fs, ts = ("right", "left") if dx > 0 else ("left", "right")
        e = {"id": "w-e{}".format(n), "fromNode": nid, "fromSide": fs, "toNode": "w-" + target,
             "toSide": ts, "toEnd": "arrow", "color": colour}
        if label:
            e["label"] = label
        out_edges.append(e)
    return json.dumps({"nodes": nodes, "edges": out_edges}, ensure_ascii=False, indent="\t")
