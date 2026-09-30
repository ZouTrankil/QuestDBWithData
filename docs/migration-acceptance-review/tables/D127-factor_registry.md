# D127 · factor_registry 表验收

优先级：P5。本文件仅验收 `factor_registry`；状态：v0.2 待 Review，未执行数据库验证。

[索引](../datasets.md) · [原任务卡](../../migration-tasks-20260929/12-factor/D127-factor_registry.md)

## 1. 已知结构与来源

- 旧快照：主时间 `updated_at`；分区 `NONE`；WAL `False`；去重 `False`。
- 旧物理键：**未声明。业务身份和幂等写法尚未冻结，不能通过写入验收。**。
- 上述为导出结构事实，不宣称键一定满足业务多行身份。以下用例发现冲突时先审定扩键/版本化/聚合方案。

- [src/quant_platform/data/adapters/questdb/models/factor_data_product.py](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/models/factor_data_product.py)（模型入口；不自动视为生产者）
- [src/quant_platform/workflows/research/factor_platform_v2.py:307](/Users/Apple/zq/fun/back-monitor/src/quant_platform/workflows/research/factor_platform_v2.py:307)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/maintenance/questdb/factor_platform_convergence.py:32](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/maintenance/questdb/factor_platform_convergence.py:32)（既有审计调用引用，实施时复核）
- [src/quant_platform/data/adapters/questdb/factor_monitor_catalog.py:4](/Users/Apple/zq/fun/back-monitor/src/quant_platform/data/adapters/questdb/factor_monitor_catalog.py:4)（既有审计调用引用，实施时复核）

**已登记来源约束：** Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

## 2. 本表专项用例

| 用例 | 输入或操作 | 必须观察到的结果 |
|---|---|---|
| D127-01 | 同因子canonical与variant两种定义 | variant_id/is_canonical/lineage_parent_id/version正确指向；source_table/source_column逐项存在 |
| D127-02 | promote_to_db=true但落库失败 | promoted不能提前true，parameters/neutralize/normalize/decay结构往返一致 |
| D127-03 | 从实际来源选两行身份相近但业务不同的 `factor_registry` 记录，并重放同一批次 | 提交自然身份字段列表和碰撞报告；两个合法实体都保留，同一实体重放不新增。没有键不能以追加两次成功作为通过。 |
| D127-04 | 在审定业务身份下更正本表一个非身份字段，模拟新快照缺少旧实体 | 明确当前状态替换/历史版本/删除策略；只允许已确认的行为，不能把来源失败当全量清空，也不能留下未解释重复。 |
| D127-05 | 本表来源第一片/批验证成功、下一片失败，再重启恢复；同范围并发执行 | 已验证 `factor_registry` 数据可证明复用，检查点不跨过失败片；冲突写入被拒绝。恢复后键集合/字段值等同一次完整运行；未知提交需对账后才可重放。 |
| D127-06 | 在本表真实输入上逐字段执行下方矩阵，随后以不同pageSize分两次以上读取同一冻结范围 | 每列都有源值→业务值→存储值→读出值对照；两次遍历的完整身份集合一致，重复/漏键/额外键/未解释字段差异均为0。 |

## 3. 全字段验证矩阵

每一行均为独立验收项。必须在证据文件填写实际源字段或推导函数、输入样例和读出值。技术运行时间不能与两次独立运行做字面相等比较，应验证身份、时区、范围和对应运行；其余业务列按同一冻结输入逐值比较。下表是物理类型规则，不根据字段名擅自推定日期/时刻语义。

