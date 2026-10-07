"""Archive the actual single finite Java live test and its saved data evidence."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
source = repo / "build/test-results/test/TEST-com.zoutrankil.data.config.EtfMarketOverviewDailyViewLiveAcceptanceTest.xml"
root = ET.parse(source).getroot()
assert int(root.get("tests")) == 1
assert all(int(root.get(field, "0")) == 0 for field in ("failures", "errors", "skipped"))
case = root.find("testcase")
assert case.get("name") == "actualViewMatchesBothBasesInPrivateAndFormalBoundedReads()"
stage_path = folder / "java-view-read-acceptance-20261006.json"
stage = json.loads(stage_path.read_text(encoding="utf-8"))
assert stage["status"] == "VERIFIED_PRIVATE_AND_FORMAL_BOUNDED_VIEW_READ"
assert stage["actual_source_revision_during_D102_test"] is False
assert all(stage[field] == 0 for field in ("source_inserts", "ddl", "cache_publications", "formal_writes", "double_tolerance"))
assert stage["private_attestation_before"] == stage["private_attestation_after"]
for target in ("private", "formal"):
    item = stage[target]
    assert item["metadata_before"] == item["metadata_after"]
    assert item["expected_source_rows"] == item["actual_view_rows"] == item["independent_jdbc_view_rows"]
    assert len(item["pages"]) == len(item["actual_view_rows"]) == 3
    assert item["source_field_comparisons"] == 12 and item["source_double_rawbit_comparisons"] == 6
    assert item["independent_jdbc_double_rawbit_comparisons"] == 6
    assert all(item[field] is True for field in ("changed_range_cursor_rejected", "first_two_day_read_replayed_exact",
        "third_day_window_extension_matched", "configured_read_group_matched", "cancelled_member_has_no_page", "write_and_replacement_preparation_rejected"))
directory = folder / "java-view-read-acceptance-xml-20261006"
directory.mkdir()
destination = directory / source.name
shutil.copyfile(source, destination)
record = {"task_id": "D102", "status": "PASSED_JAVA_ACTUAL_VIEW_READ", "checked_at": datetime.now(timezone.utc).isoformat(),
          "java_live_unique": 1, "failures": 0, "errors": 0, "skipped": 0,
          "xml": {"path": str(destination.relative_to(repo)), "sha256": sha(destination)},
          "actual_data": {"path": str(stage_path.relative_to(repo)), "sha256": sha(stage_path)},
          "log_sha256": sha(folder / "java-view-read-acceptance-20261006.log"),
          "actual_source_revision": False, "ddl_source_cache_and_formal_writes": 0}
with (folder / "java-view-read-test-20261006.json").open("x", encoding="utf-8") as output:
    json.dump(record, output, ensure_ascii=False, indent=2)
    output.write("\n")
print(json.dumps({"status": record["status"], "java_live_unique": 1}))
