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

    def test_cli_requires_a_market_or_explicit_directory_before_writing(self):
        source = self.folder / "catalog.json"
        source.write_text(json.dumps(catalog(object_definition())), encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "--catalog", str(source)],
                                cwd=self.folder, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--market", result.stderr)
        self.assertFalse((self.folder / "markets/cn").exists())

    def test_cli_routes_only_explicit_market_rows_and_rejects_unknown_ownership(self):
        source = self.folder / "catalog.json"
        source.write_text(json.dumps(catalog(object_definition(market="cn"),
                                              object_definition("UsExampleRow", market="us"))), encoding="utf-8")
        args = [sys.executable, str(SCRIPT), "--catalog", str(source), "--market", "us"]
        result = subprocess.run(args, cwd=self.folder, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        output = self.folder / "markets/us/src/main/java/com/zoutrankil/data/domain/table"
        saved = (output / "UsExampleRow.java").read_bytes()
        self.assertFalse((self.folder / "markets/cn").exists())
        source.write_text(json.dumps(catalog(object_definition())), encoding="utf-8")
        result = subprocess.run(args, cwd=self.folder, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(saved, (output / "UsExampleRow.java").read_bytes())

    def test_default_root_matches_declared_package(self):
        package_path = Path("com/zoutrankil/data/domain")
        self.assertEqual(package_path.parts, generator.PACKAGE_ROOT.parts[-len(package_path.parts):])

    def test_category_moves_generated_projection_without_touching_business_models(self):
        self.generate(catalog(object_definition()))
        handwritten = self.write('stock/market/Daily.java', 'package user;\npublic record Daily() {}\n')
        plan = self.generate(catalog(object_definition(category='stock.market')))
        self.assertFalse((self.output / 'table/ExampleRow.java').exists())
        target = self.output / 'stock/market/table/ExampleRow.java'
        self.assertTrue(target.read_text().startswith('package com.zoutrankil.data.domain.stock.market.table;'))
        self.assertEqual('package user;\npublic record Daily() {}\n', handwritten.read_text())
        first = self.snapshot()
        self.generate(catalog(object_definition(category='stock.market')))
        self.assertEqual(first, self.snapshot())

    def test_invalid_category_cannot_write_or_remove_existing_models(self):
        self.generate(catalog(object_definition()))
        before = self.snapshot()
        for category in ('../outside', 'stock/market', '.stock', 'stock..market', 'class', None):
            with self.subTest(category=category), self.assertRaises(ValueError):
                self.generate(catalog(object_definition(category=category)))
            self.assertEqual(before, self.snapshot())

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


class DocumentationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='model-documentation-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / 'domain'
        self.definition = object_definition('ExampleRow', columns=[{'name': 'trade_date', 'javaType': 'Instant'}])
        generator.apply_generation(generator.plan_generation(catalog(self.definition), self.output))
        self.field = {'name': 'trade_date', 'javaField': 'tradeDate', 'javaType': 'Instant',
                      'provider': 'Tushare', 'sourceField': 'trade_date', 'sourceType': 'str',
                      'description': '交易日期', 'unit': '', 'sourceFormat': 'YYYYMMDD',
                      'documentationUrl': 'https://tushare.pro/document/2?doc_id=27',
                      'reviewedAt': '2026-10-19', 'status': 'VERIFIED_SOURCE',
                      'notes': '日期标记不是事件时间，不能整体加 8 小时。'}
        self.documentation = {'schemaVersion': 1, 'objects': [{'objectName': 'example', 'modelClass': 'ExampleRow',
                              'endpoint': 'daily', 'title': '日线行情', 'reviewedAt': '2026-10-19',
                              'documentationUrl': self.field['documentationUrl'], 'fields': [self.field]}]}

    def test_annotation_is_idempotent_and_preserves_record_signature(self):
        target = self.output / 'table/ExampleRow.java'
        before = target.read_text()
        plan = generator.plan_documentation(self.output, self.documentation)
        generator.apply_generation(plan)
        after = target.read_text()
        self.assertIn('来源字段 trade_date', after)
        self.assertIn('YYYYMMDD', after)
        self.assertIn('不能整体加 8 小时', after)
        import re
        signature = lambda source: re.sub(r'\s+', '', re.sub(r'/\*\*.*?\*/', '',
                            source[source.index('public record'):], flags=re.S))
        self.assertEqual(signature(before), signature(after))
        generator.apply_generation(generator.plan_documentation(self.output, self.documentation))
        self.assertEqual(after, target.read_text())

    def test_invalid_later_model_stops_all_writes(self):
        extra = object_definition('SecondRow', columns=[{'name': 'trade_date', 'javaType': 'Instant'}])
        extra['name'] = 'second'
        generator.apply_generation(generator.plan_generation(catalog(self.definition, extra), self.output))
        original = {p: p.read_bytes() for p in self.output.rglob('*.java')}
        invalid = deepcopy(self.documentation['objects'][0])
        invalid.update(objectName='second', modelClass='SecondRow')
        invalid['fields'][0]['javaType'] = 'String'
        self.documentation['objects'].append(invalid)
        with self.assertRaisesRegex(ValueError, '模型字段与说明不一致'):
            generator.plan_documentation(self.output, self.documentation)
        self.assertEqual(original, {p: p.read_bytes() for p in self.output.rglob('*.java')})

    def test_normal_generation_receives_the_same_documentation_before_writing(self):
        plan = generator.plan_generation(catalog(self.definition), self.output)
        enriched = generator.with_documentation(plan, self.documentation)
        self.assertIn('来源字段 trade_date', enriched.outputs[0][1])
        generator.apply_generation(enriched)
        self.assertEqual(enriched.outputs[0][1], (self.output / 'table/ExampleRow.java').read_text())

    def test_cn_cli_uses_documentation_category_even_with_an_old_flat_catalog(self):
        self.documentation['objects'][0].update(category='stock.market',
                modelPackage='com.zoutrankil.data.domain.stock.market.table')
        source = self.root / 'catalog.json'
        source.write_text(json.dumps(catalog(dict(self.definition, market='cn'))))
        documentation = self.root / 'documentation.json'
        documentation.write_text(json.dumps(self.documentation))
        result = subprocess.run([sys.executable, str(SCRIPT), '--catalog', str(source), '--market', 'cn',
                                 '--output-root', str(self.output), '--documentation', str(documentation)],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse((self.output / 'table/ExampleRow.java').exists())
        self.assertIn('来源字段 trade_date',
                      (self.output / 'stock/market/table/ExampleRow.java').read_text())

    def test_same_class_cannot_receive_another_objects_documentation(self):
        self.documentation['objects'][0]['objectName'] = 'another_object'
        target = self.output / 'table/ExampleRow.java'
        before = target.read_bytes()
        with self.assertRaisesRegex(ValueError, '模型对象与说明不一致'):
            generator.plan_documentation(self.output, self.documentation)
        self.assertEqual(before, target.read_bytes())

    def test_cli_validates_annotations_before_overwriting_or_removing_models(self):
        stale = object_definition('StaleRow')
        stale['name'] = 'stale'
        generator.apply_generation(generator.plan_generation(catalog(self.definition, stale), self.output))
        original = {p: p.read_bytes() for p in self.output.rglob('*.java')}
        source = self.root / 'catalog.json'
        source.write_text(json.dumps(catalog(dict(self.definition, market='cn'))))
        self.field['javaType'] = 'String'
        documentation = self.root / 'documentation.json'
        documentation.write_text(json.dumps(self.documentation))
        result = subprocess.run([sys.executable, str(SCRIPT), '--catalog', str(source), '--market', 'cn',
                                 '--output-root', str(self.output), '--documentation', str(documentation)],
                                capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(original, {p: p.read_bytes() for p in self.output.rglob('*.java')})

    def test_export_preserves_empty_units_and_rejects_existing_output(self):
        import csv
        destination = self.root / 'export'
        generator.export_documentation(destination, self.documentation)
        with (destination / 'CN字段说明.csv').open(encoding='utf-8-sig', newline='') as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual('', rows[0]['来源单位'])
        self.assertEqual('VERIFIED_SOURCE', rows[0]['核验状态'])
        self.assertEqual(self.field['documentationUrl'], rows[0]['官方链接'])
        with self.assertRaisesRegex(ValueError, '导出目录已存在'):
            generator.export_documentation(destination, self.documentation)

    def test_unverified_fields_cannot_lose_their_warning(self):
        self.field.update(status='UNVERIFIED', documentationUrl='', sourceFormat='', notes='原官方文档不可用。')
        result = generator.plan_documentation(self.output, self.documentation)
        self.assertIn('未获官方字段核验', result.outputs[0][1])

    def test_checked_in_models_match_the_maintained_documentation(self):
        documentation = generator.load_documentation(REPOSITORY / generator.DOCUMENTATION_PATH)
        plan = generator.plan_documentation(REPOSITORY / generator.PACKAGE_ROOT, documentation)
        for path, expected in plan.outputs:
            self.assertEqual(expected, path.read_text(encoding='utf-8'), str(path))

    def test_official_status_requires_a_documented_source_and_review_date(self):
        path = self.root / 'documentation.json'
        self.field['documentationUrl'] = ''
        path.write_text(json.dumps(self.documentation))
        with self.assertRaisesRegex(ValueError, '已核验字段缺少官方来源'):
            generator.load_documentation(path)


if __name__ == "__main__":
    unittest.main()
