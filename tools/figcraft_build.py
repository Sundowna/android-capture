"""Build "Brain Capture.figcraft": every screen (F01-F34) as editable FigCraft layers with working
prototype links, the pixel mockup beside it, a spec card, the colour legend and two flows.

Data comes from tools/wireframes.py (screens, controls, links, specs) and docs/screens/*.png.
Usage:  python tools/figcraft_build.py <figcraft-cli.exe> <out.figcraft>
Called by tools/sync-vault-docs.py when FigCraft is installed (ArtCraft Launcher, or a portable copy).
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("wireframes", os.path.join(REPO, "tools", "wireframes.py"))
W = importlib.util.module_from_spec(spec)
spec.loader.exec_module(W)

PAGE_BG = "#1E2023"
CARD_BG = "#2B2E33"
TEXT = "#EEF0F2"
PIC_W, PIC_H = 390, 844
COL_W = W.PHONE_W + 30 + PIC_W + 220     # wireframe + picture + gap


def spec_height(spec):
    """About 62 characters per line at 11 px in a 376 px box, 15 px per line, plus padding."""
    lines = sum(len(part) // 62 + 1 for part in spec) + 2
    return 24 + 15 * lines


def tint(hex_colour, amount=0.32):
    """Card fill: the colour mixed into the dark card background, so text stays readable."""
    h = hex_colour.lstrip("#")
    c = [int(h[i:i + 2], 16) for i in (0, 2, 4)]
    b = [int(CARD_BG[1 + i:3 + i], 16) for i in (0, 2, 4)]
    return "#" + "".join("%02X" % round(b[i] + (c[i] - b[i]) * amount) for i in range(3))


def plain(text):
    return re.sub(r"\*\*(.+?)\*\*", r"\1", text)


def screens_png():
    d = os.path.join(REPO, "docs", "screens")
    return {f[:3]: os.path.join(d, f) for f in os.listdir(d) if re.match(r"F\d\d-.*\.png$", f)}


def build_script(out_path):
    steps = []

    def add(command, params, name=None):
        s = {"command": command, "params": params}
        if name:
            s["as"] = name
        steps.append(s)

    ref = lambda n: {"$ref": n}
    pngs = screens_png()
    add("page.rename", {"name": "Brain Capture"})
    add("page.setBackground", {"color": PAGE_BG})

    # Title and legend.
    add("node.create", {"type": "frame", "name": "About this file", "x": 0, "y": -900, "width": 1500, "height": 300,
                        "props": {"fill": CARD_BG, "cornerRadius": 10, "stroke": W.HEAD, "strokeWeight": 1.5}}, "about")
    add("node.create", {"type": "text", "parent": ref("about"), "x": 24, "y": 20, "width": 1450, "name": "About",
                        "text": "Brain Capture: the whole app in FigCraft\n\n"
                                "Every screen is a frame of editable layers (left) with its pixel mockup beside it (right). "
                                "Press Play: controls that lead somewhere are clickable and open the screen they lead to; "
                                "Back goes back. Two flows: 'From the phone's home screen' (F29) and 'Inside the app' (F01). "
                                "Under each screen a teal card says what it does, which code does it and which tests check it. "
                                "Generated from the app repo (tools/figcraft_build.py) on every commit: edit the repo, not this file. "
                                "Full specs: FEATURES.md, FLOWS.md, SPEC.md, E2E-TESTS.md in the same folder.",
                        "props": {"fontSize": 20, "fill": TEXT}})
    add("node.create", {"type": "frame", "name": "Colours", "x": 1600, "y": -900, "width": 1600, "height": 560,
                        "props": {"fill": CARD_BG, "cornerRadius": 10, "stroke": W.HEAD, "strokeWeight": 1.5}}, "legend")
    add("node.create", {"type": "text", "parent": ref("legend"), "x": 24, "y": 16, "text": "Colours",
                        "props": {"fontSize": 24, "fontStyle": "Bold", "fill": TEXT}})
    for i, (col, name, meaning) in enumerate(W.LEGEND):
        n = "leg%d" % i
        add("node.create", {"type": "frame", "parent": ref("legend"), "name": name,
                            "x": 24 + (i % 2) * 780, "y": 64 + (i // 2) * 96, "width": 760, "height": 84,
                            "props": {"fill": tint(col), "stroke": col, "strokeWeight": 1.5, "cornerRadius": 10}}, n)
        add("node.create", {"type": "text", "parent": ref(n), "x": 14, "y": 10, "width": 730,
                            "text": "%s: %s" % (name, meaning), "props": {"fontSize": 15, "fill": TEXT}})

    # Screens.
    n_rows = max(r for _, r in W.LAYOUT.values()) + 1
    row_h, row_spec = [], []
    for row in range(n_rows):
        ids = [s for s, (_, r) in W.LAYOUT.items() if r == row]
        ui = max(W.PAD * 2 + sum(h for h, _ in W.SCREENS[s][1]) + W.ROW_GAP_IN * (len(W.SCREENS[s][1]) - 1) for s in ids)
        sh = max(spec_height(W.SCREENS[s][2]) for s in ids)
        row_spec.append(sh)
        row_h.append(max(ui + 20 + sh, PIC_H))
    row_y, y = [], 0
    for row in range(n_rows):
        add("node.create", {"type": "text", "x": 0, "y": y, "text": W.ROW_NAMES[row], "name": "Row: " + W.ROW_NAMES[row],
                            "props": {"fontSize": 40, "fontStyle": "Bold", "fill": W.HEAD}})
        row_y.append(y + 90)
        y += 90 + row_h[row] + 260

    links = []
    for sid, (col, row) in W.LAYOUT.items():
        name, rows, (fn, tech, tests) = W.SCREENS[sid]
        x, top = col * COL_W, row_y[row]
        fr = "f" + sid
        add("node.create", {"type": "frame", "name": "%s · %s" % (sid, name), "x": x, "y": top,
                            "width": W.PHONE_W, "height": row_h[row],
                            "props": {"fill": W.PHONE, "cornerRadius": 28, "clip": True}}, fr)
        ry = W.PAD
        for ri, (h, cells) in enumerate(rows):
            inner = W.PHONE_W - W.PAD * 2 - W.CELL_GAP * (len(cells) - 1)
            total = sum(c.w for c in cells)
            cx = W.PAD
            for ci, cell in enumerate(cells):
                cw = round(inner * cell.w / total)
                if not cell.gap:
                    cid = "c%s_%d_%d" % (sid, ri, ci)
                    colour = cell.c or W.PLAIN
                    label = plain(cell.text)
                    add("node.create", {"type": "frame", "parent": ref(fr), "name": label.split("\n")[0][:40] or "Card",
                                        "x": cx, "y": ry, "width": cw, "height": h,
                                        "props": {"fill": tint(colour), "stroke": colour, "strokeWeight": 1.5,
                                                  "cornerRadius": 10, "clip": True}}, cid)
                    add("node.create", {"type": "text", "parent": ref(cid), "x": 10, "y": 8, "width": max(cw - 20, 10),
                                        "text": label, "props": {"fontSize": 13, "fill": TEXT}}, "t" + cid)
                    first = cell.text.splitlines()[0] if cell.text else ""
                    if first.startswith("**") and first.endswith("**"):  # a bold first line, as in the wireframes
                        add("text.style", {"id": ref("t" + cid), "start": 0, "end": len(plain(first)), "fontStyle": "Bold"})
                    for target, lab in cell.to:
                        links.append((cid, target, lab))
                cx += cw + W.CELL_GAP
            ry += h + W.ROW_GAP_IN
        sp = "s" + sid
        add("node.create", {"type": "frame", "parent": ref(fr), "name": "Spec", "x": W.PAD, "y": row_h[row] - row_spec[row] - W.PAD,
                            "width": W.PHONE_W - W.PAD * 2, "height": row_spec[row],
                            "props": {"fill": tint(W.SPEC), "stroke": W.SPEC, "strokeWeight": 1.5, "cornerRadius": 10, "clip": True}}, sp)
        add("node.create", {"type": "text", "parent": ref(sp), "x": 12, "y": 10, "width": W.PHONE_W - W.PAD * 2 - 24,
                            "text": "Function: %s\n\nTech: %s\n\nTests: %s" % (fn, tech, tests),
                            "props": {"fontSize": 11, "fill": TEXT}})
        if sid in pngs:
            add("image.place", {"path": pngs[sid], "x": x + W.PHONE_W + 30, "y": top, "width": PIC_W, "height": PIC_H,
                                "name": "%s · mockup" % sid})

    # Prototype links: every control to its screen; Back goes back.
    for cid, target, lab in links:
        if lab == "Back":
            add("proto.interaction.add", {"id": ref(cid), "trigger": {"type": "onClick"}, "action": {"type": "back"}})
        else:
            add("proto.interaction.add", {"id": ref(cid), "dest": ref("f" + target),
                                          "transition": {"type": "dissolve"}, "durationMs": 200})
    add("proto.flow.add", {"start": ref("fF29"), "name": "From the phone's home screen"})
    add("proto.flow.add", {"start": ref("fF01"), "name": "Inside the app"})
    add("select.none", {})
    return steps, len(links)


def main():
    cli, out = sys.argv[1], sys.argv[2]
    steps, n_links = build_script(out)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(steps, f, ensure_ascii=False)
        script = f.name
    try:
        r = subprocess.run([cli, "run", script, "--out", out], capture_output=True, text=True, encoding="utf-8")
        errors = [l for l in r.stdout.splitlines() if '"ok":false' in l.replace(" ", "") or '"error"' in l]
        if r.returncode != 0 or errors:
            print("figcraft: failed (exit %d)\n%s\n%s" % (r.returncode, "\n".join(errors[:5]), r.stderr[-2000:]))
            return 1
        print("figcraft: %d steps, %d links -> %s" % (len(steps), n_links, out))
        return 0
    finally:
        os.remove(script)


if __name__ == "__main__":
    sys.exit(main())
