# D095 · mv_market_breadth_daily_v1

- 状态：`verified`；Java canonical materialize任务、typed read和账本恢复的真实隔离验收通过；全MV排他锁回归及实际管理目录准入通过，协调器接受按序进入D096。正式生产库未验收，人工复核`pending_review`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D094`；前项验收后才执行本项。
- 数据对象：`mv_market_breadth_daily_v1`。
- 业务依赖：D009:stk_factor与已登记交易日历；canonical job依赖`data.stk_factor v2`、`data.exchange_calendar v1`。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `mv_market_breadth_daily_v1` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`materialized_view`；来源类别：`MATERIALIZED_VIEW`。
- 物理主时间列：`trade_date`；物理分区：`MONTH`；WAL：`True`；DEDUP：`False`。
- 物理UPSERT KEY：`快照未声明；必须核实自然身份与幂等方案，不凭空添加`。
- Python声明键：`未声明/未匹配`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`stk_factor`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。
- 审计快照该MV为invalid，先重新核对基表身份/WAL及刷新条件；不能在原实例直接FULL refresh作为测试。

## 本数据sync模式与注意事项

Tushare不适用；实现读取与来源刷新定义，DDL走schema迁移；普通View禁止直接写，MV由基表写入后刷新并验证覆盖/版本/有效状态。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：7字段业务模型、逐字段mapper和TIMESTAMP日历日期语义已核对正式物理列。
- [x] D02：自然业务键为单个`trade_date`日桶；MV物理无UPSERT KEY，不伪造去重键。
- [x] D03：正式MV为`trade_date`主时间、MONTH/WAL、非DEDUP；SQL与Python权威定义一致，Java显式安装仅限隔离库，已有定义不重写。
- [x] D04：单日/有界范围typed read、稳定分页和ReadGroup已在隔离QuestDB核对；失效MV拒读。
- [x] D05：MV禁止直接写入；来源为基表`stk_factor`；Java canonical job负责有界原生增量刷新，拒绝直接写MV行。
- [x] D06：隔离QuestDB两天11,105行与新增9/21的5,553行全部来自正式库SELECT；Java原生增量刷新、重跑和完整字段回读通过。正式库未写。
- [x] D07：Java job `data.mv_market_breadth_daily_v1 v1`已注册，依赖`data.stk_factor v2`和交易日历；默认INCREMENTAL，支持MATERIALIZE/RECONCILE及ledger/run/slice/status/cancel/resume。
- [x] D08：共享runner、账本、区间锁及有限可见性轮询；未知ACK保留IN_DOUBT，显式停写后真实值reconcile。分页绑定源/MV物理身份、表txn、WAL序号与刷新checkpoint。隔离FULL修复经同一账本执行。
- [x] D09：有界真实来源、Java运行、typed read/ReadGroup、故障案例和逐项结果已登记；90项定向Java测试（0失败/0错误/0跳过）与15项Python保护测试通过。
- [x] 最终协调器gate：全MV冲突范围锁回归和canonical管理目录实际启动均通过；90项定向测试0失败/0错误/0跳过，协调器`accepted_for_serial_progress`，按序进入D096。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 已冻结字段映射

7个物理字段已逐列核对，业务模型与显式mapper保持原聚合的空值、单位及日期语义。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | `LocalDate`；UTC午夜日历桶，完整自然业务键 |
| `stock_count` | `LONG` | `long`；精确计数，非负；stock_count必须为正 |
| `up_count` | `LONG` | `long`；精确计数，非负；stock_count必须为正 |
| `down_count` | `LONG` | `long`；精确计数，非负；stock_count必须为正 |
| `flat_count` | `LONG` | `long`；精确计数，非负；stock_count必须为正 |
| `avg_pct_change` | `DOUBLE` | 可空`Double`；原pct_change单位的算术平均，非空必须有限 |
| `total_amount_yi` | `DOUBLE` | 可空`Double`；Python同义`sum(amount)/100000.0`，非空必须有限 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/registry.py:33`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `mv_market_breadth_daily_v1` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `mv_market_breadth_daily_v1` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] 已核实真实来源、schema、键、参数及私有QuestDB连接/PID/数据目录；正式MV失效记录为独立生产限制。
- [x] 版本漂移、重复键、超预算、取消、恢复、未知ACK/锁排他和实值核验有针对测试；原生提交只发一次，未知结果不重发。
- [x] 默认INCREMENTAL，连续已验证范围推进checkpoint；9/18→9/21，下一次修订窗口从9/19开始。真实空源0行，缺交易日桶拒绝推进。
- [x] 正式目标只读SELECT：9/17–9/18共12个指标值与基表聚合匹配；9/19–9/24来源有4个交易日而MV无行，状态invalid。
- [x] 隔离库真实来源11,105行、幂等重跑、新增5,553行、失效拒读及恢复完整比对通过。正式目标失效及生产切换另行保留，不声称正式已就绪。
- [x] 更新[逐项完成表](../completion-register.md)和`results/D095.json`；全MV锁回归和管理目录准入均已通过，人工复核保持pending_review。
- [x] 隔离真实QuestDB及来源验收作为本卡门槛；正式库状态单独记录。人工复核保持pending_review。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D095：mv_market_breadth_daily_v1。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D095-mv_market_breadth_daily_v1.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D094 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D095.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```

## Java执行入口与验收边界（2026-10-06）

- `plan-market-breadth-daily-job|run-market-breadth-daily-job --from YYYY-MM-DD --to YYYY-MM-DD --logical-date YYYY-MM-DD [--mode INCREMENTAL|MATERIALIZE|RECONCILE]`，日期范围两端包含，执行最多31天；bootstrap锚点可长期保留，限制作用于实际checkpoint重叠窗口。
- `market-breadth-daily-job-status|cancel-market-breadth-daily-run|resume-market-breadth-daily-run --run RUN_ID`；恢复固定原request/target/source/calendar，禁止把IN_DOUBT当作可重跑失败。
- `reconcile-market-breadth-daily-run --run RUN_ID --writer-stopped true`仅SELECT核验；未通过来源/实际值/稳定版本，不释放旧区间锁。
- `install-market-breadth-daily-isolated`与`repair-market-breadth-daily-isolated`限已核实PID和`-d`私有目录的19000/18812实例。FULL修复只处理完整非空基表≤200000行、≤31天，通过共享runner持久化与回读，正式FULL未开放。
- 默认`app.sync.market-breadth-daily.mutations-enabled=false`。正式原生增量准入还需显式`expected-target-id`，不会自动启用。
- QuestDB10.0.1的user RANGE会留下refreshing目录状态；Java采用INCREMENTAL，完成仍要求valid/caughtUp。它处理checkpoint后的WAL变化，不能声称强制重算任意历史窗口；无WAL变化而现有数据漂移时，实际parity会拒绝完成。
- 待刷状态下原生任务可能扫描基表，当前保护要求完整基表≤200000行、跨度≤31天。正式大库当前invalid且不满足该工作预算；其修复、持续源写入协调及生产切换未验收。隔离验收不代表正式库就绪。
- 证据：`artifacts/java-migration/D095/commands/isolated-acceptance-20261006.json`与`java-materialize-acceptance-20261006.json`，参见`results/D095.json`。


## 最新实际验收与最终门槛（2026-10-06）

- canonical job为`data.mv_market_breadth_daily_v1 v1`，默认INCREMENTAL，支持MATERIALIZE/RECONCILE；单请求31天、单片/单页、最多31日桶、64KiB及200000原始行，单次原生提交，3分钟有限可见性等待。
- 初始真实9/17–9/18切片11105行，完整键`(trade_date,ts_code)`与4字段导入/重复重放各44420值匹配；新增9/21真实5553行再比较22212值，总16658原始行。首次/幂等运行目标各2行×7字段=14值；增量、恢复与隔离FULL目标3行×7字段=21值，日期/全部指标/空值/精度按实际SELECT核对。物理插入/更新数量无法可靠拆分，记unknown，不以提交数冒充插入数。
- SSE真实日历9/17至10/6共20行×4字段=80值全匹配，冻结独立calendar_version；缺真实交易日桶的运行FAILED，未越过未验证缺口。checkpoint由9/18到9/21，后续3日修订窗口从9/19开始；9/20 MATERIALIZE为VERIFIED_EMPTY，零刷新提交。
- 最新运行：首次`d095-d1b97554-dd38-43a9-a887-684f528f9c98`，重跑`d095-dd874fea-0dc8-434c-8ef5-885f7a82ef72`，增量`d095-ae8670a2-013b-4b26-977b-588f8370363f`，隔离FULL`d095-9fa46685-6f3f-4106-9077-9386cd6cdebc`；均VERIFIED。其余空源/失效拒绝/缺交易日/恢复run与slice回执完整见`results/D095.json`。
- 实际账本`var/d095-java-1203ad29-7f39-4792-bfa8-27e141a1a603.sqlite3`保存8个run，其中5 VERIFIED、1 VERIFIED_EMPTY、2 FAILED；已读到6个已验证slice，现场运行终结时保留锁0。FULL同样先持久化提交意图、再原生刷新、最后真实回读；未知ACK与可见性超时的纯故障测试保留IN_DOUBT/锁且拒绝重发，未伪称真实服务器发生过网络丢ACK。
- 全MV锁改动后定向Java90项（0失败/0错误/0跳过）与Python15项保护测试通过。原生刷新会影响请求窗口之外的MV状态，实际`list-sync-jobs`启动已显示38个job，D095 v1依赖stk_factor v2与交易日历v1。全MV锁采用共享runner的allDates排他范围，跨不相交窗口及旧窄lease真实回读回归通过；协调器接受`verified`，manifest剩余范围D096–D184。
- 正式库2026-10-06只读复核仍invalid：基表10265031行/txn14，MV2212行/txn2、刷新checkpoint12，缺9/21–9/24四个交易日；没有正式写入。10.3百万行及多年跨度不满足本次受控pending-refresh/FULL工作预算，正式修复、来源并发协调和生产切换未验收。其状态不要求本卡先执行正式FULL才能推进Java迁移。

- 管理目录证据：`artifacts/java-migration/D095/commands/java-job-registration-20261006.json`，实际应用启动/BUILD SUCCESSFUL；生产写入仍默认禁用。
- 早期RANGE诊断`d095-452b0bba-078c-4c11-bd2e-30718d672c44`保留在`var/d095-java-568c69ff-8453-459b-a0dd-4fc2aabb13c6.sqlite3`，仍为IN_DOUBT并持有旧9/17–9/18窄lease；未reconcile、未成功、未删除。旧私有PID39144已停止，后续PID16980及重置样本改变了source_version，不能拿新数据释放旧冻结运行锁。新INCREMENTAL/FULL_ISOLATED验收使用独立账本；其锁终结状态不代表旧诊断记录已解决。全部操作限私有库，正式无写入。

- 最终门槛证据：`artifacts/java-migration/D095/commands/target-tests-20261006.json`及`artifacts/java-migration/D095/coordinator-review-20261006.json`；全部运行ID和账本已从最后一次真实隔离验收重新读取，人工复核pending_review。
