# D057 · fina_audit 表验收

优先级：P2。本文件仅验收 `fina_audit`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/07-financial/D057-fina_audit.md)

## 1. 已知结构与来源

- 旧快照：主时间 `ann_date`；分区 `YEAR`；WAL `True`；去重 `True`。
- 旧物理键：`ts_code`, `ann_date`, `end_date`。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/stock/fina/fina_audit.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/fina_audit.py)（模型入口；不自动视为生产者）
- [src/quant_platform/data/adapters/config/tables.py:63](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/config/tables.py:63)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/adapters/mappers/financial_data.py:19](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/mappers/financial_data.py:19)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/adapters/config/table_definitions/reference_events.py:10](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/config/table_definitions/reference_events.py:10)（既有审计调用引用，实施时复核）

**已登记来源约束：** 以本卡Python入口为准逐参数翻译；实现代码/日期/月份等可支持的有限分片和分页能力声明。先冻结真实request例子及结束条件，不能根据表名套默认全量请求。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D057-01 | 两份报告的审计意见/机构/签字人不同 | audit_result/audit_agency/audit_sign 文本完整，audit_fees 空和0区分 |
| D057-02 | 同报告更正公告 | 按完整三维键核对，不能只保留最新报告期忽略更正 |
| D057-03 | 将真实非空批次规范化后保存；先确认 `ts_code, ann_date, end_date` 是否足够区分本表业务身份，再仅合并已确认的同一实体修订，写入隔离 `fina_audit`，再提交同批次 | 完整键集合与规范化源一致；第二次提交后唯一键数不变，所有业务字段不变。运行元数据变化须列出具体允许字段；禁止把非幂等字段整体排除。 |
| D057-04 | 对键 `ts_code, ann_date, end_date` 的每一维分别改变一个值，构造业务上合法的两行；另取完全相同键但非键值冲突两行 | 合法不同键均保留；冲突按审定修订规则处理，不能随机保留首行。若某维为技术时间，需额外证明同一业务事件重跑不会生成新时间而重复入库。 |
| D057-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `fina_audit` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D057-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `ts_code` | `SYMBOL` | 逐行比较源映射结果与 `fina_audit.ts_code`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `ann_date` | `TIMESTAMP` | 逐行比较源映射结果与 `fina_audit.ann_date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `end_date` | `STRING` | 逐行比较源映射结果与 `fina_audit.end_date`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 **此列为旧物理键的一部分，空键/身份冲突必须明确拒绝或审定处理。** |
| `audit_result` | `STRING` | 逐行比较源映射结果与 `fina_audit.audit_result`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `audit_fees` | `DOUBLE` | 逐行比较源映射结果与 `fina_audit.audit_fees`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `audit_agency` | `STRING` | 逐行比较源映射结果与 `fina_audit.audit_agency`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `audit_sign` | `STRING` | 逐行比较源映射结果与 `fina_audit.audit_sign`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `ann_date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `ann_date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "ts_code", "ann_date", "end_date", "audit_result", "audit_fees", "audit_agency", "audit_sign"
FROM "fina_audit"
WHERE "ann_date" >= :window_start
  AND "ann_date" < :window_end
ORDER BY "ts_code", "ann_date", "end_date"
LIMIT :page_size;
```

审计输出：`D057-source-normalized.jsonl`、`D057-actual.jsonl`、`D057-key-diff.json`、`D057-field-diff.json`、`D057-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
