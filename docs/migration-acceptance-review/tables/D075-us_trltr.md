# D075 · us_trltr 表验收

优先级：P3。本文件仅验收 `us_trltr`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/08-macro/D075-us_trltr.md)

## 1. 已知结构与来源

- 旧快照：主时间 `date`；分区 `YEAR`；WAL `True`；去重 `False`。
- 旧物理键：**未声明。业务身份和幂等写法尚未冻结，不能通过写入验收。**。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/macro/us_trltr.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/macro/us_trltr.py)（模型入口；不自动视为生产者）
- [src/quant_platform/data/adapters/config/tables.py:108](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/config/tables.py:108)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/adapters/config/table_definitions/macro.py:52](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/config/table_definitions/macro.py:52)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/application/queries/macro.py:56](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/application/queries/macro.py:56)（既有审计调用引用，实施时复核）

**已登记来源约束：** 以本卡Python入口为准逐参数翻译；实现代码/日期/月份等可支持的有限分片和分页能力声明。先冻结真实request例子及结束条件，不能根据表名套默认全量请求。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D075-01 | 三个发布日的ltr_avg含缺值 | 只映射真实平均长期利率，不拿名义ltc替代 |
| D075-02 | 非发布日与重抓 | 缺失不填零，按date幂等且来源可追溯 |
| D075-03 | 从实际来源选两行身份相近但业务不同的 `us_trltr` 记录，并重放同一批次 | 提交自然身份字段列表和碰撞报告；两个合法实体都保留，同一实体重放不新增。没有键不能以追加两次成功作为通过。 |
| D075-04 | 在审定业务身份下更正本表一个非身份字段，模拟新快照缺少旧实体 | 明确当前状态替换/历史版本/删除策略；只允许已确认的行为，不能把来源失败当全量清空，也不能留下未解释重复。 |
| D075-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `us_trltr` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D075-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `date` | `TIMESTAMP` | 逐行比较源映射结果与 `us_trltr.date`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `ltr_avg` | `DOUBLE` | 逐行比较源映射结果与 `us_trltr.ltr_avg`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `date` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `date` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "date", "ltr_avg"
FROM "us_trltr"
WHERE "date" >= :window_start
  AND "date" < :window_end
-- 冻结自然身份后补上稳定排序与游标键
LIMIT :page_size;
```

审计输出：`D075-source-normalized.jsonl`、`D075-actual.jsonl`、`D075-key-diff.json`、`D075-field-diff.json`、`D075-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
