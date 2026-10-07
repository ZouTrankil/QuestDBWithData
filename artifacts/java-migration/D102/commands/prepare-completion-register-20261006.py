"""Prepare task checklist/register/status for final review, without next-task admission."""
from pathlib import Path

repo = Path(__file__).resolve().parents[4]
docs = repo / "docs/migration-tasks-20260929"
card = docs / "11-derived/D102-v_etf_market_overview_daily.md"
text = card.read_text(encoding="utf-8")
text = text.replace("- 状态：in_progress；D101协调gate已接受，正在实现与隔离验收，人工pending_review。",
    "- 状态：实际隔离及Java验收通过，待最终协调gate；D103未准入，人工pending_review。")
text = text.replace("- [ ]", "- [x]")
text += "\n## 实际验收（2026-10-06）\n\n"
text += "四列语义DTO、trade_date自然Key、逐字段mapper、typed范围/按键/分页和实际配置ReadGroup完成。普通VIEW不具自己的WAL/物理分区/DEDUP/writer/独立job或checkpoint；快照WAL=True不作为视图写契约。D07是注册VIEW DatasetDefinition并声明两基表owner，未编造VIEW运行切片；51Dataset/40jobs/本VIEW job0。D05的批写及STATIC/WAL替换准备均在writer前拒绝。\n\n"
text += "唯一私有目标23388/19020/18832复用D101的11667真实行与164180字段值；缺失原视图CREATE ACK1，前后三次CREATE前FAILED保留。私有与正式各三日原SQL/view全12字段、6double原位零容差匹配；Java独立JDBC、typedDTO和ReadGroup实际一致，三页1行、两日真实游标恢复/重读、变range拒旧cursor、第三日窗口扩展及取消无page通过。仅复用D101已经验收的第三日来源增量，D102自身没有来源追加或修订。五表id/dir/txn/WAL/schema、视图目录/定义/状态均跨阶段固定；Java游标保留完整µs状态文本。130唯一Java(129pure+1live)+81Python全部PASS/0skip。\n\n"
text += "两个目标之间保留4项原生DOUBLE聚合bits差异（私有减正式：9/17市值+7ULP，9/18份额+1ULP及市值-2ULP，9/21市值-4ULP）；各目标内对照保持零容差，没有改SQL或舍入，不认证跨物理布局bits稳定。每SQL20秒，非整个组合绝对期限；取消覆盖成员启动前；源Javajob隔离默认目标未切换正式；provider全域/全历史/current/latest/生产切换不认证，D101正式两历史摘要异常未修复。\n\n"
text += "结果：[results/D102.json](../results/D102.json)；证据：[D102 README](../../../artifacts/java-migration/D102/README.md)。最终独立复核与根协调gate待完成，人工pending_review，D103仍未准入。\n"
card.write_text(text, encoding="utf-8")

register = docs / "completion-register.md"
lines = register.read_text(encoding="utf-8").splitlines()
replacement = "| D102 | v_etf_market_overview_daily | 实际验收通过，待协调gate | 私有23388/19020/18832；正式只读 | 四列typed READ/ReadGroup；原JOIN普通VIEW/job0/拒直写 | 11667实源/164180字段；唯一私有CREATE ACK1；各target三日 | 每target12字段/6DOUBLE原位+Java独立6bits一致；五表/view跨stage固定 | ≤31日/页≤31；实际cursor恢复/重放/变range拒绝；checkpoint N/A | Java130+Python81唯一PASS；保留3前CREATE失败；跨target4项ULP差异不加tol | 2026-10-06 / 实际数据通过，待最终独立/协调复核 | [D102结果](results/D102.json)；[任务卡](11-derived/D102-v_etf_market_overview_daily.md)；[证据](../../artifacts/java-migration/D102/README.md) | pending_review |"
assert sum(line.startswith("| D102 |") for line in lines) == 1
register.write_text("\n".join(replacement if line.startswith("| D102 |") else line for line in lines) + "\n", encoding="utf-8")
status = docs / "execution-status.md"
text = status.read_text(encoding="utf-8")
heading = "## D102 in_progress（2026-10-06）"
assert text.count(heading) == 1
prefix, _ = text.split(heading)
text = prefix + "## D102 实际验收通过，待最终协调（2026-10-06）\n\n"
text += "四列typed普通VIEW READ/ReadGroup完成，51Dataset/40jobs/viewjob0。私有23388/19020/18832唯一CREATE ACK1；11667实源/164180字段，private/formal各三日12字段6DOUBLE、Java独立JDBC及三页/真实cursor/ReadGroup/取消拒写均通过；130唯一Java+81Python0fail/error/skip。五表源frontier及view定义/目录/状态跨阶段固定。三次前CREATE失败原件保留；显式历史身份规则466eligible/9harddeny且每边界freshbirth>全部旧STOP，不借UNKNOWN恢复或按名字豁免。没有来源/cache/coverage/owner/formal业务写入、没有D102源修订；第三日窗口复用D101真实增量。跨private/formal保留4项native聚合ULP差异，各target内0tol，不认证跨布局全局bits稳定/全历史/生产切换。最终独立数据/交付复核及根协调gate待完成，D103未准入，人工pending_review。\n"
status.write_text(text, encoding="utf-8")
print("D102 checklist and actual data register ready for final review")
