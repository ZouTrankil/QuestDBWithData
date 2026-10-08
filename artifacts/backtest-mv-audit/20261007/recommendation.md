# v_backtest_daily 物化改造只读审计

观察日期：2026-10-07；目标实例：127.0.0.1:9000 / 8812；QuestDB 10.0.1，commit 7a391566ac827e0d8c269b91f2415c16b0a32a84。

本次仅查询 metadata、业务行计数和读取源码/官方资料，没有 DDL、写库、生产作业或测试执行。

## 当前对象与业务语义

`v_backtest_daily` 当前是普通 VIEW：`views()` 中 valid；`tables()` 中 id1780、table_type=V、matView=false、无 designated timestamp、逻辑对象没有持久化业务行。不能把这个入口称为原生物化视图。原始 metadata 已保存在 [object-readonly.json](./object-readonly.json)。

9月30日输出5571行、5571个唯一股票键，其中12行 is_suspended>0。业务键为 `(trade_date,ts_code)`；13列按顺序为 trade_date(TIMESTAMP)、ts_code(SYMBOL)、open/high/low/close/vol/amount/adj_factor/up_limit/down_limit(DOUBLE)、is_suspended(LONG)、is_st(INT)。

现有 SQL 包含两条路径：

1. stk_factor 的每日实际价格左连接涨跌停价、按日/股票 max 的停牌标记和 ST 状态。
2. 当停牌股票当日没有 factor 行时，以停牌日为左侧 ASOF JOIN 寻找此前最近价格，将前 close 复制到 OHLC，vol/amount置0，保留以前价格行的 adj_factor。只产出 stk_factor 存在的交易日期，且 f.trade_date<s.trade_date。

两条路径 UNION ALL。缺失的停牌日补行、ASOF历史范围、null和指标单位都必须保留；不能把改造缩减为普通 factor+limit 的当前行投影。

## 是否能够直接用原生 MV

