# D085 · l2_dataset_manifest

- 状态：verified；隔离来源与QuestDB验收完成，人工复核 pending_review。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 执行顺序：本范围由用户明确指定从 `D085` 开始；覆盖本卡原有的 `D084` 跨范围顺序前置。D084及D007–D084的既有状态不变。
- 数据对象：`l2_dataset_manifest`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `l2_dataset_manifest` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`other_domain_or_unclassified`；来源类别：`LEVEL2`。
- 物理主时间列：`trade_date_ts`；物理分区：`DAY`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`symbol,batch_id,trade_date_ts`。
- Python声明键：`未声明/未匹配`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

源文件/归档按交易日×股票×batch流式读取，trade_date与trade_date_ts一源派生；记录hash、parser/feature版本、路径和质量状态。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `l2_dataset_manifest` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `STRING` | 待逐字段映射与语义核验 |
| `symbol` | `SYMBOL` | 待逐字段映射与语义核验 |
| `market` | `STRING` | 待逐字段映射与语义核验 |
| `board` | `STRING` | 待逐字段映射与语义核验 |
| `source_root` | `STRING` | 待逐字段映射与语义核验 |
| `output_root` | `STRING` | 待逐字段映射与语义核验 |
| `feature_version` | `STRING` | 待逐字段映射与语义核验 |
| `daily_feature_ok` | `BOOLEAN` | 待逐字段映射与语义核验 |
| `t0_ok` | `BOOLEAN` | 待逐字段映射与语义核验 |
| `raw_row_counts` | `STRING` | 待逐字段映射与语义核验 |
| `output_paths` | `STRING` | 待逐字段映射与语义核验 |
| `cost_config` | `STRING` | 待逐字段映射与语义核验 |
| `horizons_min` | `STRING` | 待逐字段映射与语义核验 |
| `errors` | `STRING` | 待逐字段映射与语义核验 |
| `batch_id` | `LONG` | 待逐字段映射与语义核验 |
| `trade_date_ts` | `TIMESTAMP` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/scripts/l2/cleanup_migrated_l2_t0_sources.py:278`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:91`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:108`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:185`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:320`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/level2_batch_processor.py:104`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/stock_l2_t0_observed_snapshots.py:35`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/composition/level2_pipeline.py`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `l2_dataset_manifest` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `l2_dataset_manifest` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 已核实真实来源、权限、schema、键、参数和本地QuestDB连接；使用独立隔离表，未写正式表。
- [x] 已核查有限文件读取与超时边界、页与源完整性、取消、三日重叠checkpoint，以及写后全字段回读。
- [x] 首次非空回填、同范围幂等重跑、再次增量均有记录：3/3、3/3、4/4源行与回读均VERIFIED。
- [x] 在隔离QuestDB对实际目标按完整键查询；4条源记录与16个物理列逐字段匹配。
- [x] 真实来源收据、请求范围、隔离目标、checkpoint和验证结果已保存于证据目录。
- [x] 已更新[逐项完成表](../completion-register.md)的 `D085` 行及 `results/D085.json`；人工比对保持pending_review。
- [x] D085映射、live acceptance、取消边界、VerifiedBatchExecutor及SyncJobRunnerBoundary定向测试通过。

## 实施结果（2026-09-29/30）

- 来源：`D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset`。扫描2026-09-21至24，选出 `000001.SZ` 的4条源记录；四日期、1,589个文件、31,731源行、21,945,806字节，源完整性标记为真。来源收据位于 `artifacts/java-migration/D085/source-manifest-20260921-24.jsonl`。
- 隔离表：`java_d085_l2_dataset_manifest_acceptance_20260930`，生产表未改动。回填窗口2026-09-21至23运行两次均3/3 VERIFIED；随后增量窗口覆盖09-21至24，使用3日重叠并将checkpoint从09-23推进至09-24，4/4 VERIFIED。重复回填验证了相同范围重跑。
- 三个运行ID、计划、状态、typed read请求与所有字段比对见 `docs/migration-tasks-20260929/results/D085.json` 及 `artifacts/java-migration/D085/commands/`。
- 定向测试通过。因D011/D012仍有工作区修改造成的全工程编译冲突，测试在隔离副本中执行；仅在副本内为无关旧调用加入编译兼容改写，D085源文件与测试文件取自本工作区，副本改写未拷回。该限制已记入结果文件。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行 D085：l2_dataset_manifest。Python参考项目为 D:/work/fund_2/back-monitor；用户指定此范围从D085开始，覆盖D084跨范围前置。使用明确目标的隔离QuestDB和有界真实来源样例，默认增量sync，有效数据才写入，按完整键逐字段回读。实现有限窗口、页、取消和未知写入核验；更新任务卡、completion-register.md与results/D085.json并保留人工复核pending_review。完成后按同一范围目录顺序继续D086。
```
