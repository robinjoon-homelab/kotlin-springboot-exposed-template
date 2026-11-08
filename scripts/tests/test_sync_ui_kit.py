import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("sync_ui_kit", Path(__file__).resolve().parents[1] / "sync-ui-kit.py")
sync_ui_kit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync_ui_kit)


def write(root: Path, name: str, content: str) -> None:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def make_kit(root: Path) -> None:
    write(root, "package.json", "{}\n")
    write(root, "config/ui/prettier.json", "{}\n")
    write(root, "src/main/resources/static/ui/tokens.css", ":root {}\n")
    write(root, "src/main/resources/templates/ui/layout.html", "<html></html>\n")
    write(root, "src/main/resources/templates/error/404.html", "<p>404</p>\n")
    write(root, "src/test/ui/api.test.mjs", "// test\n")
    write(root, "src/main/resources/static/app/theme.css", ":root { --ui-accent: red; }\n")
    write(root, "src/main/resources/templates/app/nav.html", "<nav></nav>\n")
    (root / sync_ui_kit.MANIFEST).write_text(sync_ui_kit.render_manifest(root), encoding="utf-8")


class SyncUiKitTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.source = Path(directory.name) / "source"
        self.target = Path(directory.name) / "target"
        make_kit(self.source)
        make_kit(self.target)

    def test_manifest_is_sorted_and_stable(self):
        first = sync_ui_kit.render_manifest(self.source)
        self.assertEqual(first, sync_ui_kit.render_manifest(self.source))
        paths = [line.split("  ", 1)[1] for line in first.splitlines()]
        self.assertEqual(paths, sorted(paths))
        self.assertNotIn(sync_ui_kit.MANIFEST, paths)
        self.assertNotIn("src/main/resources/static/app/theme.css", paths)

    def test_verify_reports_each_difference(self):
        self.assertEqual(sync_ui_kit.verify_manifest(self.source), [])
        write(self.source, "package.json", "{ }\n")
        (self.source / "config/ui/prettier.json").unlink()
        write(self.source, "src/test/ui/new.test.mjs", "// new\n")
        self.assertEqual(
            sync_ui_kit.verify_manifest(self.source),
            ["missing: config/ui/prettier.json", "changed: package.json", "unlisted: src/test/ui/new.test.mjs"],
        )

    def test_sync_copies_kit_and_removes_stale_files(self):
        write(self.source, "src/main/resources/static/ui/tokens.css", ":root { color-scheme: dark; }\n")
        (self.source / "src/test/ui/api.test.mjs").unlink()
        (self.source / sync_ui_kit.MANIFEST).write_text(sync_ui_kit.render_manifest(self.source), encoding="utf-8")
        sync_ui_kit.sync(self.source, self.target)
        self.assertEqual((self.target / "src/main/resources/static/ui/tokens.css").read_text(), ":root { color-scheme: dark; }\n")
        self.assertFalse((self.target / "src/test/ui/api.test.mjs").exists())
        self.assertEqual(sync_ui_kit.verify_manifest(self.target), [])

    def test_sync_leaves_app_files(self):
        sync_ui_kit.sync(self.source, self.target)
        self.assertEqual((self.target / "src/main/resources/static/app/theme.css").read_text(), ":root { --ui-accent: red; }\n")
        self.assertEqual((self.target / "src/main/resources/templates/app/nav.html").read_text(), "<nav></nav>\n")

    def test_sync_refuses_a_tampered_source(self):
        write(self.source, "package.json", "{\"tampered\": true}\n")
        write(self.target, "src/main/resources/static/ui/tokens.css", ":root { --local: 1; }\n")
        with self.assertRaises(SystemExit):
            sync_ui_kit.sync(self.source, self.target)
        self.assertEqual((self.target / "package.json").read_text(), "{}\n")
        self.assertEqual((self.target / "src/main/resources/static/ui/tokens.css").read_text(), ":root { --local: 1; }\n")

    def test_sync_refuses_the_same_directory(self):
        with self.assertRaises(SystemExit):
            sync_ui_kit.sync(self.source, self.source)

    def test_symlinks_are_neither_listed_nor_followed(self):
        outside = self.source.parent / "outside.txt"
        outside.write_text("secret\n", encoding="utf-8")
        (self.source / "src/test/ui/link.mjs").symlink_to(outside)
        (self.source / "config/ui/linked-dir").symlink_to(self.source.parent)
        self.assertNotIn("src/test/ui/link.mjs", sync_ui_kit.kit_paths(self.source))
        self.assertEqual(sync_ui_kit.verify_manifest(self.source), [])
        sync_ui_kit.sync(self.source, self.target)
        self.assertFalse((self.target / "src/test/ui/link.mjs").exists())


if __name__ == "__main__":
    unittest.main()
