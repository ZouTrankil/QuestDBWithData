# D167 · l2_live_normalized_events 表验收

优先级：P6。本文件仅验收 `l2_live_normalized_events`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/14-runtime/D167-l2_live_normalized_events.md)

## 1. 已知结构与来源

- 旧快照：主时间 `recv_ts`；分区 `DAY`；WAL `True`；去重 `False`。
- 旧物理键：**未声明。业务身份和幂等写法尚未冻结，不能通过写入验收。**。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/l2_normalized_event.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/l2_normalized_event.py)（模型入口；不自动视为生产者）
- [src/quant_platform/best_trader/adapters/t200_market_data.py:57](/Users/Apple/zq/fun/back-monitor/src/quant_platform/best_trader/adapters/t200_market_data.py:57)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/adapters/questdb/terminal_market.py:30](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/terminal_market.py:30)（既有审计调用引用，实施时复核）

**已登记来源约束：** exchange_ts与recv_ts分别保留；source事件ID/序列、重连重复、乱序和接收时刻归属逐一核实。只做回放/适配，不启动实时订阅。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D167-01 | 同证券同exchange_ts但不同sub_seq/channel_no事件，含乱序接收 | 事件身份保留序列/通道等必要维度；recv_ts与exchange_ts分开 |
| D167-02 | 解析失败raw_payload及重连重放 | parse_status/parser_version完整，坏包可追溯且不冒充正常成交，重复重放不重复记量 |
| D167-03 | 从实际来源选两行身份相近但业务不同的 `l2_live_normalized_events` 记录，并重放同一批次 | 提交自然身份字段列表和碰撞报告；两个合法实体都保留，同一实体重放不新增。没有键不能以追加两次成功作为通过。 |
| D167-04 | 在审定业务身份下更正本表一个非身份字段，模拟新快照缺少旧实体 | 明确当前状态替换/历史版本/删除策略；只允许已确认的行为，不能把来源失败当全量清空，也不能留下未解释重复。 |
| D167-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `l2_live_normalized_events` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D167-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `vendor` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.vendor`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `recv_ts` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_live_normalized_events.recv_ts`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `trade_date` | `STRING` | 逐行比较源映射结果与 `l2_live_normalized_events.trade_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `exchange_ts` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_live_normalized_events.exchange_ts`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `symbol` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.symbol`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `exchange` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.exchange`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `channel` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.channel`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `session_phase` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.session_phase`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `raw_topic` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.raw_topic`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `raw_payload` | `STRING` | 逐行比较源映射结果与 `l2_live_normalized_events.raw_payload`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `parse_status` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.parse_status`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `parser_version` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.parser_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `event_type` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.event_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `aggressor_side` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.aggressor_side`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `bs_flag` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.bs_flag`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `order_type` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.order_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `order_side` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.order_side`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `volume_semantics` | `SYMBOL` | 逐行比较源映射结果与 `l2_live_normalized_events.volume_semantics`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `kuake_order_type` | `INT` | 逐行比较源映射结果与 `l2_live_normalized_events.kuake_order_type`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `price` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_normalized_events.price`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `volume` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.volume`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `amount` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_normalized_events.amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `sub_seq` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.sub_seq`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `channel_no` | `STRING` | 逐行比较源映射结果与 `l2_live_normalized_events.channel_no`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `order_id` | `STRING` | 逐行比较源映射结果与 `l2_live_normalized_events.order_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `sell_order_id` | `STRING` | 逐行比较源映射结果与 `l2_live_normalized_events.sell_order_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `buy_order_id` | `STRING` | 逐行比较源映射结果与 `l2_live_normalized_events.buy_order_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `total_volume` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.total_volume`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `total_amount` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.total_amount`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `trade_count` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.trade_count`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `bid_price_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_normalized_events.bid_price_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bid_volume_1` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.bid_volume_1`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `ask_price_1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_normalized_events.ask_price_1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `ask_volume_1` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.ask_volume_1`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `spread` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_normalized_events.spread`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `mid_price` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_normalized_events.mid_price`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `depth_top1` | `LONG` | 逐行比较源映射结果与 `l2_live_normalized_events.depth_top1`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `obi_top1` | `DOUBLE` | 逐行比较源映射结果与 `l2_live_normalized_events.obi_top1`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `updated_at` | `TIMESTAMP` | 逐行比较源映射结果与 `l2_live_normalized_events.updated_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `recv_ts` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `recv_ts` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "vendor", "recv_ts", "trade_date", "exchange_ts", "symbol", "exchange", "channel", "session_phase", "raw_topic", "raw_payload", "parse_status", "parser_version", "event_type", "aggressor_side", "bs_flag", "order_type", "order_side", "volume_semantics", "kuake_order_type", "price", "volume", "amount", "sub_seq", "channel_no", "order_id", "sell_order_id", "buy_order_id", "total_volume", "total_amount", "trade_count", "bid_price_1", "bid_volume_1", "ask_price_1", "ask_volume_1", "spread", "mid_price", "depth_top1", "obi_top1", "updated_at"
FROM "l2_live_normalized_events"
WHERE "recv_ts" >= :window_start
  AND "recv_ts" < :window_end
-- 冻结自然身份后补上稳定排序与游标键
LIMIT :page_size;
```

审计输出：`D167-source-normalized.jsonl`、`D167-actual.jsonl`、`D167-key-diff.json`、`D167-field-diff.json`、`D167-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
