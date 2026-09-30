# D134 · factor_daily_metric 表验收

优先级：P5。本文件仅验收 `factor_daily_metric`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/12-factor/D134-factor_daily_metric.md)

## 1. 已知结构与来源

- 旧快照：主时间 `trade_date`；分区 `MONTH`；WAL `True`；去重 `True`。
- 旧物理键：`trade_date`, `universe_id`, `universe_version`, `factor_id`, `definition_version`, `label_definition`, `horizon`, `evaluation_profile`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/factor_canonical.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/factor_canonical.py)（模型入口；不自动视为生产者）
- [src/api/services/runtime_tasks.py:55](/Users/Apple/zq/fun/back-monitor/src/api/services/runtime_tasks.py:55)（既有审计调用引用，实施时复核）
- [src/api/services/research_factor_performance.py:236](/Users/Apple/zq/fun/back-monitor/src/api/services/research_factor_performance.py:236)（既有审计调用引用，实施时复核）
- [src/api/services/page_snapshots.py:53](/Users/Apple/zq/fun/back-monitor/src/api/services/page_snapshots.py:53)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D134-01 | 同因子日两个horizon/evaluation_profile/label_definition | 完整九维键不覆盖，maturity_at之前不能作为已成熟评价 |
| D134-02 | 冻结样本复算rank_ic及十组收益 | sample_count/evaluation_sample_hash一致，pure_*与原始指标分开，控制变量缺失影响control_coverage |
| D134-03 | 将真实非空批次规范化后保存；先确认 `trade_date, universe_id, universe_version, factor_id, definition_version, label_definition, horizon, evaluation_profile` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `factor_daily_metric`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D134-04 | 对键 `trade_date, universe_id, universe_version, factor_id, definition_version, label_definition, horizon, evaluation_profile` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D134-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `factor_daily_metric` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D134-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `trade_date` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_daily_metric.trade_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `universe_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.universe_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `universe_version` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.universe_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `factor_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.factor_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `definition_version` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.definition_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `label_definition` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.label_definition`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `horizon` | `INT` | 逐行比较源映射结果与 `factor_daily_metric.horizon`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `evaluation_profile` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.evaluation_profile`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `label_price_basis` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.label_price_basis`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `maturity_at` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_daily_metric.maturity_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `rank_ic` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.rank_ic`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `top_minus_bottom` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.top_minus_bottom`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `win` | `BOOLEAN` | 逐行比较源映射结果与 `factor_daily_metric.win`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `decile_monotonicity` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_monotonicity`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `factor_autocorrelation` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.factor_autocorrelation`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_1_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_1_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_2_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_2_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_3_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_3_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_4_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_4_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_5_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_5_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_6_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_6_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_7_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_7_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_8_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_8_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_9_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_9_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `decile_10_mean_return` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.decile_10_mean_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pure_rank_ic` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.pure_rank_ic`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pure_top_minus_bottom` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.pure_top_minus_bottom`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pure_win` | `BOOLEAN` | 逐行比较源映射结果与 `factor_daily_metric.pure_win`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `pure_decile_monotonicity` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.pure_decile_monotonicity`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pure_model_r_squared` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.pure_model_r_squared`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `control_coverage` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.control_coverage`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `control_definition_version` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.control_definition_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `coverage` | `DOUBLE` | 逐行比较源映射结果与 `factor_daily_metric.coverage`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sample_count` | `INT` | 逐行比较源映射结果与 `factor_daily_metric.sample_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `evaluation_sample_hash` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.evaluation_sample_hash`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `membership_as_of` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_daily_metric.membership_as_of`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `available_at` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_daily_metric.available_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `data_quality_status` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.data_quality_status`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `l2_source_lineage_digest` | `SYMBOL` | 逐行比较源映射结果与 `factor_daily_metric.l2_source_lineage_digest`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `trade_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `trade_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "trade_date", "universe_id", "universe_version", "factor_id", "definition_version", "label_definition", "horizon", "evaluation_profile", "label_price_basis", "maturity_at", "rank_ic", "top_minus_bottom", "win", "decile_monotonicity", "factor_autocorrelation", "decile_1_mean_return", "decile_2_mean_return", "decile_3_mean_return", "decile_4_mean_return", "decile_5_mean_return", "decile_6_mean_return", "decile_7_mean_return", "decile_8_mean_return", "decile_9_mean_return", "decile_10_mean_return", "pure_rank_ic", "pure_top_minus_bottom", "pure_win", "pure_decile_monotonicity", "pure_model_r_squared", "control_coverage", "control_definition_version", "coverage", "sample_count", "evaluation_sample_hash", "membership_as_of", "available_at", "data_quality_status", "l2_source_lineage_digest"
FROM "factor_daily_metric"
WHERE "trade_date" >= :window_start
  AND "trade_date" < :window_end
ORDER BY "trade_date", "universe_id", "universe_version", "factor_id", "definition_version", "label_definition", "horizon", "evaluation_profile"
LIMIT :page_size;
```

审计输出：`D134-source-normalized.jsonl`、`D134-actual.jsonl`、`D134-key-diff.json`、`D134-field-diff.json`、`D134-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
