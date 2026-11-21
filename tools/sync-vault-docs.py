"""Copy FEATURES.md, FLOWS.md and docs/screens into Walter's vault.

Runs from the Git hooks (post-commit, post-merge) and can be run by hand:
    python tools/sync-vault-docs.py

The repo is the source of truth. The vault copy is read-only for people:
it is overwritten on every sync. FLOWS.md gets each flow's screen picture
embedded under its heading. Files are only written when they changed.
"""
import os
import sys
sys.dont_write_bytecode = True  # no tools/__pycache__ in the repo
import re
import shutil
import subprocess

VAULT_DIR = r"D:\BrainTest\SecondBrain\Open\Brain\10 Projects\2nd Brain\Android Voice App\Documentation"
CANVAS_URL = "https://claude.ai/code/artifact/e79ec427-0cc3-4021-a7e0-8f26ad13846b"
SCREEN_RE = re.compile(r"^F\d\d-.*\.png$")


def git(*args):
    try:
        return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return "?"


def write_if_changed(path, data):
    if os.path.exists(path):
        with open(path, "rb") as f:
            if f.read() == data:
                return False
    with open(path, "wb") as f:
        f.write(data)
    return True


VAULT_ROOT = r"D:\BrainTest\SecondBrain\Open\Brain"
CANVAS_NAME = "Brain Capture Flows.canvas"
WIREFRAME_NAME = "Brain Capture Wireframes.canvas"
IMG_W, IMG_H, TXT_W, GAP, ROW_GAP = 300, 650, 420, 20, 260


def lines_of(path):
    with open(path, encoding="utf-8") as f:
        return f.read().split("\n")


def parse_flows(lines):
    """Return (map_text, sections) where sections = [(title, [(fid, heading, body_lines)])]."""
    sections, map_lines, cur_sec, cur_flow, in_map = [], [], None, None, False
    for line in lines:
        if line.startswith("## "):
            in_map = line.strip() == "## Map"
            cur_flow = None
            if not in_map:
                cur_sec = (line[3:].strip(), [])
                sections.append(cur_sec)
            continue
        if in_map:
            map_lines.append(line)
            continue
        m = re.match(r"### (F\d\d) · (.*)", line)
        if m and cur_sec is not None:
            cur_flow = (m.group(1), m.group(2).strip(), [])
            cur_sec[1].append(cur_flow)
        elif cur_flow is not None:
            cur_flow[2].append(line)
    return "\n".join(map_lines).strip("\n"), sections


def parse_edges(map_text, sections):
    edges = []

    def add(a, b):
        if a != b and (a, b) not in edges:
            edges.append((a, b))

    context = None
    for line in map_text.split("\n"):
        if line.startswith("Home"):
            context = "F01"
        elif line.startswith("Note screen"):
            context = "F02"
        elif not line.startswith(" "):
            context = None
        ids = re.findall(r"F\d\d", re.sub(r"F\d\d–F\d\d", lambda m: m.group(0)[:3], line))
        if context and not ids[:1] == [context]:
            ids = [context] + ids
        for a, b in zip(ids, ids[1:]):
            add(a, b)
    for _, flows in sections:
        for fid, _, body in flows:
            for line in body:
                for target in re.findall(r"→ (F\d\d)", line):
                    add(fid, target)
    return edges


