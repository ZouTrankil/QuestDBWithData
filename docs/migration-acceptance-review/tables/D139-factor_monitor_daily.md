# D139 · factor_monitor_daily 表验收

优先级：P5。本文件仅验收 `factor_monitor_daily`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/12-factor/D139-factor_monitor_daily.md)

## 1. 已知结构与来源

- 旧快照：主时间 `signal_date`；分区 `MONTH`；WAL `True`；去重 `True`。
- 旧物理键：`signal_date`, `factor_id`, `definition_version`, `universe_id`, `horizon`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/factor_monitor.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/factor_monitor.py)（模型入口；不自动视为生产者）
- [src/quant_platform/data/adapters/questdb/factor_data_product.py:119](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/factor_data_product.py:119)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/application/factor_data_product.py:113](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/application/factor_data_product.py:113)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D139-01 | 正负effective_direction各一例、五组收益不同 | top_minus_bottom与oriented_top_minus_bottom按方向分别复算 |
| D139-02 | 只有不足成熟样本 | coverage=sample_count/universe_count口径冻结，data_quality_status不能显示完整 |
| D139-03 | 将真实非空批次规范化后保存；先确认 `signal_date, factor_id, definition_version, universe_id, horizon` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `factor_monitor_daily`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D139-04 | 对键 `signal_date, factor_id, definition_version, universe_id, horizon` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D139-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `factor_monitor_daily` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D139-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `signal_date` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_monitor_daily.signal_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `maturity_date` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_monitor_daily.maturity_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `factor_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.factor_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `definition_version` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.definition_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `universe_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.universe_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `horizon` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.horizon`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `direction_prior` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.direction_prior`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `effective_direction` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.effective_direction`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `rank_ic` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.rank_ic`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `rank_ic_p_value` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.rank_ic_p_value`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sample_count` | `INT` | 逐行比较源映射结果与 `factor_monitor_daily.sample_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `universe_count` | `INT` | 逐行比较源映射结果与 `factor_monitor_daily.universe_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `coverage` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.coverage`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `group_1_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.group_1_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `group_2_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.group_2_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `group_3_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.group_3_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `group_4_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.group_4_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `group_5_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.group_5_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `top_minus_bottom_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.top_minus_bottom_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `oriented_top_minus_bottom_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.oriented_top_minus_bottom_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `monotonicity_score` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.monotonicity_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `factor_autocorr` | `DOUBLE` | 逐行比较源映射结果与 `factor_monitor_daily.factor_autocorr`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `data_quality_status` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.data_quality_status`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `run_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_monitor_daily.run_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `computed_at` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_monitor_daily.computed_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `signal_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `signal_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "signal_date", "maturity_date", "factor_id", "definition_version", "universe_id", "horizon", "direction_prior", "effective_direction", "rank_ic", "rank_ic_p_value", "sample_count", "universe_count", "coverage", "group_1_return", "group_2_return", "group_3_return", "group_4_return", "group_5_return", "top_minus_bottom_return", "oriented_top_minus_bottom_return", "monotonicity_score", "factor_autocorr", "data_quality_status", "run_id", "computed_at"
FROM "factor_monitor_daily"
WHERE "signal_date" >= :window_start
  AND "signal_date" < :window_end
ORDER BY "signal_date", "factor_id", "definition_version", "universe_id", "horizon"
LIMIT :page_size;
```

审计输出：`D139-source-normalized.jsonl`、`D139-actual.jsonl`、`D139-key-diff.json`、`D139-field-diff.json`、`D139-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
