"""Accurately distinguish the raw JDBC metadata carrier from the guarded UTC token."""
import json
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
scope = {"raw_jdbc_snapshot_timestamp_json": "Epoch millisecond carrier differs by -8 hours from the native UTC timestamp and loses sub-millisecond digits",
         "authoritative_guard": "Reader uses getString and binds the complete correct UTC microsecond status text in sourceVersion; independently matched both targets to Python native metadata",
         "cross_transport_raw_epoch_is_utc_authority": False,
         "business_date_mapper_affected": False,
         "verification": "Metadata table IDs/directories/transactions/rows/WAL match exactly across stages; full view status time compared through sourceVersion token, not the raw epoch carrier"}
result_path = repo / "docs/migration-tasks-20260929/results/D102.json"
result = json.loads(result_path.read_text(encoding="utf-8"))
result["metadata_transport_scope"] = scope
path = "artifacts/java-migration/D102/commands/coordinator-view-data-review-20261006.json"
assert (repo / path).is_file()
if path not in result["evidence"]:
    result["evidence"].append(path)
result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
mapping_path = folder.parent / "mapping-contract-20261006.json"
mapping = json.loads(mapping_path.read_text(encoding="utf-8"))
mapping["metadata_transport_scope"] = scope
mapping_path.write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
paragraph = "\n原始 JDBC metadata snapshot 的 Timestamp JSON 是带默认本地时区解释的 epoch 毫秒载体，与 native UTC 时间相差 -8 小时并丢弃亚毫秒位，不能用它证明 UTC 时间相同。实际 reader 使用 getString，sourceVersion 绑定完整正确的 UTC µs 状态文本，独立复核逐项匹配两目标的 native 元数据；业务日期 mapper 未受该 metadata 表示差异影响。\n"
for path in (folder.parent / "README.md", repo / "docs/migration-tasks-20260929/11-derived/D102-v_etf_market_overview_daily.md"):
    text = path.read_text(encoding="utf-8")
    assert "原始 JDBC metadata snapshot 的 Timestamp JSON" not in text
    path.write_text(text + paragraph, encoding="utf-8")
print("D102 raw metadata carrier scope recorded in four candidate docs")
