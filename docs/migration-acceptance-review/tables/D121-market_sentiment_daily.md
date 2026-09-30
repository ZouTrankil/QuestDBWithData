# D121 · market_sentiment_daily 表验收

优先级：P4。本文件仅验收 `market_sentiment_daily`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/11-derived/D121-market_sentiment_daily.md)

## 1. 已知结构与来源

- 旧快照：主时间 `trade_date`；分区 `MONTH`；WAL `True`；去重 `False`。
- 旧物理键：**未声明。业务身份和幂等写法尚未冻结，不能通过写入验收。**。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/research/adapters/questdb/models/__init__.py:13](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/questdb/models/__init__.py:13)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/questdb/models/market_sentiment_daily.py:1](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/questdb/models/market_sentiment_daily.py:1)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/market_sentiment_daily.py:1](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/market_sentiment_daily.py:1)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/application/storage_inventory.py:67](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/application/storage_inventory.py:67)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D121-01 | 同输入两个model_version，分别core/enhanced评分 | 模型版本与各分项评分可复算；旧无键，版本保留或替换策略先冻结 |
| D121-02 | L2等增强源缺失 | core/enhanced及data_quality_flag按模型策略呈现，不能用core冒充完整enhanced |
| D121-03 | 从实际来源选两行身份相近但业务不同的 `market_sentiment_daily` 记录，并重放同一批次 | 提交自然身份字段列表和碰撞报告；两个合法实体都保留，同一实体重放不新增。没有键不能以追加两次成功作为通过。 |
| D121-04 | 在审定业务身份下更正本表一个非身份字段，模拟新快照缺少旧实体 | 明确当前状态替换/历史版本/删除策略；只允许已确认的行为，不能把来源失败当全量清空，也不能留下未解释重复。 |
| D121-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `market_sentiment_daily` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D121-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `trade_date` | `TIMESTAMP` | 逐行比较源映射结果与 `market_sentiment_daily.trade_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `month` | `SYMBOL` | 逐行比较源映射结果与 `market_sentiment_daily.month`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `sentiment_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.sentiment_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sentiment_score_core` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.sentiment_score_core`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sentiment_score_enhanced` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.sentiment_score_enhanced`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sentiment_state` | `SYMBOL` | 逐行比较源映射结果与 `market_sentiment_daily.sentiment_state`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `heat_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.heat_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `breadth_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.breadth_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `limit_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.limit_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `profit_effect_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.profit_effect_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `leverage_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.leverage_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `moneyflow_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.moneyflow_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `structure_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.structure_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `divergence_score` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.divergence_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pc1_heat` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.pc1_heat`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pc2_divergence` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.pc2_divergence`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pc3_structure` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.pc3_structure`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_amount` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.total_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amount_to_circ_mv` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.amount_to_circ_mv`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `avg_turnover_rate` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.avg_turnover_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `median_turnover_rate` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.median_turnover_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `high_turnover_stock_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.high_turnover_stock_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `top_amount_share` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.top_amount_share`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `up_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.up_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `down_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.down_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `up_down_spread` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.up_down_spread`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `limit_up_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.limit_up_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `limit_down_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.limit_down_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `limit_net_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.limit_net_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `max_limit_streak` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.max_limit_streak`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `limit_promotion_rate` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.limit_promotion_rate`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pct_above_ma20` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.pct_above_ma20`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `median_distance_to_ma20` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.median_distance_to_ma20`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pct_distance_to_ma20_gt_5` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.pct_distance_to_ma20_gt_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `pct_distance_to_ma20_lt_minus_5` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.pct_distance_to_ma20_lt_minus_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `small_large_ret` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.small_large_ret`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `growth_value_ret` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.growth_value_ret`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `margin_buy_sell_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.margin_buy_sell_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `margin_buy_amount_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.margin_buy_amount_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `margin_balance_change_5d` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.margin_balance_change_5d`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `moneyflow_net_amount_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.moneyflow_net_amount_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `moneyflow_large_net_ratio` | `DOUBLE` | 逐行比较源映射结果与 `market_sentiment_daily.moneyflow_large_net_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `index_up_breadth_down` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.index_up_breadth_down`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `amount_up_limit_down` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.amount_up_limit_down`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `small_up_large_down` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.small_up_large_down`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `sentiment_up_return_down` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.sentiment_up_return_down`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `is_false_boom` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.is_false_boom`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `is_limit_collapse` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.is_limit_collapse`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `is_leverage_warning` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.is_leverage_warning`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `is_crowded` | `BOOLEAN` | 逐行比较源映射结果与 `market_sentiment_daily.is_crowded`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `data_quality_flag` | `SYMBOL` | 逐行比较源映射结果与 `market_sentiment_daily.data_quality_flag`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `model_version` | `SYMBOL` | 逐行比较源映射结果与 `market_sentiment_daily.model_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `updated_at` | `TIMESTAMP` | 逐行比较源映射结果与 `market_sentiment_daily.updated_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `trade_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `trade_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "trade_date", "month", "sentiment_score", "sentiment_score_core", "sentiment_score_enhanced", "sentiment_state", "heat_score", "breadth_score", "limit_score", "profit_effect_score", "leverage_score", "moneyflow_score", "structure_score", "divergence_score", "pc1_heat", "pc2_divergence", "pc3_structure", "total_amount", "amount_to_circ_mv", "avg_turnover_rate", "median_turnover_rate", "high_turnover_stock_ratio", "top_amount_share", "up_ratio", "down_ratio", "up_down_spread", "limit_up_ratio", "limit_down_ratio", "limit_net_ratio", "max_limit_streak", "limit_promotion_rate", "pct_above_ma20", "median_distance_to_ma20", "pct_distance_to_ma20_gt_5", "pct_distance_to_ma20_lt_minus_5", "small_large_ret", "growth_value_ret", "margin_buy_sell_ratio", "margin_buy_amount_ratio", "margin_balance_change_5d", "moneyflow_net_amount_ratio", "moneyflow_large_net_ratio", "index_up_breadth_down", "amount_up_limit_down", "small_up_large_down", "sentiment_up_return_down", "is_false_boom", "is_limit_collapse", "is_leverage_warning", "is_crowded", "data_quality_flag", "model_version", "updated_at"
FROM "market_sentiment_daily"
WHERE "trade_date" >= :window_start
  AND "trade_date" < :window_end
-- 冻结自然身份后补上稳定排序与游标键
LIMIT :page_size;
```

审计输出：`D121-source-normalized.jsonl`、`D121-actual.jsonl`、`D121-key-diff.json`、`D121-field-diff.json`、`D121-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
