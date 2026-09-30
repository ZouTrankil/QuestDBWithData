# D087 · l2_intraday_bar_features 表验收

优先级：P4。本文件仅验收 `l2_intraday_bar_features`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/10-l2/D087-l2_intraday_bar_features.md)

## 1. 已知结构与来源

- 旧快照：主时间 `minute`；分区 `DAY`；WAL `True`；去重 `True`。
- 旧物理键：`symbol`, `minute`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/api/services/terminal/state.py:41](/Users/Apple/zq/fun/back-monitor/src/api/services/terminal/state.py:41)（既有审计调用引用，实施时复核）
- [src/api/services/terminal/intraday_source.py:15](/Users/Apple/zq/fun/back-monitor/src/api/services/terminal/intraday_source.py:15)（既有审计调用引用，实施时复核）
- [src/api/services/terminal/execution.py:66](/Users/Apple/zq/fun/back-monitor/src/api/services/terminal/execution.py:66)（既有审计调用引用，实施时复核）
- [src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:72](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:72)（既有审计调用引用，实施时复核）

**已登记来源约束：** 按股票×日期流式生成分钟特征；确认minute起止边界、交易时区、symbol+minute键及分区，不整归档载入内存。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D087-01 | 两相邻分钟、午间断档、无成交有报价分钟 | OHLC/volume/amount/tick_count按分钟边界独立复算，has_trade_1m不伪造交易 |
| D087-02 | 五档与十档深度、rolling ret_3m/5m等 | 同minute逐列对Python，窗口不得穿未来数据，午休规则明确 |
| D087-03 | 将真实非空批次规范化后保存；先确认 `symbol, minute` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `l2_intraday_bar_features`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D087-04 | 对键 `symbol, minute` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D087-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `l2_intraday_bar_features` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D087-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `trade_date` | `STRING` | 逐行比较源映射结果与 `l2_intraday_bar_features.trade_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `symbol` | `SYMBOL` | 逐行比较源映射结果与 `l2_intraday_bar_features.symbol`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `market` | `STRING` | 逐行比较源映射结果与 `l2_intraday_bar_features.market`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `board` | `STRING` | 逐行比较源映射结果与 `l2_intraday_bar_features.board`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `minute` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_intraday_bar_features.minute`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `open` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.open`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `high` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.high`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `low` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.low`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `close` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.close`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `volume` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.volume`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tick_count` | `LONG` | 逐行比较源映射结果与 `l2_intraday_bar_features.tick_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `active_buy_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.active_buy_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_sell_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.active_sell_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vwap` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vwap`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `has_trade_1m` | `LONG` | 逐行比较源映射结果与 `l2_intraday_bar_features.has_trade_1m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `bid1` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.bid1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask1` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ask1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mid` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.mid`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.spread`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `microprice` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.microprice`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_depth_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.bid_depth_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_depth_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ask_depth_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.depth_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.obi_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_depth_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.bid_depth_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_depth_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ask_depth_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.depth_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.obi_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_depth_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.bid_depth_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_depth_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ask_depth_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.depth_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.obi_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `quote_count` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.quote_count`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ofi_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ofi_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_buy_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.active_buy_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_sell_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.active_sell_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vwap_gap_to_mid` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vwap_gap_to_mid`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vwap_gap_to_open` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vwap_gap_to_open`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vwap_slope_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vwap_slope_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vwap_slope_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vwap_slope_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol_ratio_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vol_ratio_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `range_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.range_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ret_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol_ratio_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vol_ratio_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `range_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.range_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ret_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol_ratio_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vol_ratio_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `range_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.range_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ret_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol_ratio_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vol_ratio_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `range_10m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.range_10m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ret_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol_ratio_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vol_ratio_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `range_15m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.range_15m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.ret_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol_ratio_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.vol_ratio_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `range_30m` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.range_30m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `cancel_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_intraday_bar_features.cancel_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `minute` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `minute` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "trade_date", "symbol", "market", "board", "minute", "open", "high", "low", "close", "volume", "amount", "tick_count", "active_buy_amount", "active_sell_amount", "vwap", "has_trade_1m", "bid1", "ask1", "mid", "spread", "microprice", "bid_depth_1", "ask_depth_1", "depth_1", "obi_1", "bid_depth_5", "ask_depth_5", "depth_5", "obi_5", "bid_depth_10", "ask_depth_10", "depth_10", "obi_10", "quote_count", "ofi_1m", "active_buy_ratio", "active_sell_ratio", "vwap_gap_to_mid", "vwap_gap_to_open", "vwap_slope_3m", "vwap_slope_5m", "ret_1m", "vol_ratio_1m", "range_1m", "ret_3m", "vol_ratio_3m", "range_3m", "ret_5m", "vol_ratio_5m", "range_5m", "ret_10m", "vol_ratio_10m", "range_10m", "ret_15m", "vol_ratio_15m", "range_15m", "ret_30m", "vol_ratio_30m", "range_30m", "cancel_ratio"
FROM "l2_intraday_bar_features"
WHERE "minute" >= :window_start
  AND "minute" < :window_end
ORDER BY "symbol", "minute"
LIMIT :page_size;
```

审计输出：`D087-source-normalized.jsonl`、`D087-actual.jsonl`、`D087-key-diff.json`、`D087-field-diff.json`、`D087-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
