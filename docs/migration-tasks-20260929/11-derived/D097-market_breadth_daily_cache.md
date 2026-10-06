# D097 · market_breadth_daily_cache

- 状态：verified（retained_compatibility）；八字段 Java 历史 typed READ 与原 Python publisher 隔离真实验收通过。正式两条历史摘要异常单独保留，人工 pending_review。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D096`；前项验收后才执行本项。
- 数据对象：`market_breadth_daily_cache`。
- 业务依赖：本卡来源与公共功能契约；未发现的外部依赖须在实施时登记。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `market_breadth_daily_cache` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`OWNER_INGEST_OR_DERIVED_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`MONTH`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`trade_date,source_version`。
- Python声明键：`trade_date,source_version`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。
- 本对象属于旧路径/缓存候选。先核对caller与正式替代；不能为了覆盖清单再创建第二套计算责任。兼容保留或退役须有独立验收证据。

## 本数据sync模式与注意事项

Tushare不适用或入口未确认；先沿本卡源码引用核实实际owner和上游，已确认衍生表定义bounded materialize，服务结果表定义typed ingest，未确认则阻塞，不虚构API。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：本表DTO、domain、逐字段mapper与语义类型；核对下方全部物理列。
- [x] D02：本表业务Key、物理去重键、冲突/修订规则。
- [x] D03：本表主时间、WAL、分区、DDL及兼容方案；确认快照漂移。
- [x] D04：本表按键/范围的typed read与分页，接入读取组合。
- [x] D05：retained_compatibility 的 Java WRITE 为 N/A；READ-only 拒绝直写。原 Python 唯一 publisher 的隔离首次/重跑/增量逐键值验证通过。
- [x] D06：确认原 Python read-through owner；不新增 Java 第二套计算。隔离验收仅真实三日聚合，原 _publish 实际落库。
- [x] D07：八字段 DatasetDefinition/typed ReadGroup 已从实际管理入口确认；Java sync job/checkpoint 为 N/A（保留 Python 唯一 publisher）。
- [x] D08：本表限流、重试、断点、取消和完整性证据；不吞失败为empty。
- [x] D09：本表有界示例、隔离库读写及来源样例对照，完成后提交本卡结果。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | Java 显式映射已核对；语义见下方冻结契约 |
| `stock_count` | `LONG` | Java 显式映射已核对；语义见下方冻结契约 |
| `up_count` | `LONG` | Java 显式映射已核对；语义见下方冻结契约 |
| `down_count` | `LONG` | Java 显式映射已核对；语义见下方冻结契约 |
| `flat_count` | `LONG` | Java 显式映射已核对；语义见下方冻结契约 |
| `avg_pct_change` | `DOUBLE` | Java 显式映射已核对；语义见下方冻结契约 |
| `total_amount_yi` | `DOUBLE` | Java 显式映射已核对；语义见下方冻结契约 |
| `source_version` | `SYMBOL` | Java 显式映射已核对；语义见下方冻结契约 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/market_barometer_cache.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/market_barometer_cache.py:44`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `market_breadth_daily_cache` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `market_breadth_daily_cache` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 先核实本任务所需来源、权限、schema、键、参数和QuestDB连接；有阻塞即记录，不能盲目继续。
- [x] 验证本任务适用的限流/超时重试、分页异常、取消和断点恢复；已ACK但未回读一致的写入保持未验证。
- [x] 默认增量，记录checkpoint前后与有限修订窗口；sync有数据才写，没有数据明确记0，不用假数据充数。
- [x] 对本任务实际目标进行QuestDB SELECT，按完整业务键逐字段比对源规范化数据；保留请求范围、查询/参数、返回样本和汇总。
- [x] 首次非空真实来源写入、同范围幂等重跑及再次增量验证有记录；本任务为View/MV或功能时按公共契约对应的实际验收方式执行。
- [x] 更新[逐项完成表](../completion-register.md)的 `D097` 行及 `results/D097.json`；填写完成状态、表名、源行/写入行、回读结果、运行时间、证据、问题及人工比对待办。
- [x] 仅实现测试通过记implemented_not_verified；来源不可用记blocked；只有实际验收通过记verified。人工复核始终由用户决定。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D097：market_breadth_daily_cache。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D097-market_breadth_daily_cache.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D096 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D097.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```

## 实施与验收（2026-10-06）

- 处置为 retained_compatibility：native v_market_breadth_daily 直接读 D095 MV，非 native/回退路径保留 Python MarketBarometerReadThroughCache。Java 提供历史原始值读取，不自动挑 latest、不认证当前 source freshness 或覆盖回执，不新增 publisher/job/checkpoint。
- D01—D04 冻结八列与完整 (trade_date,source_version) 键。MONTH/WAL/DEDUP 保留；日期是 UTC 午夜载体，四个 LONG 必填，两个 DOUBLE 可空且不再次换算单位，generation 必须 lower64 SHA-256。
- 业务 source_version 与物理快照分别处理。分页绑定 cache 表 id/目录/physical txn/WAL sequencer，读前后不变且 WAL 结算；物理/WAL counter 不互比。一般 query、严格 JSON、游标坏 version 在 DB 前拒绝，既有 TABLE 行为回归通过。
- 原 Python publisher 隔离真实验收：QuestDB10.0.1 私有19000/18812、PID16980、D095 owned root；原业务 SQL 从16658真实源行得到三日聚合，只创建缺失的 cache 与 coverage 原模型表。首次2日、同范围重跑2日、新增9/21一日，cache/coverage各提交5行，实际完整键2→2→3；phase cache56值/receipt35值核对、原摘要全匹配。物理插入/更新量 unknown，未以提交量冒充。
- Java 实际读取：3keys×8列的按键与范围共48值匹配，三页完整键唯一；12次 DOUBLE 对照 bit-exact、容差使用0，另12次 JDBC存储bits一致。实际配置 ReadGroup、取消、坏 JSON 与原 publisher 早期真实物理游标拒绝均通过；Java及正式写入0。
- 定向 Java63项、Python脚本边界10项，均0失败/错误/跳过。最初 multiline SELECT guard 预检失败发生在零写入阶段，证据保留后修正，并完成实际验收。
- 正式只读审计五条已存回执：3匹配、2不匹配，HTTP/PG真实值及原序列化一致，不能当作表示差异。正式cache/coverage/source身份和txn前后不变。该失败与隔离成功分别保存，未修正式数据，当前 cache hit/production latest 均未认证。
- 有界请求例见 artifacts/java-migration/D097/commands/read-group-request.json；完整 range/date/version 读取用 nextCursor 恢复，物理版本变化后重新发起有界请求。写入/来源管理由原 Python owner负责，不伪造 Java run状态。
- 协调器 accepted_for_serial_progress；详见 results/D097.json 和 D097/coordinator-review-20261006.json。人工 pending_review；下一项 D098。
