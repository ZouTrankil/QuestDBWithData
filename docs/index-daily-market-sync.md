# 指数日线的显式 v2 正式入口

`index_daily_market` 的 v1 隔离任务使用物理列 `timestamp`，正式表使用 `trade_date`。两者已贯通任务、目标、写入、读取、恢复和检查点分版；没有修改正式表或启动日批。

根 `config/markets/cn.yml` 保留原默认值：

```yaml
app.sync.index-daily-market-schema-version: 1
app.sync.index-daily-market-table: java_d019_index_daily_market_acceptance
```

只有明确设置布局 v2 和正式表名，才使用正式入口。布局不按实库列名猜测，不因改表名自动升级。日期仍是 UTC 午夜载体，YEAR/WAL、完整键和字段精度不变。

## 先做只读来源核查

```sh
APP_SYNC_INDEX_DAILY_MARKET_SCHEMA_VERSION=2 \
APP_SYNC_INDEX_DAILY_MARKET_TABLE=index_daily_market \
./gradlew run --args='check-index-daily-market-source --ts-code 000300.SH --from 2026-09-18 --to 2026-09-18 --logical-date 2026-10-21'
```

只核对原物理对象、结构和稳定 WAL，并抓取显式日期范围。输出区分来源完整、目标结构和目标数据：`sourceCoverageVerified=true` 不等于 `targetDataVerified=true`。不创建正式运行账本，不执行 DDL 或发送行情。

来源仍限现有 CORE57 和 SW2021_L1_31 名单，通过 `list-index-daily-market-universe` 查看；不能据此宣称覆盖全部指数或全部历史。普通指数和申万分别走 `index_daily`、`sw_daily`，申万的 `pct_change` 显式映射到 `pct_chg`，来源缺少的 `pre_close` 保持 null。

## 计划、写入与恢复

原命令不变：`plan-index-daily-market-job`、`run-index-daily-market-job` 和 `run-index-daily-market-job --resume-from 原运行编号`。v2 选择作业版本 2、数据契约版本 2；默认 v1 继续使用原作业和原 `timestamp`。当前读取和公开绑定始终跟随明确配置的布局。

恢复先读原冻结版本，再创建同版端口，重新核对原物理目标。原 v1 请求不改成 v2，不重绑另一个同名或新建的表；当前配置指向其他目标时，恢复拒绝，须使用原部署绑定。

检查点继续要求同目标、同代码、同路由、同作业版本的完整来源凭证和实际全字段回读。v1/v2 凭证互不认领。旧表有数据但没有原来源凭证时，计划仍拒绝接管；不能用最大日期、只读来源核查或一次 `RECONCILE` 绕过历史核验。

本批普通指数、申万指数单日真实来源和正式结构检查通过；检查时申万 `801010.SI/2026-09-18` 未返回目标行。私有实例已验证 v2 首次发布、同键夹具修订、公开日期读取、冻结观察时间恢复及检查点隔离。没有正式补数、常驻调度或后续消费者验收。证据见[24表绑定核查](audits/2026-10-21-cn-existing-owner-bindings.json)。
