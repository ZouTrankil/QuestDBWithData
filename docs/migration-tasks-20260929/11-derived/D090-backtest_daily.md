# D090 · backtest_daily

- 状态：verified（`retained_compatibility`）；D089协调器验收门槛已通过；已完成Python owner复核、Java typed read定义与本机QuestDB只读验收。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D089`；前项验收后才执行本项。
- 数据对象：`backtest_daily`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `backtest_daily` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-30现场复核）

- 分类：`data_model`；来源类别：`OWNER_INGEST_OR_DERIVED_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`DAY`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`trade_date,ts_code`。
- Python声明键：`trade_date,ts_code`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；Python registry 当前登记为 `backtest_daily` readthrough product，`owner_job=None`，`source_version_on_read`，输出 `backtest_daily_cache`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。
- 现场QuestDB：正式表 `backtest_daily` 为DAY/WAL/DEDUP、13列、行数10,355,884，范围2010-01-29至2026-09-17；D090 Java实读前后行数及table_txn均为10,355,884/17。
- Python实际读链：`dataset_snapshots.py` 将backtest数据集查询改写到按source_version筛选的 `backtest_daily_cache`；缓存缺失时由 `BacktestReadThroughCache` 从 `v_backtest_daily` 生成并验证。质量规则已禁用旧表检查。旧 `run_backtest_daily()` CLI仍可手动调用，含全量DROP重建路径，不能注册为Java定时/同步owner。
- D090裁决：保留旧物理表作只读兼容及对照，不退役、不重写、不再新增Java计算责任。Java注册的 `backtest_daily` 明确READ-only；生产研究读through/cache仍由Python当前路径拥有。

## 本数据sync模式与注意事项

Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：完成13列只读业务模型、schema definition及逐字段mapper；无外部DTO，因为本裁决不接管Python物化来源。
- [x] D02：完整业务键和旧表物理DEDUP键均为 `(trade_date, ts_code)`；同键纠正只属于旧Python writer，Java不写。
- [x] D03：保留并现场核对 `trade_date`、DAY、WAL、DEDUP及原UPSERT KEY；无DDL变更。Java capability为READ-only。
- [x] D04：实现按完整Key、日期、范围的bounded typed read，稳定keyset分页，并注册ReadGroup强类型绑定。
- [x] D05：N/A并拒绝直写：当前主读链写入的是Python按source_version隔离的cache；不能把兼容表再设Java writer。定义不包含WRITE，能力校验拒绝WRITE。
- [x] D06：N/A：来源owner仍在Python readthrough；D090不启动手动旧materializer，也不虚构外部API/Java同步源。
- [x] D07：注册唯一 `backtest_daily` READ-only DatasetDefinition；不创建SyncJobDefinition，因为没有迁移的Java sync/materialize owner。
- [x] D08：适用读取预算为共享bounded reader显式投影、20秒SQL超时、最多10,000行/页、完整键唯一性与cursor fingerprint校验。网络限流/重试/checkpoint/未知写入N/A（本项无Java来源请求/写入）。
- [x] D09：本机正式QuestDB表及当前view同日200条、13列逐值相等；typed read和ReadGroup读取通过；表行数与事务号未变化。无隔离写入是本裁决要求。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表为现场复核后的物理列到Java只读业务类型映射。标准名称与物理名称由显式mapper连接。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | `LocalDate`；交易所business date，UTC零点仅为存储载体；非空、键 |
| `ts_code` | `SYMBOL` | `String`；六位证券代码+SH/SZ/BJ；非空、键 |
| `open` | `DOUBLE` | nullable `Double`；未复权CNY/股；停牌补行使用前收盘 |
| `high` | `DOUBLE` | nullable `Double`；未复权CNY/股；停牌补行使用前收盘 |
| `low` | `DOUBLE` | nullable `Double`；未复权CNY/股；停牌补行使用前收盘 |
| `close` | `DOUBLE` | nullable `Double`；未复权CNY/股；停牌补行使用前收盘 |
| `vol` | `DOUBLE` | nullable `Double`；Tushare手；停牌补行写0 |
| `amount` | `DOUBLE` | nullable `Double`；Tushare千元；停牌补行写0 |
| `adj_factor` | `DOUBLE` | nullable `Double`；无量纲复权因子 |
| `up_limit` | `DOUBLE` | nullable `Double`；CNY/股，来自 `stk_limit` |
| `down_limit` | `DOUBLE` | nullable `Double`；CNY/股，来自 `stk_limit` |
| `is_suspended` | `INT` | nullable `Integer`；停牌指示；当前view正常行coalesce为0（view实际类型为LONG，映射时精确转回INT） |
| `is_st` | `INT` | nullable `Integer`；ST指示；当前view缺失值coalesce为0 |

