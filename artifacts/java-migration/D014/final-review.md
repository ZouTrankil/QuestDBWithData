# D014 真实验收结果

- 状态 verified；人工复核 pending_review。
- 本机 QuestDB 10.0.1；隔离目标 `java_d014_etf_daily_57f2a9e58977462b90e1a258cb77c626`；正式表未修改。
- 首次真实来源 2163 行；两次同范围重跑行数与摘要相同；增量后 4299 行、0 重复键。
- 四次运行分别验证收据SHA-256，再从原始值独立核对全部 11 字段，0 缺失/0 差异。
- 取源前取消及完整页验证后取消；恢复重新取源并核对 2163 行，发送次数 0。
- 人为注入发送后丢 ACK：2 个真实来源行实际写入，发送者关闭后回读一致，UNKNOWN_RECONCILED。
- 已编译任务来源与实际运行工具；没有声称重跑完整Gradle构建或测试集。

## 修正与保留现场

实测包含 .OF 基金后缀；物理 timestamp 为 TIMESTAMP_NS，显式使用纳秒读回及完整键；来源按官方文档5000行单日上限检查，原始值按完整键稳定排序。 早期失败报告与隔离现场保留，不覆盖为成功。

## 证据

- [实际运行](../market-live-57f2a9e58977462b90e1a258cb77c626/D014-live-acceptance.json)
- [独立原始来源核对](../market-live-57f2a9e58977462b90e1a258cb77c626/D014-independent-source-readback.json)
- [取消、恢复、未知写入回读](../market-live-57f2a9e58977462b90e1a258cb77c626/D014-recovery-3faf9b64-e9ee-4352-9d61-b704fce9710e.json)

## Fresh-process CLI recovery

`run-…-job --resume-from RUN_ID` restored the original frozen request in a new JVM. Reused 2163 rows; independent raw-source readback MATCHED; target count unchanged. Evidence: `artifacts/java-migration/market-live-57f2a9e58977462b90e1a258cb77c626/D014-cli-recovery-b4a16db6-1904-479f-a7b3-376f793b95e2.json`. No new test suite run.
