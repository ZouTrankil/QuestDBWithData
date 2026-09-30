# D002 · stock_detail_info 表验收

优先级：P0。本文件仅验收 `stock_detail_info`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/02-reference/D002-stock_detail_info.md)

## 1. 已知结构与来源

- 旧快照：主时间 `None`；分区 `None`；WAL `False`；去重 `False`。
- 旧物理键：**未声明。业务身份和幂等写法尚未冻结，不能通过写入验收。**。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/fundamental.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fundamental.py)（模型入口；不自动视为生产者）
- [src/api/services/research_strategy_factor_drilldown.py:307](/Users/Apple/zq/fun/back-monitor/src/api/services/research_strategy_factor_drilldown.py:307)（既有审计调用引用，实施时复核）
- [src/api/services/research_assets.py:93](/Users/Apple/zq/fun/back-monitor/src/api/services/research_assets.py:93)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/adapters/stock_metadata.py:24](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/adapters/stock_metadata.py:24)（既有审计调用引用，实施时复核）

**已登记来源约束：** 对照stock_detail_info_sync.py按L/D/P状态获取stock_basic，核清字段全量及退市区间；现有Java仅L状态的stock_basic快照不是此表的等价替代。快照时间与update_time分别建模。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D002-01 | 分别取 L/D/P 三个状态，令同 ts_code 出现跨状态冲突 | 三类请求均有结果证据，冲突明确处理；不允许缺一次请求却发布完整快照 |
| D002-02 | 修改 name、industry、list_status、delist_date 后重跑 | 最终每 ts_code 一条当前资料；update_time 为采集时刻，list_date/delist_date 不偏移 |
| D002-03 | 从实际来源选两行身份相近但业务不同的 `stock_detail_info` 记录，并重放同一批次 | 提交自然身份字段列表和碰撞报告；两个合法实体都保留，同一实体重放不新增。没有键不能以追加两次成功作为通过。 |
| D002-04 | 在审定业务身份下更正本表一个非身份字段，模拟新快照缺少旧实体 | 明确当前状态替换/历史版本/删除策略；只允许已确认的行为，不能把来源失败当全量清空，也不能留下未解释重复。 |
| D002-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `stock_detail_info` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D002-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `stock_detail_info.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `update_time` | `TIMESTAMP` | 逐行比较源映射结果与 `stock_detail_info.update_time`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `symbol` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.symbol`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `name` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.name`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `market` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.market`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `exchange` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.exchange`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `list_status` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.list_status`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `list_date` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.list_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `fullname` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.fullname`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `enname` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.enname`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `cnspell` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.cnspell`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `area` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.area`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `industry` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.industry`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `curr_type` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.curr_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `delist_date` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.delist_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `is_hs` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.is_hs`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `act_name` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.act_name`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `act_ent_type` | `STRING` | 逐行比较源映射结果与 `stock_detail_info.act_ent_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是审核用参数化查询模板；在隔离目标中执行，`:scope_predicate` 必须替换为本表已确认的日期范围或来源身份过滤，不能直接执行占位符或无界扫描。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "update_time", "symbol", "name", "market", "exchange", "list_status", "list_date", "fullname", "enname", "cnspell", "area", "industry", "curr_type", "delist_date", "is_hs", "act_name", "act_ent_type"
FROM "stock_detail_info"
WHERE :scope_predicate
-- 冻结自然身份后补上稳定排序与游标键
LIMIT :page_size;
```

审计输出：`D002-source-normalized.jsonl`、`D002-actual.jsonl`、`D002-key-diff.json`、`D002-field-diff.json`、`D002-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
