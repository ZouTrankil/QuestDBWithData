# QuestDB 维护记录（2026-10-17）

本页保存数据库维护、重复视图清理和本地记录迁移的操作细节。日常项目导览不需要先读本页。

## 原生 MV 与旧增量辅助表

维护入口由 Java 执行，不启动全量应用或其他同步任务：

```sh
./gradlew questdbMaintenance --args='status'
./gradlew questdbMaintenance --args='refresh-etf-overview'
./gradlew questdbMaintenance --args='retire-sync-metadata'
```

`mv_etf_market_overview_daily_v1`、`mv_market_breadth_daily_v1`、`mv_retail_sentiment_daily_v1` 是 QuestDB 原生 `MATERIALIZED VIEW`。它们在 `tables()` 中也会出现，因为物化结果需要物理存储；类型应通过 `materialized_views()` 或 `information_schema.tables.table_type` 判断。Java 和 Python 直接读取上述原生 MV；原有重复 `v_*` 普通视图已在 2026-10-17 清理，不再安装或读取。

ETF MV 使用 `REFRESH IMMEDIATE` 处理 `etf_share` 基表增量。它还有 `etf_daily` JOIN 来源，QuestDB 不会仅因右侧历史修正就自动重算所有旧桶。因此 Java 检查两侧的物理身份、事务和 WAL 状态：右侧修正、输出重建或缺失证书执行 `FULL`，仅左侧修订执行 `INCREMENTAL`。刷新完成后按最多 31 天的窗口比对整个历史的业务日期、计数和聚合值；来源或输出在核验中变化时不推进检查点。未变化时复用原证书。

双来源证书存入已有 `app.sync.ledger-path` SQLite 的 `native_mv_checkpoints`，并绑定 QuestDB `current_data_id()`，避免切换数据库时误用其他实例的检查点。读取路径只读本地证书，缺失或过期会明确拒绝读取，不会创建台账、刷新视图或退回旧 QuestDB 状态表。

`retire-sync-metadata` 只清理六个登记名称：`cashflow_period_meta`、`express_period_meta`、`fina_indicator_period_meta`、`forecast_period_meta`、`income_period_meta` 和 `etf_market_overview_mv_refresh`。它先验证 ETF 新证书，然后保存全部表的原始字段、类型、DDL、数据库及表身份、行数和 SHA256；同一份归档同时写入本地 JSON 文件及 SQLite `retired_questdb_sync_metadata`。整组归档成功后，逐表重新检查内容与身份未变化，才执行 DROP。归档目录或 SQLite 写入失败、意外字段、依赖视图或来源变化均阻止删除；重复执行会跳过已移除的表。

五张财务表是旧同步的抓取记录，当前 Java 生产同步没有读写这些 `_period_meta` 表。旧 `last_fetched_at` 和 `row_count` 仅作历史归档，不能证明财报业务键覆盖，也不能直接当成已验证的增量进度。归档表 `retired_questdb_sync_metadata` 不参与同步执行；ETF 的 `native_mv_checkpoints` 也不负责这五张财务源表。

**财务增量接管尚未验收。** 当前活动 Java 源码未发现 `cashflow`、`express`、`forecast`、`income` 的完整源同步任务，也未发现 `fina_indicator` 的完整源增量任务；已有 `FinancialGrowthRepairJob` 只使用本地冻结响应修复部分增长字段。当前能确认的是旧元数据已完整归档、检查过的活动源码未直接依赖它们，不能据此保证外部旧抓取任务或上述五条未来增量正常。上文 ETF 的历史比对和 MV 刷新验证不覆盖这五条财务源同步。

2026-10-16 进一步执行实际入口 `./gradlew :run --args='show-sync-job-definitions'`，应用成功启动并返回 44 个注册任务，其中这五个 dataset 的任务数均为 0；主策略来源编排也没有纳入它们。当前本项目 `var/sync-ledger.sqlite3` 没有 `sync_runs` 表，不能提供这五条的成功增量执行记录。Python 的 `FinancialDataQueryService`、`FinancialStatementQueryService` 仅查询已存数据，不承担外部源同步。因此本项目的五条完整财务源增量接管确认不通过，而非仅缺少测试结果。该结论不排除其他仓库、机器或人工任务曾写入这些业务表。

