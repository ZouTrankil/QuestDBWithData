# D089 · l2_t0_training_labels

- 状态：verified；串行前置D088协调器验收门槛已通过；本卡真实来源与隔离QuestDB验收通过，人工复核保持 `pending_review`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D088`；前项验收后才执行本项。
- 数据对象：`l2_t0_training_labels`。
- 业务依赖：D087:l2_intraday_bar_features。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `l2_t0_training_labels` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`other_domain_or_unclassified`；来源类别：`LEVEL2`。
- 物理主时间列：`minute`；物理分区：`DAY`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`symbol,minute`。
- Python声明键：`未声明/未匹配`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

标签依赖未来窗口，确认minute锚点和maturity；不能将未成熟标签计入完成；与特征分别验收。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `l2_t0_training_labels` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `STRING` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `symbol` | `SYMBOL` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `market` | `STRING` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `board` | `STRING` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `minute` | `TIMESTAMP` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `executability_label` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `executability_reason` | `STRING` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `policy_label` | `STRING` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `policy_reason` | `STRING` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `primary_t0_side` | `STRING` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `roundtrip_cost_rate` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `stamp_tax_rate` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `commission_rate` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `slippage_bps` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_vwap_return_1m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_mid_return_1m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_gross_alpha_1m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_net_alpha_1m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_opportunity_label_1m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_gross_alpha_1m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_net_alpha_1m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_opportunity_label_1m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_vwap_return_3m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_mid_return_3m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_gross_alpha_3m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_net_alpha_3m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_opportunity_label_3m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_gross_alpha_3m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_net_alpha_3m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_opportunity_label_3m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_vwap_return_5m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_mid_return_5m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_gross_alpha_5m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_net_alpha_5m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_opportunity_label_5m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_gross_alpha_5m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_net_alpha_5m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_opportunity_label_5m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_vwap_return_10m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_mid_return_10m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_gross_alpha_10m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_net_alpha_10m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_opportunity_label_10m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_gross_alpha_10m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_net_alpha_10m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_opportunity_label_10m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_vwap_return_15m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_mid_return_15m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_gross_alpha_15m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_net_alpha_15m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_opportunity_label_15m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_gross_alpha_15m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_net_alpha_15m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_opportunity_label_15m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_vwap_return_30m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `future_mid_return_30m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_gross_alpha_30m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_net_alpha_30m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `sell_first_opportunity_label_30m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_gross_alpha_30m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_net_alpha_30m` | `DOUBLE` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |
| `buy_first_aux_opportunity_label_30m` | `LONG` | 已按字段enum/DTO、真实Parquet及QuestDB schema核验 |

## 实施结果与语义核验

- Java实现：62列enum/DTO/domain/mapper、`(symbol, minute)`业务键、DAY/WAL/DEDUP写口、键集分页读取、D085回执认证Parquet来源、任务owner、Dataset注册、读取组合、写入组合及CLI计划/运行/状态/取消入口。
- 来源：`level2_batch_processor` 通过 `build_t0_training_labels` 写出 `training_labels`；读取器按D085 manifest逐symbol/date回执定位Parquet part并核对哈希。范围 `2026-09-21..24`，symbol `000001.SZ`，扫描31,731条manifest记录、1,593个文件/25,631,460字节，返回1,014行、8页；来源指纹 `a424a04e…33febfc`，schema指纹 `94bacb04…e0333a7`。
- 时间和键：来源minute是Asia/Shanghai无时区本地分钟，Java存等价UTC整分钟；trade_date按上海时区校验。业务键与QuestDB UPSERT键均为 `(symbol, minute)`。
- 前视语义：未来1/3/5/10/15/30分钟字段是label outcome，不可当作时点特征；`future_mid_return_*` 保留来源列名，实际公式是 `future VWAP / entry mid - 1`。round-trip成本为 `2*commission + stamp_tax + slippage_bps/10000`；opportunity要求gross alpha严格大于该成本。物理表没有maturity列；gross alpha缺失而label为0代表结果缺失，不能解释为负收益。

| 窗口 | 样本 | VWAP / mid非空 | 卖/买alpha非空 | 缺失alpha却label为0（卖/买） |
| --- | ---: | ---: | ---: | ---: |
| 1m | 1,014 | 940 / 940 | 944 / 944 | 70 / 70 |
| 3m | 1,014 | 928 / 928 | 932 / 932 | 82 / 82 |
| 5m | 1,014 | 916 / 916 | 920 / 920 | 94 / 94 |
| 10m | 1,014 | 876 / 876 | 880 / 880 | 134 / 134 |
| 15m | 1,014 | 836 / 836 | 840 / 840 | 174 / 174 |
| 30m | 1,014 | 716 / 716 | 720 / 720 | 294 / 294 |

- QuestDB：隔离表 `java_d089_l2_t0_training_labels_804a7fe8` 实际回读1,014行；目标62列与正式表逐列类型一致，minute为designated timestamp，UPSERT键为symbol/minute，DAY分区、WAL、DEDUP均开启。最小/最大minute为 `2026-09-21T01:15:00Z` / `2026-09-24T07:00:00Z`。正式表 `l2_t0_training_labels` 前后均181,049,686行，未修改。
- 实际运行：BACKFILL 9/21–23写入并核验759行；相同回填重跑759行、幂等；INCREMENTAL按三日修订重叠回放至9/24，核验1,014行；精确resume再次回读并复用1,014行。全部运行状态 `VERIFIED`。独立Parquet规范化样本与QuestDB全键全列比较62,868个值，差异/重复键/缺键均为0；取消和过期source fingerprint均按预期拒绝。
- 验证：D089定向Java依赖切片按 `javac --release 24` 编译；3项mapper/domain测试通过；真实QuestDB live acceptance 1项通过。全工作区Gradle `compileJava` 仍被现有不完整Moneyflow/D024/D026源类型阻断，与D089无关。D089主任务owner及SyncJobRegistry运行状态/分片可见性已实测；写入组合的D089认证分支已接入并静态复核，未单独运行组合端到端验收。
- 证据：`results/D089.json`、`artifacts/java-migration/D089/live-acceptance-20260930.json`、`artifacts/java-migration/D089/commands/target-census-20260930.json`、`artifacts/java-migration/D089/commands/source-parquet-20260921-24.jsonl`。

## 只读参考入口

- `D:/work/fund_2/back-monitor/scripts/l2/cleanup_migrated_l2_t0_sources.py:38`
- `D:/work/fund_2/back-monitor/scripts/l2/cleanup_migrated_l2_t0_sources.py:80`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:78`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/level2_batch_processor.py:99`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/level2/l2_chip_phase_research.py:461`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/level2/l2_chip_phase_action_research.py:744`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/local_files/l2_t0_snapshots.py:29`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/composition/level2_pipeline.py`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `l2_t0_training_labels` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `l2_t0_training_labels` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D089` 行及 `results/D089.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D089：l2_t0_training_labels。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/10-l2/D089-l2_t0_training_labels.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D088 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D089.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
