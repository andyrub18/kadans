#!/usr/bin/env python3
"""python3 tools/release/test_release.py: the release script's rules (the workflow's dry run runs it too)."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release  # noqa: E402


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.dir = Path(self.scratch.name)
        self.out = self.dir / "out"
        self.notes = self.dir / "releases"
        self.out.mkdir()
        self.notes.mkdir()
        for name in release.INSTALLERS:
            (self.out / name).write_bytes(name.encode())

    def tearDown(self):
        self.scratch.cleanup()

    def test_the_tag_must_be_the_apps_version(self):
        self.assertEqual("1.1.0", release.checked_tag("v1.1.0", "push", "1.1.0"))
        with self.assertRaises(release.ReleaseError):
            release.checked_tag("v1.2.0", "push", "1.1.0")
        # A pull request's dry run has no tag.
        self.assertEqual("1.1.0", release.checked_tag("42/merge", "pull_request", "1.1.0"))

    def test_the_repositorys_version_is_one(self):
        self.assertRegex(release.app_version(), release.VERSION)

    def test_a_release_without_notes_is_its_version(self):
        latest = release.assemble(self.out, "1.1.0", self.notes)

        self.assertEqual({"version": "1.1.0"}, latest)
        self.assertEqual(latest, json.loads((self.out / "latest.json").read_text()))
        sums = (self.out / "SHA256SUMS").read_text().splitlines()
        self.assertEqual(release.INSTALLERS, [line.split("  ")[1] for line in sums])
        self.assertIn("https://kadansplanning.com/download", (self.out / "notes.md").read_text())

    def test_notes_and_a_minimum_are_carried(self):
        (self.notes / "1.1.0.json").write_text(json.dumps(
            {"minimumVersion": "1.0.0", "notes": {"en": "Faster reminders.", "fr": "Rappels plus rapides."}}))

        latest = release.assemble(self.out, "1.1.0", self.notes)

        self.assertEqual("1.0.0", latest["minimumVersion"])
        self.assertEqual("Rappels plus rapides.", latest["notes"]["fr"])
        self.assertTrue((self.out / "notes.md").read_text().startswith("Faster reminders."))

    def test_a_wrong_notes_file_stops_the_release(self):
        for bad in [
            {"minimumVersion": "1.2.0"},                       # above the release
            {"minimumVersion": "latest"},
            {"notes": {"fr": "Seulement en français."}},        # English at least
            {"notes": {"en": "Fine.", "es": "No."}},             # en, fr, ht only
            {"notes": {"en": " "}},
            {"version": "1.1.0"},                                # the version is the tag's, not the file's
        ]:
            (self.notes / "1.1.0.json").write_text(json.dumps(bad))
            with self.assertRaises(release.ReleaseError, msg=str(bad)):
                release.assemble(self.out, "1.1.0", self.notes)

    def test_a_missing_or_empty_installer_stops_the_release(self):
        (self.out / "kadans-windows-x64.msi").unlink()
        with self.assertRaises(release.ReleaseError):
            release.assemble(self.out, "1.1.0", self.notes)
        (self.out / "kadans-windows-x64.msi").write_bytes(b"")
        with self.assertRaises(release.ReleaseError):
            release.assemble(self.out, "1.1.0", self.notes)


if __name__ == "__main__":
    unittest.main()
