"""Extract a bounded user-owned workbook as lossless text CSV for the historical catalog import contract."""
from pathlib import Path
from datetime import datetime, timezone
from collections import Counter
import csv
import hashlib
import json
import openpyxl

root = Path(__file__).resolve().parents[1]
source = Path("C:/Users/zouqiang/Downloads/指数列表.xlsx")
folder = root / "artifacts/java-migration/D003"
folder.mkdir(parents=True, exist_ok=True)
workbook = openpyxl.load_workbook(source, read_only=True, data_only=False)
sheet = workbook.active
sheet.reset_dimensions()  # This workbook declares A1:A1 despite containing the full sheet.
rows = []
for cells in sheet.iter_rows():
    if len(rows) >= 5001 or len(cells) > 17:
        raise ValueError("Catalog exceeds explicit source bounds")
    if any(cell.data_type == "f" for cell in cells):
        raise ValueError("Formula source requires explicit evaluated-value provenance")
    rows.append(["" if cell.value is None else str(cell.value) for cell in cells])
workbook.close()
if not rows or len(rows[0]) != 17 or any(len(row) != 17 for row in rows):
    raise ValueError("Complete 17-column source required")
keys = Counter(row[0].strip().zfill(6) if row[0].strip().isdigit() else row[0].strip() for row in rows[1:])
if "" in keys or any(count != 1 for count in keys.values()):
    raise ValueError("Unique nonempty catalog codes required")
destination = folder / "source-catalog.csv"
with destination.open("w", encoding="utf-8", newline="") as output:
    csv.writer(output).writerows(rows)
baseline = json.loads((folder / "physical-baseline.json").read_text(encoding="utf-8"))
existing = {row["index_code"] for row in baseline["catalog"]["rows"]}
report = {
    "source_file": str(source), "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "csv_file": str(destination.relative_to(root)), "csv_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
    "extracted_at": datetime.now(timezone.utc).isoformat(), "source_rows": len(rows)-1,
    "unique_codes": len(keys), "headers": rows[0], "existing_codes": len(existing),
    "source_only_codes": sorted(set(keys)-existing), "target_only_codes": sorted(existing-set(keys)),
    "inference": "matching historical import headers; this file is not proven to be the exact April input",
    "price_asof": "not declared by workbook; import time must not imply price or return as-of date",
    "workbook_dimension_correction": "ignore declared A1:A1; iterate actual sheet cells",
}
(folder / "source-provenance.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({key: report[key] for key in ("source_rows", "unique_codes", "existing_codes")}, ensure_ascii=True))
print("source_only", len(report["source_only_codes"]), "target_only", len(report["target_only_codes"]))
