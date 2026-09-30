# D093 · v_backtest_daily

- 状态：verified（`retained_compatibility`）；D092协调器验收门槛已通过，Python权威SQL、QuestDB实视图、Java只读契约及本机实库样本已验收。人工复核 `pending_review`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D092`；前项验收后才执行本项。
- 数据对象：`v_backtest_daily`。
- 业务依赖：D009:stk_factor, D010:stk_limit, D011:stk_suspend, D012:stk_st_daily。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `v_backtest_daily` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`view`；来源类别：`VIEW`。
- 物理主时间列：普通VIEW没有designated timestamp；视图输出 `trade_date TIMESTAMP` 仍为交易所business date。物理分区/WAL/DEDUP均不适用（快照中的WAL布尔值不能作为视图WAL属性）。
- 物理UPSERT KEY：无；普通VIEW不可直接写或去重。
- 业务读取身份：由Python SQL和上游唯一键推出 `(trade_date, ts_code)`；不是视图物理UPSERT KEY。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`未登记`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

Tushare不适用。Python `backtest_view.VIEW_SELECT` 是当前权威SQL，`install_view` 在建视图前检查 `stk_factor`、`stk_limit`、`stk_st_daily` 的完整日期/证券键；`stk_suspend` 先按日期/证券聚合。视图直接反映基表，不存在独立刷新job、分区或checkpoint。Java只注册现有视图READ能力，不创建竞争的DDL owner。

2026-09-30本机QuestDB `views()` 报告 `valid`，`view_sql` 与Python `VIEW_SELECT` 逐字一致（SHA-256 `3fdd642eec364d0443e8e8174614e4cf003c67b3226d1ff011c65d01fc9e85fa`），13列类型全匹配、无物理UPSERT KEY和designated timestamp。2026-09-17有界实读200行：Python HTTP SELECT与Java JDBC typed read按 `(trade_date, ts_code)` 比对13列2,600值全匹配；ReadGroup返回相同200行；四张来源表行数/table_txn前后未变。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：13列只读业务record与逐字段mapper；视图 `is_suspended LONG` 与D090旧物理表 `INT` 的差异显式保留。
- [x] D02：业务读取键为 `(trade_date, ts_code)`；视图无物理DEDUP/UPSERT KEY。上游三表唯一键及停牌聚合经Python安装契约和实表只读确认。
- [x] D03：现场确认普通VIEW无designated timestamp、分区、WAL、DEDUP；当前 `views()` SQL与Python权威定义一致，无DDL变更。
- [x] D04：完整键、日期、范围的有界typed read和稳定cursor分页；ReadGroup强类型绑定通过。
- [x] D05：普通VIEW直写N/A并由READ-only DatasetDefinition拒绝；更新入口是四张上游表的已有owner，不新增视图writer。
- [x] D06：普通VIEW无独立source sync/materialization或refresh job；基表变更时SQL动态读取，Python `install_view` 保留DDL owner。
- [x] D07：注册READ-only DatasetDefinition，不创建无独立来源/写入状态的SyncJobDefinition。
- [x] D08：Java共享有界读取器实施页长、SQL超时、完整键唯一和cursor约束；Python安装时唯一键守卫经实库核验。网络限流、写入重试/断点/取消对视图只读不适用。
- [x] D09：本机QuestDB/Python/Java 200个完整键×13列=2,600值全匹配，SQL定义哈希一致，来源表未变。结果见 `../results/D093.json`。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | `LocalDate`；交易所business date，UTC零点仅为存储载体；业务键 |
| `ts_code` | `SYMBOL` | `String`；六位代码+交易所后缀；业务键 |
| `open` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `high` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `low` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `close` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `vol` | `DOUBLE` | nullable `Double`；Tushare手 |
| `amount` | `DOUBLE` | nullable `Double`；Tushare千元 |
| `adj_factor` | `DOUBLE` | nullable `Double`；无量纲复权因子 |
| `up_limit` | `DOUBLE` | nullable `Double`；CNY/股 |
| `down_limit` | `DOUBLE` | nullable `Double`；CNY/股 |
| `is_suspended` | `LONG` | nullable `Long`；停牌聚合标记，视图coalesce提升为LONG |
| `is_st` | `INT` | nullable `Integer`；特别处理标记 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:277`
- `D:/work/fund_2/back-monitor/config/yaml/data/data_quality_rules.yaml:411`
- `D:/work/fund_2/back-monitor/scripts/tools/data/verify_post_close_recovery.py:56`
- `D:/work/fund_2/back-monitor/scripts/tools/data/verify_post_close_recovery.py:58`
- `D:/work/fund_2/back-monitor/scripts/tools/data/verify_post_close_recovery.py:65`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/factors/cross_sectional/l2_daily/base.py:126`
- `D:/work/fund_2/back-monitor/src/quant_platform/research/adapters/naozong_industry_chain_inputs.py:38`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `v_backtest_daily` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `v_backtest_daily` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] Python `VIEW_SELECT`、QuestDB `views()`、13列schema、上游键、连接和权限已核实。
- [x] Java有界读和Python安装唯一键守卫已验证；视图无独立网络取源/写入重试/断点/取消。
- [x] 视图无checkpoint或增量写入窗口；Java写入0，未执行DDL。
- [x] QuestDB实际SELECT 200个完整键，与Python HTTP样本按13列逐值比对，留存SQL哈希、样本和汇总。
- [x] 普通VIEW按公共契约执行只读、权威SQL及上游键验收；非空写入/幂等重跑不适用。
- [x] 已更新完成表和 `results/D093.json`，保留Python/Java/QuestDB证据与人工待审。
- [x] 状态为verified（只读兼容与当前视图语义），人工复核 `pending_review`。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D093：v_backtest_daily。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D093-v_backtest_daily.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D092 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D093.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
