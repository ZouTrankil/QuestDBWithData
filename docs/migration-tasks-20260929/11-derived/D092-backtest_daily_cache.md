# D092 · backtest_daily_cache

- 状态：verified（`retained_compatibility`）；D091协调器验收门槛已通过，Python readthrough owner、Java typed read及本机QuestDB完整版本化切片已验收。人工复核 `pending_review`。
- 工作区：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData`。
- Python项目目录（持续只读查找）：`D:/work/fund_2/back-monitor`；同步配置、connectors、模型、读写SQL和测试均可沿实际调用链检索。
- 串行前置：`D091`；前项验收后才执行本项。
- 数据对象：`backtest_daily_cache`。
- 业务依赖：D009:stk_factor, D010:stk_limit, D011:stk_suspend, D012:stk_st_daily。
- 必读：[公共契约](../00-common-contract.md)、[总体计划](../README.md)。

## 目标与边界

只完成 `backtest_daily_cache` 一个数据对象的Java业务定义、来源任务、分区/键、读取、写入和验收。已有projection不等于完成。公共D01—D09全部适用；不在本卡实现其他数据。

## 当前证据（2026-09-29快照，执行前复核）

- 分类：`data_model`；来源类别：`OWNER_INGEST_OR_DERIVED_TO_VERIFY`。
- 物理主时间列：`trade_date`；物理分区：`MONTH`；WAL：`True`；DEDUP：`True`。
- 物理UPSERT KEY：`trade_date,ts_code,source_version`。
- Python声明键：`trade_date,ts_code,source_version`。
- 模型与物理差异：`清单未发现已比较项差异；不代表全部字段一致`。
- 配置source_api：`无匹配`；sync_function：`无匹配，须查实际owner`。
- 配置同步日期列：`无匹配`；衍生源记录：`stk_factor,stk_limit,stk_suspend,stk_st_daily`。
- 配置事实（数组表示匹配记录，非当前授权额度）：`{}`。

## 本数据sync模式与注意事项

本表是Python `BacktestReadThroughCache` 当前readthrough路径拥有的版本化cache。`read()` 按来源表和 `v_backtest_daily` 的指纹定位 `(trade_date, source_version)`；未命中时从view取数，在再次确认指纹稳定后写cache，轮询WAL可见性与内容摘要，然后发布D091 coverage回执。Java不成为第二个计算或发布owner，仅为已发布版本提供强类型有界读取。

2026-09-30本机只读验收：物理表MONTH/WAL/DEDUP、14列、去重键 `(trade_date, ts_code, source_version)` 与Python声明一致。2026-09-21已存版本回执5,565行；Java typed repository读取全切片，按完整键与D091已被Python原版摘要核验的切片比对14列共77,910值，全部相等；ReadGroup强类型结果相同。cache表7,531,793行/table_txn=48及coverage表1,367行/table_txn=4前后不变。该已存版本不同于当前来源指纹；本验收不声明当前版本热缓存，也未触发Python可能写入的cache miss重建。

Python调用/限流证据（仅源码事实，未逐接口验证线上配额）：

- 未提取到独立装饰器/页长声明；不得解释为无限流。实施时沿调用链核实。

## 单数据交付清单

- [x] D01：复用13字段 `BacktestDaily` 类型，加 `source_version` 形成14字段cache模型；逐字段mapper及空值、精度映射通过。
- [x] D02：完整业务Key和物理DEDUP键均为 `(trade_date, ts_code, source_version)`；同版本内容与coverage摘要由Python owner校验。
- [x] D03：现场确认 `trade_date` designated TIMESTAMP、MONTH/WAL/DEDUP及完整UPSERT KEY；无DDL变更。
- [x] D04：完整Key、版本化日期和日期范围的有界typed read；稳定cursor和ReadGroup强类型绑定通过实库验收。
- [x] D05：N/A；Python readthrough保留writer，Java DatasetDefinition仅READ，拒绝直写，避免无回执或竞争版本。
- [x] D06：N/A；Python从 `v_backtest_daily` 计算并发布cache；不虚构独立Tushare source或Java物化job。
- [x] D07：注册唯一READ-only DatasetDefinition；不创建没有迁移来源所有权的Java SyncJobDefinition。
- [x] D08：共享有界reader实施页长、20秒SQL超时、完整键唯一性和cursor校验；Python owner执行来源指纹复查、WAL可见性、行数及摘要校验。写入重试/断点/取消对本Java只读裁决不适用。
- [x] D09：本机QuestDB已存版本完整5,565行、14列77,910个字段值与Python摘要核验过的切片相等；表行数与事务号不变。结果见 `../results/D092.json`。

## 可观察验收

本表全字段映射可核对；完整业务键和值一致；日期/空值/精度符合冻结契约；重跑幂等；请求触顶、失败、重复页或未知写入不能成功；管理入口能够查到本表job/run/slice及失败原因。没有真实来源/权限时如实报告阻塞，不用fixture宣称真实同步完成。

## 物理字段清单

下表是待映射输入，不是已经确认的Java业务类型。标准名称与物理名称可以通过显式mapper兼容。

| 当前列 | 快照类型 | 任务要求 |
| --- | --- | --- |
| `trade_date` | `TIMESTAMP` | `LocalDate`；交易所business date，UTC零点仅为存储载体；键 |
| `ts_code` | `SYMBOL` | `String`；六位代码及交易所后缀；键 |
| `open` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `high` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `low` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `close` | `DOUBLE` | nullable `Double`；未复权CNY/股 |
| `vol` | `DOUBLE` | nullable `Double`；Tushare手 |
| `amount` | `DOUBLE` | nullable `Double`；Tushare千元 |
| `adj_factor` | `DOUBLE` | nullable `Double`；无量纲复权因子 |
| `up_limit` | `DOUBLE` | nullable `Double`；CNY/股 |
| `down_limit` | `DOUBLE` | nullable `Double`；CNY/股 |
| `is_suspended` | `INT` | nullable `Integer`；停牌标记 |
| `is_st` | `INT` | nullable `Integer`；特别处理标记 |
| `source_version` | `SYMBOL` | `String`；来源指纹SHA-256；键 |

## 只读参考入口

- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/backtest_cache.py`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/materializers/registry.py:62`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/dataset_snapshots.py:318`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/dataset_snapshots.py:370`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/backtest_cache.py:103`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/backtest_cache.py:125`
- `D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/models/stock/backtest_cache.py:17`

- 全量引用和字段依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/objects.csv` 中 `backtest_daily_cache` 对应行。
- 字典依据：`D:/work/fund_2/back-monitor/artifacts/storage-audit-20260929/object-dictionary.md` 的 `backtest_daily_cache` 小节。

