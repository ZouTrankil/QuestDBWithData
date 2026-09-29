# D009 真实验收结果

- 状态 verified；人工复核 pending_review。
- 本机 QuestDB 10.0.1；隔离目标 `java_d009_factor_bcf7b1ef91bc42928a647214f19a01d8`；正式表未修改。
- 首次真实来源 5557 行；两次同范围重跑行数与摘要相同；增量后 11114 行、0 重复键。
- 四次运行分别验证收据SHA-256，再从原始值独立核对全部 35 字段，0 缺失/0 差异。
- 取源前取消及完整页验证后取消；恢复重新取源并核对 5557 行，发送次数 0。
- 人为注入发送后丢 ACK：2 个真实来源行实际写入，发送者关闭后回读一致，UNKNOWN_RECONCILED。
- 已编译任务来源与实际运行工具；没有声称重跑完整Gradle构建或测试集。

## 修正与保留现场

采用实测并经官方文档核对的 legacy stk_factor 35字段契约（版本2，pct_change）；checkpoint 查询使用当前任务版本；对原始字段值按完整键排序后生成稳定指纹。 早期失败报告与隔离现场保留，不覆盖为成功。

## 证据

- [实际运行](../market-live-bcf7b1ef91bc42928a647214f19a01d8/D009-live-acceptance.json)
- [独立原始来源核对](../market-live-bcf7b1ef91bc42928a647214f19a01d8/D009-independent-source-readback.json)
- [取消、恢复、未知写入回读](../market-live-bcf7b1ef91bc42928a647214f19a01d8/D009-recovery-ce654bac-041a-4bc0-95e7-65ca32b5e3ce.json)

## Fresh-process CLI recovery

`run-…-job --resume-from RUN_ID` restored the original frozen request in a new JVM. Reused 5557 rows; independent raw-source readback MATCHED; target count unchanged. Evidence: `artifacts/java-migration/market-live-bcf7b1ef91bc42928a647214f19a01d8/D009-cli-recovery-d47fc1a1-5b12-418b-941a-877dcf5d69fa.json`. No new test suite run.
