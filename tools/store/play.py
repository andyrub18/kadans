#!/usr/bin/env python3
"""Google Play's store listing (store/play/, README there):

    python3 tools/store/play.py check                    # every text within Play's limits (CI runs it)
    python3 tools/store/play.py crop shot1.png shot2.png  # screenshots trimmed to Play's 2:1, beside them as *-play.png

Play refuses a screenshot whose long side is more than twice its short side; a phone's full-screen capture (1080 x
2340, 20:9) is just over it. `crop` cuts the difference off the top (the status bar) and a third of it off the bottom
(the navigation bar), and writes a 24-bit PNG (Play takes no transparency). It needs ImageMagick (`convert`).
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LISTING = ROOT / "store" / "play"
LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
CHANGELOG_LIMIT = 500


def check():
    problems = []
    locales = sorted(p for p in LISTING.iterdir() if p.is_dir())
    for locale in locales:
        for name, limit in LIMITS.items():
            path = locale / name
            if not path.is_file():
                problems.append(f"{locale.name}/{name}: missing")
                continue
            length = len(path.read_text(encoding="utf-8").rstrip("\n"))
            if not 0 < length <= limit:
                problems.append(f"{locale.name}/{name}: {length} characters (1 to {limit})")
        for changelog in sorted((locale / "changelogs").glob("*.txt")):
            length = len(changelog.read_text(encoding="utf-8").rstrip("\n"))
            if length > CHANGELOG_LIMIT:
                problems.append(f"{locale.name}/changelogs/{changelog.name}: {length} characters (at most {CHANGELOG_LIMIT})")
    for problem in problems:
        print(problem)
    print("store/play is within Play's limits." if not problems else f"{len(problems)} problem(s).")
    return 1 if problems else 0


def size(path):
    out = subprocess.run(["identify", "-format", "%w %h", str(path)], check=True, capture_output=True, text=True).stdout
    width, height = map(int, out.split())
    return width, height


def crop(paths):
    for name in paths:
        path = Path(name)
        width, height = size(path)
        short, long_ = sorted((width, height))
        excess = max(0, long_ - 2 * short)
        target = path.with_name(path.stem + "-play.png")
        if width <= height:  # portrait: off the top (status bar) and the bottom (navigation bar)
            top = excess - excess // 3
            geometry = f"{width}x{height - excess}+0+{top}"
        else:
            left = excess // 2
            geometry = f"{width - excess}x{height}+{left}+0"
        subprocess.run(["convert", str(path), "-crop", geometry, "+repage", "-background", "white", "-flatten",
                        "-alpha", "off", "-strip", f"PNG24:{target}"], check=True)
        print(f"{target.name}: {size(target)[0]} x {size(target)[1]}")
    return 0


def main(argv):
    if argv[:1] == ["check"]:
        return check()
    if argv[:1] == ["crop"] and argv[1:]:
        return crop(argv[1:])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
