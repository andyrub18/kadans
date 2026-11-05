#!/usr/bin/env python3
"""The Microsoft Store's package of the desktop app, for .github/workflows/release.yml (docs/DEPLOYMENT.md → Releasing
the desktop app):

    python tools/release/msix.py <app folder> <out.msix>     # on Windows, with the Windows SDK (makepri, makeappx)

<app folder> is jpackage's Windows app (createDistributable: Kadans.exe, app\\, runtime\\). The package is that folder,
plus:
- AppxManifest.xml from clients/app/desktopApp/msix/: the identity Partner Center reserved, kadans.version as a.b.c.0;
- the logos (branding/generate.py), indexed into resources.pri by makepri, one file per screen scale;
- -Dkadans.distribution=msstore in the launcher's .cfg: the app then leaves its updates to the Store and its start at
  sign-in to the package (desktop/Distribution);
- KadansBackground.exe, the same launcher under another name, whose own .cfg passes --background: what the package's
  startup task starts at sign-in (a startup task passes no arguments).
Unsigned: the Store signs what it publishes. Standard library only.
"""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release  # noqa: E402

MSIX = release.ROOT / "clients" / "app" / "desktopApp" / "msix"
LAUNCHER = "Kadans"
BACKGROUND_LAUNCHER = "KadansBackground"
STORE_OPTION = "java-options=-Dkadans.distribution=msstore"
SDK_BIN = Path(r"C:\Program Files (x86)\Windows Kits\10\bin")


class MsixError(Exception):
    pass


def package_version(version):
    """kadans.version as a package's four parts: the Store keeps the fourth for itself, and it must be 0."""
    if not release.VERSION.match(version):
        raise MsixError(f"kadans.version must be MAJOR.MINOR.PATCH, got '{version}'")
    return version + ".0"


def manifest(version, template=MSIX / "AppxManifest.xml"):
    text = template.read_text(encoding="utf-8")
    if "{{VERSION}}" not in text:
        raise MsixError(f"{template.name} has no {{{{VERSION}}}} to fill")
    return text.replace("{{VERSION}}", package_version(version))


def store_cfg(cfg):
    """The launcher's .cfg with the Store's mark first among its Java options."""
    if STORE_OPTION in cfg.splitlines():
        return cfg
    lines = cfg.splitlines()
    if "[JavaOptions]" in lines:
        lines.insert(lines.index("[JavaOptions]") + 1, STORE_OPTION)
    else:
        lines += ["", "[JavaOptions]", STORE_OPTION]
    return "\n".join(lines) + "\n"


def background_cfg(cfg):
    """The second launcher's .cfg: the same app, with the argument a session's start carries."""
    if "[ArgOptions]" in cfg.splitlines():
        raise MsixError("the launcher already passes arguments: add --background to them instead of a second section")
    return cfg.rstrip("\n") + "\n\n[ArgOptions]\narguments=--background\n"


def named_logos(manifest_text):
    """The logos the manifest names (Assets\\Name.png): Windows finds each one's files for each scale through the PRI."""
    return sorted(set(re.findall(r"Assets\\([A-Za-z0-9]+)\.png", manifest_text)))


def check_logos(manifest_text, assets=MSIX / "Assets"):
    """Each named logo has its scale-100 file, which a screen at 100 % takes and the Store checks the size of."""
    missing = [name for name in named_logos(manifest_text) if not (assets / f"{name}.scale-100.png").is_file()]
    if missing:
        raise MsixError(f"no scale-100 file in {assets} for {', '.join(missing)}: run branding/generate.py")


def read_cfg(path):
    """A .cfg's text and its line ending: jpackage writes CRLF on Windows, and it is kept as written."""
    raw = path.read_bytes().decode("utf-8")
    return raw.replace("\r\n", "\n"), "\r\n" if "\r\n" in raw else "\n"


def write_cfg(path, text, newline):
    path.write_bytes(text.replace("\n", newline).encode("utf-8"))


def lay_out(app, folder, version):
    """The package's files in [folder], which must not exist: jpackage's app, the manifest, the logos, the Store's
    mark and the second launcher. Returns the manifest."""
    exe, cfg = app / f"{LAUNCHER}.exe", app / "app" / f"{LAUNCHER}.cfg"
    if not exe.is_file() or not cfg.is_file():
        raise MsixError(f"{app} is not jpackage's Windows app: no {LAUNCHER}.exe or app\\{LAUNCHER}.cfg")
    text = manifest(version)
    check_logos(text)
    shutil.copytree(app, folder)
    marked, newline = read_cfg(folder / "app" / f"{LAUNCHER}.cfg")
    marked = store_cfg(marked)
    write_cfg(folder / "app" / f"{LAUNCHER}.cfg", marked, newline)
    shutil.copy2(folder / f"{LAUNCHER}.exe", folder / f"{BACKGROUND_LAUNCHER}.exe")
    write_cfg(folder / "app" / f"{BACKGROUND_LAUNCHER}.cfg", background_cfg(marked), newline)
    (folder / "AppxManifest.xml").write_text(text, encoding="utf-8")
    shutil.copytree(MSIX / "Assets", folder / "Assets")
    return text


def sdk_tool(name):
    """makepri.exe or makeappx.exe of the newest Windows 10/11 SDK here (GitHub's Windows runners have one)."""
    found = sorted(SDK_BIN.glob(f"10.*/x64/{name}"), key=lambda p: [int(n) for n in p.parts[-3].split(".")])
    if not found:
        raise MsixError(f"no {name} under {SDK_BIN}: the Windows SDK is needed")
    return found[-1]


def run(command):
    print("+", " ".join(str(c) for c in command), flush=True)
    subprocess.run([str(c) for c in command], check=True)


def resources_pri(folder, manifest_text, makepri):
    """resources.pri, the index Windows reads to pick each logo's file for the screen's scale. Made from the manifest
    and the logos alone, in a folder of their own: indexing jpackage's thousands of files would only slow it."""
    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch) / "pri"
        root.mkdir()
        (root / "AppxManifest.xml").write_text(manifest_text, encoding="utf-8")
        shutil.copytree(MSIX / "Assets", root / "Assets")
        config = Path(scratch) / "priconfig.xml"
        run([makepri, "createconfig", "/cf", config, "/dq", "en-US", "/pv", "10.0.0", "/o"])
        run([makepri, "new", "/pr", root, "/cf", config, "/mn", root / "AppxManifest.xml", "/of", folder / "resources.pri", "/o"])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("app", type=Path, help="jpackage's Windows app folder")
    parser.add_argument("out", type=Path, help="the .msix to write")
    args = parser.parse_args(argv)
    try:
        version = release.app_version()
        with tempfile.TemporaryDirectory() as scratch:
            folder = Path(scratch) / "package"
            text = lay_out(args.app, folder, version)
            resources_pri(folder, text, sdk_tool("makepri.exe"))
            args.out.parent.mkdir(parents=True, exist_ok=True)
            run([sdk_tool("makeappx.exe"), "pack", "/d", folder, "/p", args.out, "/o"])
        print(f"{args.out}: Kadans {package_version(version)}, {args.out.stat().st_size // (1024 * 1024)} MB")
    except (MsixError, release.ReleaseError, subprocess.CalledProcessError) as e:
        print(f"msix: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
