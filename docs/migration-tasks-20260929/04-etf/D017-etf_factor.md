# D017 · etf_factor

- 状态：verified；真实来源、89字段回读、增量幂等和恢复验收通过；人工复核 pending_review。详见 results/D017.json。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 计划顺序前置：`D016`；`serial_after` 是执行顺序，不代表业务依赖。已核对本任务直接数据依赖为D001交易日历，不依赖D016的ETF份额数据。
- 数据对象：`etf_factor`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `etf_factor` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`YEAR`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts_code,trade_date`。
- Python声明键：`ts_code,trade_date`。
- 模型与物理差异：`2026-09-30静态核对task card、Python get_questdb_schema与Java物理列均为89个同名字段；Python Pydantic类只显式声明49个字段，但同步写入链保留schema中输入存在的其余字段`。
- 配置source_api：`fund_factor_pro`；sync_function：`sync_etf_factor`。
- 配置同步日期列：`trade_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["fund_factor_pro"], "date_column": ["trade_date"], "frequency": ["daily"], "time": ["03:45"], "rate_limit": [30], "timeout": [600]}`。

## 本数据sync模式与注意事项

fund_factor_pro按交易日，Python调用限流30/60s；逐日有界写入并检查截断；与原始行情/复权因子独立任务。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- `src/quant_platform/data/adapters/connectors/etf/etf_factor_sync.py:47` 使用 `@api_rate_limit(api_limit=30, period=60)`；`:53` 每个日期调用一次 `pro.fund_factor_pro(trade_date=trade_date)`；`:102` 按D001交易日逐日迭代。这个装饰器值只是Python源码事实，不是账号当前授权额度。
- 官方文档：[`fund_factor_pro` 文档359](https://tushare.pro/wctapi/documents/359.md)，本次只读核对于2026-09-30。输入含 `ts_code/start_date/end_date/trade_date`，单次上限8000；文档未声明limit/offset分页参数。Java按日期分片，只传 `trade_date`；一日一次请求，达到8000行因完整性不明而拒绝，并保存未验证原始响应。执行时仍使用共享credential/endpoint限流，不按Python装饰器提高当前额度。

### Java实施快照（2026-09-30；尚未验收）

- 任务卡与Python `ETFFactor.get_questdb_schema()` 核对为89列：`ts_code,trade_date` 两个键列、9个行情/量能列、78个技术因子列；Java `EtfFactorDataset.sourceFields()` 与任务卡89列同名、无重复。Python API文档另列 `trade_date_doris`，但Python同步明确丢弃该临时日期列，物理schema不含；Java请求显式89字段，不映射该列。
- 发现Python Pydantic `ETFFactor` 类显式声明49字段，而其QuestDB schema列出89字段；真实 `prepare_questdb_dataframe` 对模型未显式声明但输入中存在的schema列做保留。本Java实现以schema/API字段清单为准，逐列做同名、无变换映射，未依赖不完整的Python属性声明。
- `ts_code`保持Tushare基金代码原值；`trade_date`按业务日解析并以UTC午夜TIMESTAMP载体持久化；所有数值为可空有限DOUBLE并保持原值。`vol`单位手、`amount`单位千元、`pct_change`保留来源百分比单位；`_bfq`指标明确是不复权来源指标，不重算、不缩放。
- 正式 `etf_factor` 属已有外部对象：实现不加Flyway/正式表迁移。只提供 `EtfFactorDataset.createIsolatedTableSql`，强制目标名 `java_d017_etf_factor_<suffix>`，TIMESTAMP(trade_date)、YEAR、WAL、DEDUP UPSERT `(ts_code,trade_date)`。
- 默认INCREMENTAL；无可信checkpoint时要求显式bootstrap起点、目标为空，且最多366日；超过预算时计划记录 `cappedByBudget`，用后续incremental继续。checkpoint要求同target、同job版本、连续日历区间及逐交易日原始receipt；续跑在已验证through前最多重取5个日历日修订窗口，并冻结物理min/max与target identity。计划至运行前会重核target身份/日期范围/checkpoint；resume从prior run的FrozenRunRequest恢复原请求。
- 逐日期响应保存确定性canonical raw receipt及SHA-256；HTTP/业务失败、字段/范围/重复键错误、取消、达到cap时均不构成empty或checkpoint。只在正常的完整response返回0行时记录verified empty；非空按有界64行/1 MiB QWP批次写并逐键全列回读。
- 当前自有 `EtfFactorJobService` 提供 `plan(Mode,from,to,logicalDate)` / `planDetailed`、`run(Plan)`、`resume(priorRunId)` / `resume(Plan,priorRunId)`、`status`、`entries`、`cancel`。共享Dataset/CLI/read-group/write-group尚未接线，正式endpoint额度也未调整。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测（源码实现；未进行live来源验收）。
- [x] D07：注册 `etf_factor` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty（代码实现；验收待执行）。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

本次仅完成自有实现与静态契约核对。共享注册/CLI/read-group/write-group接线、编译、隔离QuestDB非空首次写入与回读、幂等重跑、checkpoint增量、空日及取消/未知写入恢复均待协调器实测，当前不可标记verified。

## 物理字段清单

下表是已核对的物理列与Java同名映射；字段类型、单位、空值和日历日期语义见上方实施快照。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts_code` | `SYMBOL` | Java同名映射（见实施快照） |
| `trade_date` | `TIMESTAMP` | Java同名映射（见实施快照） |
| `open` | `DOUBLE` | Java同名映射（见实施快照） |
| `high` | `DOUBLE` | Java同名映射（见实施快照） |
| `low` | `DOUBLE` | Java同名映射（见实施快照） |
| `close` | `DOUBLE` | Java同名映射（见实施快照） |
| `pre_close` | `DOUBLE` | Java同名映射（见实施快照） |
| `change` | `DOUBLE` | Java同名映射（见实施快照） |
| `pct_change` | `DOUBLE` | Java同名映射（见实施快照） |
| `vol` | `DOUBLE` | Java同名映射（见实施快照） |
| `amount` | `DOUBLE` | Java同名映射（见实施快照） |
| `asi_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `asit_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `bbi_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `bias1_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `bias2_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `bias3_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `brar_ar_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `brar_br_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `cr_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `dfma_dif_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `dfma_difma_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `dpo_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `madpo_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `ema_bfq_5` | `DOUBLE` | Java同名映射（见实施快照） |
| `ema_bfq_10` | `DOUBLE` | Java同名映射（见实施快照） |
| `ema_bfq_20` | `DOUBLE` | Java同名映射（见实施快照） |
| `ema_bfq_30` | `DOUBLE` | Java同名映射（见实施快照） |
| `ema_bfq_60` | `DOUBLE` | Java同名映射（见实施快照） |
| `ema_bfq_90` | `DOUBLE` | Java同名映射（见实施快照） |
| `ema_bfq_250` | `DOUBLE` | Java同名映射（见实施快照） |
| `emv_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `maemv_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `expma_12_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `expma_50_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `ktn_down_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `ktn_mid_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `ktn_upper_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_bfq_5` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_bfq_10` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_bfq_20` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_bfq_30` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_bfq_60` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_bfq_90` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_bfq_250` | `DOUBLE` | Java同名映射（见实施快照） |
| `macd_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `macd_dif_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `macd_dea_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `kdj_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `kdj_k_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `kdj_d_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `rsi_bfq_6` | `DOUBLE` | Java同名映射（见实施快照） |
| `rsi_bfq_12` | `DOUBLE` | Java同名映射（见实施快照） |
| `rsi_bfq_24` | `DOUBLE` | Java同名映射（见实施快照） |
| `boll_upper_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `boll_mid_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `boll_lower_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `atr_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `cci_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `dmi_pdi_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `dmi_mdi_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `dmi_adx_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `dmi_adxr_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `mass_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `ma_mass_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `mfi_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `mtm_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `mtmma_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `obv_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `psy_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `psyma_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `roc_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `maroc_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `taq_down_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `taq_mid_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `taq_up_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `trix_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `trma_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `vr_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `wr_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `wr1_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `xsii_td1_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `xsii_td2_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `xsii_td3_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `xsii_td4_bfq` | `DOUBLE` | Java同名映射（见实施快照） |
| `updays` | `DOUBLE` | Java同名映射（见实施快照） |
| `downdays` | `DOUBLE` | Java同名映射（见实施快照） |
| `lowdays` | `DOUBLE` | Java同名映射（见实施快照） |
| `topdays` | `DOUBLE` | Java同名映射（见实施快照） |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/etf/etf_factor_sync.py:78`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/etf/market_data.py`
- `D:/work/fund_2/back-monitor/config/yaml/research/factor_platform_v2.yaml:226`
- `D:/work/fund_2/back-monitor/scripts/tools/data/verify_post_close_recovery.py:26`
- `D:/work/fund_2/back-monitor/src/api/_terminal_market_data.py:127`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/factor_source_discovery.py:257`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/panels/etf_components.py:168`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/panels/etf_components.py:185`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/panels/etf_components.py:186`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `etf_factor` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `etf_factor` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D017` 行及 `results/D017.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D017：etf_factor。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/04-etf/D017-etf_factor.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md。manifest中的serial_after D016只表示计划顺序；D017业务上依赖D001交易日历，按协调器授权判断是否已满足。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D017.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
