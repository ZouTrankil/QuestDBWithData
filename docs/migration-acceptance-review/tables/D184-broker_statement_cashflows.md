# D184 · broker_statement_cashflows 表验收

优先级：P6。本文件仅验收 `broker_statement_cashflows`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/14-runtime/D184-broker_statement_cashflows.md)

## 1. 已知结构与来源

- 旧快照：主时间 `event_at`；分区 `MONTH`；WAL `True`；去重 `True`。
- 旧物理键：`source_key`, `event_at`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/workflows/best_trader/import_broker_records.py:89](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/best_trader/import_broker_records.py:89)（既有审计调用引用，实施时复核）
- [src/quant_platform/best_trader/application/trading_alpha_review.py:472](/Users/Apple/zq/fun/back-monitor/src/quant_platform/best_trader/application/trading_alpha_review.py:472)（既有审计调用引用，实施时复核）
- [src/quant_platform/best_trader/adapters/broker_statement_pdf.py:5](/Users/Apple/zq/fun/back-monitor/src/quant_platform/best_trader/adapters/broker_statement_pdf.py:5)（既有审计调用引用，实施时复核）
- [src/quant_platform/best_trader/adapters/questdb/models/broker_statements.py:39](/Users/Apple/zq/fun/back-monitor/src/quant_platform/best_trader/adapters/questdb/models/broker_statements.py:39)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用；按单文件×批次导入券商交割记录，保留source_key与文件hash，有限行流式处理并对账。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D184-01 | 同账单入金、出金、利息和费用各一笔 | transaction_type/amount符号与post_balance逐笔对照 |
| D184-02 | 同文件改名重导及不同文件重复事件 | source_key/hash策略避免重复计账，event_at与business_date保留真实来源时区 |
| D184-03 | 将真实非空批次规范化后保存；先确认 `source_key, event_at` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `broker_statement_cashflows`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D184-04 | 对键 `source_key, event_at` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D184-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `broker_statement_cashflows` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D184-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `source_key` | `SYMBOL` | 逐行比较源映射结果与 `broker_statement_cashflows.source_key`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `broker_name` | `SYMBOL` | 逐行比较源映射结果与 `broker_statement_cashflows.broker_name`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `account_id` | `SYMBOL` | 逐行比较源映射结果与 `broker_statement_cashflows.account_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `client_no` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.client_no`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `business_date` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.business_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `event_time` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.event_time`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `event_at` | `TIMESTAMP` | 逐行比较源映射结果与 `broker_statement_cashflows.event_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `serial_no` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.serial_no`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `market` | `SYMBOL` | 逐行比较源映射结果与 `broker_statement_cashflows.market`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `currency` | `SYMBOL` | 逐行比较源映射结果与 `broker_statement_cashflows.currency`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `transaction_type` | `SYMBOL` | 逐行比较源映射结果与 `broker_statement_cashflows.transaction_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `amount` | `DOUBLE` | 逐行比较源映射结果与 `broker_statement_cashflows.amount`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `post_balance` | `DOUBLE` | 逐行比较源映射结果与 `broker_statement_cashflows.post_balance`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `remark` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.remark`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source_page` | `INT` | 逐行比较源映射结果与 `broker_statement_cashflows.source_page`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `source_file` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.source_file`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source_sha256` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.source_sha256`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `statement_period` | `STRING` | 逐行比较源映射结果与 `broker_statement_cashflows.statement_period`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `imported_at` | `TIMESTAMP` | 逐行比较源映射结果与 `broker_statement_cashflows.imported_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `event_at` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `event_at` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "source_key", "broker_name", "account_id", "client_no", "business_date", "event_time", "event_at", "serial_no", "market", "currency", "transaction_type", "amount", "post_balance", "remark", "source_page", "source_file", "source_sha256", "statement_period", "imported_at"
FROM "broker_statement_cashflows"
WHERE "event_at" >= :window_start
  AND "event_at" < :window_end
ORDER BY "source_key", "event_at"
LIMIT :page_size;
```

审计输出：`D184-source-normalized.jsonl`、`D184-actual.jsonl`、`D184-key-diff.json`、`D184-field-diff.json`、`D184-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
