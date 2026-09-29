# D010 真实验收结果

- 状态 verified；人工复核 pending_review。
- 本机 QuestDB 10.0.1；隔离目标 `java_d010_stk_limit_6f4fe7d2d30e465e903ad6d510c2df7c`；正式表未修改。
- 首次真实来源 5648 行；两次同范围重跑行数与摘要相同；增量后 11298 行、0 重复键。
- 四次运行分别验证收据SHA-256，再从原始值独立核对全部 4 字段，0 缺失/0 差异。
- 取源前取消及完整页验证后取消；恢复重新取源并核对 5648 行，发送次数 0。
- 人为注入发送后丢 ACK：2 个真实来源行实际写入，发送者关闭后回读一致，UNKNOWN_RECONCILED。
- 已编译任务来源与实际运行工具；没有声称重跑完整Gradle构建或测试集。

## 修正与保留现场

DISTINCT 日期查询按投影别名排序；checkpoint按BASIC_ISO_DATE解析8位收据日期游标；来源收据按完整业务键稳定排序；Repository允许Spring代理。 早期失败报告与隔离现场保留，不覆盖为成功。

## 证据

- [实际运行](../market-live-6f4fe7d2d30e465e903ad6d510c2df7c/D010-live-acceptance.json)
- [独立原始来源核对](../market-live-6f4fe7d2d30e465e903ad6d510c2df7c/D010-independent-source-readback.json)
- [取消、恢复、未知写入回读](../market-live-6f4fe7d2d30e465e903ad6d510c2df7c/D010-recovery-9481afde-1d3b-4896-93fd-46608c291ba5.json)

## Fresh-process CLI recovery

`run-…-job --resume-from RUN_ID` restored the original frozen request in a new JVM. Reused 5648 rows; independent raw-source readback MATCHED; target count unchanged. Evidence: `artifacts/java-migration/market-live-6f4fe7d2d30e465e903ad6d510c2df7c/D010-cli-recovery-95300fe4-d0a0-4834-97e5-8623c172d964.json`. No new test suite run.
