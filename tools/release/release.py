#!/usr/bin/env python3
"""The desktop release's own files, for .github/workflows/release.yml (docs/DEPLOYMENT.md → Releasing the desktop app):

    python3 tools/release/release.py version --ref v1.1.0 --event push   # the tag must be kadans.version: version=1.1.0
    python3 tools/release/release.py assemble out/                       # latest.json, SHA256SUMS, notes.md beside the installers

latest.json is what every installed desktop app reads once a day (AppUpdates): {version, minimumVersion?, notes?}.
The minimum and the notes come from clients/app/releases/<version>.json when it exists (its README). Standard library
only.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROPERTIES = ROOT / "clients" / "app" / "gradle.properties"
RELEASES = ROOT / "clients" / "app" / "releases"
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
LANGUAGES = {"en", "fr", "ht"}

# The names never change from one release to the next: the site's buttons and latest.json's address point at the
# latest release's file of that name (deploy/nginx/site-https.conf.template).
INSTALLERS = ["kadans-linux-amd64.deb", "kadans-linux-x86_64.rpm", "kadans-windows-x64.msi"]
DOWNLOAD_PAGE = "https://kadansplanning.com/download"


class ReleaseError(Exception):
    pass


def app_version(properties=PROPERTIES):
    for line in properties.read_text(encoding="utf-8").splitlines():
        key, _, value = line.partition("=")
        if key.strip() == "kadans.version":
            value = value.strip()
            if not VERSION.match(value):
                raise ReleaseError(f"kadans.version in {properties.name} must be MAJOR.MINOR.PATCH, got '{value}'")
            return value
    raise ReleaseError(f"no kadans.version in {properties}")


def parts(version):
    return tuple(int(p) for p in version.split("."))


def checked_tag(ref, event, version):
    """A tag's push releases kadans.version and nothing else: the tag and the file must agree."""
    if event == "push" and ref != f"v{version}":
        raise ReleaseError(f"The tag is {ref}, but clients/app/gradle.properties says kadans.version={version}: "
                           f"push the tag v{version}, or change kadans.version first.")
    return version


def extras(version, releases=RELEASES):
    """clients/app/releases/<version>.json, checked: what latest.json carries besides the version."""
    path = releases / f"{version}.json"
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise ReleaseError(f"{path.name} is not JSON: {e}")
    unknown = set(data) - {"minimumVersion", "notes"}
    if unknown:
        raise ReleaseError(f"{path.name}: unknown keys {sorted(unknown)} (minimumVersion, notes)")
    out = {}
    if "minimumVersion" in data:
        minimum = data["minimumVersion"]
        if not isinstance(minimum, str) or not VERSION.match(minimum):
            raise ReleaseError(f"{path.name}: minimumVersion must be MAJOR.MINOR.PATCH")
        if parts(minimum) > parts(version):
            raise ReleaseError(f"{path.name}: minimumVersion {minimum} is above the release itself ({version})")
        out["minimumVersion"] = minimum
    if "notes" in data:
        notes = data["notes"]
        if not isinstance(notes, dict) or not notes or set(notes) - LANGUAGES or "en" not in notes:
            raise ReleaseError(f"{path.name}: notes are by language (en, fr, ht), English at least")
        if not all(isinstance(v, str) and v.strip() for v in notes.values()):
            raise ReleaseError(f"{path.name}: every note is a sentence")
        out["notes"] = {k: notes[k].strip() for k in sorted(notes)}
    return out


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def assemble(folder, version, releases=RELEASES):
    """Beside the three installers: latest.json, SHA256SUMS and the release's text (notes.md, not published)."""
    missing = [name for name in INSTALLERS if not (folder / name).is_file() or (folder / name).stat().st_size == 0]
    if missing:
        raise ReleaseError(f"missing from {folder}: {', '.join(missing)}")
    latest = {"version": version, **extras(version, releases)}
    (folder / "latest.json").write_text(json.dumps(latest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    sums = "".join(f"{sha256(folder / name)}  {name}\n" for name in INSTALLERS)
    (folder / "SHA256SUMS").write_text(sums, encoding="utf-8")
    lines = [latest["notes"]["en"], ""] if "notes" in latest else []
    lines += [
        f"Download Kadans for your computer from {DOWNLOAD_PAGE}, or below.",
        "",
        "| File | For |",
        "|------|-----|",
        "| `kadans-linux-amd64.deb` | Ubuntu, Debian, Mint, Pop!_OS: `sudo apt install ./kadans-linux-amd64.deb` |",
        "| `kadans-linux-x86_64.rpm` | Fedora, openSUSE: `sudo dnf install ./kadans-linux-x86_64.rpm` |",
        "| `kadans-windows-x64.msi` | Windows 10 and 11 |",
        "",
        "SHA-256:",
        "```",
        sums.rstrip("\n"),
        "```",
    ]
    if "minimumVersion" in latest:
        lines += ["", f"Versions before {latest['minimumVersion']} no longer work with the server: they ask to update."]
    (folder / "notes.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return latest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    v = commands.add_parser("version")
    v.add_argument("--ref", required=True)
    v.add_argument("--event", required=True)
    a = commands.add_parser("assemble")
    a.add_argument("folder", type=Path)
    args = parser.parse_args(argv)
    try:
        version = app_version()
        if args.command == "version":
            print(f"version={checked_tag(args.ref, args.event, version)}")
        else:
            latest = assemble(args.folder, version)
            print(json.dumps(latest, indent=2, ensure_ascii=False))
    except ReleaseError as e:
        print(f"release: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
