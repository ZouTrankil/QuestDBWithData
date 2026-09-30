# D132 · factor_materialization_run 表验收

优先级：P5。本文件仅验收 `factor_materialization_run`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/12-factor/D132-factor_materialization_run.md)

## 1. 已知结构与来源

- 旧快照：主时间 `period_end`；分区 `MONTH`；WAL `True`；去重 `True`。
- 旧物理键：`period_end`, `factor_id`, `definition_version`, `frequency`, `entity_scope`, `universe_id`, `input_fingerprint`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/factor_data_product.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/factor_data_product.py)（模型入口；不自动视为生产者）
- [src/quant_platform/data/maintenance/questdb/factor_platform_convergence.py:31](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/maintenance/questdb/factor_platform_convergence.py:31)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/contracts/factor_canonical.py:25](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/contracts/factor_canonical.py:25)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/adapters/questdb/factor_data_product.py:47](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/factor_data_product.py:47)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D132-01 | 同period_end因子同版本但两个universe_id/input_fingerprint | 两份物化记录均保留；period_start与频率一致 |
| D132-02 | expected_entity_count=100实际80 | coverage_ratio与计数相符，status/reason不能无条件成功 |
| D132-03 | 将真实非空批次规范化后保存；先确认 `period_end, factor_id, definition_version, frequency, entity_scope, universe_id, input_fingerprint` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `factor_materialization_run`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D132-04 | 对键 `period_end, factor_id, definition_version, frequency, entity_scope, universe_id, input_fingerprint` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D132-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `factor_materialization_run` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D132-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `period_end` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_materialization_run.period_end`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `period_start` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_materialization_run.period_start`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `factor_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.factor_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `definition_version` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.definition_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `frequency` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.frequency`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `entity_scope` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.entity_scope`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `universe_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.universe_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `input_fingerprint` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.input_fingerprint`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `action` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.action`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `status` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.status`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `expected_entity_count` | `INT` | 逐行比较源映射结果与 `factor_materialization_run.expected_entity_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `actual_entity_count` | `INT` | 逐行比较源映射结果与 `factor_materialization_run.actual_entity_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `coverage_ratio` | `DOUBLE` | 逐行比较源映射结果与 `factor_materialization_run.coverage_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `reason` | `STRING` | 逐行比较源映射结果与 `factor_materialization_run.reason`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `run_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_materialization_run.run_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `started_at` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_materialization_run.started_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `completed_at` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_materialization_run.completed_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `period_end` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `period_end` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "period_end", "period_start", "factor_id", "definition_version", "frequency", "entity_scope", "universe_id", "input_fingerprint", "action", "status", "expected_entity_count", "actual_entity_count", "coverage_ratio", "reason", "run_id", "started_at", "completed_at"
FROM "factor_materialization_run"
WHERE "period_end" >= :window_start
  AND "period_end" < :window_end
ORDER BY "period_end", "factor_id", "definition_version", "frequency", "entity_scope", "universe_id", "input_fingerprint"
LIMIT :page_size;
```

审计输出：`D132-source-normalized.jsonl`、`D132-actual.jsonl`、`D132-key-diff.json`、`D132-field-diff.json`、`D132-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
