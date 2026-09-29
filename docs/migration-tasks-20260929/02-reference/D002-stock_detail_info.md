# D002 · stock_detail_info

- 状态：verified，本地串行验收完成；人工复核 `pending_review`。生产表仅只读，真实来源写入及发布验收在隔离QuestDB表完成，详见[结果](../results/D002.json)与[最终核对](../../../artifacts/java-migration/D002/final-review.md)。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D001`；前项验收后才执行本项。
- 数据对象：`stock_detail_info`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `stock_detail_info` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`TUSHARE_TO_VERIFY`。
- 物理主时间列：`无/未声明`；物理分区：`NONE`；WAL：`False`；DEDUP：`False`。
- 物理UPSERT KEY：`快照未声明；必须核实自然身份与幂等方案，不凭空添加`。
- Python声明键：`未声明/未匹配`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`['stock_basic']`；sync_function：`sync_gegu_details`。
- 配置同步日期列：`update_time`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{"api": [["stock_basic"]], "date_column": ["update_time"], "frequency": ["weekly"], "time": ["monday 02:00"]}`。

## 本数据sync模式与注意事项

对照stock_detail_info_sync.py按L/D/P状态获取stock_basic，核清字段全量及退市区间；现有Java仅L状态的stock_basic快照不是此表的等价替代。快照时间与update_time分别建模。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- src/quant_platform/data/adapters/connectors/stock/basic/stock_detail_info_sync.py:32 `@api_rate_limit(api_limit=50, period=60)`

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；非WAL静态替换走专用发布协议。
- [x] D06：本表真实来源sync，有限状态/交易所切片及截断检测。
- [x] D07：注册 `stock_detail_info` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、安全重放、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `ts_code` | `SYMBOL` | 已核验；见映射与回读证据 |
| `update_time` | `TIMESTAMP` | 已核验；见映射与回读证据 |
| `symbol` | `STRING` | 已核验；见映射与回读证据 |
| `name` | `STRING` | 已核验；见映射与回读证据 |
| `market` | `STRING` | 已核验；见映射与回读证据 |
| `exchange` | `STRING` | 已核验；见映射与回读证据 |
| `list_status` | `STRING` | 已核验；见映射与回读证据 |
| `list_date` | `STRING` | 已核验；见映射与回读证据 |
| `fullname` | `STRING` | 已核验；见映射与回读证据 |
| `enname` | `STRING` | 已核验；见映射与回读证据 |
| `cnspell` | `STRING` | 已核验；见映射与回读证据 |
| `area` | `STRING` | 已核验；见映射与回读证据 |
| `industry` | `STRING` | 已核验；见映射与回读证据 |
| `curr_type` | `STRING` | 已核验；见映射与回读证据 |
| `delist_date` | `STRING` | 已核验；见映射与回读证据 |
| `is_hs` | `STRING` | 已核验；见映射与回读证据 |
| `act_name` | `STRING` | 已核验；见映射与回读证据 |
| `act_ent_type` | `STRING` | 已核验；见映射与回读证据 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/connectors/stock/basic/stock_detail_info_sync.py:223`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/fundamental.py`
- `D:/work/fund_2/back-monitor/src/api/services/research_strategy_factor_drilldown.py:307`
- `D:/work/fund_2/back-monitor/src/api/services/research_assets.py:93`
- `D:/work/fund_2/back-monitor/src/api/services/research_assets.py:206`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/stock_metadata.py:24`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/naozong_industry_chain_inputs.py:60`
- `D:/work/fund_2/back-monitor/src/quant_platform/best_trader/application/tracked_symbols.py:252`
- `D:/work/fund_2/back-monitor/src/quant_platform/best_trader/application/paired_execution_service.py:951`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `stock_detail_info` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `stock_detail_info` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；无未解决阻塞。
- [x] 验证本任务适用的限流/超时重试、截断异常、取消和安全重放；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量以完整身份发现与业务值合并实现；checkpoint为已验证目标身份/内容，不以本地 `update_time` 推进来源水位；无数据记0且不发布。
- [x] 对隔离目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留九片请求、物理读回和独立核对。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录。
- [x] 更新[逐项完成表](../completion-register.md)的 `D002` 行及 `results/D002.json`，人工比对保留 `pending_review`。
- [x] 实际QuestDB验收通过后记verified；人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D002：stock_detail_info。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/02-reference/D002-stock_detail_info.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D001 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D002.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
