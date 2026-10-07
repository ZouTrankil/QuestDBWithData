# D103 · equity_style_monthly

- 状态：verified（2026-10-06，隔离有界验收）；协调器 accepted_for_serial_progress，按序准许 D104；人工 pending_review。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D102`；前项验收后才执行本项。
- 数据对象：`equity_style_monthly`。
- 业务依赖：D022:index_monthly。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `equity_style_monthly` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`OWNER_INGEST_OR_DERIVED_TO_VERIFY`。
- 物理主时间列：`month`；物理分区：`YEAR`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`month`。
- Python声明键：`month`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`index_monthly`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `equity_style_monthly` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `month` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `hs300_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `zz500_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `all_a_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cs1000_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `small_large_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mid_large_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `growth_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `value_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `growth_value_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `energy_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `materials_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `industrials_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `consumer_discretionary_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `consumer_staples_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `healthcare_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `financials_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `it_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `telecom_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `utilities_ret_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `energy_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `materials_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `industrials_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `consumer_discretionary_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `consumer_staples_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `healthcare_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `financials_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `it_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `telecom_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `utilities_vs_all_a_1m` | `DOUBLE` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/index/equity_style_monthly.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/workflows.py:45`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/workflows.py:63`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/regime/features_monthly.py:16`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/market/__init__.py:3`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/market/equity_style_monthly.py:3`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/registry.py:75`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/registry.py:103`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `equity_style_monthly` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `equity_style_monthly` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D103` 行及 `results/D103.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D103：equity_style_monthly。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D103-equity_style_monthly.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D102 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D103.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```

## 实际验收完成登记（2026-10-06）

状态：verified（隔离有界验收），协调器 accepted_for_serial_progress；人工 pending_review。按序准许 D104。

- 30 列 typed domain/key/mapper/read/write、ReadGroup/WriteGroup、canonical job 和管理入口完成。
- 真实 D022 来源：2026-06 至 2026-08，48 行、672 字段值、432 个 DOUBLE 位值。
- 首次两月、同范围重跑、取消恢复、只读 reconcile、typed WriteGroup 的原实际操作，由冻结账本及 fresh Java 只读恢复逐字段核实；恢复没有 DDL/DML 或账本改写。
- 实际增量 run `d103-9a1915a5-31f0-4e0c-9412-ae4fc64c366b`：重叠七月并新增八月，VERIFIED 2 行；最终三月 90 字段、87 个 DOUBLE 位值一致，容差 0。
- 最终账本 9 runs / 21 entries / 85 events / 1 group / 0 leases；125 个唯一 Java 方法和 122 个 Python 保护检查通过。
- 正式库只读；六月/七月既存输出缺口保留。没有 FULL、正式修复、生产切换或真实旧期来源修订声明。
- 原 source INSERT 的 UNKNOWN ACK 与原 Java initial 最终 YearMonth 序列化失败均保留；没有重发初始写入或将原失败改为成功。
- 来源 pct_chg 不换算、不舍入；采用 stored-units 补充契约，保留原增长/价值代码绑定。

[最新协调准入](../../../artifacts/java-migration/D103/coordinator-review-20261006.json) · [任务结果](../results/D103.json)

`README.md`、初始 mapping-contract 和增量 mapping-contract 均为此前冻结截止点，其 gate pending 描述不是当前状态；以本完成登记与最新协调准入为准。

完整证据：[结果](../results/D103.json)、[完成登记](../../../artifacts/java-migration/D103/completion-summary-20261006.md)、[协调准入](../../../artifacts/java-migration/D103/coordinator-review-20261006.json)。
