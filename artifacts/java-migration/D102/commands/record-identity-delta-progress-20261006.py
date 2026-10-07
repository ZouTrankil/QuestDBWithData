"""Record real pre-CREATE failures and completed pure checks in mutable task docs."""
import json
from datetime import datetime, timezone
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
folder = Path(__file__).resolve().parent
result_path = repo / "docs/migration-tasks-20260929/results/D102.json"
result = json.loads(result_path.read_text(encoding="utf-8"))
assert result["data_validation_status"] == "awaiting_isolated_validation"
result["updated_at"] = datetime.now(timezone.utc).isoformat()
result["tests"]["python_unique"] = 59
result["blocker"] = "Two explicit attempts refused before CREATE, no DDL claim. Static current identity whitelist also matches the audit's newly reused temporary PIDs; independent original OS birth/complete STOP admission rule analysis pending"
result["private_precreate_failure"]["explicit_precreate_attempts"] = 2
result["private_precreate_failure"]["latest_error"] = "Present producer is not the exact explicitly reviewed OS identity"
result["private_precreate_failure"]["ddl_attempts"] = 0
for name in ["coordinator-precreate-process-identity-review-20261006.json", "python-identity-guards-20261006.json",
             "coordinator-identity-admission-delta-review-20261006.json",
             "coordinator-private-view-identity-readmission-20261006.json",
             "view-isolated-acceptance-new-identity-20261006.json", "failed-precreate-identity-delta-diagnostic-20261006.json"]:
    assert (folder / name).is_file()
    value = "artifacts/java-migration/D102/commands/" + name
    if value not in result["evidence"]:
        result["evidence"].append(value)
result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

readme_path = folder.parent / "README.md"
text = readme_path.read_text(encoding="utf-8")
text = text.replace("[Python 脚本护栏](commands/python-script-guards-20261006.json)：39 项通过", "[Python 脚本护栏](commands/python-identity-guards-20261006.json)：59 个最终唯一方法通过（含旧 39 项）")
text = text.replace("独立复核正在检查新旧 OS 出生时刻与完整结束凭证；不终止当前系统服务、Codex 或 Chrome 进程。", "首批 7 个当前 PID 的独立复核通过，随后 [新的显式尝试](commands/view-isolated-acceptance-new-identity-20261006.json) 在 CREATE 前仍拒绝：[新只读诊断](commands/failed-precreate-identity-delta-diagnostic-20261006.json) 显示审计自身临时进程也分配历史 PID。两次均 0 DDL、无 CREATE claim。正分析完整旧 OS birth/STOP 凭证规则，默认拒绝及 UNKNOWN 不重试保持；没有终止用户进程。")
readme_path.write_text(text, encoding="utf-8")

register = repo / "docs/migration-tasks-20260929/completion-register.md"
lines = register.read_text(encoding="utf-8").splitlines()
replacement = "| D102 | v_etf_market_overview_daily | in_progress（Java 已实现，待隔离验收） | 私有23388/19020/18832；正式只读 | 四列typed READ/ReadGroup；普通VIEW拒绝直写/job0 | 复用D101 11667真实源行/164180字段；正式3日12字段/6double一致 | 隔离视图缺失；两次CREATE前身份拒绝，0DDL/无claim | ≤31日/页≤31；cursor绑定两源版本；独立checkpoint不适用 | Java129纯测+Python59唯一PASS；当前身份准入规则待独立复核 | 2026-10-06 / in_progress，D103未准入 | [D102结果](results/D102.json)；[任务卡](11-derived/D102-v_etf_market_overview_daily.md)；[证据](../../artifacts/java-migration/D102/README.md) | pending_review |"
assert sum(line.startswith("| D102 |") for line in lines) == 1
register.write_text("\n".join(replacement if line.startswith("| D102 |") else line for line in lines) + "\n", encoding="utf-8")
status = repo / "docs/migration-tasks-20260929/execution-status.md"
text = status.read_text(encoding="utf-8")
heading = "## D102 in_progress（2026-10-06）"
assert heading not in text
text += "\n" + heading + "\n\nJava四列DTO/Key/mapper/普通VIEWtyped READ及ReadGroup完成；51Dataset/40jobs/本VIEW独立job0，启动0DB连接。Java129纯方法、Python59最终唯一方法通过。正式真实三日12字段/6double原位零容差对照通过，0正式写。固定私有D101源五表未变；两次显式验收均在CREATE前拒绝，0DDL/无claim。首批已审PID复用身份之后，临时审计自身进程又分配历史PID；正在独立核对旧完整OS身份与STOP规则，尚未执行Java live或准入D103。旧失败文件保留，UNKNOWN不可重试，人工pending_review。\n"
status.write_text(text, encoding="utf-8")
print("D102 progress recorded; D103 remains unadmitted")
