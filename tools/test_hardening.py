"""Offline stdlib regression tests for APK comparison and string audits."""
import importlib.util
import tempfile
import subprocess
import sys
import unittest
import warnings
import zipfile
from pathlib import Path

from compare_apks import compare, entries

spec = importlib.util.spec_from_file_location("audit_strings", Path(__file__).with_name("audit-app-strings.py"))
strings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(strings)


class ReproducibilityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.a = Path(self.temp.name) / "a"
        self.b = Path(self.temp.name) / "b"
        self.a.mkdir()
        self.b.mkdir()

    def apk(self, directory, payload=b"dex", year=2020, comment=b""):
        path = directory / "app-arm64-v8a-release-unsigned.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(zipfile.ZipInfo("classes.dex", (year, 1, 1, 0, 0, 0)), payload)
            archive.comment = comment
        return path

    def test_identical(self):
        self.apk(self.a)
        self.apk(self.b)
        report, same = compare(self.a, self.b)
        self.assertTrue(same)
        self.assertIn("**MATCH**", report)

    def test_payload_difference_is_listed(self):
        self.apk(self.a)
        self.apk(self.b, b"other")
        report, same = compare(self.a, self.b)
        self.assertFalse(same)
        self.assertIn("`classes.dex`: content differs", report)

    def test_timestamp_difference_is_listed(self):
        self.apk(self.a)
        self.apk(self.b, year=2021)
        report, same = compare(self.a, self.b)
        self.assertFalse(same)
        self.assertIn("ZIP metadata/compression differs", report)

    def test_container_difference_is_not_a_match(self):
        self.apk(self.a)
        self.apk(self.b, comment=b"different")
        report, same = compare(self.a, self.b)
        self.assertFalse(same)
        self.assertIn("container bytes differ", report)

    def test_empty_missing_and_unequal_sets_fail(self):
        with self.assertRaises(ValueError):
            compare(self.a, self.b)
        self.apk(self.a)
        with self.assertRaises(ValueError):
            compare(self.a, self.b)
        with self.assertRaises(ValueError):
            compare(self.a, self.b / "missing")

    def test_duplicate_and_corrupt_zip_fail(self):
        path = self.apk(self.a)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("classes.dex", b"duplicate")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            entries(path)
        path.write_bytes(b"bad ZIP")
        with self.assertRaises(zipfile.BadZipFile):
            entries(path)

    def test_cli_difference_is_informational_unless_strict_and_errors_always_fail(self):
        script = Path(__file__).with_name("compare_apks.py")
        args = [sys.executable, str(script), str(self.a), str(self.b)]
        self.assertEqual(1, subprocess.run(args, capture_output=True).returncode)
        self.apk(self.a)
        self.apk(self.b, b"different")
        self.assertEqual(0, subprocess.run(args, capture_output=True).returncode)
        self.assertEqual(1, subprocess.run(args + ["--strict"], capture_output=True).returncode)

    def test_complete_entry_diff_is_not_truncated(self):
        for directory, payload in ((self.a, b"a"), (self.b, b"b")):
            with zipfile.ZipFile(directory / "test.apk", "w") as archive:
                for i in range(60):
                    archive.writestr(f"file-{i:02}", payload)
        report, same = compare(self.a, self.b)
        self.assertFalse(same)
        self.assertEqual(60, report.count("content differs"))


class StringAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for locale in ("values", "values-bn"):
            (self.root / "app/src/main/res" / locale).mkdir(parents=True)

    def xml(self, locale, text, name="strings.xml"):
        (self.root / "app/src/main/res" / locale / name).write_text(f"<resources>{text}</resources>", encoding="utf-8")

    def test_repository_resources(self):
        result = strings.audit(strings.ROOT)
        self.assertGreater(result["required"], 500)
        self.assertEqual([], result["errors"])

    def test_no_new_direct_ui_translation_debt(self):
        candidates = strings.audit(strings.ROOT)["ui_literal_candidates"]
        self.assertEqual([], candidates, "Extract UI literals to values/ and values-bn/")

    def test_missing_excludes_nontranslatable(self):
        self.xml("values", '<string name="missing">Hello</string><string name="brand" translatable="false">Brand</string>')
        self.xml("values-bn", '<string name="extra">Extra</string>')
        result = strings.audit(self.root)
        self.assertEqual(["missing"], result["missing"])
        self.assertEqual(["extra"], result["extra"])

    def test_placeholder_reordering_and_mismatch(self):
        self.assertEqual(strings.placeholders("%1$s %2$d %%"), strings.placeholders("%2$d %1$s"))
        self.assertNotEqual(strings.placeholders("%1$s"), strings.placeholders("%1$d"))
        self.assertEqual([(1, "s"), (1, "s")], strings.placeholders("%s %<s"))
        self.xml("values", '<string name="text">%1$d items</string>')
        self.xml("values-bn", '<string name="text">%1$s \u0995</string>')
        self.assertIn("format arguments differ", strings.audit(self.root)["errors"][0])

    def test_plurals_other_blank_arrays_duplicates_and_unchanged(self):
        self.xml("values", '<plurals name="count"><item quantity="one">%1$d item</item><item quantity="other">%1$d items</item></plurals><string-array name="array"><item>Hello</item></string-array>')
        self.xml("values-bn", '<plurals name="count"><item quantity="one">%1$d \u0995</item></plurals><string-array name="array"><item></item><item>extra</item></string-array>')
        result = strings.audit(self.root)
        self.assertEqual(3, len(result["errors"]))
        self.xml("values-bn", '<string name="same">Same</string>')
        self.xml("values", '<string name="same">Same</string>')
        self.assertIn("unchanged English", strings.audit(self.root)["errors"][0])
        self.xml("values", '<string name="same">Again</string>', "other.xml")
        with self.assertRaisesRegex(ValueError, "duplicate resource"):
            strings.audit(self.root)

    def test_ui_literals_ignore_comments_wordmarks_and_interpolations(self):
        self.xml("values", '<string name="text">Hello</string>')
        self.xml("values-bn", '<string name="text">\u0995</string>')
        kotlin = self.root / "app/src/main/kotlin/Test.kt"
        kotlin.parent.mkdir(parents=True)
        kotlin.write_text('// Text("Comment")\nText("harkara")\nText("$label: $value")\nText("Translate me")\ncopyToClipboard("Translate label", data)', encoding="utf-8")
        candidates = strings.audit(self.root)["ui_literal_candidates"]
        self.assertEqual([4, 5], [c["line"] for c in candidates])


if __name__ == "__main__":
    unittest.main()
