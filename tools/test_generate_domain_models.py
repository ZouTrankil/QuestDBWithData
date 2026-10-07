"""Offline generator contract tests; all output stays in temporary directories."""

from __future__ import annotations

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[1]
SCRIPT = REPOSITORY / "tools/generate_domain_models.py"
SPEC = importlib.util.spec_from_file_location("generate_domain_models", SCRIPT)
generator = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = generator
SPEC.loader.exec_module(generator)


def object_definition(class_name="ExampleRow", **overrides):
    return {
        "name": "example", "objectType": "TABLE", "domainClass": class_name,
        "columns": [{"name": "trade_date", "javaType": "Instant"}], **overrides,
    }


def catalog(*objects):
    return {"snapshotDate": "2026-09-28", "objects": list(objects)}


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="domain-generator-test-")
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.output = self.folder / "domain"

    def generate(self, value):
        plan = generator.plan_generation(value, self.output)
        generator.apply_generation(plan)
        return plan

    def write(self, relative, content):
        target = self.output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return target

    def snapshot(self):
        return {path.relative_to(self.output).as_posix(): path.read_bytes()
                for path in self.output.rglob("*") if path.is_file()}

    def test_default_root_matches_declared_package(self):
        package_path = Path("com/zoutrankil/data/domain")
        self.assertEqual(package_path.parts, generator.PACKAGE_ROOT.parts[-len(package_path.parts):])

    def test_full_catalog_generates_184_models_and_repeats_identically(self):
        value = json.loads((REPOSITORY / "schema-export/questdb-qdb-2026-09-28/domain-catalog.json")
                           .read_text(encoding="utf-8"))
        original = deepcopy(value)
        plan = self.generate(value)
        self.assertEqual(184, len(plan.outputs))
        first = self.snapshot()
        self.assertEqual({"table": 172, "view": 10, "materializedview": 2},
                         {folder: sum(path.startswith(folder + "/") for path in first)
                          for folder in ("table", "view", "materializedview")})
        for relative, content in first.items():
            folder = relative.split("/")[0]
            self.assertTrue(content.startswith(f"package com.zoutrankil.data.domain.{folder};\n".encode()))
            self.assertNotIn(b"\r", content)
        self.generate(value)
        self.assertEqual(first, self.snapshot())
        self.assertEqual(original, value)
        self.assertFalse((self.folder / "questdbwithdata").exists())

    def test_cleanup_is_limited_to_direct_generated_java_files(self):
        self.generate(catalog(object_definition("OldRow")))
        self.write("table/Handwritten.java", "package user;\npublic record Handwritten() {}\n")
        self.write("table/MarkerMention.java", f"package user;\n/* {generator.MARKER} */\n")
        self.write("table/keep.txt", generator.MARKER)
        self.write("table/nested/Keep.java", generator.MARKER)
        self.write("other/Keep.java", generator.MARKER)
        before = self.snapshot()
        self.generate(catalog(object_definition()))
        after = self.snapshot()
        self.assertNotIn("table/OldRow.java", after)
        self.assertIn("table/ExampleRow.java", after)
        for path, content in before.items():
            if path != "table/OldRow.java":
                self.assertEqual(content, after[path])

    def test_invalid_later_objects_do_not_write_or_remove_anything(self):
        self.generate(catalog(object_definition("OldRow")))
        before = self.snapshot()
        invalid_objects = [
            object_definition("../Escape"), object_definition("..\\Escape"),
            object_definition("C:/Escape"), object_definition("class"), object_definition("CON"),
            object_definition("ExampleRow"), object_definition("exampleRow"),
            object_definition("BrokenRow", columns=[{"name": "class", "javaType": "String"}]),
            object_definition("BrokenRow", columns=[{"name": "_", "javaType": "String"}]),
            object_definition("BrokenRow", columns=[{"name": "to_string", "javaType": "String"}]),
            object_definition("BrokenRow", columns=[{"name": "value", "javaType": "MANUAL_MAPPING"}]),
            object_definition("BrokenRow", columns=[{"name": "value", "javaType": "String injected"}]),
            object_definition("BrokenRow", columns=[{"name": "a_b", "javaType": "String"},
                                                    {"name": "a__b", "javaType": "String"}]),
            object_definition("BrokenRow", columns=[]),
            object_definition("BrokenRow", name="injected */ source"),
            object_definition("BrokenRow", objectType="UNKNOWN"),
        ]
        for invalid in invalid_objects:
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    self.generate(catalog(object_definition(), invalid))
                self.assertEqual(before, self.snapshot())
        self.assertFalse((self.folder / "Escape.java").exists())

    def test_invalid_catalog_does_not_create_output_directory(self):
        for value in ({}, {"snapshotDate": "2026-09-28", "objects": {}},
                      {"snapshotDate": "2026-09-28 */", "objects": []}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.generate(value)
            self.assertFalse(self.output.exists())

    def test_generated_target_cannot_overwrite_handwritten_file(self):
        self.generate(catalog(object_definition("OldRow")))
        self.write("table/ExampleRow.java", "package user;\npublic record ExampleRow() {}\n")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "non-generated"):
            self.generate(catalog(object_definition()))
        self.assertEqual(before, self.snapshot())

    def test_apply_rechecks_ownership_before_any_writes(self):
        self.generate(catalog(object_definition("OldRow")))
        plan = generator.plan_generation(catalog(object_definition()), self.output)
        self.write("table/OldRow.java", "package user;\npublic record OldRow() {}\n")
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, "non-generated"):
            generator.apply_generation(plan)
        self.assertEqual(before, self.snapshot())

    def test_cli_reads_utf8_and_writes_only_explicit_output(self):
        source = self.folder / "catalog.json"
        source.write_text(json.dumps(catalog(object_definition(name="中文数据")), ensure_ascii=False),
                          encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(SCRIPT), "--catalog", str(source),
                                 "--output-root", str(self.output)], cwd=self.folder,
                                capture_output=True, text=True, encoding="utf-8", check=True)
        self.assertIn("Generated 1 schema projection records", result.stdout)
        self.assertIn("中文数据", (self.output / "table/ExampleRow.java").read_text(encoding="utf-8"))
        self.assertFalse((self.folder / "src").exists())

    def test_linked_package_cannot_escape_output_root(self):
        outside = self.folder / "outside"
        outside.mkdir()
        self.output.mkdir()
        linked = self.output / "table"
        try:
            linked.symlink_to(outside, target_is_directory=True)
        except (OSError, NotImplementedError) as failure:
            self.skipTest(f"Directory links unavailable: {failure}")
        with self.assertRaisesRegex(ValueError, "escapes output root"):
            self.generate(catalog(object_definition()))
        self.assertEqual([], list(outside.iterdir()))

    def test_resolved_path_outside_output_root_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "escapes output root"):
            generator.require_contained(self.output / ".." / "Escape.java", self.output.resolve())
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
