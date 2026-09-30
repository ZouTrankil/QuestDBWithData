# D054 · express 表验收

优先级：P2。本文件仅验收 `express`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/07-financial/D054-express.md)

## 1. 已知结构与来源

- 旧快照：主时间 `ann_date`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`ts_code`, `ann_date`, `end_date`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/fina/express.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/express.py)（模型入口；不自动视为生产者）
- [src/quant_platform/research/engines/etf_factor_catalog.py:202](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/etf_factor_catalog.py:202)（既有审计调用引用，实施时复核）
- [src/quant_platform/research/engines/etf_factor_calculations.py:382](/Users/Apple/zq/fun/back-monitor/src/quant_platform/research/engines/etf_factor_calculations.py:382)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/adapters/config/tables.py:64](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/config/tables.py:64)（既有审计调用引用，实施时复核）

**已登记来源约束：** 普通接口按证券代码×公告日期窗，VIP路径按报告期；核查实际入口、权限、分页/触顶检测与修订。Python普通路径装饰器限流不能视为VIP路径同样受限；关联period_meta只通过其独立适配器更新。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D054-01 | 同 end_date 的快报更正，is_audit 和 perf_summary 改变 | 按 ann_date 及定义版本保留更正，文本完整不截断 |
| D054-02 | yoy_sales/yoy_op/yoy_tp 与基础收入利润 | 逐列检查，快报不能直接覆盖 income 正式报表 |
| D054-03 | 将真实非空批次规范化后保存；先确认 `ts_code, ann_date, end_date` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `express`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D054-04 | 对键 `ts_code, ann_date, end_date` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D054-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `express` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D054-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `express.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `ann_date` | `TIMESTAMP` | 逐行比较源映射结果与 `express.ann_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `end_date` | `STRING` | 逐行比较源映射结果与 `express.end_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `revenue` | `DOUBLE` | 逐行比较源映射结果与 `express.revenue`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `operate_profit` | `DOUBLE` | 逐行比较源映射结果与 `express.operate_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_profit` | `DOUBLE` | 逐行比较源映射结果与 `express.total_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `n_income` | `DOUBLE` | 逐行比较源映射结果与 `express.n_income`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_assets` | `DOUBLE` | 逐行比较源映射结果与 `express.total_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `total_hldr_eqy_exc_min_int` | `DOUBLE` | 逐行比较源映射结果与 `express.total_hldr_eqy_exc_min_int`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `diluted_eps` | `DOUBLE` | 逐行比较源映射结果与 `express.diluted_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `diluted_roe` | `DOUBLE` | 逐行比较源映射结果与 `express.diluted_roe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_net_profit` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_net_profit`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `bps` | `DOUBLE` | 逐行比较源映射结果与 `express.bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_sales` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_sales`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_op` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_op`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_tp` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_tp`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_dedu_np` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_dedu_np`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_eps` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_eps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_roe` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_roe`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `growth_assets` | `DOUBLE` | 逐行比较源映射结果与 `express.growth_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `yoy_equity` | `DOUBLE` | 逐行比较源映射结果与 `express.yoy_equity`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `growth_bps` | `DOUBLE` | 逐行比较源映射结果与 `express.growth_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `open_net_assets` | `DOUBLE` | 逐行比较源映射结果与 `express.open_net_assets`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `open_bps` | `DOUBLE` | 逐行比较源映射结果与 `express.open_bps`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `or_last_year` | `DOUBLE` | 逐行比较源映射结果与 `express.or_last_year`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `op_last_year` | `DOUBLE` | 逐行比较源映射结果与 `express.op_last_year`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `tp_last_year` | `DOUBLE` | 逐行比较源映射结果与 `express.tp_last_year`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `np_last_year` | `DOUBLE` | 逐行比较源映射结果与 `express.np_last_year`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `eps_last_year` | `DOUBLE` | 逐行比较源映射结果与 `express.eps_last_year`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `perf_summary` | `STRING` | 逐行比较源映射结果与 `express.perf_summary`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `is_audit` | `INT` | 逐行比较源映射结果与 `express.is_audit`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `remark` | `STRING` | 逐行比较源映射结果与 `express.remark`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `ann_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `ann_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "ann_date", "end_date", "revenue", "operate_profit", "total_profit", "n_income", "total_assets", "total_hldr_eqy_exc_min_int", "diluted_eps", "diluted_roe", "yoy_net_profit", "bps", "yoy_sales", "yoy_op", "yoy_tp", "yoy_dedu_np", "yoy_eps", "yoy_roe", "growth_assets", "yoy_equity", "growth_bps", "open_net_assets", "open_bps", "or_last_year", "op_last_year", "tp_last_year", "np_last_year", "eps_last_year", "perf_summary", "is_audit", "remark"
FROM "express"
WHERE "ann_date" >= :window_start
  AND "ann_date" < :window_end
ORDER BY "ts_code", "ann_date", "end_date"
LIMIT :page_size;
```

审计输出：`D054-source-normalized.jsonl`、`D054-actual.jsonl`、`D054-key-diff.json`、`D054-field-diff.json`、`D054-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
