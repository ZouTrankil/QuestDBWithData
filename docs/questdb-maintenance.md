# QuestDB 维护

这里保留维护方法和已知限制。旧操作的运行编号、行数快照和排障流水不再当作当前使用说明。

## 维护入口

在项目根目录执行：

```sh
./gradlew questdbMaintenance --args='status'
./gradlew questdbMaintenance --args='refresh-etf-overview'
./gradlew questdbMaintenance --args='retire-sync-metadata'
```

`status` 看状态，后两个命令会实际刷新或删除登记过的旧元数据对象；不是预览命令。该入口不启动全量同步应用。

## ETF 物化视图怎样更新

当前直接读取三个原生 MV：`mv_etf_market_overview_daily`、`mv_market_breadth_daily`、`mv_retail_sentiment_daily`，不再依赖旧的重复 `v_*` 入口或 `_v1` 名字。

ETF MV 以 `etf_share` 为基表，还关联 `etf_daily`。左侧新增由即时刷新处理；右侧历史价格修正不保证自动重算，因此 Java 会比较两边的物理身份、事务和 WAL。

右侧修订、输出重建或缺少证书时执行 `FULL`；仅左侧变化时使用 `INCREMENTAL`。刷新后分窗口比较完整历史日期、计数和聚合值。核验中来源或输出变化，就不推进检查点。

证书保存在 `app.sync.ledger-path` 指定的 SQLite `native_mv_checkpoints`，并绑定数据库身份。读取时只检查证书，过期或缺失就拒绝使用，不暗中刷新，也不退回旧状态表。未变化时可以复用原证书。

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

## 五条财务增量还没接管

**删掉旧 meta 表，不代表这五条业务已经有新的自动增量。** 目前尚未完成接管验收：

| 业务表 | 当前限制 |
| --- | --- |
| `cashflow` | 未接入完整源增量任务 |
| `express` | 未接入完整源增量任务 |
| `fina_indicator` | 现有增长字段修复只处理本地冻结响应，不能替代完整同步 |
| `forecast` | 未接入完整源增量任务 |
| `income` | 未接入完整源增量任务 |

旧记录里的抓取时间、行数、表中最大公告日都不能证明增量正常。ETF 的检查点也不负责这五条财务源。

后续要逐条补齐抓取范围、分页结束、完整业务键、公告和历史修订、写后读回、失败恢复，再验证新增公告、同季度补披露、历史修订和失败重跑。没有这些结果，不能对外说已完成增量接管。

## 清理和备份注意什么

策略运行、订单、成交等记录已迁往相邻 Python 项目的本地 SQLite。市场行情和历史 L2 特征属于数据产品，不按交易记录清理。历史归档可在 `var/questdb-record-cleanup/`、`var/retired-sync-metadata/`、`var/unused-object-cleanup/` 等操作产物目录中查找。

`java_*backup*` 是否可删要查实际依赖；`java_*stage*` 可能仍属于未完成发布，不能按前缀批量删除。普通视图查询为空也不代表底表无用。

部署时保留配置指定的 SQLite 运行记录和归档目录。实时 L2 还要一起备份引用的 Parquet 文件，不能只复制 SQLite。数据库连接统一看[配置说明](configuration.md)，不要沿用旧排障记录里的临时转发地址。
