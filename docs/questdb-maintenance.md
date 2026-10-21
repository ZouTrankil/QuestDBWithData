# QuestDB 维护

这里保留维护方法和已知限制。旧操作的运行编号、行数快照和排障流水不再当作当前使用说明。

## 维护入口

在项目根目录执行：

```sh
./gradlew questdbMaintenance --args='status'
./gradlew questdbMaintenance --args='inspect-cn-reference-dates --output var/cn-reference-dates-新编号.json'
./gradlew questdbMaintenance --args='refresh-etf-overview'
./gradlew questdbMaintenance --args='retire-sync-metadata'
./gradlew questdbMaintenance --args='rehearse-cn-time-columns --output var/cn-time-rehearsal-新编号.json'
```

`status` 看状态；刷新和退役旧元数据命令会实际改库。`rehearse-cn-time-columns` 只在本轮随机创建的隔离副本上演练六项指定日期列的新布局，验证后按物理身份清理；正式表只读，不迁移原账本、不刷新 MV。输出文件不能已存在，任一对象失败返回非零。实际范围和限制见[时间迁移记录](operations/cn-time-migration/README.md)。该入口不启动全量同步应用。

`inspect-cn-reference-dates` 只读五项公告/利率标准日期，窗口为上海当前日期前一年至明日，每表返回两行样本。不启动来源、不执行迁移或写入；它不验证来源全量覆盖。失败或缺样本返回非零，输出不覆盖旧文件，见[执行记录](operations/cn-time-migration/README.md)。

## ETF 物化视图怎样更新

当前直接读取三个原生 MV：`mv_etf_market_overview_daily`、`mv_market_breadth_daily`、`mv_retail_sentiment_daily`，不再依赖旧的重复 `v_*` 入口或 `_v1` 名字。

ETF MV 以 `etf_share` 为基表，还关联 `etf_daily`。左侧新增由即时刷新处理；右侧历史价格修正不保证自动重算，因此 Java 会比较两边的物理身份、事务和 WAL。

右侧修订、输出重建或缺少证书时执行 `FULL`；仅左侧变化时使用 `INCREMENTAL`。刷新后分窗口比较完整历史日期、计数和聚合值。核验中来源或输出变化，就不推进检查点。

证书保存在 `app.sync.ledger-path` 指定的 SQLite `native_mv_checkpoints`，并绑定数据库身份。读取时只检查证书，过期或缺失就拒绝使用，不暗中刷新，也不退回旧状态表。未变化时可以复用原证书。

## 回测 MV 的显式维护

先只读查看：

```sh
./gradlew questdbMaintenance --args='plan-backtest-refresh'
```

确认原来源已停写、原账本及未决归属后，才运行：

```sh
./gradlew questdbMaintenance --args='refresh-backtest --sources-stopped'
```

停写参数是操作人的声明，不能替代原部署证据。命令不建表、不切换日期布局，只维护当前配置指向的既有原生 MV。`app.materialized-views.backtest.refresh-timeout` 默认 `2m`，可通过标准配置覆盖。

计划不写 SQLite 或 QuestDB。需要 FULL 时，先将原定义、来源物理版本、完整窗口和预期行摘要存入 `app.sync.ledger-path` 的 `native_backtest_refresh_ops`，再发送一次。完成须核对原生刷新、完整历史键值、原摘要、WAL 和稳定版本；不证明上游来源覆盖完整。

超时、断连或输入变化均保留 `IN_DOUBT`，普通调用不能重发。确认原提交者已停止后，只读核对原请求：

```sh
./gradlew questdbMaintenance --args='reconcile-backtest --run-id 原运行编号 --original-sender-stopped'
```

核对不向 QuestDB 写入，成功后只更新原 SQLite 结果。原输入或物理代际改变时继续保留未决，不换用当前配置重签。`status` 可查看未决项。

本轮仅相关本地测试通过；新增实库测试未执行，正式刷新及迁移未执行。缺失材料见[逐表推进记录](operations/cn-time-migration/逐表推进.md)。

## 旧 meta 表为什么能删

退休命令只处理这六个登记名称：

- `cashflow_period_meta`
- `express_period_meta`
- `fina_indicator_period_meta`
- `forecast_period_meta`
- `income_period_meta`
- `etf_market_overview_mv_refresh`

先核验 ETF 新证书，再归档旧表的结构、物理身份、内容和 SHA-256。归档同时写到本地 JSON 和 SQLite `retired_questdb_sync_metadata`；整组保存成功后，逐表重查未变化才删除。

归档失败、结构异常、依赖未解除或身份变化都会阻止删除。重复执行跳过已不存在的登记表，不扫描删除所有 `_meta`。归档只是历史资料，不作为新的增量进度。

## 五条财务增量接管

五条源的正式同步、恢复和可配置日批已实现，操作见[财务同步](financial-sync.md)。它们使用 Java 共享账本，旧 meta 归档不充当新进度。

本轮只读核验了真实源和实库结构；新增、补披露、修订及恢复有本地回归。没有运行全市场正式写入或启用日批，生产接管验收仍需明确证券范围及实际运行结果。旧的增长字段局部修复不再被当作完整增量能力。

## 清理和备份注意什么

策略运行、订单、成交等记录已迁往相邻 Python 项目的本地 SQLite。这次迁移使用的一次性快照、迁移和消费端核验工具已移除，原始快照、SQLite 备份和核验回执保留。市场行情和历史 L2 特征属于数据产品，不按交易记录清理。历史归档可在 `var/questdb-record-cleanup/`、`var/retired-sync-metadata/`、`var/unused-object-cleanup/` 等操作产物目录中查找。

`java_*backup*` 是否可删要查实际依赖；`java_*stage*` 可能仍属于未完成发布，不能按前缀批量删除。普通视图查询为空也不代表底表无用。

部署时保留配置指定的 SQLite 运行记录和归档目录。实时 L2 还要一起备份引用的 Parquet 文件，不能只复制 SQLite。数据库连接统一看[配置说明](configuration.md)，不要沿用旧排障记录里的临时转发地址。
