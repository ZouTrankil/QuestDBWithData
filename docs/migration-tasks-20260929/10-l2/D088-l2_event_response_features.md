# D088 · l2_event_response_features

- 状态：verified；D088实现、隔离目标与来源逐字段回读已通过。Orca主协调器验收门槛于2026-09-30通过，人工复核仍为 `pending_review`；已按序启动D089。门槛复核记录见 `artifacts/java-migration/D088/coordinator-review-20260930.json`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D087`；前项验收后才执行本项。
- 数据对象：`l2_event_response_features`。
- 业务依赖：D087:l2_intraday_bar_features。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `l2_event_response_features` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 执行前快照与完成证据（2026-09-30复核）

- 分类：`other_domain_or_unclassified`；来源类别：`LEVEL2`。
- 物理主时间列：`minute`；物理分区：`DAY`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`symbol,minute,event_type`。
- Python物理唯一键：`symbol,minute,event_type`，与QuestDB UPSERT键一致。
- D088隔离表与来源样例完成73列逐字段回读；执行前模型快照的比较范围限制不影响本次实际比对。
- 实际来源owner：`level2_batch_processor` 调用 `build_event_response_features`，物化到D085认证的本地Parquet。
- 配置同步日期列：`minute`对应的本地交易日分区；不是远程API，因此无服务端配额。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

事件响应粒度含event_type，不能沿用分钟表两列键；明确响应窗口与可得时间、未来信息边界。

Python调用/限流结论（基于已核验调用链）：

- 来源为本地manifest/Parquet，无远端接口配额；Java任务配置`maxAttempts=1`，以固定超时、行/文件/页/字节预算和两小时deadline限制执行。

已核实的Python业务语义：

- 每个symbol/交易日的amount 95分位用于大额事件；active buy或sell占比分别至少0.65时标记主动买/卖，否则记 `large_turnover`。
- OFI低于 `-0.5 * abs(depth_1)` 标记 `bid_depth_depletion`，高于 `0.5 * abs(depth_1)` 标记 `ask_depth_depletion`；`cancel_ratio >= 0.5` 标记 `high_cancel_ratio`。完整事件类别为 `large_active_buy`、`large_active_sell`、`large_turnover`、`bid_depth_depletion`、`ask_depth_depletion`、`high_cancel_ratio`。
- 完整业务键为 `(symbol, minute, event_type)`。相同事件键的源修订按QuestDB DEDUP UPSERT覆盖。
- `future_vwap_return_{1,3,5,10,15,30}m` 是未来VWAP相对事件分钟VWAP的收益；源字段 `future_mid_return_*` 实际也是未来VWAP，但分母为事件分钟mid。两组未来收益均是前视结果，不可作为事件时点可得特征；源函数按时间向前取值，允许不超过该horizon分钟的容差。
- Python未产出事件行时可能没有Parquet part；D088对分区或part缺失采取失败，不把缺文件解释成已验证空结果。

## 单数据交付清单

- [x] D01：73字段DTO、domain、显式mapper与逐字段语义已核对。
- [x] D02：完整键 `(symbol,minute,event_type)`、QuestDB物理UPSERT键与修订覆盖已核实。
- [x] D03：`minute`主时间、DAY/WAL/DEDUP及隔离DDL与在线快照一致。
- [x] D04：本表typed key/range read、分页和read group已接入。
- [x] D05：73列typed batch write、逐键值回读和写组完整源键验证已接入；不适用View/MV直写检查。
- [x] D06：D085 manifest认证的本地Parquet真实来源、有限窗口/页/文件/行/字节和完整性检查已接入。
- [x] D07：Dataset/job注册，管理、计划预览、运行、状态及slice查询通过live验收。
- [x] D08：本地来源采用 `maxAttempts=1`，有限超时/预算；取消、过期fingerprint拒绝、断点及未知写入核验通过；远端限流不适用。
- [x] D09：非空隔离样例backfill、幂等重跑、增量、resume、写组和全73列QuestDB回读对照均通过。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表字段已对照本地Parquet、QuestDB物理表与显式Java mapper逐字段核验。标准名称与物理名称通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `STRING` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `symbol` | `SYMBOL` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `market` | `STRING` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `board` | `STRING` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `minute` | `TIMESTAMP` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `open` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `high` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `low` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `close` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `volume` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `amount` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `tick_count` | `LONG` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `active_buy_amount` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `active_sell_amount` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vwap` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `has_trade_1m` | `LONG` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `bid1` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ask1` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `mid` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `spread` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `microprice` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `bid_depth_1` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ask_depth_1` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `depth_1` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `obi_1` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `bid_depth_5` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ask_depth_5` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `depth_5` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `obi_5` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `bid_depth_10` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ask_depth_10` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `depth_10` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `obi_10` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `quote_count` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ofi_1m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `active_buy_ratio` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `active_sell_ratio` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vwap_gap_to_mid` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vwap_gap_to_open` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vwap_slope_3m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vwap_slope_5m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ret_1m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vol_ratio_1m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `range_1m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ret_3m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vol_ratio_3m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `range_3m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ret_5m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vol_ratio_5m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `range_5m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ret_10m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vol_ratio_10m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `range_10m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ret_15m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vol_ratio_15m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `range_15m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `ret_30m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `vol_ratio_30m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `range_30m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `cancel_ratio` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `event_type` | `STRING` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_vwap_return_1m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_mid_return_1m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_vwap_return_3m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_mid_return_3m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_vwap_return_5m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_mid_return_5m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_vwap_return_10m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_mid_return_10m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_vwap_return_15m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_mid_return_15m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_vwap_return_30m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |
| `future_mid_return_30m` | `DOUBLE` | 已逐字段核验；显式映射见 `L2EventResponseFeatureField` |

## 只读参考入口

- `D:/work/fund_2/back-monitor/scripts/l2/cleanup_migrated_l2_t0_sources.py:39`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/migrate_l2_t0_dataset_questdb.py:85`
- `D:/work/fund_2/back-monitor/src/quant_platform/workflows/data/level2_batch_processor.py:102`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/engines/level2/l2_chip_phase_research.py:418`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/local_files/l2_t0_snapshots.py:27`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/composition/level2_pipeline.py`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `l2_event_response_features` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `l2_event_response_features` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [ ] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [ ] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [ ] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [ ] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [ ] 更新[逐项完成表](../completion-register.md)的 `D088` 行及 `results/D088.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [ ] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D088：l2_event_response_features。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/10-l2/D088-l2_event_response_features.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D087 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D088.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
