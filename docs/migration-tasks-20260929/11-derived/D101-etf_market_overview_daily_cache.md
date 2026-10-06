# D101 · etf_market_overview_daily_cache

- 状态：verified；实际隔离验收及独立证据复核通过，协调器 accepted_for_serial_progress；人工 pending_review。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D100`；前项验收后才执行本项。
- 数据对象：`etf_market_overview_daily_cache`。
- 业务依赖：D016:etf_share, D014:etf_daily, D013:etf_basic。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `etf_market_overview_daily_cache` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`OWNER_INGEST_OR_DERIVED_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`MONTH`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`trade_date,source_version`。
- Python声明键：`trade_date,source_version`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`etf_share,etf_daily,etf_basic`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `etf_market_overview_daily_cache` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | 待逐字段映射与语义核验 |
| `etf_count` | `LONG` | 待逐字段映射与语义核验 |
| `total_share` | `DOUBLE` | 待逐字段映射与语义核验 |
| `total_size_yi` | `DOUBLE` | 待逐字段映射与语义核验 |
| `source_version` | `SYMBOL` | 待逐字段映射与语义核验 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/market_barometer_cache.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/registry.py:54`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/market_barometer_cache.py:66`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `etf_market_overview_daily_cache` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `etf_market_overview_daily_cache` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D101` 行及 `results/D101.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D101：etf_market_overview_daily_cache。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D101-etf_market_overview_daily_cache.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D100 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D101.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```

## 实际验收（2026-10-06）

Java五列typed READ、完整(date,source_version)键/MONTH/WAL/DEDUP、ReadGroup及canonical管理已实现；prepared WRITE仅断言委托原Python owner，D094仍公开只读。专用PID23388/19020/18832复制11667真实来源行并核对164180字段值；FIRST2miss/4ACK→新HIT2hit/0发布→第三日全前缀3miss/6ACK→配置typed3hit/0发布全部通过。历史cache/coverage各5键及5列、digest、原位double和真实旧游标拒绝通过，checkpoint9/18→9/21，0lease。未知首日HIT保留原失败证据，再显式只读恢复：全窗口proof2/原slice1，无重发，不当HIT2；之后独立新HIT2单列验收。190唯一Java+75唯一Python通过。正式3一致/2历史摘要异常未修复，不认证current/latest或生产切换，同键源值更正未现场测试。结果见[results/D101.json](../results/D101.json)及[证据](../../../artifacts/java-migration/D101/README.md)，最终协调gate已接受串行推进D102。

最终协调结论：本卡 verified，accepted_for_serial_progress；按序准许 D102。记录见 `artifacts/java-migration/D101/coordinator-review-20261006.json`，人工复核仍 pending_review。