def build_canvas(lines, by_id, note):
    import json
    map_text, sections = parse_flows(lines)
    shots_dir = os.path.relpath(os.path.join(VAULT_DIR, "Screens"), VAULT_ROOT).replace("\\", "/")
    nodes, edges, known = [], [], set()
    cell_w = IMG_W + GAP + TXT_W
    nodes.append({"id": "title", "type": "text", "x": 0, "y": -900, "width": 1200, "height": 160,
                  "text": "# Brain Capture flows\n\n" + note.lstrip("> ") +
                          " Each flow: its screen on the left, its steps on the right. Arrows = where a flow leads."})
    nodes.append({"id": "map", "type": "text", "x": 0, "y": -700, "width": 1500, "height": 560,
                  "text": "## Map\n\n" + map_text})
    y = 0
    for si, (title, flows) in enumerate(sections):
        if not flows:
            continue
        per_row = 6
        rows = (len(flows) + per_row - 1) // per_row
        width = min(len(flows), per_row) * (cell_w + GAP * 3) + GAP * 3
        height = rows * (IMG_H + GAP * 3) + GAP * 3
        nodes.append({"id": "sec%d" % si, "type": "group", "label": title,
                      "x": -GAP * 2, "y": y - GAP * 2, "width": width, "height": height})
        for fi, (fid, heading, body) in enumerate(flows):
            x = (fi % per_row) * (cell_w + GAP * 3) + GAP
            fy = y + (fi // per_row) * (IMG_H + GAP * 3) + GAP
            text = "### {} · {}\n".format(fid, heading) + "\n".join(body).strip("\n")
            if fid in by_id:
                nodes.append({"id": fid, "type": "file", "file": shots_dir + "/" + by_id[fid],
                              "x": x, "y": fy, "width": IMG_W, "height": IMG_H})
                nodes.append({"id": fid + "-steps", "type": "text", "text": text,
                              "x": x + IMG_W + GAP, "y": fy, "width": TXT_W, "height": IMG_H})
            else:
                nodes.append({"id": fid, "type": "text", "text": text,
                              "x": x, "y": fy, "width": cell_w, "height": IMG_H})
            known.add(fid)
        y += height + ROW_GAP
    for a, b in parse_edges(map_text, sections):
        if a in known and b in known:
            edges.append({"id": "e-%s-%s" % (a, b), "fromNode": a, "fromSide": "bottom",
                          "toNode": b, "toSide": "top", "toEnd": "arrow"})
    return json.dumps({"nodes": nodes, "edges": edges}, ensure_ascii=False, indent="\t")


FIGCRAFT_NAME = "Brain Capture.figcraft"
FIGCRAFT_CLIS = [r"C:\Program Files\ArtCraft\FigCraft\figcraft-cli.exe",  # installed by the ArtCraft Launcher
                 r"D:\dev\figcraft\*\figcraft-cli.exe"]                  # a portable copy


def build_figcraft(repo):
    import glob
    import hashlib
    clis = [c for pattern in FIGCRAFT_CLIS for c in sorted(glob.glob(pattern))]
    if not clis:
        return []
    clis = [clis[0]]
    inputs = [os.path.join(repo, "tools", f) for f in ("wireframes.py", "figcraft_build.py")]
    shots = os.path.join(repo, "docs", "screens")
    inputs += sorted(os.path.join(shots, f) for f in os.listdir(shots) if SCREEN_RE.match(f))
    h = hashlib.sha256(clis[-1].encode())
    for p in inputs:
        with open(p, "rb") as f:
            h.update(f.read())
    digest = h.hexdigest()
    stamp = os.path.join(git("rev-parse", "--git-common-dir"), "figcraft-inputs.sha")
    out = os.path.join(VAULT_DIR, FIGCRAFT_NAME)
    try:
        with open(stamp) as f:
            if f.read().strip() == digest and os.path.exists(out):
                return []
    except OSError:
        pass
    r = subprocess.run([sys.executable, "-I", os.path.join(repo, "tools", "figcraft_build.py"), clis[-1], out],
                       capture_output=True, text=True)
    print((r.stdout + r.stderr).strip())
    if r.returncode != 0:
        return []
    with open(stamp, "w") as f:
        f.write(digest)
    return [FIGCRAFT_NAME]


def main():
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    if not os.path.isdir(os.path.dirname(VAULT_DIR)):
        print("sync-vault-docs: vault folder not found, skipped")
        return 0
    screens_src = os.path.join(repo, "docs", "screens")
    screens_dst = os.path.join(VAULT_DIR, "Screens")
    os.makedirs(screens_dst, exist_ok=True)
    changed = []

    # Screens: copy new/changed pictures, remove ones that no longer exist.
    src_shots = sorted(f for f in os.listdir(screens_src) if SCREEN_RE.match(f))
    for name in src_shots:
        with open(os.path.join(screens_src, name), "rb") as f:
            if write_if_changed(os.path.join(screens_dst, name), f.read()):
                changed.append("Screens/" + name)
    for name in os.listdir(screens_dst):
        if SCREEN_RE.match(name) and name not in src_shots:
            os.remove(os.path.join(screens_dst, name))
            changed.append("Screens/" + name + " (removed)")
    by_id = {name[:3]: name for name in src_shots}

    # Stamp = last commit that touched the docs, so unrelated commits don't rewrite the files.
    stamp = git("log", "-1", "--format=%cd · %h", "--date=format:%d.%m.%Y %H:%M",
                "--", "FEATURES.md", "FLOWS.md", "docs/screens")
    note = ("> Copy from the app repo (android-capture), updated automatically on every commit. "
            "Docs last changed: {}. Edit the repo, not this copy. "
            "Screens also on the [design canvas]({}).").format(stamp, CANVAS_URL)

    # FEATURES.md: plain copy with the note under the title.
    with open(os.path.join(repo, "FEATURES.md"), encoding="utf-8") as f:
        lines = f.read().split("\n")
    out = lines[:1] + ["", note] + lines[1:]
    if write_if_changed(os.path.join(VAULT_DIR, "FEATURES.md"), "\n".join(out).encode("utf-8")):
        changed.append("FEATURES.md")

    # FLOWS.md: note under the title, screen picture under each "### Fnn" heading.
    with open(os.path.join(repo, "FLOWS.md"), encoding="utf-8") as f:
        lines = f.read().split("\n")
    out = []
    for i, line in enumerate(lines):
        out.append(line)
        if i == 0:
            out += ["", note]
        m = re.match(r"### (F\d\d) ", line)
        if m and m.group(1) in by_id:
            out += ["", "![[Screens/{}|260]]".format(by_id[m.group(1)])]
    if write_if_changed(os.path.join(VAULT_DIR, "FLOWS.md"), "\n".join(out).encode("utf-8")):
        changed.append("FLOWS.md")

    # Extra docs copied as they are (with the note under the title).
    for extra in ("E2E-TESTS.md", "SPEC.md"):
        p = os.path.join(repo, extra)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                lines = f.read().split("\n")
            if write_if_changed(os.path.join(VAULT_DIR, extra),
                                "\n".join(lines[:1] + ["", note] + lines[1:]).encode("utf-8")):
                changed.append(extra)

    # Obsidian canvas: one row per FLOWS.md section, each flow = screen + its steps, arrows between flows.
    canvas = build_canvas(lines_of(os.path.join(repo, "FLOWS.md")), by_id, note)
    if write_if_changed(os.path.join(VAULT_DIR, CANVAS_NAME), canvas.encode("utf-8")):
        changed.append(CANVAS_NAME)

    # Wireframe canvas: screens drawn with canvas cards, arrows from the tapped control (tools/wireframes.py).
    import importlib.util
    spec = importlib.util.spec_from_file_location("wireframes", os.path.join(repo, "tools", "wireframes.py"))
    wireframes = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(wireframes)
    if write_if_changed(os.path.join(VAULT_DIR, WIREFRAME_NAME), wireframes.build(note, os.path.relpath(VAULT_DIR, VAULT_ROOT).replace(os.sep, "/")).encode("utf-8")):
        changed.append(WIREFRAME_NAME)

    # FigCraft file: rebuilt only when its inputs changed and FigCraft is installed on this computer.
    changed += build_figcraft(repo)

    print("sync-vault-docs: " + (", ".join(changed) if changed else "vault copy already up to date"))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # never block a commit
        print("sync-vault-docs: failed: {}".format(e))
        sys.exit(0)