## D090兼容裁决与验收记录

- 旧物化器及 `backtest_daily` 物理表仍在，`run_backtest_daily()`可手动调用；它不是registry当前owner。旧全量分支会DROP TABLE，故不执行。
- Python `BacktestReadThroughCache` 以四张来源表的分区版本计算缓存身份；miss从 `v_backtest_daily`构建，并校验源版本、行数、唯一键、内容digest及WAL可见性，再写coverage。Python `dataset_snapshots.py` 将研究读取路由到匹配source_version的cache。
- Java只提供审计/兼容读取，不映射cache source_version，也不替代Python版本校验。没有新增D090 SyncJobDefinition、写组合或数据库对象。
- 2026-09-30本机QuestDB只读验收：`2026-09-17`正式表首200条与 `v_backtest_daily` 同键有序首200条全13字段相等（2,600个字段值）；Java typed `BacktestDailyReadRepository` 与typed ReadGroup结果一致。正式表行数10,355,884、table_txn=17，读取前后不变。证据：`artifacts/java-migration/D090/commands/java-live-read-20260930.json`、`live-compatibility-20260930.json`。
- `javac --release 24` D090依赖切片编译通过；映射4/4、本机QuestDB只读验收1/1通过。测试日志：`artifacts/java-migration/D090/commands/focused-tests-20260930.log`。未执行整仓Gradle回归；既有工作区Moneyflow/D024/D026未完成源阻断全量编译的事实延续D089记录。
- 缓存命中/缺失时Python owner会验证并可能写cache；本任务的Java验收只读查询正式表和view，没有调用该可能写入的owner，也没有改正式表或cache。

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/backtest_daily.py`
- `D:/work/fund_2/back-monitor/config/yaml/research/factor_platform_v2.yaml:202`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:278`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/factors/cross_sectional/l2_daily/base.py:133`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/factor_source_discovery.py:213`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/naozong_industry_chain_inputs.py:41`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/market/__init__.py:5`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/derived/market/backtest_daily.py:1`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `backtest_daily` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `backtest_daily` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 已核实Python owner/view/cache、QuestDB正式表schema/键、READ权限和连接；无来源请求或DDL。
- [x] 已核对适用读取边界：显式投影、20秒SQL超时、最多10,000行/页、完整键唯一性与cursor fingerprint。来源重试、sync取消/恢复和未知写入N/A（没有D090 Java sync/writer）。
- [x] 增量checkpoint及修订窗口N/A；Java不拉取/物化，不推进source checkpoint。
- [x] 已对正式物理表和当前view执行有界SELECT，按 `(trade_date, ts_code)` 对齐并比较全部13列；保存SQL、参数及样例。
- [x] 写入、幂等回灌和增量写入N/A；D090裁决为只读兼容，验收期间正式表/cache无写入，正式表行数及table_txn不变。
- [x] 已更新[逐项完成表](../completion-register.md)的 `D090` 行及 `results/D090.json`，记录读行/写入0、回读、运行时间、证据和人工复核状态。
- [x] Java切片编译及映射/本机QuestDB验收通过，故本项登记verified；人工复核仍为 `pending_review`。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D090：backtest_daily。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D090-backtest_daily.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D089 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D090.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
