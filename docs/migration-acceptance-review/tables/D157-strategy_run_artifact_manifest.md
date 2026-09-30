# D157 · strategy_run_artifact_manifest 表验收

优先级：P5。本文件仅验收 `strategy_run_artifact_manifest`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/13-strategy/D157-strategy_run_artifact_manifest.md)

## 1. 已知结构与来源

- 旧快照：主时间 `created_at`；分区 `MONTH`；WAL `True`；去重 `True`。
- 旧物理键：`artifact_manifest_id`, `artifact_path`, `created_at`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/research/adapters/strategy_production_store.py:273](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/strategy_production_store.py:273)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/etf_only_top2/etf_only_top2_persistence.py:478](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/etf_only_top2/etf_only_top2_persistence.py:478)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D157-01 | 同run一个CSV和一个图像文件 | bytes/content_hash/file_ext/path与真实文件一致，rows仅适用结构化文件 |
| D157-02 | 文件移动/丢失/内容变化 | 验证报告明确失败，不因manifest存在就算产物可用；is_cache与structured_table不混 |
| D157-03 | 将真实非空批次规范化后保存；先确认 `artifact_manifest_id, artifact_path, created_at` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `strategy_run_artifact_manifest`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D157-04 | 对键 `artifact_manifest_id, artifact_path, created_at` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D157-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `strategy_run_artifact_manifest` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D157-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `artifact_manifest_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.artifact_manifest_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `run_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.run_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `strategy_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.strategy_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `instance_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.instance_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source_strategy` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.source_strategy`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `artifact_path` | `STRING` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.artifact_path`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `relative_path` | `STRING` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.relative_path`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `artifact_kind` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.artifact_kind`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `artifact_role` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.artifact_role`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `file_ext` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.file_ext`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `bytes` | `LONG` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.bytes`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `rows` | `LONG` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.rows`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `min_date` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.min_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `max_date` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.max_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `mtime` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.mtime`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `content_hash` | `STRING` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.content_hash`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `is_cache` | `BOOLEAN` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.is_cache`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `is_structured` | `BOOLEAN` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.is_structured`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `structured_table` | `SYMBOL` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.structured_table`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `structured_rows` | `LONG` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.structured_rows`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `created_at` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_run_artifact_manifest.created_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `created_at` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `created_at` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "artifact_manifest_id", "run_id", "strategy_id", "instance_id", "source_strategy", "artifact_path", "relative_path", "artifact_kind", "artifact_role", "file_ext", "bytes", "rows", "min_date", "max_date", "mtime", "content_hash", "is_cache", "is_structured", "structured_table", "structured_rows", "created_at"
FROM "strategy_run_artifact_manifest"
WHERE "created_at" >= :window_start
  AND "created_at" < :window_end
ORDER BY "artifact_manifest_id", "artifact_path", "created_at"
LIMIT :page_size;
```

审计输出：`D157-source-normalized.jsonl`、`D157-actual.jsonl`、`D157-key-diff.json`、`D157-field-diff.json`、`D157-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
