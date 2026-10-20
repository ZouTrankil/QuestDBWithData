# 财务增量同步

`cashflow`、`income`、`express`、`forecast`、`fina_indicator` 已接入正式 Java 写入协议。复用 `app.sync.ledger-path`、整表锁、完整键和值回读及未决恢复，不重建旧 `_meta` 表。

每次请求一只证券的一个季度，保存原始响应；满 100 行就停止，不假定 offset 可用。公告日期和报告期分别处理，重查旧报告期可收到晚到公告和历史更正。

`income`、`cashflow` 只接 `report_type=1`，现有 `ts_code,ann_date,end_date` 业务键无法同时容纳其他类别。`update_flag=1` 优先于 `0`，同优先级冲突时停止。[利润表约定](https://tushare.pro/document/2?doc_id=33)、[修订标记说明](https://tushare.pro/document/1?doc_id=122)。

指标表中 11 个历史本地列没有供应商映射。已有行保留原值，新行保持 NULL；不猜公式，也不清空已有值。显式列契约在 `markets/cn/data/src/main/resources/financial/schemas.json`。

```sh
./gradlew run --args='plan-financial-sync --dataset income --codes 600000.SH --periods 2026-06-30 --logical-date 2026-10-17'
./gradlew run --args='run-financial-sync --dataset income --codes 600000.SH --periods 2026-06-30 --logical-date 2026-10-17'
./gradlew run --args='resume-financial-sync --dataset income --run 原运行ID'
```

证券和报告期可逗号分隔，最多 6,000 只、16 个报告期。模式支持 `INCREMENTAL`、`BACKFILL`、`RECONCILE`；恢复保留原范围和日期，不自动重发未决写入。

自动日批在 `app.sync.financial` 配置，默认关闭。只在数据 API 的持久进程中启用 `enabled`（主策略进程和有限 CLI 不启动这项调度），填 `scope-file`、`scope-sha256`，默认上海时间每天 19:30 运行。范围文件格式：

```json
{"schema":"java.financial.scope.v1","validFrom":"2026-10-17","validThrough":"2026-12-31","codes":["600000.SH"]}
```

这是指定证券示例，不代表全市场。日批反复核对近期四个已结束季度，另轮巡一个旧期；历史起点由 `earliest-period` 设置。范围过期或任一源未完成时停止，保留运行记录，不用表中最大公告日推进进度。

本轮核验了五张真实表结构和五条接口响应，没有写生产库或启用日批。新增、补披露、修订、冲突和失败恢复有本地回归；全市场接管仍需实际范围及正式写入验收。

## 日批预算

日批先检查全部五条请求，再按当前 Tushare 配额估算整轮耗时。`config/apps/cn-data.yml` 中的 `max-request-attempts` 默认 1000，包含客户端最大重试次数；`execution-window` 默认 `PT4H`，最多 12 小时。配额耗时下界不能超过窗口的一半，剩余时间留给响应、写入和核验。超限会在第一次采集前报错，不偷偷截断证券集合。

五条来源共用剩余执行时间，到期停止后续采集。已经发送的写入继续遵守原核验和未决协议，不强行解锁。实际耗时仍可能受正在进行的请求、回读和数据库超时影响。

这只是预算保护，还不是全市场分片队列。默认配额下，5000 只证券 × 5 个季度 × 5 条来源需要 125000 次来源请求，不能按日完成；提高请求预算不会提高供应商配额。全市场接管仍需要可恢复的跨日分片、独立历史轮巡和相应的来源能力验收。当前只启用预算允许的批准范围，历史回补用显式 CLI 范围执行。
