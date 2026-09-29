# D017 · etf_factor

- 状态：planned，尚未派发。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D016`；前项验收后才执行本项。
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
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`fund_factor_pro`；sync_function：`sync_etf_factor`。
- 配置同步日期列：`trade_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["fund_factor_pro"], "date_column": ["trade_date"], "frequency": ["daily"], "time": ["03:45"], "rate_limit": [30], "timeout": [600]}`。

## 本数据sync模式与注意事项

fund_factor_pro按交易日，Python调用限流30/60s；逐日有界写入并检查截断；与原始行情/复权因子独立任务。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/etf/etf_factor_sync.py:47 `@api_rate_limit(api_limit=30, period=60)`

## 单数据交付清单

- [ ] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [ ] D02：本表业务Key、物理去重键、冲突/修订规则。
- [ ] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [ ] D04：本表按键/范围的typed read与分页，接入读取组合。
- [ ] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [ ] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [ ] D07：注册 `etf_factor` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [ ] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [ ] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts_code` | `SYMBOL` | 待逐字段映射与语义核验 |
| `trade_date` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `open` | `DOUBLE` | 待逐字段映射与语义核验 |
| `high` | `DOUBLE` | 待逐字段映射与语义核验 |
| `low` | `DOUBLE` | 待逐字段映射与语义核验 |
| `close` | `DOUBLE` | 待逐字段映射与语义核验 |
| `pre_close` | `DOUBLE` | 待逐字段映射与语义核验 |
| `change` | `DOUBLE` | 待逐字段映射与语义核验 |
| `pct_change` | `DOUBLE` | 待逐字段映射与语义核验 |
| `vol` | `DOUBLE` | 待逐字段映射与语义核验 |
| `amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `asi_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `asit_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `bbi_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `bias1_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `bias2_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `bias3_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `brar_ar_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `brar_br_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cr_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dfma_dif_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dfma_difma_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dpo_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `madpo_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ema_bfq_5` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ema_bfq_10` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ema_bfq_20` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ema_bfq_30` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ema_bfq_60` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ema_bfq_90` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ema_bfq_250` | `DOUBLE` | 待逐字段映射与语义核验 |
| `emv_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `maemv_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `expma_12_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `expma_50_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ktn_down_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ktn_mid_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ktn_upper_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_bfq_5` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_bfq_10` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_bfq_20` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_bfq_30` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_bfq_60` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_bfq_90` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_bfq_250` | `DOUBLE` | 待逐字段映射与语义核验 |
| `macd_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `macd_dif_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `macd_dea_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `kdj_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `kdj_k_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `kdj_d_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rsi_bfq_6` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rsi_bfq_12` | `DOUBLE` | 待逐字段映射与语义核验 |
| `rsi_bfq_24` | `DOUBLE` | 待逐字段映射与语义核验 |
| `boll_upper_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `boll_mid_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `boll_lower_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `atr_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `cci_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dmi_pdi_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dmi_mdi_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dmi_adx_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `dmi_adxr_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mass_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `ma_mass_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mfi_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mtm_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `mtmma_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `obv_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `psy_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `psyma_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `roc_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `maroc_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `taq_down_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `taq_mid_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `taq_up_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `trix_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `trma_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `vr_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `wr_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `wr1_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `xsii_td1_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `xsii_td2_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `xsii_td3_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `xsii_td4_bfq` | `DOUBLE` | 待逐字段映射与语义核验 |
| `updays` | `DOUBLE` | 待逐字段映射与语义核验 |
| `downdays` | `DOUBLE` | 待逐字段映射与语义核验 |
| `lowdays` | `DOUBLE` | 待逐字段映射与语义核验 |
| `topdays` | `DOUBLE` | 待逐字段映射与语义核验 |

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

- [ ] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [ ] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [ ] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [ ] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [ ] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [ ] 更新[逐项完成表](../completion-register.md)的 `D017` 行及 `results/D017.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [ ] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D017：etf_factor。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/04-etf/D017-etf_factor.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D016 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D017.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