同次只读实库核验确认五张业务表仍存在：`cashflow` 164,525 行、`express` 14,205 行、`fina_indicator` 197,174 行、`forecast` 57,417 行、`income` 177,762 行。业务表有数据不代表存在自动增量任务；表中最大公告日期也不是任务最近执行时间或已验证的同步水位。

项目其他已接入的同步使用冻结请求、运行台账、明确的业务范围及物理读回校验。财务源后续接入同样需要逐表实现抓取范围、分页完成、完整业务键、公告及修订覆盖、写后读回、失败重试和检查点提交，完成“新增公告、同季度补披露、历史修订、失败重跑”验收后才能报告接管完成。不要用单个 `MAX(end_date)` 或一次 `row_count=0` 跳过中间缺口或封存季度。

部署需要一起保留并备份配置指定的 SQLite 台账与归档目录。更新到本版本后再执行退休命令；旧版 Java 的 ETF 读取器依赖已退休的 QuestDB 刷新表。

2026-10-16 已在实际 QuestDB 10.0.1 实例执行：五张财务表各 35 行、ETF 旧刷新表 1 行，共 176 行完成双副本与 SHA256 校验后移除。ETF 历史 2,117 个日期聚合逐窗比对通过；原生 MV 为 `valid`、`immediate`，基表及刷新事务均为 8；当时的公开 `v_etf_market_overview_daily` 为普通 VIEW，后于 2026-10-17 清理。重复退休命令没有新增归档或删除其他对象。归档保存在本项目 `var/retired-sync-metadata/` 与 `var/sync-ledger.sqlite3`。专项测试 76 项中 74 项通过、2 项按条件跳过；四模块生产/测试源码编译、分发包及模块/驱动/依赖检查通过，不代表整仓全量测试已通过。

本机到远端 PGWire 的 JDBC 直连仍在认证阶段出现 EOF，本次通过临时本机 TCP 转发连接同一远端实例完成 Java 维护，仅在执行命令时覆盖 `SPRING_DATASOURCE_URL`，未修改部署数据库地址。实际部署应验证其 PGWire 连接正常；后台双来源检查在更新后的主 Web 服务启动后运行。

## 重复视图、空表与本地记录

2026-10-17 已清理九个重复普通视图：`v_regime_features_monthly`、`v_macro_core_monthly`、`v_retail_sentiment_daily`、`v_market_breadth_monthly`、`v_regime_features_monitor_daily`、`v_market_breadth_daily`、`v_regime_market_monthly`、`v_etf_market_overview_daily`、`v_macro_liquidity_credit_monthly`。实查它们有 200～2226 行可见结果，删除仅移除重复入口，底表与原生 MV 的物理身份、行数未变化。Java 已删除别名读取模块、注册及相关守卫；ETF 维护不再创建别名。Python 日频产品直接读取原生 MV，月频/策略产品保持本地存储。

两张确认为零行的 `l2_live_events_v2`、`l2_live_micro_state_1m_v2` 已删除。实时 L2 的 QuestDB 写入器已移除；原始报文、解析事件、分钟观察和累计检查点都存入 `l2.root/raw-journal.sqlite3`（默认 `var/l2-live/raw-journal.sqlite3`），不可变 `prepared_batch.payload` 保存完整批次及 SHA256。重启先读取并核验原批次，再推进本地检查点；QuestDB 只用于读取交易日历。原 8 GiB 配额不变，现有旧版远端写入未决批次不会被自动覆盖为本地成功。

清单中的两张 `java_backtest_daily_backup_*`、`orders`、`order_fills` 在本次操作前已不存在。当前没有 `java_*backup*`；新产生的 `java_stk_suspend_stage_*` 是发布暂存对象，不能按备份名批量删除。本次删除前后记录位于 `var/unused-object-cleanup/20261016T160750Z/{before,after}.json`，保留视图 SQL、空表 DDL、字段、物理身份和验证结果。

策略与交易记录已经从 QuestDB 迁至相邻 Python 项目的五个 `records.sqlite3`：`artifacts/quant_platform/best_trader/{trade_evidence,broker_records}/`、`artifacts/quant_platform/research/{strategy_runs,strategy_production,strategy_identity}/`。本次冷启动重新核验 26 个原始记录表的完整字段与内容摘要，应用从本地 SQLite 读取，不访问远端记录表；`orders`、`order_fills` 原本为空。迁移证据保存在 `var/questdb-record-cleanup/20261016T150229Z/`。对应已无运行时使用的 Java 记录投影已删除。原始行情与历史 L2 特征不属于策略订单记录。
