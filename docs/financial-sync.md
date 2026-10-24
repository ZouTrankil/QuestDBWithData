# 财务增量同步

`cashflow`、`income`、`express`、`forecast`、`fina_indicator`、`balance` 已接入正式 Java 写入协议。复用 `app.sync.ledger-path`、整表锁、完整键和值回读及未决恢复，不重建旧 `_meta` 表。

每次请求一只证券的一个季度，保存原始响应；满 100 行就停止，不假定 offset 可用。公告日期和报告期分别处理，重查旧报告期可收到晚到公告和历史更正。

`income`、`cashflow`、`balance` 只接 `report_type=1`，现有 `ts_code,ann_date,end_date` 业务键无法同时容纳其他类别。`update_flag=1` 优先于 `0`，同优先级冲突时停止。[利润表约定](https://tushare.pro/document/2?doc_id=33)、[修订标记说明](https://tushare.pro/document/1?doc_id=122)。

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

## balance 本轮接入

`balance` 写原表，请求官方 `balancesheet`，沿用单证券、单报告期、`report_type=1` 和修订择优。159 个原物理字段均显式登记，旧 `update_time` 按真实 UTC 微秒保留，新行缺这个技术时刻时保持 NULL。2026-06-30 的 `301677.SZ` 非空来源及原表结构/WAL 预检通过，未正式写入。

日批候选增加为六条，但仍默认关闭，共享原预算和跨日队列；`balancesheet` 的限流覆盖按真实接口名计算。原五表队列和原冻结请求不改，旧目标摘要不符时仍阻断，不能删队列强行升级。[阶段证据](audits/2026-10-21-cn-remaining-contract-tables.json)不代表已接管或已完成公告驱动全市场增量。

## 跨日分片

日批先验证全部证券和报告期，再按“单证券、单季度、单来源”切片。六条来源轮流跑，整轮没完成就不换证券和报告期；每片首次开始时冻结当日业务日期，原运行恢复继续原日期；旧季度按已完成轮次推进，不按当天取模跳过。

`max-request-attempts` 默认每天 1000，按客户端最大重试次数预留。`execution-window` 默认 `PT4H`，最多 12 小时。当天额度或窗口用完就停，次日继续；同日重启不会重置预算。全市场一轮仍可能耗时很久，分片不增加供应商配额。

进度存在原账本旁的 `<账本文件名>.financial-daily-queue/queue.sqlite3`，备份时一起保留。队列先保存运行 ID，再调用原 `FinancialSyncOwner`；原账本、整表锁、完整键值回读和 WAL 核验不变。同轮固定批准摘要和物理目标，换表或换范围会阻断。

```sh
./gradlew run --args='show-financial-daily-queue'
```

也可查 `GET /api/v1/jobs/financial-daily/status`。返回原轮次日期、已验收片数、总片数、当天预算和下一片。`CYCLE_VERIFIED` 只表示这一轮完成，不代表今天或全历史都齐了。

遇到未验收运行会停在原片，不自动重发。先用原恢复命令处理；队列只接受冻结请求和物理目标都相同的已验收后继。如果中断发生在原账本建运行之前，不能拿不存在的运行直接恢复，需要先核对原进程和意图，不能删队列绕过。

这次完成了分片、预算、重启、恢复后继和换表阻断的本地回归。正式日批仍关闭，尚未拿到原部署账本、实际批准范围并完成真实写入验收，不能宣称已接管。