## 本任务容错、实际验收与完成登记（必做）

- [x] Python owner、QuestDB连接、实表schema、键和真实已存回执版本已核实。
- [x] Java有界读/分页/超时和Python owner完整性保护已核验；Java不执行来源网络调用或写入。
- [x] checkpoint、增量窗口、幂等写入对Java只读兼容不适用；写入0行，未触发cache重建。
- [x] QuestDB按完整版本化键实读5,565行，与D091已被Python摘要核验的14列切片逐值比对。
- [x] 首次非空写入验收对 `retained_compatibility` 不适用；用已有真实发布版本和coverage回执验收。
- [x] 已更新完成表和 `results/D092.json`，保留Java/QuestDB证据与当前版本cache miss限制。
- [x] 状态为verified（只读兼容与已存版本完整性）；人工复核 `pending_review`。

## Orca执行提示

```text
在 C:/Users/zouqiang/IdeaProjects/QuestDBWithData 执行计划任务 D092：backtest_daily_cache。Python参考项目为 D:/work/fund_2/back-monitor，请持续按真实调用链只读查找，不只依赖摘要。先读取 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/11-derived/D092-backtest_daily_cache.md 和 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/00-common-contract.md，核验串行前置 D091 的验收记录。只完成本卡功能或单个数据，不代做后续任务。保留已有用户修改，使用明确目标的隔离QuestDB和有界真实来源样例。默认增量sync，有有效数据才写入，按完整键实际SELECT回读逐字段核对；首次0行不能认定写入验收完成。实现有限重试/限流/断点/取消/未知写入核验，验收失败保留现场并停止。更新 C:/Users/zouqiang/IdeaProjects/QuestDBWithData/docs/migration-tasks-20260929/completion-register.md 和 results/D092.json，记录源返回、写入、QuestDB回读、checkpoint及证据，人工复核保持pending_review。若本卡为conditional，先核验显式准入记录；无记录不实施。完成后停止，由Orca协调器验收再决定下一项。
```
