# D150 · strategy_backtest_runs 表验收

优先级：P5。本文件仅验收 `strategy_backtest_runs`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/13-strategy/D150-strategy_backtest_runs.md)

## 1. 已知结构与来源

- 旧快照：主时间 `created_at`；分区 `MONTH`；WAL `True`；去重 `True`。
- 旧物理键：`run_id`, `created_at`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/research/adapters/etf_only_top2/etf_only_top2_persistence.py:188](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/etf_only_top2/etf_only_top2_persistence.py:188)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/etf7030/etf7030_persistence.py:60](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/etf7030/etf7030_persistence.py:60)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D150-01 | 一个真实回测run从初始资金到final_equity | 收益/费用/成交数分别与该run日权益和成交汇总核对 |
| D150-02 | 同参数不同code_version/data_asof | run_id隔离，params_hash可重算；artifact_dir指向对应运行不是最新目录 |
| D150-03 | 将真实非空批次规范化后保存；先确认 `run_id, created_at` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `strategy_backtest_runs`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D150-04 | 对键 `run_id, created_at` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D150-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `strategy_backtest_runs` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D150-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `run_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_backtest_runs.run_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `strategy_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_backtest_runs.strategy_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `instance_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_backtest_runs.instance_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source_strategy` | `SYMBOL` | 逐行比较源映射结果与 `strategy_backtest_runs.source_strategy`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `backtest_id` | `SYMBOL` | 逐行比较源映射结果与 `strategy_backtest_runs.backtest_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `artifact_dir` | `STRING` | 逐行比较源映射结果与 `strategy_backtest_runs.artifact_dir`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `params_hash` | `SYMBOL` | 逐行比较源映射结果与 `strategy_backtest_runs.params_hash`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `code_version` | `STRING` | 逐行比较源映射结果与 `strategy_backtest_runs.code_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `start_date` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_backtest_runs.start_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `end_date` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_backtest_runs.end_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `data_asof` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_backtest_runs.data_asof`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `initial_capital` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.initial_capital`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `final_equity` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.final_equity`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_return` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.total_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `annualized_return` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.annualized_return`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `annualized_vol` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.annualized_vol`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sharpe` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.sharpe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `max_drawdown` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.max_drawdown`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `win_rate` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.win_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_daily_turnover` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.avg_daily_turnover`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_trade_value` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.total_trade_value`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_commission` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.total_commission`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_stamp_tax` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.total_stamp_tax`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_management_fee` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.total_management_fee`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_cost` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.total_cost`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `trade_count` | `INT` | 逐行比较源映射结果与 `strategy_backtest_runs.trade_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `avg_stock_weight` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.avg_stock_weight`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_etf_weight` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.avg_etf_weight`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_cash_weight` | `DOUBLE` | 逐行比较源映射结果与 `strategy_backtest_runs.avg_cash_weight`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `notes` | `STRING` | 逐行比较源映射结果与 `strategy_backtest_runs.notes`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `created_at` | `TIMESTAMP` | 逐行比较源映射结果与 `strategy_backtest_runs.created_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `created_at` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `created_at` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "run_id", "strategy_id", "instance_id", "source_strategy", "backtest_id", "artifact_dir", "params_hash", "code_version", "start_date", "end_date", "data_asof", "initial_capital", "final_equity", "total_return", "annualized_return", "annualized_vol", "sharpe", "max_drawdown", "win_rate", "avg_daily_turnover", "total_trade_value", "total_commission", "total_stamp_tax", "total_management_fee", "total_cost", "trade_count", "avg_stock_weight", "avg_etf_weight", "avg_cash_weight", "notes", "created_at"
FROM "strategy_backtest_runs"
WHERE "created_at" >= :window_start
  AND "created_at" < :window_end
ORDER BY "run_id", "created_at"
LIMIT :page_size;
```

审计输出：`D150-source-normalized.jsonl`、`D150-actual.jsonl`、`D150-key-diff.json`、`D150-field-diff.json`、`D150-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
