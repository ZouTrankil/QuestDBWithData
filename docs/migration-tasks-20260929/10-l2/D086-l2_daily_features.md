# D086 · l2_daily_features

- 状态：verified；本地QuestDB隔离验收通过；人工复核 `pending_review`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D085`，已完成隔离验收并登记于 `results/D085.json`。
- 数据对象：`l2_daily_features`。
- 业务依赖：D085:l2_dataset_manifest。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `l2_daily_features` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`LEVEL2`。
- 物理主时间列：`ts`；物理分区：`DAY`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts,symbol`。
- Python声明键：`ts,symbol`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`dfcf_level2_archive`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

对照level2_pipeline.py和各feature builder复刻日特征，固定源样例逐字段对齐；ts映射trade_date，保留symbol/版本及lineage；不借改名重放未核验WAL。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `l2_daily_features` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `symbol` | `SYMBOL` | 待逐字段映射与语义核验 |
| `has_deal` | `BOOLEAN` | 待逐字段映射与语义核验 |
| `has_order` | `BOOLEAN` | 待逐字段映射与语义核验 |
| `has_snapshot` | `BOOLEAN` | 待逐字段映射与语义核验 |
| `deal_records` | `LONG` | 待逐字段映射与语义核验 |
| `order_records` | `LONG` | 待逐字段映射与语义核验 |
| `snapshot_records` | `LONG` | 待逐字段映射与语义核验 |
| `continuous_auction_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `crossed_quote_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `locked_quote_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `missing_top5_quote_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `feature_version` | `STRING` | 待逐字段映射与语义核验 |
| `parser_version` | `STRING` | 待逐字段映射与语义核验 |
| `order_link_coverage` | `DOUBLE` | 待逐字段映射与语义核验 |
| `deal_order_match_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `nonnegative_depth_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `valid_spread_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `orphan_execution_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cancel_without_fill_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `partial_fill_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `median_cancel_time_ms` | `DOUBLE` | 待逐字段映射与语义核验 |
| `median_first_fill_time_ms` | `DOUBLE` | 待逐字段映射与语义核验 |
| `passive_fill_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `large_order_fill_rate` | `DOUBLE` | 待逐字段映射与语义核验 |
| `partial_cancel_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `replace_like_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `open_30m_spread_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `close_30m_obi_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `midday_liquidity_drop` | `DOUBLE` | 待逐字段映射与语义核验 |
| `afternoon_ofi_reversal` | `DOUBLE` | 待逐字段映射与语义核验 |
| `open_close_vol_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `close_30m_impact_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `aggressor_label_match_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mid_cross_match_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `tick_rule_fallback_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `unclassified_trade_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depth_recovery_5s` | `DOUBLE` | 待逐字段映射与语义核验 |
| `best_quote_depletion_rate` | `DOUBLE` | 待逐字段映射与语义核验 |
| `add_cancel_execute_ratio_top1` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depth_turnover_top5` | `DOUBLE` | 待逐字段映射与语义核验 |
| `book_pressure_decay` | `DOUBLE` | 待逐字段映射与语义核验 |
| `queue_depletion_speed` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mean_rel_aggro` | `DOUBLE` | 待逐字段映射与语义核验 |
| `median_inter_arrival_ms` | `DOUBLE` | 待逐字段映射与语义核验 |
| `algo_windows` | `LONG` | 待逐字段映射与语义核验 |
| `total_windows` | `LONG` | 待逐字段映射与语义核验 |
| `mean_algo_entropy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mean_retail_entropy` | `DOUBLE` | 待逐字段映射与语义核验 |
| `algo_total_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `retail_total_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `wash_trade_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `wash_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `clean_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `spoof_count` | `LONG` | 待逐字段映射与语义核验 |
| `fake_pressure_count` | `LONG` | 待逐字段映射与语义核验 |
| `fake_support_count` | `LONG` | 待逐字段映射与语义核验 |
| `mean_sell_otr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mean_buy_otr` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mean_cancel_distance_ticks` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_net_flow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q2_accumulation` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mean_vwap_skew` | `DOUBLE` | 待逐字段映射与语义核验 |
| `q1_count` | `LONG` | 待逐字段映射与语义核验 |
| `q2_count` | `LONG` | 待逐字段映射与语义核验 |
| `q3_count` | `LONG` | 待逐字段映射与语义核验 |
| `q4_count` | `LONG` | 待逐字段映射与语义核验 |
| `main_net_inflow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `retail_funds_net_inflow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `main_funds_buy_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `main_funds_sell_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mid_tier_net_inflow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `trade_size_gini` | `DOUBLE` | 待逐字段映射与语义核验 |
| `open_auction_net_inflow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `close_auction_net_inflow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mean_spread` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mean_obi_top5` | `DOUBLE` | 待逐字段映射与语义核验 |
| `amihud_illiquidity` | `DOUBLE` | 待逐字段映射与语义核验 |
| `obi_top1_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `obi_top5_std` | `DOUBLE` | 待逐字段映射与语义核验 |
| `obi_top1_std` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depth_top1_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depth_top5_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depth_slope` | `DOUBLE` | 待逐字段映射与语义核验 |
| `depth_convexity` | `DOUBLE` | 待逐字段映射与语义核验 |
| `microprice_minus_mid_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `microprice_minus_mid_std` | `DOUBLE` | 待逐字段映射与语义核验 |
| `effective_spread_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `realized_spread_1m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `impact_30s` | `DOUBLE` | 待逐字段映射与语义核验 |
| `impact_5m` | `DOUBLE` | 待逐字段映射与语义核验 |
| `orderbook_replenish_speed` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ofi_mean` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ofi_std` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ofi_persistence` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ofi_positive_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ofi_negative_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `near_touch_cancel_add_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `gmm_main_force_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `gmm_hft_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `gmm_retail_ratio` | `DOUBLE` | 待逐字段映射与语义核验 |
| `gmm_main_force_net_inflow` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mfi_pulse` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mfi_escort` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mfi_precip` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mfi_score` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ofi_slope` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_records` | `LONG` | 待逐字段映射与语义核验 |
| `clean_records` | `LONG` | 待逐字段映射与语义核验 |
| `large_order_records` | `LONG` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/l2_features.py`
- `D:/work/fund_2/back-monitor/scripts/validation/validate_main_strategy_daily_data.py:30`
- `D:/work/fund_2/back-monitor/config/yaml/research/factor_platform_v2.yaml:170`
- `D:/work/fund_2/back-monitor/scripts/l2/cleanup_migrated_l2_t0_sources.py:279`
- `D:/work/fund_2/back-monitor/config/yaml/data/query_scenarios.yaml:137`
- `D:/work/fund_2/back-monitor/config/yaml/data/query_scenarios.yaml:144`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:235`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_governance_contracts.yaml:142`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/composition/level2_pipeline.py`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `l2_daily_features` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `l2_daily_features` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D086` 行及 `results/D086.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## 验收记录（2026-09-30）

- QuestDB目标：`java_d086_l2_daily_features_acceptance_final8_20260930`（`java_d086_`隔离表）；正式表 `l2_daily_features` 只读，未修改。
- 源：`D:/work/fund_2/back-monitor/artifacts/level2_t0_dataset`，PyArrow Parquet + D085 manifest receipt；样例证券 `000001.SZ`，日期 `2026-09-21..24`。全源指纹 `c2cca97d…842ad9a`，schema指纹 `79b8834b…de1a1d`；扫描31,731条清单记录、1,593个文件、22,283,980字节，4页返回4行，均在预算内。
- Python模型、源Parquet schema及QuestDB `SHOW COLUMNS` 核对110列一致。正式表只读样本与源Parquet的110列比较也通过；隔离表 typed readback 与独立Parquet样本的4×110=440个字段值全部相等。
- 首次回填 `2026-09-21..23`：3行VERIFIED；同范围重跑：3行VERIFIED，值和键保持不变；增量：从前一checkpoint `2026-09-23`回退三日到`2026-09-21`，到`2026-09-24`共4行VERIFIED；精确resume重新读取4页、读回核验后复用4行VERIFIED。run ID见 [`results/D086.json`](../results/D086.json)。
- 边界：取消在首个可写页前抛出 `CancellationException`；篡改预期源指纹被显式拒绝；分页完成证据核对页数、行数、文件数和指纹。页checkpoint指纹绑定全源指纹、页游标和规范化行值，确保跨页可恢复。该源为本地文件读取，不适用远端限流；自动重试为一次尝试，失败不转为空成功。
- `DatasetRegistry` / `SyncJobRegistry`以及 `CommandLineRunner` 的job列表、详情、计划预览、状态和run/slice账本入口通过定向实测。D086及真实QuestDB验收测试在隔离副本通过；主工作区 `compileJava` 仍被未完成的ETF源类型阻断，完整Spring启动另受现有final ETF Repository代理问题影响，均未在本任务范围修补，也未影响本次D086直接服务/命令分发验收。
- 证据：[`live-acceptance-20260930.json`](../../../artifacts/java-migration/D086/live-acceptance-20260930.json)、[`Parquet与正式表只读比对`](../../../artifacts/java-migration/D086/commands/parquet-vs-formal-readback.json)、[`独立Parquet样本`](../../../artifacts/java-migration/D086/commands/source-parquet-20260921-24.jsonl)。人工复核保持 `pending_review`。
## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D086：l2_daily_features。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/10-l2/D086-l2_daily_features.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D085 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D086.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```

