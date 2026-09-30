# D094 · market_barometer_cache_coverage

- 状态：verified（`retained_compatibility`）；D093协调器验收门槛已通过，Python多dataset发布者、零行回执语义、Java只读模型及本机QuestDB三产品回执已验收。人工复核 `pending_review`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D093`；前项验收后才执行本项。
- 数据对象：`market_barometer_cache_coverage`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `market_barometer_cache_coverage` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`OWNER_INGEST_OR_DERIVED_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`MONTH`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`trade_date,dataset_id,source_version`。
- Python声明键：`trade_date,dataset_id,source_version`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

本表是Python `MarketBarometerReadThroughCache` 对 `market_breadth_daily`、`etf_market_overview_daily`、`retail_sentiment_daily` 三种产品的共享完整性回执。发布者先写对应cache表，等WAL可见且原版摘要一致后发布coverage。每日期/版本缓存为0或1条；零行日期仍可发布 `row_count=0` 与空内容摘要。Java只读回执，不成为第二个publisher。

2026-09-30本机QuestDB：coverage实表MONTH/WAL/DEDUP，5列，完整UPSERT KEY `(trade_date,dataset_id,source_version)`；2,466行/table_txn=13。三个产品最新已存非空回执分别为市场宽度2026-09-18、ETF概览2026-09-18、零售情绪2026-09-17，每个row_count=1，实际对应cache各1条且Python原版 `_record_digest` 与回执摘要一致。Java typed repository按完整键读出三条，ReadGroup一致；coverage及三张cache行数/table_txn前后不变。本次仅验证已存版本，不声明它们就是当前来源指纹；未触发可能写入的cache miss重建。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：5列只读业务record和逐字段mapper，`row_count` 允许0或1、SHA-256摘要显式校验。
- [x] D02：完整业务Key和物理DEDUP键均为 `(trade_date,dataset_id,source_version)`；同版本内容校验与修订由Python publisher负责。
- [x] D03：现场确认 `trade_date` designated TIMESTAMP、MONTH/WAL/DEDUP及完整UPSERT KEY；无DDL变更。
- [x] D04：按完整Key、日期和范围的有界typed read，稳定cursor分页；ReadGroup三产品强类型绑定通过。
- [x] D05：N/A；coverage是Python在cache内容核验后发布的断言，Java直写会伪造或竞争；DatasetDefinition仅READ。
- [x] D06：N/A；Python `MarketBarometerReadThroughCache` 是三产品唯一readthrough/materialize owner；无独立Java source/sync。
- [x] D07：注册READ-only DatasetDefinition，不创建无来源所有权的Java SyncJobDefinition。
- [x] D08：共享有界reader负责页长、超时和cursor；Python publisher的WAL可见性及摘要保护按真实已存回执核验。网络限流、写入重试/断点/取消对Java只读裁决不适用。
- [x] D09：本机QuestDB三产品各一条真实非空回执及cache记录、Python原版摘要全匹配；Java typed read/ReadGroup通过，四表行数及txn不变。结果见 `../results/D094.json`。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | `LocalDate`；交易所business date，UTC零点仅为存储载体；键 |
| `dataset_id` | `SYMBOL` | `String`；三产品之一；键 |
| `source_version` | `SYMBOL` | `String`；来源与视图指纹SHA-256；键 |
| `row_count` | `LONG` | `long`；每日版本化cache记录数，允许0或1 |
| `content_digest` | `STRING` | `String`；Python规范化记录或空内容的SHA-256 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/market_barometer_cache.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/market_barometer_cache.py:310`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/market_barometer_cache.py:122`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `market_barometer_cache_coverage` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `market_barometer_cache_coverage` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] Python发布者、三产品规格、QuestDB连接、实表schema/键和真实最新已存回执已核实。
- [x] Java有界读和Python publisher摘要/WAL保护已核验；Java没有网络取源或写入。
- [x] checkpoint、增量窗口、幂等写入对只读回执不适用；本次写入0行。
- [x] QuestDB按三个完整版本化键SELECT，对应cache记录与Python原版摘要逐项相等。
- [x] 首次非空Java写入验收对 `retained_compatibility` 不适用；以三条真实非空已发布回执验收。
- [x] 已更新完成表和 `results/D094.json`，保留Python/Java/QuestDB证据。
- [x] 状态为verified（只读兼容与已存回执完整性），人工复核 `pending_review`。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D094：market_barometer_cache_coverage。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D094-market_barometer_cache_coverage.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D093 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D094.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
