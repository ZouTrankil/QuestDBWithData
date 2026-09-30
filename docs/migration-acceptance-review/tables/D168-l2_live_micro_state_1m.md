# D168 · l2_live_micro_state_1m 表验收

优先级：P6。本文件仅验收 `l2_live_micro_state_1m`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/14-runtime/D168-l2_live_micro_state_1m.md)

## 1. 已知结构与来源

- 旧快照：主时间 `trade_minute`；分区 `DAY`；WAL `True`；去重 `False`。
- 旧物理键：**未声明。业务身份和幂等写法尚未冻结，不能通过写入验收。**。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/api/_terminal_market_data.py:58](/Users/Apple/zq/fun/back-monitor/src/api/_terminal_market_data.py:58)（既有审计调用引用，实施时复核）
- [src/api/services/terminal/state.py:40](/Users/Apple/zq/fun/back-monitor/src/api/services/terminal/state.py:40)（既有审计调用引用，实施时复核）
- [src/api/services/terminal/execution.py:66](/Users/Apple/zq/fun/back-monitor/src/api/services/terminal/execution.py:66)（既有审计调用引用，实施时复核）
- [src/quant_platform/best_trader/interfaces/commands/run_risk_off_chase_recorder.py:4](/Users/Apple/zq/fun/back-monitor/src/quant_platform/best_trader/interfaces/commands/run_risk_off_chase_recorder.py:4)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用；定位QMT/事件/运行状态owner，实现有界历史读取、事件适配与typed写入，使用回放样例。定义ingest/reconcile任务，不开实盘、不启动新实时订阅。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D168-01 | 一分钟内多笔交易和十档快照交错 | OHLC/vwap/volume与事件独立聚合；bid/ask1..10顺序不反，depth/OBI逐档复算 |
| D168-02 | 无成交分钟、延迟/乱序事件 | data_delay_ms反映接收差异，micro_action/action_reason按模型输入一致，不用下一分钟数据回填 |
| D168-03 | 从实际来源选两行身份相近但业务不同的 `l2_live_micro_state_1m` 记录，并重放同一批次 | 提交自然身份字段列表和碰撞报告；两个合法实体都保留，同一实体重放不新增。没有键不能以追加两次成功作为通过。 |
| D168-04 | 在审定业务身份下更正本表一个非身份字段，模拟新快照缺少旧实体 | 明确当前状态替换/历史版本/删除策略；只允许已确认的行为，不能把来源失败当全量清空，也不能留下未解释重复。 |
| D168-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `l2_live_micro_state_1m` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D168-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `symbol` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_micro_state_1m.symbol`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `trade_minute` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_live_micro_state_1m.trade_minute`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `trade_date` | `STRING` | 逐行比较源映射结果与 `l2_live_micro_state_1m.trade_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_micro_state_1m.source`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `data_mode` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_micro_state_1m.data_mode`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `trade_count_1m` | `INT` | 逐行比较源映射结果与 `l2_live_micro_state_1m.trade_count_1m`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `last_price` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.last_price`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mid_price` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.mid_price`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `open_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.open_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `high_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.high_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `low_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.low_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `close_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.close_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ret_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_3m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ret_3m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ret_5m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ret_5m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vwap_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.vwap_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vwap_gap_bps` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.vwap_gap_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `amount_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.amount_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `volume_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.volume_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `data_delay_ms` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.data_delay_ms`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `spread_bps` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.spread_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_2` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_2`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_3` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_3`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_4` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_4`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_6` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_6`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_7` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_7`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_8` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_8`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_9` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_9`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_price_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_price_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_2` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_2`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_3` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_3`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_4` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_4`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_6` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_6`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_7` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_7`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_8` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_8`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_9` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_9`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_price_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_price_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_2` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_2`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_3` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_3`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_4` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_4`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_6` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_6`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_7` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_7`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_8` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_8`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_9` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_9`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_vol_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_vol_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_2` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_2`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_3` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_3`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_4` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_4`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_5` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_6` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_6`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_7` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_7`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_8` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_8`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_9` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_9`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_vol_10` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_vol_10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_bid_top1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_bid_top1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_ask_top1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_ask_top1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_bid_top5` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_bid_top5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_ask_top5` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_ask_top5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_bid_top10` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_bid_top10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_ask_top10` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_ask_top10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_top1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.obi_top1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_top5` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.obi_top5`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `obi_top10` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.obi_top10`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `microprice` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.microprice`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `microprice_gap_bps` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.microprice_gap_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_wall_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_wall_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_wall_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_wall_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_depletion_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_depletion_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_recovery_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.depth_recovery_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_buy_amount_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.active_buy_amount_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_sell_amount_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.active_sell_amount_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_buy_volume_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.active_buy_volume_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_sell_volume_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.active_sell_volume_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_buy_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.active_buy_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `active_sell_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.active_sell_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `large_buy_amount_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.large_buy_amount_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `large_sell_amount_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.large_sell_amount_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `large_trade_imbalance` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.large_trade_imbalance`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `price_impact_bps` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.price_impact_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `trade_intensity_zscore` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.trade_intensity_zscore`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `vol_ratio_1m` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.vol_ratio_1m`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `near_bid_add_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.near_bid_add_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `near_ask_add_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.near_ask_add_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `near_bid_cancel_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.near_bid_cancel_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `near_ask_cancel_amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.near_ask_cancel_amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_cancel_add_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.bid_cancel_add_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_cancel_add_ratio` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.ask_cancel_add_ratio`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fake_bid_support_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.fake_bid_support_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `fake_ask_pressure_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.fake_ask_pressure_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `queue_consume_bid_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.queue_consume_bid_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `queue_consume_ask_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.queue_consume_ask_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `no_trade_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.no_trade_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_chase_risk_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.buy_chase_risk_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `buy_support_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.buy_support_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_pressure_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.sell_pressure_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sell_exhaustion_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.sell_exhaustion_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `passive_grid_score` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_micro_state_1m.passive_grid_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `micro_action` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_micro_state_1m.micro_action`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `action_reason` | `STRING` | 逐行比较源映射结果与 `l2_live_micro_state_1m.action_reason`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `updated_at` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_live_micro_state_1m.updated_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `trade_minute` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `trade_minute` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "symbol", "trade_minute", "trade_date", "source", "data_mode", "trade_count_1m", "last_price", "mid_price", "open_1m", "high_1m", "low_1m", "close_1m", "ret_1m", "ret_3m", "ret_5m", "vwap_1m", "vwap_gap_bps", "amount_1m", "volume_1m", "data_delay_ms", "spread_bps", "bid_price_1", "bid_price_2", "bid_price_3", "bid_price_4", "bid_price_5", "bid_price_6", "bid_price_7", "bid_price_8", "bid_price_9", "bid_price_10", "ask_price_1", "ask_price_2", "ask_price_3", "ask_price_4", "ask_price_5", "ask_price_6", "ask_price_7", "ask_price_8", "ask_price_9", "ask_price_10", "bid_vol_1", "bid_vol_2", "bid_vol_3", "bid_vol_4", "bid_vol_5", "bid_vol_6", "bid_vol_7", "bid_vol_8", "bid_vol_9", "bid_vol_10", "ask_vol_1", "ask_vol_2", "ask_vol_3", "ask_vol_4", "ask_vol_5", "ask_vol_6", "ask_vol_7", "ask_vol_8", "ask_vol_9", "ask_vol_10", "depth_bid_top1", "depth_ask_top1", "depth_bid_top5", "depth_ask_top5", "depth_bid_top10", "depth_ask_top10", "obi_top1", "obi_top5", "obi_top10", "microprice", "microprice_gap_bps", "bid_wall_score", "ask_wall_score", "depth_depletion_score", "depth_recovery_score", "active_buy_amount_1m", "active_sell_amount_1m", "active_buy_volume_1m", "active_sell_volume_1m", "active_buy_ratio", "active_sell_ratio", "large_buy_amount_1m", "large_sell_amount_1m", "large_trade_imbalance", "price_impact_bps", "trade_intensity_zscore", "vol_ratio_1m", "near_bid_add_amount", "near_ask_add_amount", "near_bid_cancel_amount", "near_ask_cancel_amount", "bid_cancel_add_ratio", "ask_cancel_add_ratio", "fake_bid_support_score", "fake_ask_pressure_score", "queue_consume_bid_score", "queue_consume_ask_score", "no_trade_score", "buy_chase_risk_score", "buy_support_score", "sell_pressure_score", "sell_exhaustion_score", "passive_grid_score", "micro_action", "action_reason", "updated_at"
FROM "l2_live_micro_state_1m"
WHERE "trade_minute" >= :window_start
  AND "trade_minute" < :window_end
-- 冻结自然身份后补上稳定排序与游标键
LIMIT :page_size;
```

审计输出：`D168-source-normalized.jsonl`、`D168-actual.jsonl`、`D168-key-diff.json`、`D168-field-diff.json`、`D168-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
