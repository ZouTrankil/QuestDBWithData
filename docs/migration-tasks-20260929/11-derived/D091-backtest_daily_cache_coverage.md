# D091 · backtest_daily_cache_coverage

- 状态：verified（`retained_compatibility`）；D090协调器验收门槛已通过；Python owner、Java只读模型、实表及历史回执完整性已验收。人工复核仍为 `pending_review`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D090`；前项验收后才执行本项。
- 数据对象：`backtest_daily_cache_coverage`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `backtest_daily_cache_coverage` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`OWNER_INGEST_OR_DERIVED_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`MONTH`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`trade_date,source_version`。
- Python声明键：`trade_date,source_version`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

本表不是独立来源或可任意重算的同步目标。Python `BacktestReadThroughCache._publish` 先写 `backtest_daily_cache`，等WAL和内容摘要可见后才发布 coverage 回执；`_cached` 以完整 `(trade_date, source_version)` 找回执，校验行数、唯一证券键和内容摘要。Java只提供READ能力及有界读取，不发布另一份完整性声明。

2026-09-30本机只读验收：coverage 实表为MONTH/WAL/DEDUP，4列类型和完整去重键与Python模型一致；最新存储回执日期 `2026-09-21`，该版本5,565行，5,565个唯一证券键，Python原版摘要复算一致。coverage表1,367行/table_txn=4、cache表7,531,793行/table_txn=48，前后未变化。当前上游指纹与这条已存回执不同，因此当前版本 `_cached` 返回cache miss；仅证明已存历史版本完整，不声明当前版本已有热缓存，也未触发可能写入的重建。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：4列只读业务record和逐字段mapper，日期为交易所business date、来源/摘要为64位小写SHA-256、行数为正LONG。
- [x] D02：业务Key和物理DEDUP键均为 `(trade_date, source_version)`；同键修订由Python发布者负责，Java不写。
- [x] D03：现场确认 `trade_date` designated TIMESTAMP、MONTH/WAL/DEDUP及完整UPSERT KEY；无DDL变更。
- [x] D04：按完整Key、日期、范围的有界typed read和稳定cursor分页；ReadGroup强类型绑定通过实库验收。
- [x] D05：N/A；此回执是Python在cache内容核验后发布的完整性断言，Java直写会伪造或竞争该断言；DatasetDefinition仅READ。
- [x] D06：N/A；来源与发布者为Python `BacktestReadThroughCache`，没有独立Java source/sync；真实调用链已复核。
- [x] D07：注册READ-only DatasetDefinition，不创建无来源所有权的Java SyncJobDefinition；此表按回执读取使用。
- [x] D08：Java共享有界读取器负责页长、超时及cursor约束；Python owner 的唯一键、行数、摘要、WAL可见性在实库只读路径得到核验。写入重试/断点/取消对本Java只读裁决不适用。
- [x] D09：本机QuestDB实表、5,565行cache切片与Python原版摘要复算通过；只读前后行数和table_txn不变。结果见 `../results/D091.json`。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | `LocalDate`；交易所business date，UTC零点仅为存储载体；非空、键 |
| `source_version` | `SYMBOL` | `String`；视图定义和来源表分区状态的SHA-256；非空、键 |
| `row_count` | `LONG` | `long`；已验证cache行数，必须大于0 |
| `content_digest` | `STRING` | `String`；排序后pandas内容哈希字节的SHA-256；64位小写十六进制 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/backtest_cache.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/backtest_cache.py:96`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/backtest_cache.py:34`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `backtest_daily_cache_coverage` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `backtest_daily_cache_coverage` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 已核实Python owner、QuestDB连接、实表schema、键和实际回执日期。
- [x] 适用的Java有界读、分页与超时约束及Python owner完整性检查已核验；Java没有写入或网络取源。
- [x] checkpoint、增量窗口、幂等写入对只读回执不适用；写入0行，未触发Python cache重建。
- [x] QuestDB按完整 `(trade_date, source_version)` SELECT回读5,565行对应切片，键唯一、行数和13字段内容摘要匹配。
- [x] 非空写入验收对 `retained_compatibility` 不适用；核验真实已发布回执及相关cache切片。
- [x] 已更新完成表和 `results/D091.json`，保留原始Java/Python/QuestDB证据与当前版本cache miss事实。
- [x] 状态为verified（只读兼容与已存回执完整性）；人工复核保持 `pending_review`。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D091：backtest_daily_cache_coverage。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D091-backtest_daily_cache_coverage.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D090 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D091.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
