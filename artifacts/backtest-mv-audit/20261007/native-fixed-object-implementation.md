# 固定目标原生 Backtest MV 实现

本文件替代此前 `recommendation.md` 中的普通别名或多代 MV 建议。用户要求最终 `v_backtest_daily` 本身为真正 QuestDB MATERIALIZED VIEW，不创建中间 VIEW。

## 对象与读取

- 唯一业务物化基表：`backtest_daily`，DAY/WAL/DEDUP，完整键 `(trade_date, ts_code)`，原 13 字段保留。
- 目标：`v_backtest_daily`，直接原生 MV，`WITH BASE backtest_daily REFRESH MANUAL DEFERRED`，每股每日 singleton `last(...) SAMPLE BY 1d ALIGN TO CALENDAR`。基表与源数据必须先证明完整键唯一，聚合不允许隐藏重复。
- 目标 `is_suspended` 保持 LONG，`is_st` 保持 INT；基表两标记为 INT。价格/成交量/调整因子/上下限九字段保持 DOUBLE。
- 旧普通 VIEW 的完整 SQL 仅保存在持久化 source/journal 证据中。切换时移除旧定义并创建同名原生 MV。
- `QuestDbBoundedReader` 对固定原生目标和基表采用发布守卫：原 Java run 与 publication VERIFIED、无活跃或未决拥有者租约、四源全历史版本相同、原始证据和最终证据 SHA256、基表和 MV 物理身份/事务/Schema/原生定义/刷新追平一致。分页前后版本必须相同。
- Python `install_view` 识别并保留已经部署的固定 Java 原生目标；不会将其替换为普通 VIEW。

## 正式 API

`BacktestDailyMaterializationJobService`：

- `bootstrapPlan(LocalDate logicalDate)`：全实际源历史，不缩窄到最近日期。
- `plan(LocalDate from, LocalDate to, LocalDate logicalDate)`：首轮自动全历史 bootstrap，之后明确窗口最多 366 日。
- `run(Plan)`：返回 `MaterializationResult(result, sourceRows, baseRows, materializedRows, sourceFingerprint, windowFingerprint, evidence, bootstrap)`。
- `finishInterrupted(String runId, boolean writerStopped)`：显式原拥有者停止证明、原租约、已 VERIFIED 的全部月度 slice、原持久化备份与阶段物理绑定、全字段精确读回才恢复。
- `status(String runId)`：只读原 SQLite run。

作业：`data.backtest_daily` v1，模式 MATERIALIZE，Frequency DAILY，可作为常驻 Batch 阶段；策略注册 `backtest_daily.month_receipts`。runner 的 sourceRows/verifiedRows 计数代表月度完整性回执。实际业务行数必须读取 `MaterializationResult.sourceRows/baseRows/materializedRows`，不可将月数当成股票行数。

## 完整性、备份与性能

首次源范围来自 `stk_factor` 的物理日期边界：停牌补充 arm 显式限定在 factor 的 DISTINCT 日期，所以不会扩展历史范围。边界中的 factor 行数是源完整 UNION 行数下界。完整源计数由每月全 13 字段 SHA256/唯一键证书累积，最多 4000 万行、每月 100 万行、完整历史最多 100 年。

源 SQL 是原始普通 VIEW 的固定完整 SQL。日窗优化仅筛选价格输出 arm 与停牌输出 arm/当日副表；ASOF 右侧 `stk_factor` 保留完整历史。各月逐行排序、键唯一、UTC 日期、类型与有限值通过后，通过原 durable SUBMITTED slice 按月执行 server INSERT SELECT 到拥有者 DAY 阶段表。整个 source window 再次全字段读回通过才修改正式基表。

bootstrap 保留旧全历史基表，通过物理 rename 发布完整新基表，再完整核验基表/源/native 的全历史所有月份。原 native（若有）在 DROP 前先复制为备份 TABLE 并完整逐月验证。后续只备份并替换指定 DAY 分区，保持基表物理 ID；窗外全部 DAY 分区的行数、范围与 seqTxn 必须保持一致。过时键随分区替换移除，不能只 UPSERT 留下删除键。

ReferencePublicationJournal 是发布状态 authority。source 证据和最终 publication 证据 fsync；最终证据 SHA 在同一 SQLite ledger 的 `backtest_publication_proofs` 中保留为不可变 attestation。此表没有另一套状态。未完成发布保留原拥有者锁；恢复不能因为超时推定发送者已经停止。

纯追加日期使用 INCREMENTAL。替换已有 DAY 分区会使原生 MV 无效，因此需要 FULL 才恢复；RANGE 不可作为无效状态的恢复手段。FULL 会重新计算全历史，这是修订窗口的明确性能成本。原生刷新异步，命令返回不代表完成；必须等待 valid/caught-up/新 FULL 完成凭据、WAL settled、全部请求窗口字段 SHA/键和全历史日期行数一致。

## 本轮编译与执行范围

`gradlew classes --no-problems-report -q` 实际 exit 0；未新增或运行测试。本实施子任务没有启动 provider 或生产 DDL/DML。父任务随后启动正式本地 bootstrap，实际数据库验收和最终报告由父任务汇总，不能由编译成功推定已发布。
