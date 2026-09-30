# D087 · l2_intraday_bar_features

- 状态：verified；隔离QuestDB真实验收通过，人工复核保持 `pending_review`。依任务卡要求停止于D087，等待Orca协调器验收后再决定D088。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D086`；前项验收后才执行本项。
- 数据对象：`l2_intraday_bar_features`。
- 业务依赖：D085:l2_dataset_manifest。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `l2_intraday_bar_features` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

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

按股票×日期流式生成分钟特征；确认minute起止边界、交易时区、symbol+minute键及分区，不整归档载入内存。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；完整性验证拒绝缺源键。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `l2_intraday_bar_features` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表有限处理预算、显式失败、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表保留执行前的物理审计快照。字段语义和Java类型已由 `L2IntradayBarFeatureField` 与mapper冻结，并与Parquet样例和正式QuestDB表逐字段核验。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `STRING` | 已核验；完整样例字段值对照通过 |
| `symbol` | `SYMBOL` | 已核验；完整样例字段值对照通过 |
| `market` | `STRING` | 已核验；完整样例字段值对照通过 |
| `board` | `STRING` | 已核验；完整样例字段值对照通过 |
| `minute` | `TIMESTAMP` | 已核验；完整样例字段值对照通过 |
| `open` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `high` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `low` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `close` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `volume` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `amount` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `tick_count` | `LONG` | 已核验；完整样例字段值对照通过 |
| `active_buy_amount` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `active_sell_amount` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vwap` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `has_trade_1m` | `LONG` | 已核验；完整样例字段值对照通过 |
| `bid1` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ask1` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `mid` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `spread` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `microprice` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `bid_depth_1` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ask_depth_1` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `depth_1` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `obi_1` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `bid_depth_5` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ask_depth_5` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `depth_5` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `obi_5` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `bid_depth_10` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ask_depth_10` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `depth_10` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `obi_10` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `quote_count` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ofi_1m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `active_buy_ratio` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `active_sell_ratio` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vwap_gap_to_mid` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vwap_gap_to_open` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vwap_slope_3m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vwap_slope_5m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ret_1m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vol_ratio_1m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `range_1m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ret_3m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vol_ratio_3m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `range_3m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ret_5m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vol_ratio_5m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `range_5m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ret_10m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vol_ratio_10m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `range_10m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ret_15m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vol_ratio_15m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `range_15m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `ret_30m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `vol_ratio_30m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `range_30m` | `DOUBLE` | 已核验；完整样例字段值对照通过 |
| `cancel_ratio` | `DOUBLE` | 已核验；完整样例字段值对照通过 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/scripts/l2/cleanup_migrated_l2_t0_sources.py:37`
- `D:/work/fund_2/back-monitor/src/api/services/terminal/state.py:41`
- `D:/work/fund_2/back-monitor/src/api/services/terminal/intraday_source.py:15`
- `D:/work/fund_2/back-monitor/src/api/services/terminal/intraday_source.py:32`
- `D:/work/fund_2/back-monitor/src/api/services/terminal/execution.py:66`
- `D:/work/fund_2/back-monitor/src/api/services/terminal/execution.py:168`
- `D:/work/fund_2/back-monitor/src/api/services/terminal/execution.py:197`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/composition/level2_pipeline.py`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `l2_intraday_bar_features` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `l2_intraday_bar_features` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证适用的超时、分页完整性、取消和断点恢复；已ACK但未回读一致的写入保持未验证。来源为本地Parquet/manifest，无远程限流；确定性本地错误不自动重试，最多一次执行并明确失败。
- [x] 默认增量，记录checkpoint前后与三日修订窗口；有源数据才写入，没有假造数据。
- [x] 对隔离目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留范围、参数、独立样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑、再次增量和精确resume均有记录。
- [x] 更新[逐项完成表](../completion-register.md)的 `D087` 行及 `results/D087.json`；记录目标、源/提交行、回读、checkpoint、容错证据和人工待办。
- [x] 真实来源和QuestDB验收通过，D087状态为verified；人工复核仍由用户决定。

## 执行验收结果（2026-09-30）

- 源：`D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset`，通过D085逐股票/交易日manifest收据选择完整Parquet来源；样例 `000001.SZ`，2026-09-21至24共1014行，8页，每页上限200，源字节28,105,645。
- 冻结契约：60列，DAY/WAL/DEDUP，主时间 `minute`，完整键 `(symbol, minute UTC instant)`；Parquet上海本地时间显式转UTC，`trade_date`与分钟上海交易日一致。
- 正式表只读预检：物理表158,752,546行；2026-09-21的253行、60列与Parquet逐值相同（15,180/15,180，无差异）。正式表未写入。
- 目标：`java_d087_l2_intraday_bar_features_20260930044414_f3a0942e`；非正式隔离表。BACKFILL 759行，幂等重跑759行，INCREMENTAL提交1014行，RESUME复核并复用1014行；最终1014行全60列匹配独立Parquet捕获（60,840个字段值，0缺失/重复/差异）。
- 写组入口：额外提交并回读2026-09-21的253行；省略一个来源键的prepared数据被发送前拒绝，目标保持不变。
- 容错：源取消、过期指纹、缺失来源键均被拒绝；状态、计划、job和slice管理命令通过。账本提交计数见 `artifacts/java-migration/D087/commands/ledger-summary-20260930044414_f3a0942e.json`。
- 失败现场：三次早期验收尝试的隔离表均保留；一张0行（发现并修复空表`max(minute)`响应处理），两张1014行（同步通过，测试注册表最初漏D085/D001依赖）。各表计数和原因见 `artifacts/java-migration/D087/commands/live-target-census-20260930.json`。没有触碰正式表。
- 测试：4项映射测试和1项本地QuestDB live验收通过。使用JDK 24；为编译D087临时排除了工作区中与D087无关且未完成的未跟踪 `IndexDailyMarketReadRepository.java` 与 `IndexDailyBasic*.java`，之后删除临时排除配置并保留原有 `build.gradle` 改动。默认全工作区编译仍会被这些既有未跟踪源文件阻塞。
- 详细结果：[results/D087.json](../results/D087.json)；独立来源、live、账本及schema证据见 `artifacts/java-migration/D087/`。人工复核状态：`pending_review`。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D087：l2_intraday_bar_features。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/10-l2/D087-l2_intraday_bar_features.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D086 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D087.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```

