# Batch 采集和恢复

Batch 由 A 股数据 API 常驻进程托管，用 SQLite 记录任务、写入和恢复状态。`jdb.enabled` 默认关闭；启用后管理 API、Quartz 和任务随市场进程启动及关闭，有限 CLI 不启动。启动和配置看[配置说明](configuration.md)，请求字段看[接口定义](batch/openapi.yaml)。同一运行记录库只允许一个持有者。迁移时先停旧独立 Batch，再启市场托管，继续使用原数据库和归档目录。

当前 `source_*` 写入固定使用 `jdb_test_*` 隔离目标；`jdb.production-enabled=true` 会被启动校验拒绝。启用 Batch 托管或调度不等于接管正式表。正式 `data.*` owner、默认目标和接管步骤见[清单](formal-sync-takeover.md)。

## 先采集，再写入

```sh
questdb-batch-control sources
questdb-batch-control collect /absolute/path/source-collect.json collect-001
questdb-batch-control run /absolute/path/source-run.json
questdb-batch-control detail <instance-id>
```

采集请求示例在 `deploy/batch/source-collect.example.json`。先固定日期、预期证券集合 `expectedCodes` 和范围版本 `universeVersion`。单证券样本不能代表全市场。

`collect` 只采集并保存来源，不写 QuestDB。完成后取结果中的 `fingerprint`，填入运行请求的 `inputFingerprint`；`job` 使用 `source_<dataset>`，定义版本按当前注册信息填写。`run` 才进入统一写入和恢复流程。

同一个采集请求 ID 重试会复用结果。来源分页未结束、达到上限、缺证券、重复键或日期不符，都不能标记完整。空结果也要有来源完整证据。写入后逐字段读回核验，成功才推进检查点。

## 盘后任务需要冻结计划

`post_close` 先按计划依次执行核心来源，再运行盘后任务。计划放在配置 `jdb.archive-root` 下的 `post-close-plans/<sha256>.json`，文件名按原始字节的 SHA-256 命名。请求的 `inputFingerprint` 和 `scopeIdentity` 都要等于这个摘要。

计划包含 `schemaVersion`、`logicalDate`、`calendarVersion`、`zone` 和 `sources`。来源必须恰好是 `PostCloseGraph.CORE_TABLES` 的 14 项加 `exchange_calendar`，每项只出现一次；来源项固定定义版本、预期证券集合、范围版本和 revision。日历源使用 `SSE`、`SZSE`，时区使用 `Asia/Shanghai`。

日期、范围、版本或摘要不符就停止。只有来源全部得到 `VERIFIED` 或契约允许的 `VERIFIED_EMPTY`，才继续盘后任务。完整 15 源计划尚未通过真实来源整链验收，不能因单源样本通过就说全市场已就绪。

## 失败后怎么处理

`post_close_recovery` 在工作日 19:00–23:45 每 15 分钟检查，`post_close_recovery_final` 在 23:55 再检查一次，按上海时区运行。两者初始暂停，安装触发器不会自动启用调度。

只重试当日唯一、未被替代且处于 `BLOCKED`、`PARTIAL`、`WAITING_SOURCE` 或 `WAITING_UPSTREAM` 的实例。每晚最多 21 次；正常冷却 15 分钟，末次 10 分钟。闭市、跨日、超过窗口或次数上限时跳过。

`RUNNING`、`IN_DOUBT`、`FAILED` 不自动重放，先核对原运行和物理写入。已有写入意图后不能偷偷换来源；输入变化要显式增加 revision，并记录前驱实例和原因。

这套恢复只覆盖盘后任务的有限重试，不代表历史回补队列或主策略缺口恢复已经完成。

美国利率回补预览使用 `calendarVersion=source-date-v1`，逐个查询来源日期；不会用 SSE 开市日筛掉美国日期。它只是有限日期计划，不自动提交执行，也不代表已接通完整美股交易日历。

主策略独立运行时的新日批使用定义 v4，固定输入协议；历史 v3 请求按原指纹和计划恢复，未决写入与调度暂停状态不变。根配置选择不能替代来源或训练验收，见[协议决定](decisions/2026-10-20-主策略Batch冻结输入协议.md)。

## 独立来源怎么选择日期布局

`collect` 仍按原来源合同保存 v1 归档，不加布局参数。随后在 `run` 请求的 `definitionVersion` 明确选择写入布局：

| 来源 | 原定义 | 新定义 |
| --- | --- | --- |
| ETF 复权 | `etf_adj-v1` | `etf_adj-trade-date-v2` |
| ETF 日线 | `etf_daily-v1` | `etf_daily-trade-date-v2` |
| ETF 份额 | `etf_share-v1` | `etf_share-trade-date-v2` |
| ST 日频 | `stk_st_daily-v1` | `stk_st_daily-trade-date-v2` |
| 停牌 | `stk_suspend-v1` | `stk_suspend-trade-date-v2` |

例如 `job=source_etf_adj`，先正常采集，取得 `fingerprint`，再用 `definitionVersion=etf_adj-trade-date-v2` 发起运行。它从原归档读取 `timestamp` 日期，向当前实例的 `jdb_test_*` 目标写 `trade_date`，回读也使用 `trade_date`。业务日期不做时区换算；ETF 日线保留纳秒类型，其他类型及分区不变。

盘后计划的来源项也能填写这些新定义。验收凭证必须匹配冻结计划，不能借用另一布局的最新记录；不匹配时阻断。旧请求恢复仍用原定义和原目标，未决批次不会自动重发或解锁。正式 `data.*` 的根配置不控制这些 Batch 隔离来源。

这是可选的新布局入口；原定义、采集归档和正式默认保持原样。具体边界见[设计决定](decisions/2026-10-21-Batch独立来源日期布局.md)。
