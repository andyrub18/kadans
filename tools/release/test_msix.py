#!/usr/bin/env python3
"""python3 tools/release/test_msix.py: the Microsoft Store package's rules, without Windows (the workflow runs it first)."""
import sys
import tempfile
import unittest
import xml.dom.minidom
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import msix  # noqa: E402

CFG = "[Application]\r\napp.mainclass=kadans.Kadans\r\n\r\n[JavaOptions]\r\njava-options=-Djpackage.app-version=1.1.0\r\n"


class MsixTests(unittest.TestCase):
    def test_the_version_has_a_fourth_part_of_zero(self):
        self.assertEqual("1.1.0.0", msix.package_version("1.1.0"))
        with self.assertRaises(msix.MsixError):
            msix.package_version("1.1")

    def test_the_manifest_keeps_the_reserved_identity(self):
        text = msix.manifest("1.1.0")
        identity = xml.dom.minidom.parseString(text).getElementsByTagName("Identity")[0]
        self.assertEqual("AndersonRuban.Kadans", identity.getAttribute("Name"))
        self.assertEqual("CN=0F6500C3-0FC2-4B9B-9C79-28A0D1543941", identity.getAttribute("Publisher"))
        self.assertEqual("1.1.0.0", identity.getAttribute("Version"))
        self.assertEqual("x64", identity.getAttribute("ProcessorArchitecture"))
        # The startup task starts the launcher that passes --background.
        self.assertIn('Executable="KadansBackground.exe"', text)

    def test_every_logo_the_manifest_names_is_drawn(self):
        text = msix.manifest("1.1.0")
        self.assertEqual(["Square150x150Logo", "Square44x44Logo", "StoreLogo"], msix.named_logos(text))
        msix.check_logos(text)
        with tempfile.TemporaryDirectory() as empty, self.assertRaises(msix.MsixError):
            msix.check_logos(text, Path(empty))

    def test_the_store_mark_goes_among_the_java_options_once(self):
        marked = msix.store_cfg(CFG.replace("\r\n", "\n"))
        lines = marked.splitlines()
        self.assertEqual(msix.STORE_OPTION, lines[lines.index("[JavaOptions]") + 1])
        self.assertEqual(marked, msix.store_cfg(marked))
        self.assertIn(msix.STORE_OPTION, msix.store_cfg("[Application]\napp.mainclass=kadans.Kadans\n"))

    def test_the_second_launcher_passes_background(self):
        cfg = msix.background_cfg(msix.store_cfg(CFG.replace("\r\n", "\n")))
        self.assertTrue(cfg.endswith("[ArgOptions]\narguments=--background\n"))
        with self.assertRaises(msix.MsixError):
            msix.background_cfg(cfg)

    def test_the_layout_holds_both_launchers_the_manifest_and_the_logos(self):
        with tempfile.TemporaryDirectory() as scratch:
            app = Path(scratch) / "Kadans"
            (app / "app").mkdir(parents=True)
            (app / "runtime").mkdir()
            (app / "Kadans.exe").write_bytes(b"MZ launcher")
            (app / "app" / "Kadans.cfg").write_bytes(CFG.encode())
            folder = Path(scratch) / "package"

            msix.lay_out(app, folder, "1.1.0")

            self.assertEqual(b"MZ launcher", (folder / "KadansBackground.exe").read_bytes())
            main_cfg = (folder / "app" / "Kadans.cfg").read_bytes()
            background = (folder / "app" / "KadansBackground.cfg").read_bytes()
            self.assertIn(msix.STORE_OPTION.encode(), main_cfg)
            self.assertNotIn(b"[ArgOptions]", main_cfg)
            self.assertIn(b"[ArgOptions]\r\narguments=--background\r\n", background)  # CRLF kept, as jpackage wrote it
            self.assertIn(b"\r\n", main_cfg)
            self.assertNotIn(b"\r\r", main_cfg)
            self.assertTrue((folder / "AppxManifest.xml").is_file())
            self.assertTrue((folder / "Assets" / "Square44x44Logo.targetsize-256_altform-unplated.png").is_file())
            # jpackage's own folder is left as it was.
            self.assertFalse((app / "KadansBackground.exe").exists())

    def test_a_folder_that_is_not_jpackages_app_is_refused(self):
        with tempfile.TemporaryDirectory() as scratch:
            with self.assertRaises(msix.MsixError):
                msix.lay_out(Path(scratch), Path(scratch) / "package", "1.1.0")


if __name__ == "__main__":
    unittest.main()
