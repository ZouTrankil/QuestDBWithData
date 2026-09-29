# D006 · ths_member

- 状态：verified，本地隔离目标实测通过；前置 D005 已 verified，人工 pending_review。证据见[结果](../results/D006.json)与[最终核对](../../../artifacts/java-migration/D006/final-review.md)。正式 `ths_member` 保持只读，未做消费者切换。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D005`；前项验收后才执行本项。
- 数据对象：`ths_member`。
- 业务依赖：D004:ths_index。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `ths_member` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`update_time`；物理分区：`MONTH`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`ts_code,con_code,update_time`。
- Python声明键：`ts_code,con_code,update_time`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`ths_member`；sync_function：`sync_ths_member`。
- 配置同步日期列：`update_time`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": ["ths_member"], "date_column": ["update_time"], "frequency": ["monthly"], "time": ["04:30"], "rate_limit": [200], "timeout": [1800]}`。

## 本数据sync模式与注意事项

以本卡Python入口为准逐参数翻译；实现代码/日期/月份等可支持的有限分片和分页能力声明。先冻结真实request例子及结束条件，不能根据表名套默认全量请求。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:38 `@api_rate_limit(api_limit=200, period=60)`
- src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:52 `@api_rate_limit(api_limit=200, period=60)`

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；本对象是 TABLE，View/MV拒绝直写由共享契约验证。
- [x] D06：本表真实来源sync/ingest/materialize，单板块有限请求/批及触顶检测；未声称全供应商原子快照。
- [x] D07：注册 `ths_member` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts_code` | `SYMBOL` | 待逐字段映射与语义核验 |
| `con_code` | `SYMBOL` | 待逐字段映射与语义核验 |
| `con_name` | `STRING` | 待逐字段映射与语义核验 |
| `weight` | `DOUBLE` | 待逐字段映射与语义核验 |
| `in_date` | `STRING` | 待逐字段映射与语义核验 |
| `out_date` | `STRING` | 待逐字段映射与语义核验 |
| `is_new` | `STRING` | 待逐字段映射与语义核验 |
| `update_time` | `TIMESTAMP` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:67`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/index/ths.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/best_trader/application/market_monitor.py:526`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/config/tables.py:60`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:4`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:57`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:78`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:88`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/index/ths_member_sync.py:112`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `ths_member` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `ths_member` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际隔离目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为 TABLE。
- [x] 更新[逐项完成表](../completion-register.md)的 `D006` 行及 `results/D006.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 本地隔离真实来源验收通过记verified；正式表只读、人工复核由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D006：ths_member。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/02-reference/D006-ths_member.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D005 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D006.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
