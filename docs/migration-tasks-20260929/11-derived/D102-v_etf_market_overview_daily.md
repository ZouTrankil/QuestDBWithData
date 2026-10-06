# D102 · v_etf_market_overview_daily

- 状态：verified（隔离验收）；协调gate accepted_for_serial_progress，按序准许D103，人工pending_review。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D101`；前项验收后才执行本项。
- 数据对象：`v_etf_market_overview_daily`。
- 业务依赖：D016:etf_share, D014:etf_daily。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `v_etf_market_overview_daily` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`view`；来源类别：`VIEW`。
- 物理主时间列：`trade_date`；物理分区：`N/A`；WAL：`True`；DEDUP：`False`。
- 物理UPSERT KEY：`快照未声明；必须核实自然身份与幂等方案，不凭空添加`。
- Python声明键：`未声明/未匹配`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

Tushare不适用；实现读取与来源刷新定义，DDL走schema迁移；普通View禁止直接写，MV由基表写入后刷新并验证覆盖/版本/有效状态。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：本表typed batch write及逐键值验证，接入写入组合；View/MV提供拒绝直写的验证。
- [x] D06：本表真实来源sync/ingest/materialize，有限窗口/页/批及截断检测。
- [x] D07：注册 `v_etf_market_overview_daily` DatasetDefinition和单数据job，支持管理、计划预览、运行与状态查询。
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

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/market_barometer.py:15`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/market_barometer.py:19`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/derived.py:317`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/market_barometer_cache.py:56`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/market_barometer_cache.py:58`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/market_barometer.py:24`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/application/market_barometer.py:18`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `v_etf_market_overview_daily` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `v_etf_market_overview_daily` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D102` 行及 `results/D102.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D102：v_etf_market_overview_daily。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D102-v_etf_market_overview_daily.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D101 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D102.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```

## 实际验收（2026-10-06）

四列语义DTO、trade_date自然Key、逐字段mapper、typed范围/按键/分页和实际配置ReadGroup完成。普通VIEW不具自己的WAL/物理分区/DEDUP/writer/独立job或checkpoint；快照WAL=True不作为视图写契约。D07是注册VIEW DatasetDefinition并声明两基表owner，未编造VIEW运行切片；51Dataset/40jobs/本VIEW job0。D05的批写及STATIC/WAL替换准备均在writer前拒绝。

唯一私有目标23388/19020/18832复用D101的11667真实行与164180字段值；缺失原视图CREATE ACK1，前后三次CREATE前FAILED保留。私有与正式各三日原SQL/view全12字段、6double原位零容差匹配；Java独立JDBC、typedDTO和ReadGroup实际一致，三页1行、两日真实游标恢复/重读、变range拒旧cursor、第三日窗口扩展及取消无page通过。仅复用D101已经验收的第三日来源增量，D102自身没有来源追加或修订。五表id/dir/txn/WAL/schema、视图目录/定义/状态均跨阶段固定；Java游标保留完整µs状态文本。130唯一Java(129pure+1live)+81Python全部PASS/0skip。

两个目标之间保留4项原生DOUBLE聚合bits差异（私有减正式：9/17市值+7ULP，9/18份额+1ULP及市值-2ULP，9/21市值-4ULP）；各目标内对照保持零容差，没有改SQL或舍入，不认证跨物理布局bits稳定。每SQL20秒，非整个组合绝对期限；取消覆盖成员启动前；源Javajob隔离默认目标未切换正式；provider全域/全历史/current/latest/生产切换不认证，D101正式两历史摘要异常未修复。

结果：[results/D102.json](../results/D102.json)；证据：[D102 README](../../../artifacts/java-migration/D102/README.md)。最终独立复核与根协调gate通过，accepted_for_serial_progress，按序准许D103，人工pending_review。

原始 JDBC metadata snapshot 的 Timestamp JSON 是带默认本地时区解释的 epoch 毫秒载体，与 native UTC 时间相差 -8 小时并丢弃亚毫秒位，不能用它证明 UTC 时间相同。实际 reader 使用 getString，sourceVersion 绑定完整正确的 UTC µs 状态文本，独立复核逐项匹配两目标的 native 元数据；业务日期 mapper 未受该 metadata 表示差异影响。

最终协调记录：[D102 gate](../../../artifacts/java-migration/D102/coordinator-review-20261006.json)。