不能将当前 SQL 原样改为 CREATE MATERIALIZED VIEW。官方要求时间聚合和 designated timestamp。tag10.0.1 的创建校验对所有 refreshType 执行同一规则，FULL没有任意SQL例外。[官方创建说明](https://questdb.com/docs/query/sql/create-mat-view/)；[10.0.1源码校验](https://github.com/questdb/questdb/blob/10.0.1/core/src/main/java/io/questdb/griffin/engine/ops/CreateMatViewOperationImpl.java#L373-L435)。

官方还明确 raw逐行扩列 enrichment 不适合原生MV。JOIN需要 WITH BASE，只追踪指定base的变化；右侧 limit/ST/suspend 修订不会自动触发。base启用DEDUP时非聚合列须属于其key。[官方概念与约束](https://questdb.com/docs/concepts/materialized-views/)。

不把 UNION 或 ASOF JOIN 本身笼统标为禁止：9.0 已公布self-UNION支持。当前定义失败的确定依据是整体逐行enrichment没有顶层时间聚合，不是所有连接或UNION都不支持。[9.0官方发布](https://questdb.com/blog/questdb-9-release/)。

IMMEDIATE/EVERY/MANUAL改变调度，FULL/INCREMENTAL/RANGE改变刷新范围，均不能突破定义限制。FULL重建合法MV；invalid不能通过INCREMENTAL恢复；RANGE不推进增量checkpoint，命令返回也不是完成验收。[官方刷新说明](https://questdb.com/docs/query/sql/refresh-mat-view/)。

## 已有实现可复用范围

| 位置 | 可复用内容 | 限制 |
| --- | --- | --- |
| repository/MarketBreadthDailyV1MaterializationPort.java、RetailSentimentDailyV1MaterializationPort.java | 原生DDL/refresh、冻结source/target身份、WAL与checkpoint、完整实际readback | 都是单日期聚合key；31天/200000源行预算；正式路径仅INCREMENTAL，FULL仅隔离实例 |
| service/MarketBreadthDailyV1JobService.java、RetailSentimentDailyV1JobService.java | canonical SyncJobRunner、恢复、ledger、DatasetIntervalLock、target admission | 不能直接当新backtest13列复合键owner |
| repository/QuestDbBoundedReader.java | 已有固定SELECT * alias与nativeMV状态/身份/分页sourceVersion guard | 目前只为breadth/retail注册；v_backtest_daily没走这个guard |
| domain/BacktestDailyViewDataset.java、repository/BacktestDailyViewReadRepository.java、mapper/BacktestDailyViewMapper.java | 13列typed读与完整(date,code)分页，保持用户read入口 | 当前ObjectKind.VIEW，deps为空，Python install是DDL owner |
| domain/BacktestDailyDataset.java、BacktestDailyCacheDataset.java、BacktestDailyCacheCoverageDataset.java | 已有13列物化表、14列版本cache、完整业务键和coverage模型 | 当前都是Java只读兼容定义；写入owner需正式改为唯一owner |
| repository/NativeDailyWindowWritePort.java、NativeDailyWindowPublication.java | retained backup、durable publication、全字段verify、source SHA和window/outside保护思路 | 实现只接受每日一行、10000全表快照、MONTH/nonDEDUP，不能直接套每日5500股票/超过千万行表 |

已有物理对象：backtest_daily id1757，10355884行，DAY/WAL/DEDUP，最新0917；backtest_daily_cache id1914，11916956行，MONTH/WAL/DEDUP，版本key(date,code,source_version)，最新0930；coverage id1915，2155行，最新0930。当前cache已有持久化效果，不能称为原生MV。

两个既有nativeMV现在均invalid，理由均为“materialized view is ahead of base table and cannot be synchronized”。breadth数据最新0918，retail最新0831。两个公共alias在views()自身valid，但它们的下游MV invalid，因此Java现有guard会拒绝读取。可复用代码模式，不能把这些实际结果当正常依赖或复制状态。

## 推荐落地路径

推荐优先复用 `backtest_daily` 物化表，以Java SQL作业按明确日窗刷新完整13列；`v_backtest_daily` 改为该表的稳定13列alias。这实现持久化读，不冒充QuestDB原生MV：

1. 冻结当前权威VIEW_SELECT和四源metadata/WAL/partition版本；源明确窗口内唯一键；停牌ASOF需要窗口以前的全部相关价格历史。按已存在13列typed mapper读出有界日窗结果，验证完整键/有限值/字段null语义。
2. 复用SyncJobRunner、VerifiedBatchExecutor、DatasetIntervalLock与ReferencePublicationJournal；已有物化表DAY/WAL/DEDUP继续使用(date,code)；增加适合复合键与大表的有界staging/发布port。不要把day-only generic port的guard简单放宽。
3. 整日替换或等价有界窗口发布以处理消失键；单纯UPSERT只会覆盖/新增，无法移除旧结果中本次已不存在的键。保留原始表与outside数据；实际readback全13列、完整唯一键、行数、SHA和WALsettled通过才发布。
4. 转换入口时明确is_suspended仍为LONG、is_st仍INT，其余列顺序/名字/单位/null不变。Java read dataset与guard更新为实际alias依赖、已发布source generation、physical ID/txn/摘要，分页前后验证稳定；不允许旧stale表仅maxDate达到0930就验收。
5. 旧Python `BacktestReadThroughCache.install()`/`derived.install('backtest_daily')`/CLI `--install`会重新安装原实时VIEW_SELECT，必须由root协调DDL唯一owner，保留新alias或拒绝冲突。cache的generation也包含旧VIEW_SELECT；改owner/入口时保留同一权威计算版本或显式迁移，不能伪称旧generation仍然等价。

### 如必须使用真正原生 MATERIALIZED VIEW

可采用两阶段结构：先让完整13列结果落到已有 `backtest_daily`，保持每(date,code)严格一行；再建立每股每日 `SAMPLE BY 1d` singleton聚合的原生MV，并让 v_backtest_daily作为SELECT * alias读取它。指标采用经过单行唯一证明的等价聚合、必要cast维持LONG/INT。这是真正nativeMV，但仍需Java负责四源复杂计算，增加一份结果存储与一个refresh步骤。

这一方案仅为设计建议，本次未执行DDL或验证其新定义。前置全键唯一必须检查，禁止靠MAX/SUM掩盖重复；源与输出null及类型逐列核验。批次结束后显式MANUAL refresh并等valid/caught-up/readback完成。四源修订须先回填flattened表；历史price修订还可能改变随后停牌日，需计算影响范围。业务意义不应被为满足MV语法而改变。

## 文件定位

Java根：`C:/Users/zouqiang/IdeaProjects/QuestDBWithData/src/main/java/com/zoutrankil/data/`。

权威现有计算：`D:/work/fund_2/back-monitor/src/quant_platform/data/adapters/questdb/backtest_view.py:10`；安装入口同文件`:34`；版本cache/coverage机制 `backtest_cache.py:40`，ASOF历史fingerprint`:77`；旧表materializer `adapters/materializers/backtest_daily.py:361`。只读参考这些现有定义，不建议重新启动其全量DROP重建路径。

只读证据：[object-readonly.json](./object-readonly.json)。最终实施取舍由root决定。