| 目标列 | 物理类型 | 具体字段检查 |
|---|---|---|
| `updated_at` | `TIMESTAMP` | 逐行比较源映射结果与 `factor_registry.updated_at`；写明业务日期/绝对时刻/技术载体之一及单位；验证非零精度或跨日边界，日期不得因+08显示换日；禁止将epoch秒当微秒。 |
| `run_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.run_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `factor_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.factor_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `definition_version` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.definition_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `asset_type` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.asset_type`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source_group` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.source_group`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source_table` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.source_table`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `source_column` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.source_column`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `formula` | `STRING` | 逐行比较源映射结果与 `factor_registry.formula`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `factor_family` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.factor_family`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `frequency` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.frequency`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `role` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.role`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `variant_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.variant_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `is_canonical` | `BOOLEAN` | 逐行比较源映射结果与 `factor_registry.is_canonical`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `validation_profile` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.validation_profile`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `lifecycle_status` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.lifecycle_status`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `lineage_parent_id` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.lineage_parent_id`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `lineage_parent_version` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.lineage_parent_version`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `change_reason` | `STRING` | 逐行比较源映射结果与 `factor_registry.change_reason`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `direction_prior` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.direction_prior`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `transform` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.transform`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `pit_policy` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.pit_policy`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `enabled` | `BOOLEAN` | 逐行比较源映射结果与 `factor_registry.enabled`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `promote_to_db` | `BOOLEAN` | 逐行比较源映射结果与 `factor_registry.promote_to_db`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `promoted` | `BOOLEAN` | 逐行比较源映射结果与 `factor_registry.promoted`；true/false/空按实际契约区分，不以缺失默认成功。 |
| `evidence_score` | `DOUBLE` | 逐行比较源映射结果与 `factor_registry.evidence_score`；分别覆盖正/负/零/空中业务允许的值，单位与容差事前按本列冻结；NaN/null语义明确，不能把空值自动填0。 |
| `category` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.category`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `horizon` | `STRING` | 逐行比较源映射结果与 `factor_registry.horizon`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `lookback_periods` | `INT` | 逐行比较源映射结果与 `factor_registry.lookback_periods`；整数精确相等，验证零/空的区别与合法边界；若为epoch必须注明单位。 |
| `parameters` | `STRING` | 逐行比较源映射结果与 `factor_registry.parameters`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `neutralize_policy` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.neutralize_policy`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `normalize_policy` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.normalize_policy`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `decay_policy` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.decay_policy`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `owner` | `SYMBOL` | 逐行比较源映射结果与 `factor_registry.owner`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |
| `aliases` | `STRING` | 逐行比较源映射结果与 `factor_registry.aliases`；空、空串、中文/特殊字符按本列契约处理，代码前导零及大小写不丢；若内容是JSON/数组，保留结构和元素顺序语义。 |

## 4. 本表回读证据

**前置：上述专项用例若发现旧物理键不足，必须先批准并应用键方案，再更新下方排序/游标；严禁按旧键预先去重后掩盖丢行。**

以下是本表按主时间 `updated_at` 的半开区间回读模板。应用绑定 window_start/window_end/page_size；范围由真实验收样本冻结。若 `updated_at` 是技术占位，须另绑定本表证券/批次/运行身份，不能靠占位时间宣称来源覆盖。仅在隔离目标执行。文件中使用实际参数另存；分批读取直到覆盖全部预期键，单次 LIMIT 不是完整性证明。

```sql
SELECT "updated_at", "run_id", "factor_id", "definition_version", "asset_type", "source_group", "source_table", "source_column", "formula", "factor_family", "frequency", "role", "variant_id", "is_canonical", "validation_profile", "lifecycle_status", "lineage_parent_id", "lineage_parent_version", "change_reason", "direction_prior", "transform", "pit_policy", "enabled", "promote_to_db", "promoted", "evidence_score", "category", "horizon", "lookback_periods", "parameters", "neutralize_policy", "normalize_policy", "decay_policy", "owner", "aliases"
FROM "factor_registry"
WHERE "updated_at" >= :window_start
  AND "updated_at" < :window_end
-- 冻结自然身份后补上稳定排序与游标键
LIMIT :page_size;
```

审计输出：`D127-source-normalized.jsonl`、`D127-actual.jsonl`、`D127-key-diff.json`、`D127-field-diff.json`、`D127-recovery.json`。运行控制/元数据表使用真实运行产物作为来源，不直接插入成功状态充当验收。

## 验收记录

- 标准 review：pending；实现：未在本文认定；实际验证：未执行；用户验收：pending。
- 逐用例填写：实际输入/请求、源证据文件、目标身份、SQL与参数、期望/实际、差异数、run/attempt/slice、PASS/FAIL/BLOCKED。
- 用例实际结果为空不能勾选通过；源码语义/键未冻结的阻塞项解决前不得记 verified。
- [ ] 用户认可本表标准。
- [ ] 所有适用用例与字段矩阵逐项有实际结果。
- [ ] 用户确认验收 accepted。
- Review意见、历史覆盖范围、数值逐字段容差、SLA：待填写。
