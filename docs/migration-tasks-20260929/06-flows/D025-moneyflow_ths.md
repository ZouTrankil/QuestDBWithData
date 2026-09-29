# D025 · moneyflow_ths

- 状态：verified；四轮13列独立回读、取消恢复/CLI/丢确认及回补后增量通过；人工复核 pending_review。详见 results/D025.json。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D024`；前项验收后才执行本项。
- 数据对象：`moneyflow_ths`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `moneyflow_ths` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`YEAR`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts_code,trade_date`。
- Python声明键：`ts_code,trade_date`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`moneyflow_ths`；sync_function：`sync_moneyflow_ths`。
- 配置同步日期列：`trade_date`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["moneyflow_ths"], "date_column": ["trade_date"], "frequency": ["daily"], "time": ["06:10"], "rate_limit": [150], "timeout": [1800]}`。

## 本数据sync模式与注意事项

以本卡Python入口为准逐参数翻译；实现代码/日期/月份等可支持的有限分片和分页能力声明。先冻结真实request例子及结束条件，不能根据表名套默认全量请求。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- `moneyflow_ths_sync.py` 的 `get_moneyflow_ths_by_date` 对每个交易日请求一次 `pro.moneyflow_ths(trade_date=YYYYMMDD)`，无offset分页；该函数文档记录单次最大6000行。
- 同步配置的 `rate_limit` 为150/min，装饰器为1500/min；Java实现遵循较保守的150/min约束，账号实际可用配额仍待真实验收确认。
- `sync_moneyflow_ths` 首跑从配置下限回退 `20260101`，后续从 `last_update+1` 开始，并按完成日上限截断。Java实现沿用该bootstrap下限，用已验证checkpoint及5日重叠检测修订；不把Python的空DataFrame异常吞并语义带入Java，来源失败必须失败并保留不完整响应证据。
- Python模型和物理DDL的13列均有对应Java显式映射；业务键/DEDUP键为 `(ts_code,trade_date)`，金额保留万元（10,000 CNY）单位，百分比和价格值不缩放，nullable字段保留null。
- 正式 `moneyflow_ths` 是外部管理表；实现不新增正式表迁移，真实验收必须使用命名明确的 `java_d025_moneyflow_ths_<suffix>` 隔离目标。恰好6000行的非分页响应视为可能截断并拒绝推进。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；全13列有显式映射。
- [x] D02：业务Key与物理去重键 `(ts_code,trade_date)`；5日重叠用于修订，来源省略已有键时fail closed。
- [x] D03：主时间、YEAR/WAL/DEDUP契约和隔离表DDL已定义；不对外部正式表应用DDL。
- [x] D04：按键/日期有界typed read已实现；公共读取组合接线待协调器完成。
- [x] D05：250行/1MiB typed batch writer、ACK和全列回读验证已实现；公共写入组合接线待协调器完成。
- [x] D06：按已覆盖SSE开市日逐日请求、完整raw receipt、6000行触顶拒绝和有界窗口已实现；真实来源尚未请求。
- [x] D07：本数据Dataset/job定义及JobService已实现；公共注册、CLI、限流配置与write-group接线待协调器完成。
- [x] D08：有限重试/请求界、SSE覆盖、receipt SHA、取消、5日重叠checkpoint和失败不冒充empty已实现；真实源/QuestDB容错验收待执行。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts_code` | `SYMBOL` | 待逐字段映射与语义核验 |
| `trade_date` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `name` | `STRING` | 待逐字段映射与语义核验 |
| `pct_change` | `DOUBLE` | 待逐字段映射与语义核验 |
| `latest` | `DOUBLE` | 待逐字段映射与语义核验 |
| `net_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `net_d5_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `buy_lg_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `buy_lg_amount_rate` | `DOUBLE` | 待逐字段映射与语义核验 |
| `buy_md_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `buy_md_amount_rate` | `DOUBLE` | 待逐字段映射与语义核验 |
| `buy_sm_amount` | `DOUBLE` | 待逐字段映射与语义核验 |
| `buy_sm_amount_rate` | `DOUBLE` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/moneyflow/moneyflow_ths_sync.py:61`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fina/moneyflow_ths.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/config/tables.py:77`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/config/table_definitions/reference_market.py:174`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/config/table_definitions/reference_market.py:175`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/config/table_definitions/reference_market.py:182`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/moneyflow.py:18`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/moneyflow.py:24`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/catalog_definitions/equity_alternative.py:27`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `moneyflow_ths` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `moneyflow_ths` 小节。

## 本任务容错、实际验收与完成登记（必做）

当前实现状态和未完成验收见 [D025结果](../results/D025.json) 及 `artifacts/java-migration/D025/implementation-review.md`。实现阶段未连接Tushare或QuestDB，也未运行构建/测试。操作型助手 `artifacts/java-migration/operations/MoneyflowThsIndependentReadback.java` 从ledger FETCHED事件独立读取原始JSON并直接SELECT隔离表全部13列；单次回读限10个SSE开市日、60,000源行及160MiB原始证据，超界时安全拒绝，验收需拆成更小的run窗口。

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D025` 行及 `results/D025.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D025：moneyflow_ths。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/06-flows/D025-moneyflow_ths.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D024 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D025.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
